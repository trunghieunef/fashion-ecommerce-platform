package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import vn.fashion.order.saga.SagaWorker;

/** 06 section 1.2: failures back off, then MANUAL with the obligation kept (never dropped). */
@TestPropertySource(properties = "fashion.order.saga.max-retries=3")
class SagaManualIntegrationTest extends OrderTestSupport {
  @Autowired SagaWorker worker;
  @Autowired MeterRegistry meters;

  @Test void retriesBackOffThenGoManual() {
    UUID id = placeOrder();
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(503, StubServers.error("TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE")));
    double before = meters.counter("order.saga.manual", "step", "RESERVE").count();
    Instant previous = Instant.EPOCH;
    for (int i = 1; i <= 2; i++) {
      worker.runOnce();
      var saga = saga(id);
      assertThat(saga).containsEntry("retry_count", i).containsEntry("status", "RUNNING").containsEntry("inventory_state", "UNKNOWN");
      Instant next = ((Timestamp) saga.get("next_retry_at")).toInstant();
      assertThat(next).isAfter(clock.instant()).isAfter(previous);
      assertThat(saga.get("last_error").toString()).contains("inventory");
      previous = next;
      clock.shift(Duration.ofMinutes(10));
    }
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("retry_count", 3).containsEntry("inventory_state", "UNKNOWN");
    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(meters.counter("order.saga.manual", "step", "RESERVE").count()).isEqualTo(before + 1);
    clock.shift(Duration.ofHours(1));
    StubServers.INVENTORY.seen.clear();
    worker.runOnce();
    assertThat(StubServers.INVENTORY.seen).isEmpty();                    // MANUAL is never picked up again
  }

  @Test void aCrashAfterTheIntentStillSpendsTheBudget() {
    // PR #33 review M1: the worker dies after the intent (simulated: the lease is gone, the result is dropped) every time.
    UUID id = placeOrder();
    StubServers.INVENTORY.reply(s -> {
      asOwner("update order_sagas set lease_until = now() - interval '1 second' where order_id = '" + id + "'");
      return new StubServers.Reply(200, StubServers.ok("{\"status\":\"ACTIVE\"}"));
    });
    for (int i = 1; i <= 3; i++) {
      worker.runOnce();
      assertThat(saga(id)).containsEntry("retry_count", i).containsEntry("status", "RUNNING");
      assertThat(((Timestamp) saga(id).get("next_retry_at")).toInstant()).isAfter(clock.instant());   // backs off
      clock.shift(Duration.ofMinutes(10));
    }
    StubServers.INVENTORY.seen.clear();
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "MANUAL");
    assertThat(StubServers.INVENTORY.seen).isEmpty();
  }

  @Test void aCrashAfterACompensationIntentStillEndsManual() {
    // ORD-04 spec section 7: every compensation call has an intent, so a worker dying mid-call each time still ends MANUAL.
    UUID id = placeOrder();
    asOwner("update orders set status = 'CANCELLING', cancel_reason = 'OPS_CANCELLED' where id = '" + id + "'");
    asOwner("update order_sagas set intent = 'CANCEL', step = 'COMPENSATE', status = 'COMPENSATING', inventory_state = 'COMMITTED' where order_id = '" + id + "'");
    java.util.function.Function<StubServers.Seen, StubServers.Reply> crash = s -> {
      asOwner("update order_sagas set lease_until = now() - interval '1 second' where order_id = '" + id + "'");
      return new StubServers.Reply(200, StubServers.ok("{\"status\":\"CANCELLED\"}"));
    };
    StubServers.SHIPPING.reply(crash);
    StubServers.INVENTORY.reply(crash);
    StubServers.PAYMENT.reply(crash);
    for (int i = 1; i <= 3; i++) {
      worker.runOnce();
      assertThat(saga(id)).containsEntry("retry_count", i).containsEntry("status", "COMPENSATING").containsEntry("inventory_state", "COMMITTED");
      assertThat(((Timestamp) saga(id).get("next_retry_at")).toInstant()).isAfter(clock.instant());
      clock.shift(Duration.ofMinutes(10));
    }
    double before = meters.counter("order.saga.manual", "step", "COMPENSATE").count();
    StubServers.SHIPPING.seen.clear();
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("inventory_state", "COMMITTED").containsEntry("shipment_state", "NONE");
    assertThat(StubServers.SHIPPING.seen).isEmpty();
    assertThat(StubServers.INVENTORY.seen).isEmpty();
    assertThat(meters.counter("order.saga.manual", "step", "COMPENSATE").count()).isEqualTo(before + 1);
    assertThat(status(id)).isEqualTo("CANCELLING");
  }

  @Test void aStepThatThrowsCountsAsARetryAndEndsManual() {
    // Review I2: payment 200 without payment_id throws; it must spend the retry budget, not loop every lease forever.
    UUID id = placeOrder();
    stubSagaOk();
    StubServers.PAYMENT.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"COD_PENDING\"}")));
    for (int i = 1; i <= 3; i++) {
      worker.runOnce();
      clock.shift(Duration.ofMinutes(10));
    }
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("retry_count", 3).containsEntry("payment_state", "UNKNOWN");
    assertThat(saga(id).get("lease_token")).isNull();
    assertThat(status(id)).isEqualTo("PENDING");
  }
}
