package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.saga.SagaRepository;
import vn.fashion.order.saga.SagaWorker;

/** ORD-02 spec section 3-4: RESERVE -> CREATE_PAYMENT -> CONFIRMED, intents before HTTP, results by lease CAS. */
class SagaForwardIntegrationTest extends OrderTestSupport {
  @Autowired SagaWorker worker;
  @Autowired SagaRepository sagas;

  @Test void aCodOrderIsReservedPaidForAndConfirmed() throws Exception {
    UUID id = placeOrder();
    stubSagaOk();
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CONFIRMED");
    var saga = saga(id);
    assertThat(saga).containsEntry("step", "WAIT_OPS").containsEntry("status", "WAITING").containsEntry("inventory_state", "HELD")
        .containsEntry("payment_state", "CREATED").containsEntry("retry_count", 0);
    assertThat(saga.get("payment_id").toString()).isEqualTo("55555555-5555-4555-8555-555555555591");
    assertThat(saga.get("lease_token")).isNull();
    assertThat(((Timestamp) saga.get("next_retry_at")).toInstant())
        .isEqualTo(jdbc.queryForObject("select expires_at from orders where id = ?", Timestamp.class, id).toInstant());
    assertThat(count("select count(*) from order_status_history where order_id = ? and from_status = 'PENDING' and to_status = 'CONFIRMED' and actor_type = 'SYSTEM'", id))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select version from orders where id = ?", Long.class, id)).isEqualTo(1);
    assertThat(count("select count(*) from outbox_events where event_type = 'ORDER_CONFIRMED'")).isEqualTo(1);
    var event = mapper.readTree(jdbc.queryForObject("select payload::text from outbox_events where event_type = 'ORDER_CONFIRMED'", String.class));
    assertThat(event.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(event.path("order_version").asLong()).isEqualTo(1);
    assertThat(count("select count(*) from background_tasks where kind = 'CART_CLEANUP' and business_key = ?", id.toString())).isEqualTo(1);
    // The reserve and payment calls carry the order's fixed keys.
    assertThat(StubServers.INVENTORY.seen.get(0).idempotencyKey()).isEqualTo("reserve:" + id);
    assertThat(StubServers.PAYMENT.seen.get(0).idempotencyKey()).isEqualTo("payment:" + id);
    // Nothing is due any more: another run does nothing.
    StubServers.INVENTORY.seen.clear();
    assertThat(worker.runOnce()).isZero();
    assertThat(StubServers.INVENTORY.seen).isEmpty();
  }

  @Test void aLostReserveResponseIsRetriedWithTheSameKey() {
    // T03: inventory reserved but the answer timed out; the retry sends the same key and hash and holds stock once.
    UUID id = placeOrder();
    stubSagaOk();
    StubServers.INVENTORY.delay(1500);
    worker.runOnce();
    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(saga(id)).containsEntry("inventory_state", "UNKNOWN").containsEntry("status", "RUNNING").containsEntry("retry_count", 1);
    assertThat(saga(id).get("lease_token")).isNull();
    assertThat(StubServers.PAYMENT.seen).isEmpty();
    StubServers.INVENTORY.delay(0);
    assertThat(worker.runOnce()).isZero();           // backoff: not due yet
    clock.shift(Duration.ofMinutes(1));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CONFIRMED");
    var calls = StubServers.INVENTORY.seen;
    assertThat(calls).hasSize(2);
    assertThat(calls.get(1).idempotencyKey()).isEqualTo(calls.get(0).idempotencyKey());
    assertThat(calls.get(1).body()).isEqualTo(calls.get(0).body());
  }

  @Test void aStaleWorkerCannotOverwriteTheNewOne() {
    UUID id = placeOrder();
    var a = sagas.claimDue(10, Duration.ofSeconds(30)).get(0);
    assertThat(sagas.claimDue(10, Duration.ofSeconds(30))).isEmpty();     // leased: nobody else takes it
    asOwner("update order_sagas set lease_until = now() - interval '1 second' where order_id = '" + id + "'");
    var b = sagas.claimDue(10, Duration.ofSeconds(30)).get(0);
    assertThat(b.leaseToken()).isNotEqualTo(a.leaseToken());
    assertThat(sagas.write(a, jdbc -> jdbc.sql("update order_sagas set last_error = 'A' where order_id = :id").param("id", id).update(), false))
        .isFalse();
    assertThat(sagas.write(b, jdbc -> jdbc.sql("update order_sagas set last_error = 'B' where order_id = :id").param("id", id).update(), true))
        .isTrue();
    assertThat(saga(id)).containsEntry("last_error", "B");
    assertThat(saga(id).get("lease_token")).isNull();
    assertThat(sagas.write(b, jdbc -> { }, true)).isFalse();             // released: the token is gone
  }
}
