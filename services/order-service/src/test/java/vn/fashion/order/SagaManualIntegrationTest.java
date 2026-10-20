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
    double before = meters.counter("order.saga.manual").count();
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
    assertThat(meters.counter("order.saga.manual").count()).isEqualTo(before + 1);
    clock.shift(Duration.ofHours(1));
    StubServers.INVENTORY.seen.clear();
    worker.runOnce();
    assertThat(StubServers.INVENTORY.seen).isEmpty();                    // MANUAL is never picked up again
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
