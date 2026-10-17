package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ReserveIntegrationTest extends ReservationTestSupport {
  @Test void reservesAllSkusWithLedgerAndEvents() {
    seedStock("SKU-A",5,0);seedStock("SKU-B",3,1);UUID order=UUID.randomUUID();
    var s=snapshot(order,in(15),"sku-b",2,"SKU-A",1);
    var r=reserve(s);var d=data(r,201);noStore(r);
    assertThat(d.path("status").asString()).isEqualTo("ACTIVE");assertThat(d.path("version").asLong()).isEqualTo(1);
    assertThat(d.path("items").toString()).isEqualTo("[{\"sku\":\"SKU-A\",\"quantity\":1},{\"sku\":\"SKU-B\",\"quantity\":2}]");
    assertThat(d.path("restore_required").asBoolean()).isFalse();
    assertThat(reserved("SKU-A")).isEqualTo(1);assertThat(reserved("SKU-B")).isEqualTo(3);
    assertThat(jdbc.queryForList("select operation_key from stock_transactions where type='RESERVE' order by sku",String.class))
        .containsExactly("order:"+order+":RESERVE","order:"+order+":RESERVE");
    assertThat(outbox("INVENTORY_RESERVED")).isEqualTo(1);assertThat(outbox("INVENTORY_UPDATED")).isEqualTo(2);
    var event=jdbc.queryForMap("select aggregate_type,aggregate_id,aggregate_version,partition_key from outbox_events where event_type='INVENTORY_RESERVED'");
    assertThat(event).containsEntry("aggregate_type","reservation").containsEntry("aggregate_id",d.path("reservation_id").asString())
        .containsEntry("aggregate_version",1L).containsEntry("partition_key",s.orderNo());
  }
  @Test void t01LastUnitGoesToExactlyOneOrder() throws Exception {
    seedStock("SKU-A",1,0);var gate=new CountDownLatch(1);var codes=new ConcurrentLinkedQueue<Integer>();
    try(var pool=Executors.newFixedThreadPool(8)) {
      for(int i=0;i<8;i++) pool.submit(()->{gate.await();codes.add(reserve(snapshot(UUID.randomUUID(),in(15),"SKU-A",1)).statusCode());return null;});
      gate.countDown();
    }
    assertThat(codes).filteredOn(c->c==201).hasSize(1);assertThat(codes).filteredOn(c->c==409).hasSize(7);
    assertThat(reserved("SKU-A")).isEqualTo(1);assertThat(ledger("RESERVE")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from stock_reservations",Integer.class)).isEqualTo(1);
  }
  @Test void t02OneShortSkuRollsBackEverything() {
    seedStock("SKU-A",5,0);seedStock("SKU-B",1,0);
    jdbc.update("insert into stock_items(sku,product_id,variant_id,on_hand,status) values ('SKU-C',?,?,9,'INACTIVE')",UUID.randomUUID(),UUID.randomUUID());
    var r=reserve(snapshot(UUID.randomUUID(),in(15),"SKU-A",2,"SKU-B",2,"SKU-C",1,"SKU-Z",1));
    assertThat(problem(r,409)).isEqualTo("OUT_OF_STOCK");assertThat(json(r).path("code").asString()).isEqualTo("OUT_OF_STOCK");
    assertThat(json(r).path("errors").toString()).contains("items[SKU-B]","requested=2 available=1","items[SKU-C]","requested=1 available=0","items[SKU-Z]")
        .doesNotContain("items[SKU-A]");
    assertThat(reserved("SKU-A")).isZero();assertThat(ledger("RESERVE")).isZero();
    assertThat(jdbc.queryForObject("select count(*) from stock_reservations",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from outbox_events",Integer.class)).isZero();
  }
  @Test void t03RetrySameHashReturnsExistingOnce() throws Exception {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",2);
    var first=data(reserve(s),201);
    var again=data(reserve(s),200);
    assertThat(again.path("reservation_id")).isEqualTo(first.path("reservation_id"));
    var gate=new CountDownLatch(1);var codes=new ConcurrentLinkedQueue<Integer>();var t=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);
    try(var pool=Executors.newFixedThreadPool(6)) {
      var futures=new ArrayList<Future<?>>();
      for(int i=0;i<6;i++) futures.add(pool.submit(()->{gate.await();codes.add(reserve(t).statusCode());return null;}));
      gate.countDown();
      for(var future:futures) future.get(30,TimeUnit.SECONDS);
    }
    assertThat(codes).hasSize(6);
    assertThat(codes).containsOnly(200,201).filteredOn(c->c==201).hasSize(1);
    assertThat(reserved("SKU-A")).isEqualTo(3);assertThat(ledger("RESERVE")).isEqualTo(2);
  }
  @Test void sameOrderDifferentHashIsConflict() {
    seedStock("SKU-A",5,0);UUID order=UUID.randomUUID();
    data(reserve(snapshot(order,in(15),"SKU-A",1)),201);
    assertThat(problem(reserve(snapshot(order,in(15),"SKU-A",2)),409)).isEqualTo("RESERVATION_CONFLICT");
    assertThat(reserved("SKU-A")).isEqualTo(1);
  }
  @Test void replayAfterDeadlineExpiresUnsweptReservation() {
    // Retry of a request whose expires_at already passed: the stored row answers before the 400 deadline check,
    // and an overdue ACTIVE row is expired in the same transaction instead of being reported as still held.
    seedStock("SKU-A",5,1);var s=snapshot(UUID.randomUUID(),in(-5),"SKU-A",1);UUID id=UUID.randomUUID();
    jdbc.update("insert into stock_reservations(id,order_id,order_no,request_hash,status,expires_at,version) values (?,?,?,?,'ACTIVE',now()-interval '5 minutes',1)",
        id,s.orderId(),s.orderNo(),s.hash());
    jdbc.update("insert into stock_reservation_items(reservation_id,sku,quantity) values (?,'SKU-A',1)",id);
    assertThat(problem(reserve(s),409)).isEqualTo("RESERVATION_CLOSED");
    assertThat(jdbc.queryForMap("select status,release_reason,version from stock_reservations"))
        .containsEntry("status","EXPIRED").containsEntry("release_reason","expired").containsEntry("version",2L);
    assertThat(reserved("SKU-A")).isZero();assertThat(ledger("RELEASE")).isEqualTo(1);assertThat(outbox("INVENTORY_RELEASED")).isEqualTo(1);
    assertThat(problem(reserve(s),409)).isEqualTo("RESERVATION_CLOSED");assertThat(ledger("RELEASE")).isEqualTo(1);
  }
  @Test void committedReplayAfterDeadlineStaysCommitted() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);data(reserve(s),201);data(commit(s.orderId()),200);
    jdbc.update("update stock_reservations set expires_at=now()-interval '1 second'");
    assertThat(data(reserve(s),200).path("status").asString()).isEqualTo("COMMITTED");assertThat(ledger("RELEASE")).isZero();
  }
  @Test void manyItemsAreStoredInOneReservation() {
    var skus=new ArrayList<Object>();
    for(int i=0;i<100;i++) { String sku=String.format("SKU-%03d",i);seedStock(sku,5,0);skus.add(sku);skus.add(1+i%3); }
    var d=data(reserve(snapshot(UUID.randomUUID(),in(15),skus.toArray())),201);
    assertThat(d.path("items")).hasSize(100);assertThat(d.path("items").get(99).path("sku").asString()).isEqualTo("SKU-099");
    assertThat(jdbc.queryForObject("select sum(quantity) from stock_reservation_items",Integer.class))
        .isEqualTo(jdbc.queryForObject("select sum(reserved) from stock_items",Integer.class));
  }
  @Test void deadlineBoundsUseDatabaseClock() {
    seedStock("SKU-A",5,0);
    var r=reserve(snapshot(UUID.randomUUID(),in(-1),"SKU-A",1));
    assertThat(problem(r,400)).isEqualTo("INVALID_INPUT");assertThat(json(r).path("errors").get(0).path("field").asString()).isEqualTo("expires_at");
    assertThat(problem(reserve(snapshot(UUID.randomUUID(),in(24*60+1),"SKU-A",1)),400)).isEqualTo("INVALID_INPUT");
    assertThat(reserve(snapshot(UUID.randomUUID(),in(24*60-1),"SKU-A",1)).statusCode()).isEqualTo(201);
    assertThat(jdbc.queryForObject("select count(*) from stock_reservations",Integer.class)).isEqualTo(1);
  }
  @Test void authCallerAndKeyAreCheckedBeforeBody() {
    var path=BASE+"/reserve";
    assertThat(problem(send("POST",path,null,"k","not json"),401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("POST",path,serviceToken("cart-service"),"k","not json"),403)).isEqualTo("CALLER_NOT_ALLOWED");
    assertThat(problem(send("POST",path,orderToken(),null,"{}"),400)).isEqualTo("INVALID_KEY");
  }
  @Test void malformedBodyIs400OnlyAfterAuth() {
    var path=BASE+"/reserve";
    assertThat(problem(send("POST",path,orderToken(),"k","not json"),400)).isEqualTo("MALFORMED_JSON");
    assertThat(problem(send("POST",path,orderToken(),"k","[1]"),400)).isEqualTo("INVALID_INPUT");
    assertThat(problem(send("POST",path,orderToken(),"k",null),400)).isEqualTo("MALFORMED_JSON");
  }
  @Test void getReturnsReservationAnd404() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);data(reserve(s),201);
    var r=get(s.orderId());var d=data(r,200);noStore(r);
    assertThat(d.path("order_no").asString()).isEqualTo(s.orderNo());
    assertThat(d.path("expires_at").asString()).isEqualTo(ReservationSnapshot.time(s.expiresAt()));
    assertThat(problem(get(UUID.randomUUID()),404)).isEqualTo("RESERVATION_NOT_FOUND");
    assertThat(problem(send("GET",BASE+"/reservations/nope",null,null,null),401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("GET",BASE+"/reservations/nope",orderToken(),null,null),400)).isEqualTo("INVALID_INPUT");
  }
  @Test void reserveConcurrentWithAdjustDoesNotDeadlock() throws Exception {
    seedStock("SKU-A",1000,0);seedStock("SKU-B",1000,0);seedStock("SKU-C",1000,0);
    var gate=new CountDownLatch(1);var codes=new ConcurrentLinkedQueue<Integer>();
    try(var pool=Executors.newFixedThreadPool(16)) {
      for(int i=0;i<8;i++) {
        pool.submit(()->{gate.await();codes.add(reserve(snapshot(UUID.randomUUID(),in(15),"SKU-C",1,"SKU-A",1,"SKU-B",1)).statusCode());return null;});
        String sku=i%2==0?"SKU-C":"SKU-A";
        pool.submit(()->{gate.await();codes.add(call("POST","/adjust",Map.of("sku",sku,"delta",1,"reason","race")).statusCode());return null;});
      }
      gate.countDown();pool.shutdown();assertThat(pool.awaitTermination(60,TimeUnit.SECONDS)).isTrue();
    }
    assertThat(codes).hasSize(16).containsOnly(200,201);
  }
}
