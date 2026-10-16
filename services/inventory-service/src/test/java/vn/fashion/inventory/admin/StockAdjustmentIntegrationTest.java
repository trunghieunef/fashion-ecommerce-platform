package vn.fashion.inventory.admin;

import static org.assertj.core.api.Assertions.*;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import vn.fashion.inventory.InventoryTestSupport;

class StockAdjustmentIntegrationTest extends InventoryTestSupport {
  @Test void adjustmentCannotMoveOnHandBelowReserved() {
    seedStock("SKU-1",5,4);var r=adjust("SKU-1",-2,"damage","op-1");
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(json(r).path("message").asText()).isEqualTo("ON_HAND_BELOW_RESERVED");
    assertStock("SKU-1",5,4); assertLedgerEmpty();
    assertThat(jdbc.queryForObject("select count(*) from outbox_events",Integer.class)).isZero();
  }
  @Test void positiveAdjustWritesLedgerAndRestocked() {
    seedStock("SKU-1",0,0); var d=data(adjust("SKU-1",5," restock ","op-1"),200);
    assertThat(d.path("available").asInt()).isEqualTo(5);assertThat(d.path("version").asLong()).isEqualTo(1);
    assertThat(jdbc.queryForMap("select type,on_hand_delta,reason,actor_id from stock_transactions"))
        .containsEntry("type","ADJUST").containsEntry("on_hand_delta",5).containsEntry("reason","restock").containsEntry("actor_id",ACTOR);
    assertThat(jdbc.queryForList("select event_type from outbox_events order by aggregate_sequence",String.class))
        .containsExactly("INVENTORY_UPDATED","INVENTORY_RESTOCKED");
  }
  @Test void negativeAdjustToZeroEmitsOnlyUpdated() {
    seedStock("SKU-1",5,0);data(adjust("SKU-1",-5,"damage","op-1"),200);assertStock("SKU-1",0,0);
    assertThat(jdbc.queryForList("select event_type from outbox_events",String.class)).containsExactly("INVENTORY_UPDATED");
  }
  @Test void replaySameKeyReturnsSameResponse() {
    seedStock("SKU-1",0,0);var a=adjust("SKU-1",5,"in","op-1");var b=adjust("SKU-1",5,"in","op-1");
    assertThat(data(a,200)).isEqualTo(data(b,200));
    assertThat(json(a).path("metadata").path("request_id")).isNotEqualTo(json(b).path("metadata").path("request_id"));
    assertStock("SKU-1",5,0);assertThat(jdbc.queryForObject("select count(*) from stock_transactions",Integer.class)).isEqualTo(1);
  }
  @Test void sameKeyDifferentBodyIsConflict() {
    seedStock("SKU-1",0,0);data(adjust("SKU-1",5,"in","op-1"),200);
    assertThat(adjust("SKU-1",6,"in","op-1").statusCode()).isEqualTo(409);assertStock("SKU-1",5,0);
  }
  @Test void zeroDeltaAndBlankReasonAreRejected() {
    seedStock("SKU-1",0,0);
    invalid(adjust("SKU-1",0,"in","zero"),"delta");
    invalid(adjust("SKU-1",1," ","blank"),"reason");
    invalid(adjust("SKU-1",1,"x".repeat(501),"long"),"reason");assertLedgerEmpty();
  }
  @Test void overflowIsRejected() {
    seedStock("SKU-1",Integer.MAX_VALUE-1,0);invalid(adjust("SKU-1",2,"in","overflow"),"delta");assertLedgerEmpty();
  }
  @Test void unknownSkuIs404() {
    var r=adjust("MISSING",1,"in","missing");assertThat(r.statusCode()).isEqualTo(404);
    assertThat(json(r).path("message").asText()).isEqualTo("SKU_NOT_FOUND");
  }
  @Test void lowercaseSkuIsCanonicalized() {
    seedStock("SKU-1",0,0);data(adjust(" sku-1 ",1,"in","canonical"),200);assertStock("SKU-1",1,0);
    data(adjust("SKU-1",1,"in","canonical"),200);assertStock("SKU-1",1,0);
  }
  @Test void concurrentAdjustsDoNotLoseUpdates() throws Exception {
    seedStock("SKU-1",0,0);var gate=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(20)) {
      var futures=new ArrayList<Future<Integer>>();
      for(int i=0;i<20;i++) { String key="race-"+i;futures.add(pool.submit(()->{gate.await();return adjust("SKU-1",1,"race",key).statusCode();})); }
      gate.countDown();for(var f:futures) assertThat(f.get(30,TimeUnit.SECONDS)).isEqualTo(200);
    }
    assertStock("SKU-1",20,0);
    assertThat(jdbc.queryForObject("select version from stock_items where sku='SKU-1'",Long.class)).isEqualTo(20);
    assertThat(jdbc.queryForObject("select count(*) from stock_transactions",Integer.class)).isEqualTo(20);
  }
  @Test void readPermissionCannotAdjust() {
    seedStock("SKU-1",0,0);String body=mapper.writeValueAsString(Map.of("sku","SKU-1","delta",1,"reason","in"));
    assertThat(send("POST","/admin/api/v1/inventory/adjust",token("inventory.read"),"key",body).statusCode()).isEqualTo(403);
    assertThat(send("POST","/admin/api/v1/inventory/adjust",null,"key",body).statusCode()).isEqualTo(401);
    invalid(send("POST","/admin/api/v1/inventory/adjust",token("inventory.adjust"),null,body),"Idempotency-Key");assertLedgerEmpty();
  }
  @Test void separateActorsCanReuseKeyForSameSku() {
    seedStock("SKU-1",0,0);data(adjust("SKU-1",1,"in","shared"),200);
    String body=mapper.writeValueAsString(Map.of("sku","SKU-1","delta",1,"reason","in"));
    data(send("POST","/admin/api/v1/inventory/adjust",tokenFor(UUID.randomUUID(),"inventory.adjust"),"shared",body),200);
    assertStock("SKU-1",2,0);
    assertThat(jdbc.queryForObject("select count(distinct operation_key) from stock_transactions",Integer.class)).isEqualTo(2);
  }
  @Test void invalidFieldsAre400WithoutWrites() {
    seedStock("SKU-1",0,0);
    for(String body:List.of("{\"sku\":\"SKU-1\",\"delta\":null,\"reason\":\"in\"}","{\"sku\":\"SKU-1\",\"delta\":1.5,\"reason\":\"in\"}"))
      invalid(send("POST","/admin/api/v1/inventory/adjust",token("inventory.adjust"),UUID.randomUUID().toString(),body),"delta");
    invalid(send("POST","/admin/api/v1/inventory/adjust",token("inventory.adjust"),"extra","{\"sku\":\"SKU-1\",\"delta\":1,\"reason\":\"in\",\"extra\":true}"),"extra");
    assertLedgerEmpty();
  }
  HttpResponse<String> adjust(String sku,int delta,String reason,String key) {
    return send("POST","/admin/api/v1/inventory/adjust",token("inventory.adjust"),key,mapper.writeValueAsString(Map.of("sku",sku,"delta",delta,"reason",reason)));
  }
  void assertStock(String sku,int onHand,int reserved) {
    assertThat(jdbc.queryForMap("select on_hand,reserved from stock_items where sku=?",sku)).containsEntry("on_hand",onHand).containsEntry("reserved",reserved);
  }
  void assertLedgerEmpty() { assertThat(jdbc.queryForObject("select count(*) from stock_transactions",Integer.class)).isZero(); }
  void invalid(HttpResponse<String> r,String field) {
    assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(400);
    assertThat(json(r).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    assertThat(json(r).path("errors").get(0).path("field").asText()).isEqualTo(field);
  }
}
