package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ReleaseIntegrationTest extends ReservationTestSupport {
  @Test void releaseExistingExpiredIsNoop() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",2);data(reserve(s),201);
    jdbc.update("update stock_reservations set expires_at=now()-interval '1 second'");
    assertThat(problem(commit(s.orderId()),409)).isEqualTo("RESERVATION_EXPIRED");
    var before=jdbc.queryForMap("select version,released_at,release_reason,status from stock_reservations");
    var ledgerBefore=jdbc.queryForList("select * from stock_transactions order by id");
    var outboxBefore=jdbc.queryForList("select * from outbox_events order by id");
    var d=data(release(s,"must not replace expired reason"),200);
    assertThat(d.path("status").asString()).isEqualTo("EXPIRED");
    assertThat(d.path("release_reason").asString()).isEqualTo("expired");
    assertThat(d.path("version").asLong()).isEqualTo(2);
    assertThat(jdbc.queryForMap("select version,released_at,release_reason,status from stock_reservations")).isEqualTo(before);
    assertThat(onHand("SKU-A")).isEqualTo(5);assertThat(reserved("SKU-A")).isZero();
    assertThat(jdbc.queryForList("select * from stock_transactions order by id")).isEqualTo(ledgerBefore);
    assertThat(jdbc.queryForList("select * from outbox_events order by id")).isEqualTo(outboxBefore);
  }
  @Test void releaseWaitsForReservationInsertBeforeTakingSkuLock() throws Exception {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",2);
    var started=new CountDownLatch(1);
    var pool=Executors.newSingleThreadExecutor();
    try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),"postgres","postgres")) {
      c.setAutoCommit(false);
      try(var insert=c.prepareStatement("insert into stock_reservations(id,order_id,order_no,request_hash,status,expires_at,version) values (?,?,?,?,'ACTIVE',?,1)")) {
        insert.setObject(1,UUID.randomUUID());insert.setObject(2,s.orderId());insert.setString(3,s.orderNo());
        insert.setString(4,s.hash());insert.setTimestamp(5,Timestamp.from(s.expiresAt()));insert.executeUpdate();
      }
      int blocker;
      try(var statement=c.createStatement();var result=statement.executeQuery("select pg_backend_pid()")) {
        result.next();blocker=result.getInt(1);
      }
      var response=pool.submit(()->{started.countDown();return release(s,"reserve timeout");});
      assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
      boolean waiting=false;
      while(System.nanoTime()<deadline && !response.isDone()) {
        waiting=jdbc.queryForObject("""
            select exists(select 1 from pg_stat_activity a
              where a.wait_event_type='Lock' and a.query like '%insert into stock_reservations%'
              and ? = any(pg_blocking_pids(a.pid)))
            """,Boolean.class,blocker);
        if(waiting) break;
        Thread.onSpinWait();
      }
      assertThat(waiting).as("release must wait on the competing reservation insert").isTrue();
      try(var statement=c.createStatement()) {
        statement.execute("set local lock_timeout='2s'");
        // A real reserve inserts its reservation before locking SKU. It must not deadlock with release.
        try(var result=statement.executeQuery("select sku from stock_items where sku='SKU-A' for update")) {
          assertThat(result.next()).isTrue();
        }
      }
      c.rollback();
      var d=data(response.get(10,TimeUnit.SECONDS),200);
      assertThat(d.path("status").asString()).isEqualTo("RELEASED");
      assertThat(d.path("version").asLong()).isEqualTo(1);
      assertThat(reserved("SKU-A")).isZero();assertThat(ledger("RESERVE")).isZero();
      assertThat(ledger("RELEASE")).isZero();assertThat(outbox("INVENTORY_RELEASED")).isEqualTo(1);
      assertThat(problem(reserve(s),409)).isEqualTo("RESERVATION_CLOSED");
    } finally {
      pool.shutdownNow();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();
    }
  }
  @Test void releaseActiveFreesStockAndRepeatIsNoop() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",5);data(reserve(s),201);
    var r=release(s,"customer cancel");var d=data(r,200);noStore(r);
    assertThat(d.path("status").asString()).isEqualTo("RELEASED");assertThat(d.path("release_reason").asString()).isEqualTo("customer cancel");
    assertThat(reserved("SKU-A")).isZero();assertThat(ledger("RELEASE")).isEqualTo(1);
    assertThat(outbox("INVENTORY_RESTOCKED")).isEqualTo(1);
    assertThat(data(release(s,"again"),200).path("version").asLong()).isEqualTo(2);
    assertThat(ledger("RELEASE")).isEqualTo(1);assertThat(outbox("INVENTORY_RELEASED")).isEqualTo(1);
  }
  @Test void t04TombstoneBlocksLateReserve() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",2);
    var d=data(release(s,"reserve timeout"),200);
    assertThat(d.path("status").asString()).isEqualTo("RELEASED");assertThat(d.path("version").asLong()).isEqualTo(1);
    assertThat(d.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    assertThat(problem(reserve(s),409)).isEqualTo("RESERVATION_CLOSED");
    assertThat(reserved("SKU-A")).isZero();assertThat(ledger("RESERVE")).isZero();assertThat(outbox("INVENTORY_RELEASED")).isEqualTo(1);
    assertThat(data(release(s,"reserve timeout"),200).path("status").asString()).isEqualTo("RELEASED");
  }
  @Test void releaseAfterCommitRequiresRestoreWithoutTouchingStock() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",2);data(reserve(s),201);data(commit(s.orderId()),200);
    var d=data(release(s,"cancel"),200);
    assertThat(d.path("status").asString()).isEqualTo("COMMITTED");assertThat(d.path("restore_required").asBoolean()).isTrue();
    assertThat(onHand("SKU-A")).isEqualTo(3);assertThat(ledger("RELEASE")).isZero();
  }
  @Test void releaseOfExpiredDeadlineSnapshotIsAccepted() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(-60),"SKU-A",1);
    assertThat(data(release(s,"late"),200).path("status").asString()).isEqualTo("RELEASED");
  }
  @Test void differentSnapshotForStoredReservationIsConflict() {
    seedStock("SKU-A",5,0);UUID order=UUID.randomUUID();data(reserve(snapshot(order,in(15),"SKU-A",1)),201);
    assertThat(problem(release(snapshot(order,in(15),"SKU-A",2),"cancel"),409)).isEqualTo("RESERVATION_CONFLICT");
    assertThat(reserved("SKU-A")).isEqualTo(1);
  }
  @Test void tombstoneWithUnknownSkuIs400() {
    var r=release(snapshot(UUID.randomUUID(),in(15),"NOPE",1),"cancel");
    assertThat(problem(r,400)).isEqualTo("INVALID_INPUT");
    assertThat(json(r).path("errors").get(0).path("field").asString()).isEqualTo("original_reservation.items[NOPE]");
    assertThat(jdbc.queryForObject("select count(*) from stock_reservations",Integer.class)).isZero();
  }
  @Test void hashMismatchAndBlankReasonAre400() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);
    assertThat(problem(release(s," "),400)).isEqualTo("INVALID_INPUT");
    var body=new java.util.HashMap<String,Object>(java.util.Map.of("order_id",s.orderId().toString(),"reason","x",
        "original_request_hash","0".repeat(64),"original_reservation",java.util.Map.of("order_no",s.orderNo(),
        "items",java.util.List.of(java.util.Map.of("sku","SKU-A","quantity",1)),"expires_at",ReservationSnapshot.time(s.expiresAt()))));
    assertThat(problem(post("release",body),400)).isEqualTo("REQUEST_HASH_MISMATCH");
  }
}
