package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

/** INV-03 spec section 3 / T17: committed stock comes back once per operation_key, never beyond the commit. */
class ReturnIntegrationTest extends ReservationTestSupport {
  UUID committed(Object... skuOnHandQty) {
    var items=new ArrayList<Object>();
    for(int i=0;i<skuOnHandQty.length;i+=3) {
      seedStock((String)skuOnHandQty[i],(Integer)skuOnHandQty[i+1],0);items.add(skuOnHandQty[i]);items.add(skuOnHandQty[i+2]);
    }
    var s=snapshot(UUID.randomUUID(),in(15),items.toArray());data(reserve(s),201);data(commit(s.orderId()),200);
    return s.orderId();
  }
  Map<String,Object> body(UUID order,String key,Object... skuQty) {
    var items=new ArrayList<Map<String,Object>>();
    for(int i=0;i<skuQty.length;i+=2) items.add(Map.of("sku",skuQty[i],"quantity",skuQty[i+1]));
    return new LinkedHashMap<>(Map.of("order_id",order.toString(),"operation_key",key,"reason","customer return counted",
        "evidence_ref","ops-check-1","items",items));
  }
  HttpResponse<String> returns(Object body,String key) {
    return send("POST",BASE+"/returns",orderToken(),key,mapper.writeValueAsString(body));
  }
  HttpResponse<String> returnStock(UUID order,String key,Object... skuQty) { return returns(body(order,key,skuQty),key); }
  int returnRows() { return jdbc.queryForObject("select count(*) from stock_returns",Integer.class); }

  @Test void restoreAddsOnHandOnce() {
    UUID order=committed("SKU-A",5,3);
    int updates=outbox("INVENTORY_UPDATED");
    var r=returnStock(order,"ret-1","SKU-A",2);var d=data(r,200);noStore(r);
    assertThat(d.path("order_id").asString()).isEqualTo(order.toString());
    assertThat(d.path("operation_key").asString()).isEqualTo("ret-1");
    assertThat(d.path("items").get(0).path("sku").asString()).isEqualTo("SKU-A");
    assertThat(d.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    assertThat(d.path("items").get(0).path("committed").asInt()).isEqualTo(3);
    assertThat(d.path("items").get(0).path("returned_total").asInt()).isEqualTo(2);
    assertThat(onHand("SKU-A")).isEqualTo(4);assertThat(reserved("SKU-A")).isZero();
    assertThat(ledger("RETURN")).isEqualTo(1);
    assertThat(jdbc.queryForMap("select operation_key,on_hand_delta,reserved_delta,order_id from stock_transactions where type='RETURN'"))
        .containsEntry("operation_key","order:"+order+":RETURN:ret-1").containsEntry("on_hand_delta",2)
        .containsEntry("reserved_delta",0).containsEntry("order_id",order);
    assertThat(jdbc.queryForMap("select reason,evidence_ref,quantity from stock_returns"))
        .containsEntry("reason","customer return counted").containsEntry("evidence_ref","ops-check-1").containsEntry("quantity",2);
    assertThat(outbox("INVENTORY_UPDATED")).isEqualTo(updates+1);
    assertThat(data(get(order),200).path("status").asString()).isEqualTo("COMMITTED");
  }

  @Test void restockedWhenAvailableWasZero() {
    UUID order=committed("SKU-A",2,2);
    int before=outbox("INVENTORY_RESTOCKED");
    data(returnStock(order,"ret-1","SKU-A",1),200);
    assertThat(outbox("INVENTORY_RESTOCKED")).isEqualTo(before+1);
  }

  @Test void t17SameKeyReplaysWithoutAdding() throws Exception {
    UUID order=committed("SKU-A",5,3);
    var first=data(returnStock(order,"ret-1","SKU-A",2),200);
    assertThat(data(returnStock(order,"ret-1","SKU-A",2),200)).isEqualTo(first);
    assertThat(onHand("SKU-A")).isEqualTo(4);assertThat(ledger("RETURN")).isEqualTo(1);assertThat(returnRows()).isEqualTo(1);
    UUID other=committed("SKU-B",5,3);
    var barrier=new CyclicBarrier(2);
    var calls=new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for(int i=0;i<2;i++) calls.add(CompletableFuture.supplyAsync(()->{
      try { barrier.await(); } catch(Exception e) { throw new AssertionError(e); }
      return returnStock(other,"ret-p","SKU-B",3);
    }));
    assertThat(data(calls.get(0).get(),200)).isEqualTo(data(calls.get(1).get(),200));
    assertThat(onHand("SKU-B")).isEqualTo(5);
  }

  @Test void multiSkuReplayIgnoresCollationOrder() {
    // Review I1: en_US collation ignores '-' (A1 < A-2), Java code-point order puts A-2 first.
    assertThat(jdbc.queryForList("select s from unnest(array['A-2','A1']) s order by s",String.class)).containsExactly("A1","A-2");
    UUID order=committed("A1",5,2,"A-2",5,2);
    var first=data(returnStock(order,"ret-1","A1",1,"A-2",1),200);
    assertThat(data(returnStock(order,"ret-1","A-2",1,"A1",1),200)).isEqualTo(first);
    assertThat(onHand("A1")).isEqualTo(4);assertThat(onHand("A-2")).isEqualTo(4);
  }

  @Test void sameKeyDifferentItemsIs409() {
    UUID order=committed("SKU-A",5,3);
    data(returnStock(order,"ret-1","SKU-A",1),200);
    assertThat(problem(returnStock(order,"ret-1","SKU-A",2),409)).isEqualTo("RETURN_CONFLICT");
    var otherEvidence=body(order,"ret-1","SKU-A",1);otherEvidence.put("evidence_ref","ops-check-2");
    assertThat(problem(returns(otherEvidence,"ret-1"),409)).isEqualTo("RETURN_CONFLICT");
    assertThat(onHand("SKU-A")).isEqualTo(3);
  }

  @Test void cumulativeGuard() {
    UUID order=committed("SKU-A",5,3,"SKU-B",5,1);
    data(returnStock(order,"ret-1","SKU-A",2),200);
    var r=returnStock(order,"ret-2","SKU-A",2,"SKU-B",1);
    assertThat(problem(r,409)).isEqualTo("RETURN_EXCEEDS_COMMITTED");
    assertThat(json(r).path("errors").get(0).path("field").asString()).isEqualTo("items[SKU-A]");
    assertThat(json(r).path("errors").get(0).path("message").asString()).isEqualTo("committed=3 returned=2 requested=2");
    assertThat(onHand("SKU-A")).isEqualTo(4);assertThat(onHand("SKU-B")).isEqualTo(4);assertThat(returnRows()).isEqualTo(1);
    var last=data(returnStock(order,"ret-3","SKU-A",1),200);
    assertThat(last.path("items").get(0).path("returned_total").asInt()).isEqualTo(3);
    assertThat(onHand("SKU-A")).isEqualTo(5);
  }

  @Test void parallelReturnsNeverExceedCommit() throws Exception {
    UUID order=committed("SKU-A",5,3);
    var barrier=new CyclicBarrier(2);
    var calls=new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for(String key:List.of("ret-x","ret-y")) calls.add(CompletableFuture.supplyAsync(()->{
      try { barrier.await(); } catch(Exception e) { throw new AssertionError(e); }
      return returnStock(order,key,"SKU-A",2);
    }));
    var codes=new ArrayList<Integer>();for(var c:calls) codes.add(c.get().statusCode());
    assertThat(codes).containsExactlyInAnyOrder(200,409);
    assertThat(onHand("SKU-A")).isEqualTo(4);assertThat(returnRows()).isEqualTo(1);
  }

  @Test void notCommittedIs409() {
    seedStock("SKU-A",5,0);
    var active=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);data(reserve(active),201);
    assertThat(problem(returnStock(active.orderId(),"ret-1","SKU-A",1),409)).isEqualTo("RESERVATION_NOT_COMMITTED");
    var released=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);data(reserve(released),201);data(release(released,"cancel"),200);
    assertThat(problem(returnStock(released.orderId(),"ret-1","SKU-A",1),409)).isEqualTo("RESERVATION_NOT_COMMITTED");
    assertThat(onHand("SKU-A")).isEqualTo(5);assertThat(returnRows()).isZero();assertThat(ledger("RETURN")).isZero();
  }

  @Test void unknownOrderIs404() {
    assertThat(problem(returnStock(UUID.randomUUID(),"ret-1","SKU-A",1),404)).isEqualTo("RESERVATION_NOT_FOUND");
  }

  @Test void skuNotInReservationIs400() {
    UUID order=committed("SKU-A",5,3);seedStock("SKU-Z",5,0);
    assertThat(problem(returnStock(order,"ret-1","SKU-A",1,"SKU-Z",1),400)).isEqualTo("INVALID_INPUT");
    assertThat(onHand("SKU-A")).isEqualTo(2);assertThat(onHand("SKU-Z")).isEqualTo(5);assertThat(returnRows()).isZero();
  }

  @Test void lowercaseSkuMatches() {
    UUID order=committed("SKU-A",5,3);
    assertThat(data(returnStock(order,"ret-1","sku-a",1),200).path("items").get(0).path("sku").asString()).isEqualTo("SKU-A");
    assertThat(onHand("SKU-A")).isEqualTo(3);
  }

  @Test void validation() {
    UUID order=committed("SKU-A",5,3);
    var many=new ArrayList<Object>();for(int i=0;i<101;i++) { many.add("S-"+i);many.add(1); }
    var cases=new ArrayList<Map<String,Object>>(List.of(
        body(order,"ret-1","SKU-A",1,"sku-a",1),body(order,"ret-1",many.toArray()),body(order,"ret-1"),
        body(order,"ret-1","SKU-A",0),body(order,"ret-1","SKU-A",100),body(order,"ret-1","bad sku!",1)));
    for(String field:List.of("reason","evidence_ref")) for(String bad:List.of("  ","x".repeat(501))) {
      var b=body(order,"ret-1","SKU-A",1);b.put(field,bad);cases.add(b);
    }
    var missing=body(order,"ret-1","SKU-A",1);missing.remove("evidence_ref");cases.add(missing);
    var extra=body(order,"ret-1","SKU-A",1);extra.put("restock",true);cases.add(extra);
    for(var b:cases) assertThat(problem(returns(b,"ret-1"),400)).withFailMessage(b.toString()).isEqualTo("INVALID_INPUT");
    assertThat(problem(returns(body(order,"ret-1","SKU-A",1),"other"),400)).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
    assertThat(problem(returns(body(order,"ret-1","SKU-A",1),null),400)).isEqualTo("INVALID_KEY");
    String longKey="k".repeat(129);
    assertThat(problem(returns(body(order,longKey,"SKU-A",1),longKey),400)).isEqualTo("INVALID_KEY");
    assertThat(onHand("SKU-A")).isEqualTo(2);assertThat(returnRows()).isZero();
  }

  @Test void runtimeCannotRewriteReturns() {
    UUID order=committed("SKU-A",5,3);data(returnStock(order,"ret-1","SKU-A",1),200);
    assertThatThrownBy(()->jdbc.update("update stock_returns set quantity=3")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(()->jdbc.update("delete from stock_returns")).isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test void authBeforeBody() {
    assertThat(problem(send("POST",BASE+"/returns",null,"k","{bad"),401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("POST",BASE+"/returns",serviceToken("cart-service"),"k","{bad"),403)).isEqualTo("CALLER_NOT_ALLOWED");
  }

  @Test void getShowsReturned() {
    UUID order=committed("SKU-A",5,3,"SKU-B",5,1);
    data(returnStock(order,"ret-1","SKU-A",2),200);
    var items=data(get(order),200).path("items");
    assertThat(items.get(0).path("sku").asString()).isEqualTo("SKU-A");
    assertThat(items.get(0).path("returned").asInt()).isEqualTo(2);
    assertThat(items.get(1).path("returned").asInt()).isZero();
  }
}
