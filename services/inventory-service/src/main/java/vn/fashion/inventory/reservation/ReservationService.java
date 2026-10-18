package vn.fashion.inventory.reservation;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.inventory.admin.StockAdminService;
import vn.fashion.inventory.stock.*;
import vn.fashion.inventory.web.Api;

/** 06 section 6.1: lock the reservation, then SKUs in sku order; no HTTP inside a transaction. */
@Service
public class ReservationService {
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReservationService.class);
  public record Outcome(int status,String error,ReservationView view) { }
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ReservationRepository reservations;
  private final StockAdminService stocks;
  private final ReservationEvents events;
  private final io.micrometer.core.instrument.MeterRegistry meters;
  public ReservationService(JdbcClient jdbc,TransactionTemplate tx,ReservationRepository reservations,StockAdminService stocks,
      ReservationEvents events,io.micrometer.core.instrument.MeterRegistry meters) {
    this.jdbc=jdbc;this.tx=tx;this.reservations=reservations;this.stocks=stocks;this.events=events;
    this.meters=meters;
  }

  public Outcome reserve(ReservationRequests.ReserveCommand c,String traceId) {
    var s=c.snapshot();
    return tx.execute(status->{
      var existing=reservations.lock(s.orderId());
      if(existing.isPresent()) return replay(existing.get(),c.requestHash(),traceId);
      boolean inWindow=jdbc.sql("select :e > now() and :e <= now() + interval '24 hours'")
          .param("e",java.time.OffsetDateTime.ofInstant(s.expiresAt(),java.time.ZoneOffset.UTC)).query(Boolean.class).single();
      if(!inWindow) throw ReservationRequests.invalid("expires_at","must be after now and at most 24 hours ahead");
      UUID id=UUID.randomUUID();
      var inserted=reservations.insertIfAbsent(id,s,c.requestHash(),"ACTIVE",null);
      if(inserted.isEmpty()) return replay(reservations.lock(s.orderId()).orElseThrow(),c.requestHash(),traceId);
      var locked=stocks.lockForUpdate(s.skus());
      var shortages=new ArrayList<Api.FieldError>();
      for(var item:s.items()) {
        var row=locked.get(item.sku());
        int available=row==null || !"ACTIVE".equals(row.status())?0:row.available();
        if(available<item.quantity()) shortages.add(new Api.FieldError("items["+item.sku()+"]","requested="+item.quantity()+" available="+available));
      }
      if(!shortages.isEmpty()) { count("reserve","out_of_stock");throw new Api.Problem(HttpStatus.CONFLICT,"OUT_OF_STOCK","OUT_OF_STOCK",shortages); }
      reservations.insertItems(id,s.items());
      for(var item:s.items()) move(locked.get(item.sku()),0,item.quantity(),"RESERVE",s.orderId(),"order reserve",traceId);
      var row=inserted.get();
      events.append("INVENTORY_RESERVED",row,s.items(),traceId);
      count("reserve","created");
      return new Outcome(201,null,ReservationView.of(row,s.items(),false));
    });
  }
  public Outcome commit(UUID orderId,String traceId) {
    return tx.execute(status->{
      var row=reservations.lock(orderId).orElseThrow(ReservationService::notFound);
      switch(row.status()) {
        case "COMMITTED": count("commit","replayed");return new Outcome(200,null,view(row,false));
        case "EXPIRED": count("commit","expired");return new Outcome(409,"RESERVATION_EXPIRED",null);
        case "RELEASED": count("commit","closed");return new Outcome(409,"RESERVATION_CLOSED",null);
        default: break;
      }
      // Persist expiry before the controller reports the conflict outside this transaction.
      if(row.due()) { finish(row,"EXPIRED","expired",traceId);count("commit","expired");return new Outcome(409,"RESERVATION_EXPIRED",null); }
      var items=reservations.items(row.id());
      var locked=stocks.lockForUpdate(items.stream().map(ReservationSnapshot.Item::sku).toList());
      for(var item:items) move(locked.get(item.sku()),-item.quantity(),-item.quantity(),"COMMIT",orderId,"order commit",traceId);
      var after=reservations.transition(row.id(),"COMMITTED",null);
      events.append("INVENTORY_DEDUCTED",after,items,traceId);
      count("commit","committed");
      return new Outcome(200,null,ReservationView.of(after,items,false));
    });
  }
  public Outcome release(ReservationRequests.ReleaseCommand c,String traceId) {
    var s=c.snapshot();
    return tx.execute(status->{
      var existing=reservations.lock(c.orderId());
      if(existing.isEmpty()) {
        UUID id=UUID.randomUUID();
        // Serialize on order_id before locking any SKU, as reserve does. An unknown SKU rolls back the tombstone.
        var inserted=reservations.insertIfAbsent(id,s,c.originalRequestHash(),"RELEASED",c.reason());
        if(inserted.isPresent()) {
          var known=stocks.lockForUpdate(s.skus()).keySet();
          var unknown=s.skus().stream().filter(sku->!known.contains(sku))
              .map(sku->new Api.FieldError("original_reservation.items["+sku+"]","unknown sku")).toList();
          if(!unknown.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","INVALID_INPUT",unknown);
          reservations.insertItems(id,s.items());
          var tombstone=inserted.get();
          events.append("INVENTORY_RELEASED",tombstone,s.items(),traceId);
          count("release","tombstone");
          return new Outcome(200,null,ReservationView.of(tombstone,s.items(),false));
        }
        existing=reservations.lock(c.orderId());
      }
      var row=existing.orElseThrow();
      if(!row.requestHash().equals(c.originalRequestHash())) throw conflict("RESERVATION_CONFLICT");
      return switch(row.status()) {
        case "ACTIVE" -> { count("release","released");yield new Outcome(200,null,finish(row,"RELEASED",c.reason(),traceId)); }
        case "COMMITTED" -> { count("release","restore_required");yield new Outcome(200,null,view(row,true)); }
        default -> { count("release","noop");yield new Outcome(200,null,view(row,false)); }
      };
    });
  }
  /** One transaction per reservation; SKIP LOCKED leaves rows a commit holds. */
  public int expireDue(int batch) {
    int expired=0;
    for(UUID id:reservations.dueIds(batch)) {
      try {
        Boolean done=tx.execute(status->reservations.lockDue(id)
            .map(row->{finish(row,"EXPIRED","expired","reservation-sweep-"+UUID.randomUUID());return true;}).orElse(false));
        if(Boolean.TRUE.equals(done)) { expired++;count("expire","expired"); }
      } catch(RuntimeException e) { LOG.warn("Reservation expiry failed for reservation_id={}",id,e); }
    }
    return expired;
  }

  void count(String operation,String outcome) {
    meters.counter("inventory.reservations","operation",operation,"outcome",outcome).increment();
  }
  /** Releases reserved stock of an ACTIVE reservation; caller holds the reservation row lock. */
  ReservationView finish(ReservationRow row,String status,String reason,String traceId) {
    var items=reservations.items(row.id());
    var locked=stocks.lockForUpdate(items.stream().map(ReservationSnapshot.Item::sku).toList());
    for(var item:items) move(locked.get(item.sku()),0,-item.quantity(),"RELEASE",row.orderId(),reason,traceId);
    var after=reservations.transition(row.id(),status,reason);
    events.append("INVENTORY_RELEASED",after,items,traceId);
    return ReservationView.of(after,items,false);
  }
  public ReservationView find(UUID orderId) {
    var row=reservations.find(orderId).orElseThrow(ReservationService::notFound);
    return ReservationView.of(row,reservations.items(row.id()),false,reservations.returnedTotals(row.id()));
  }

  private Outcome replay(ReservationRow row,String hash,String traceId) {
    if(!row.requestHash().equals(hash)) { count("reserve","conflict");throw conflict("RESERVATION_CONFLICT"); }
    // An overdue ACTIVE row is expired here, as commit does, so a retry never reports stock held past the deadline.
    if(row.status().equals("ACTIVE") && row.due()) {
      finish(row,"EXPIRED","expired",traceId);count("reserve","closed");return new Outcome(409,"RESERVATION_CLOSED",null);
    }
    if(row.status().equals("ACTIVE") || row.status().equals("COMMITTED")) { count("reserve","replayed");return new Outcome(200,null,view(row,false)); }
    count("reserve","closed");
    throw conflict("RESERVATION_CLOSED");
  }
  ReservationView view(ReservationRow row,boolean restoreRequired) {
    return ReservationView.of(row,reservations.items(row.id()),restoreRequired,reservations.returnedTotals(row.id()));
  }
  /** Applies one stock movement with its ledger row and stock snapshot event. */
  StockRow move(StockRow before,int onHandDelta,int reservedDelta,String type,UUID orderId,String reason,String traceId) {
    return stocks.move(before,onHandDelta,reservedDelta,type,"order:"+orderId+":"+type,orderId,reason,null,traceId);
  }
  static Api.Problem conflict(String message) { return new Api.Problem(HttpStatus.CONFLICT,"CONFLICT",message,List.of()); }
  static Api.Problem notFound() { return new Api.Problem(HttpStatus.NOT_FOUND,"NOT_FOUND","RESERVATION_NOT_FOUND",List.of()); }
}
