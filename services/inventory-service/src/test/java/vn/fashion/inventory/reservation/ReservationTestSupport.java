package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import vn.fashion.inventory.InventoryTestSupport;

abstract class ReservationTestSupport extends InventoryTestSupport {
  static final String BASE="/internal/api/v1/inventory";
  String orderToken() { return serviceToken("order-service"); }
  Instant in(long minutes) { return Instant.now().plus(minutes,ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS); }
  ReservationSnapshot snapshot(UUID order,Instant expires,Object... skuQty) {
    var items=new ArrayList<ReservationSnapshot.Item>();
    for(int i=0;i<skuQty.length;i+=2) items.add(new ReservationSnapshot.Item((String)skuQty[i],(Integer)skuQty[i+1]));
    return new ReservationSnapshot(order,"ORD-"+order.toString().substring(0,8),expires,items).normalized();
  }
  Map<String,Object> body(ReservationSnapshot s) {
    var items=s.items().stream().map(i->Map.of("sku",i.sku(),"quantity",i.quantity())).toList();
    return Map.of("order_id",s.orderId().toString(),"order_no",s.orderNo(),"items",items,
        "expires_at",ReservationSnapshot.time(s.expiresAt()),"request_hash",s.hash());
  }
  HttpResponse<String> post(String op,Object body) {
    return send("POST",BASE+"/"+op,orderToken(),UUID.randomUUID().toString(),mapper.writeValueAsString(body));
  }
  HttpResponse<String> reserve(ReservationSnapshot s) { return post("reserve",body(s)); }
  HttpResponse<String> commit(UUID order) { return post("commit",Map.of("order_id",order.toString())); }
  HttpResponse<String> release(ReservationSnapshot s,String reason) {
    var original=new LinkedHashMap<>(body(s));original.remove("order_id");original.remove("request_hash");
    return post("release",Map.of("order_id",s.orderId().toString(),"original_reservation",original,
        "original_request_hash",s.hash(),"reason",reason));
  }
  HttpResponse<String> get(UUID order) { return send("GET",BASE+"/reservations/"+order,orderToken(),null,null); }
  void noStore(HttpResponse<String> r) { assertThat(r.headers().firstValue("Cache-Control")).contains("no-store"); }
  int reserved(String sku) { return jdbc.queryForObject("select reserved from stock_items where sku=?",Integer.class,sku); }
  int onHand(String sku) { return jdbc.queryForObject("select on_hand from stock_items where sku=?",Integer.class,sku); }
  int ledger(String type) { return jdbc.queryForObject("select count(*) from stock_transactions where type=?",Integer.class,type); }
  int outbox(String type) { return jdbc.queryForObject("select count(*) from outbox_events where event_type=?",Integer.class,type); }
  String problem(HttpResponse<String> r,int status) {
    assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(status);noStore(r);return json(r).path("message").asString();
  }
}
