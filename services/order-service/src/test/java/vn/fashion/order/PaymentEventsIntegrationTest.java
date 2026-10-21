package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Connection;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.orders.PaymentEvents;
import vn.fashion.order.saga.SagaWorker;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.EventEnvelope;

/** ORD-02 spec section 6 / T07: payment facts are recorded durably and never move the order backwards. */
class PaymentEventsIntegrationTest extends OrderTestSupport {
  @Autowired PaymentEvents events;
  @Autowired InboxGuard inbox;
  @Autowired SagaWorker saga;
  @Autowired DataSource dataSource;

  EventEnvelope envelope(UUID eventId, String type, UUID orderId, String paidAt, String reason) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", orderId.toString()); payload.put("order_no", "ORD1"); payload.put("payment_id", UUID.randomUUID().toString());
    payload.put("method", "COD"); payload.put("amount", 439000); payload.put("currency", "VND");
    payload.put("status", type.equals("PAYMENT_COMPLETED") ? "SUCCESS" : "FAILED"); payload.put("paid_at", paidAt);
    payload.put("provider_txn_id", null); payload.put("reason", reason);
    return new EventEnvelope(eventId, type, 1, Instant.now(), orderId.toString(), 1, 1, "corr", mapper.valueToTree(payload));
  }
  void deliver(EventEnvelope e) { inbox.applyOnce("order-service", e.eventId(), () -> events.apply(e)); }

  @Test void aCompletedPaymentBeforeTheSagaIsKeptAndTheSagaStillConfirms() {
    UUID id = placeOrder();
    UUID event = UUID.randomUUID();
    deliver(envelope(event, "PAYMENT_COMPLETED", id, "2026-10-20T05:00:00Z", null));
    assertThat(jdbc.queryForObject("select paid_at::text from orders where id = ?", String.class, id)).startsWith("2026-10-20 05:00:00");
    assertThat(saga(id)).containsEntry("payment_state", "PAID");
    assertThat(status(id)).isEqualTo("PENDING");                         // a payment fact does not change the order status
    stubSagaOk();
    saga.runOnce();
    assertThat(status(id)).isEqualTo("CONFIRMED");
    assertThat(saga(id)).containsEntry("payment_state", "PAID");            // never overwritten by CREATED
    // Redelivered, also with a later paid_at: applied once, the first paid_at stays.
    deliver(envelope(event, "PAYMENT_COMPLETED", id, "2026-10-21T05:00:00Z", null));
    deliver(envelope(UUID.randomUUID(), "PAYMENT_COMPLETED", id, "2026-10-21T05:00:00Z", null));
    assertThat(jdbc.queryForObject("select paid_at::text from orders where id = ?", String.class, id)).startsWith("2026-10-20 05:00:00");
    assertThat(count("select count(*) from processed_events where consumer_name = 'order-service'")).isEqualTo(2);
  }

  /** Lock order order_sagas -> orders: while another tx holds the saga row, PaymentEvents waits without having locked orders. */
  @Test void paymentEventsLockTheSagaBeforeTheOrder() throws Exception {
    UUID id = placeOrder();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.createStatement().execute("select 1 from order_sagas where order_id = '" + id + "' for update");
      var pending = CompletableFuture.runAsync(
          () -> deliver(envelope(UUID.randomUUID(), "PAYMENT_COMPLETED", id, "2026-10-20T05:00:00Z", null)));
      Thread.sleep(1000);
      assertThat(pending).isNotDone();                       // blocked on the saga lock
      c.createStatement().execute("select 1 from orders where id = '" + id + "' for update nowait");   // fails if orders was locked first
      c.commit();
      pending.get(10, TimeUnit.SECONDS);
    }
    assertThat(saga(id)).containsEntry("payment_state", "PAID");
  }

  @Test void otherPaymentEventsAreOnlyAcknowledged() {
    UUID id = placeOrder();
    deliver(envelope(UUID.randomUUID(), "PAYMENT_FAILED", id, null, "CLOSED"));
    deliver(envelope(UUID.randomUUID(), "PAYMENT_CREATED", id, null, null));
    deliver(envelope(UUID.randomUUID(), "PAYMENT_COMPLETED", UUID.randomUUID(), "2026-10-20T05:00:00Z", null));  // unknown order
    assertThat(saga(id)).containsEntry("payment_state", "NONE");
    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(count("select count(*) from processed_events")).isEqualTo(3);
  }
}
