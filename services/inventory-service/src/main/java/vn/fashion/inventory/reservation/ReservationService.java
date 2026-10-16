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
  public record Outcome(int status,String error,ReservationView view) { }
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ReservationRepository reservations;
  private final StockAdminService stocks;
  private final StockEvents stockEvents;
  private final ReservationEvents events;
  public ReservationService(JdbcClient jdbc,TransactionTemplate tx,ReservationRepository reservations,StockAdminService stocks,
      StockEvents stockEvents,ReservationEvents events) {
    this.jdbc=jdbc;this.tx=tx;this.reservations=reservations;this.stocks=stocks;this.stockEvents=stockEvents;this.events=events;
  }

  public Outcome reserve(ReservationRequests.ReserveCommand c,String traceId) {
    var s=c.snapshot();
    return tx.execute(status->{
      var existing=reservations.lock(s.orderId());
      if(existing.isPresent()) return replay(existing.get(),c.requestHash());
      boolean inWindow=jdbc.sql("select :e > now() and :e <= now() + interval '24 hours'")
          .param("e",java.time.OffsetDateTime.ofInstant(s.expiresAt(),java.time.ZoneOffset.UTC)).query(Boolean.class).single();
      if(!inWindow) throw ReservationRequests.invalid("expires_at","must be after now and at most 24 hours ahead");
      UUID id=UUID.randomUUID();
      if(!reservations.insertIfAbsent(id,s,c.requestHash(),"ACTIVE",null)) return replay(reservations.lock(s.orderId()).orElseThrow(),c.requestHash());
      var locked=stocks.lockForUpdate(s.skus());
      var shortages=new ArrayList<Api.FieldError>();
      for(var item:s.items()) {
        var row=locked.get(item.sku());
        int available=row==null || !"ACTIVE".equals(row.status())?0:row.available();
        if(available<item.quantity()) shortages.add(new Api.FieldError("items["+item.sku()+"]","requested="+item.quantity()+" available="+available));
      }
      if(!shortages.isEmpty()) throw new Api.Problem(HttpStatus.CONFLICT,"OUT_OF_STOCK","OUT_OF_STOCK",shortages);
      reservations.insertItems(id,s.items());
      for(var item:s.items()) move(locked.get(item.sku()),0,item.quantity(),"RESERVE",s.orderId(),"order reserve",traceId);
      var row=reservations.lock(s.orderId()).orElseThrow();
      events.append("INVENTORY_RESERVED",row,s.items(),traceId);
      return new Outcome(201,null,ReservationView.of(row,s.items(),false));
    });
  }
  public Outcome commit(UUID orderId,String traceId) {
    return tx.execute(status->{
      var row=reservations.lock(orderId).orElseThrow(ReservationService::notFound);
      switch(row.status()) {
        case "COMMITTED": return new Outcome(200,null,view(row,false));
        case "EXPIRED": return new Outcome(409,"RESERVATION_EXPIRED",null);
        case "RELEASED": return new Outcome(409,"RESERVATION_CLOSED",null);
        default: break;
      }
      // Persist expiry before the controller reports the conflict outside this transaction.
      if(row.due()) { finish(row,"EXPIRED","expired",traceId);return new Outcome(409,"RESERVATION_EXPIRED",null); }
      var items=reservations.items(row.id());
      var locked=stocks.lockForUpdate(items.stream().map(ReservationSnapshot.Item::sku).toList());
      for(var item:items) move(locked.get(item.sku()),-item.quantity(),-item.quantity(),"COMMIT",orderId,"order commit",traceId);
      var after=reservations.transition(row.id(),"COMMITTED",null);
      events.append("INVENTORY_DEDUCTED",after,items,traceId);
      return new Outcome(200,null,ReservationView.of(after,items,false));
    });
  }
  /** Releases reserved stock of an ACTIVE reservation; caller holds the reservation row lock. */
  ReservationRow finish(ReservationRow row,String status,String reason,String traceId) {
    var items=reservations.items(row.id());
    var locked=stocks.lockForUpdate(items.stream().map(ReservationSnapshot.Item::sku).toList());
    for(var item:items) move(locked.get(item.sku()),0,-item.quantity(),"RELEASE",row.orderId(),reason,traceId);
    var after=reservations.transition(row.id(),status,reason);
    events.append("INVENTORY_RELEASED",after,items,traceId);
    return after;
  }
  public ReservationView find(UUID orderId) {
    var row=reservations.find(orderId).orElseThrow(ReservationService::notFound);
    return ReservationView.of(row,reservations.items(row.id()),false);
  }

  private Outcome replay(ReservationRow row,String hash) {
    if(!row.requestHash().equals(hash)) throw conflict("RESERVATION_CONFLICT");
    if(row.status().equals("ACTIVE") || row.status().equals("COMMITTED")) return new Outcome(200,null,view(row,false));
    throw conflict("RESERVATION_CLOSED");
  }
  ReservationView view(ReservationRow row,boolean restoreRequired) { return ReservationView.of(row,reservations.items(row.id()),restoreRequired); }
  /** Applies one stock movement with its ledger row and stock snapshot event. */
  StockRow move(StockRow before,int onHandDelta,int reservedDelta,String type,UUID orderId,String reason,String traceId) {
    var after=jdbc.sql("update stock_items set on_hand=on_hand+:o,reserved=reserved+:r,version=version+1,updated_at=now() where sku=:sku returning *")
        .param("o",onHandDelta).param("r",reservedDelta).param("sku",before.sku()).query(StockRow.class).single();
    jdbc.sql("""
        insert into stock_transactions(id,sku,operation_key,type,on_hand_delta,reserved_delta,order_id,reason)
        values (:id,:sku,:key,:type,:o,:r,:order,:reason)
        """).param("id",UUID.randomUUID()).param("sku",before.sku()).param("key","order:"+orderId+":"+type)
        .param("type",type).param("o",onHandDelta).param("r",reservedDelta).param("order",orderId).param("reason",reason).update();
    stockEvents.append(after,before.available()==0 && after.available()>0,traceId);
    return after;
  }
  static Api.Problem conflict(String message) { return new Api.Problem(HttpStatus.CONFLICT,"CONFLICT",message,List.of()); }
  static Api.Problem notFound() { return new Api.Problem(HttpStatus.NOT_FOUND,"NOT_FOUND","RESERVATION_NOT_FOUND",List.of()); }
}
