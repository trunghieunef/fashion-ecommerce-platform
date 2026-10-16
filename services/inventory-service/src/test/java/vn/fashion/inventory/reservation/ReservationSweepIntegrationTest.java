package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationSweepIntegrationTest extends ReservationTestSupport {
  @Autowired ReservationService service;
  @Autowired MeterRegistry meters;
  @Autowired ReservationRepository reservations;
  @Autowired TransactionTemplate tx;
  ReservationSnapshot due(String sku,int q) {
    seedStock(sku,5,0);var s=snapshot(UUID.randomUUID(),in(15),sku,q);data(reserve(s),201);
    jdbc.update("update stock_reservations set expires_at=now()-interval '1 second' where order_id=?",s.orderId());return s;
  }
  @Test void expiresDueReservationsOnly() {
    var expired=meters.counter("inventory.reservations","operation","expire","outcome","expired");
    double before=expired.count();
    var old=due("SKU-A",2);seedStock("SKU-B",5,0);var fresh=snapshot(UUID.randomUUID(),in(15),"SKU-B",1);data(reserve(fresh),201);
    assertThat(meters.get("inventory.reservations.overdue").gauge().value()).isEqualTo(1.0);
    assertThat(service.expireDue(100)).isEqualTo(1);
    assertThat(data(get(old.orderId()),200).path("status").asString()).isEqualTo("EXPIRED");
    assertThat(data(get(fresh.orderId()),200).path("status").asString()).isEqualTo("ACTIVE");
    assertThat(reserved("SKU-A")).isZero();assertThat(reserved("SKU-B")).isEqualTo(1);
    assertThat(service.expireDue(100)).isZero();assertThat(ledger("RELEASE")).isEqualTo(1);
    assertThat(meters.get("inventory.reservations.overdue").gauge().value()).isZero();
    assertThat(meters.get("inventory.reservations").tags("operation","expire","outcome","expired").counter().count()).isEqualTo(before+1.0);
  }
  @Test void t05CommitAndSweepRaceHasOneOutcome() throws Exception {
    for(int round=0;round<10;round++) {
      clearInventory();var s=due("SKU-A",2);var gate=new CountDownLatch(1);
      try(var pool=Executors.newFixedThreadPool(2)) {
        var commit=pool.submit(()->{gate.await();return commit(s.orderId()).statusCode();});
        var sweep=pool.submit(()->{gate.await();return service.expireDue(100);});
        gate.countDown();
        assertThat(commit.get(30,TimeUnit.SECONDS)).isEqualTo(409);sweep.get(30,TimeUnit.SECONDS);
      }
      assertThat(jdbc.queryForObject("select status from stock_reservations",String.class)).isEqualTo("EXPIRED");
      assertThat(ledger("RELEASE")).isEqualTo(1);assertThat(ledger("COMMIT")).isZero();
      assertThat(reserved("SKU-A")).isZero();assertThat(onHand("SKU-A")).isEqualTo(5);
    }
  }
  @Test void sweepSkipsRowHeldByCommitInFlight() throws Exception {
    var s=due("SKU-A",1);
    try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),"postgres","postgres")) {
      c.setAutoCommit(false);
      try(var st=c.prepareStatement("select id from stock_reservations where order_id=? for update")) { st.setObject(1,s.orderId());st.executeQuery(); }
      assertThat(CompletableFuture.supplyAsync(()->service.expireDue(100)).get(10,TimeUnit.SECONDS)).isZero();
      c.rollback();
    }
    assertThat(service.expireDue(100)).isEqualTo(1);
  }
  @Test void commitBeforeDeadlineBeatsSweep() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);data(reserve(s),201);
    data(commit(s.orderId()),200);
    jdbc.update("update stock_reservations set expires_at=now()-interval '1 second'");
    var id=jdbc.queryForObject("select id from stock_reservations where order_id=?",UUID.class,s.orderId());
    // Simulate a stale candidate selected before commit: lockDue must recheck ACTIVE itself.
    var candidate=tx.execute(status->reservations.lockDue(id));
    assertThat(candidate).isEmpty();
    assertThat(service.expireDue(100)).isZero();assertThat(onHand("SKU-A")).isEqualTo(4);
  }
}
