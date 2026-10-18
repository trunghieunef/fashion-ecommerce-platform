package vn.fashion.inventory.reservation;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.inventory.admin.StockAdminService;
import vn.fashion.inventory.web.Api;

/**
 * INV-03 spec section 3 (T17): restore committed stock once per operation_key. The reservation row lock serializes
 * every return of an order, so "returned so far + quantity <= committed" holds under concurrency. No HTTP inside.
 */
@Service
public class ReturnService {
  public record Line(String sku,int quantity,int committed,int returnedTotal) { }
  public record ReturnView(UUID orderId,UUID reservationId,String operationKey,List<Line> items) { }
  private record Stored(String sku,int quantity,String reason,String evidenceRef) { }
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ReservationRepository reservations;
  private final StockAdminService stocks;
  private final ReservationService counters;

  public ReturnService(JdbcClient jdbc,TransactionTemplate tx,ReservationRepository reservations,StockAdminService stocks,
      ReservationService counters) {
    this.jdbc=jdbc;this.tx=tx;this.reservations=reservations;this.stocks=stocks;this.counters=counters;
  }

  public ReturnView restore(ReservationRequests.ReturnCommand c,String traceId) {
    return tx.execute(status->{
      var row=reservations.lock(c.orderId()).orElseThrow(ReservationService::notFound);
      if(!"COMMITTED".equals(row.status())) { counters.count("return","not_committed");throw ReservationService.conflict("RESERVATION_NOT_COMMITTED"); }
      var stored=jdbc.sql("select sku,quantity,reason,evidence_ref from stock_returns where reservation_id=:r and operation_key=:k")
          .param("r",row.id()).param("k",c.operationKey()).query(Stored.class).list();
      if(!stored.isEmpty()) {
        var requested=c.items().stream().map(i->new Stored(i.sku(),i.quantity(),c.reason(),c.evidenceRef())).toList();
        // Order-independent: DB collation and Java code-point order disagree on SKUs such as A1 / A-2 (review I1).
        if(!new HashSet<>(stored).equals(new HashSet<>(requested))) { counters.count("return","conflict");throw ReservationService.conflict("RETURN_CONFLICT"); }
        counters.count("return","replayed");
        return view(row,c);
      }
      var committed=new HashMap<String,Integer>();
      for(var item:reservations.items(row.id())) committed.put(item.sku(),item.quantity());
      var unknown=c.items().stream().filter(i->!committed.containsKey(i.sku()))
          .map(i->new Api.FieldError("items["+i.sku()+"]","not in reservation")).toList();
      if(!unknown.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","INVALID_INPUT",unknown);
      var returned=reservations.returnedTotals(row.id());
      var over=new ArrayList<Api.FieldError>();
      for(var item:c.items()) {
        int before=returned.getOrDefault(item.sku(),0);
        if(before+item.quantity()>committed.get(item.sku()))
          over.add(new Api.FieldError("items["+item.sku()+"]","committed="+committed.get(item.sku())+" returned="+before+" requested="+item.quantity()));
      }
      if(!over.isEmpty()) { counters.count("return","exceeds");throw new Api.Problem(HttpStatus.CONFLICT,"CONFLICT","RETURN_EXCEEDS_COMMITTED",over); }
      var locked=stocks.lockForUpdate(c.items().stream().map(ReservationSnapshot.Item::sku).toList());
      for(var item:c.items()) {
        jdbc.sql("""
            insert into stock_returns(id,reservation_id,sku,operation_key,quantity,reason,evidence_ref)
            values (:id,:r,:sku,:k,:q,:reason,:evidence)""")
            .param("id",UUID.randomUUID()).param("r",row.id()).param("sku",item.sku()).param("k",c.operationKey())
            .param("q",item.quantity()).param("reason",c.reason()).param("evidence",c.evidenceRef()).update();
        try {
          stocks.move(locked.get(item.sku()),item.quantity(),0,"RETURN","order:"+c.orderId()+":RETURN:"+c.operationKey(),
              c.orderId(),c.reason(),null,traceId);
        } catch(ArithmeticException e) { throw ReservationRequests.invalid("items["+item.sku()+"]","exceeds stock integer range"); }
      }
      counters.count("return","restored");
      return view(row,c);
    });
  }

  private ReturnView view(ReservationRow row,ReservationRequests.ReturnCommand c) {
    var committed=new HashMap<String,Integer>();
    for(var item:reservations.items(row.id())) committed.put(item.sku(),item.quantity());
    var totals=reservations.returnedTotals(row.id());
    return new ReturnView(row.orderId(),row.id(),c.operationKey(),c.items().stream()
        .map(i->new Line(i.sku(),i.quantity(),committed.get(i.sku()),totals.getOrDefault(i.sku(),0))).toList());
  }
}
