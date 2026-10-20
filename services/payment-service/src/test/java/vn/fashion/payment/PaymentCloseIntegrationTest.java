package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

class PaymentCloseIntegrationTest extends PaymentTestSupport {
  @Test void closingAPendingCodPaymentFailsItOnce() {
    String order = UUID.randomUUID().toString(), body = snapshot(order, "C-1", "COD", 90000);
    String id = data(create("k", body), 200).path("payment_id").asString();
    var closed = data(close(order, "c1", withReason(body, "khách hủy")), 200);
    assertThat(closed.path("status").asString()).isEqualTo("FAILED");
    assertThat(closed.path("closed_at").isNull()).isFalse();
    assertThat(closed.path("version").asLong()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select close_reason from payments where id = cast(? as uuid)", String.class, id)).isEqualTo("khách hủy");
    assertThat(count("select count(*) from payment_transactions where payment_id = cast(? as uuid) and source = 'CLOSE'", id)).isEqualTo(1);
    var failed = jdbc.queryForMap("select payload::text as payload from outbox_events where event_type = 'PAYMENT_FAILED'");
    assertThat(mapper.readTree((String) failed.get("payload")).path("reason").asString()).isEqualTo("CLOSED");
    // Another key closes again: the current state, no second event or evidence row; a create stays refused.
    assertThat(data(close(order, "c2", withReason(body, "lần nữa")), 200).path("version").asLong()).isEqualTo(1);
    assertThat(count("select count(*) from outbox_events where event_type = 'PAYMENT_FAILED'")).isEqualTo(1);
    assertThat(count("select count(*) from payment_transactions where source = 'CLOSE'")).isEqualTo(1);
    assertThat(problem(create("k2", body), 409)).isEqualTo("PAYMENT_CLOSED");
    // The order's UNKNOWN retry of the original create gets the current fact, not the stored COD_PENDING answer.
    assertThat(problem(create("k", body), 409)).isEqualTo("PAYMENT_CLOSED");
  }

  @Test void closeBeforeCreateLeavesATombstoneThatBlocksTheCreate() {
    String order = UUID.randomUUID().toString(), body = snapshot(order, "C-2", "COD", 120000);
    var tombstone = data(close(order, "c1", withReason(body, "hết hạn")), 200);
    assertThat(tombstone.path("status").asString()).isEqualTo("FAILED");
    assertThat(tombstone.path("closed_at").isNull()).isFalse();
    assertThat(jdbc.queryForObject("select snapshot ->> 'order_no' from payments where order_id = cast(? as uuid)", String.class, order))
        .isEqualTo("C-2");
    assertThat(count("select count(*) from outbox_events where event_type = 'PAYMENT_FAILED'")).isEqualTo(1);
    assertThat(problem(create("k1", body), 409)).isEqualTo("PAYMENT_CLOSED");
    assertThat(problem(create("k2", snapshot(order, "C-2", "COD", 1)), 409)).isEqualTo("PAYMENT_CLOSED");
    // Review M2: closed is checked before the 422 input rules (spec section 5).
    assertThat(problem(create("k3", snapshot(order, "C-2", "COD", 0)), 409)).isEqualTo("PAYMENT_CLOSED");
    assertThat(problem(create("k4", snapshot(order, "C-2", "VNPAY", 120000)), 409)).isEqualTo("PAYMENT_CLOSED");
    assertThat(count("select count(*) from outbox_events where event_type = 'PAYMENT_CREATED'")).isZero();
  }

  @Test void closeAfterTheMoneyWasCollectedKeepsTheMoneyState() {
    String order = UUID.randomUUID().toString(), body = snapshot(order, "C-3", "COD", 70000);
    String id = data(create("k", body), 200).path("payment_id").asString();
    jdbc.update("update payments set status = 'COD_COLLECTED' where id = cast(? as uuid)", id);
    var closed = data(close(order, "c1", withReason(body, "trả hàng")), 200);
    assertThat(closed.path("status").asString()).isEqualTo("COD_COLLECTED");
    assertThat(closed.path("closed_at").isNull()).isFalse();
    assertThat(count("select count(*) from outbox_events where event_type = 'PAYMENT_FAILED'")).isZero();
  }

  @Test void badClosesAreRefused() {
    String order = UUID.randomUUID().toString(), body = snapshot(order, "C-4", "COD", 10000);
    data(create("k", body), 200);
    assertThat(problem(close(UUID.randomUUID().toString(), "a", withReason(body, "x")), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(close(order, "b", withReason(snapshot(order, "C-4", "COD", 10001), "x")), 409)).isEqualTo("PAYMENT_CONFLICT");
    assertThat(problem(close(order, "c", body), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(close(order, "d", withReason(body, " ")), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(close(order, "e", withReason(body, "x".repeat(501))), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(close(order, "f", "{\"reason\":\"x\"}"), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(close(order, "g", withReason(body, "x").replace("}", ",\"extra\":1}")), 400)).isEqualTo("INVALID_FIELDS");
    // Review M3: a negative amount is a bad snapshot (400), never a 500 from the CHECK constraint.
    String fresh = UUID.randomUUID().toString();
    assertThat(problem(close(fresh, "h", withReason(snapshot(fresh, "C-5", "COD", -1), "x")), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(jdbc.queryForObject("select status from payments where order_id = cast(? as uuid)", String.class, order)).isEqualTo("COD_PENDING");
  }

  @Test void parallelCloseAndCreateNeverLeaveAnOpenPayment() {
    for (int i = 0; i < 4; i++) {
      String order = UUID.randomUUID().toString(), body = snapshot(order, "R-" + i, "COD", 5000), n = String.valueOf(i);
      var barrier = new CyclicBarrier(2);
      var created = CompletableFuture.supplyAsync(() -> { PaymentCreateIntegrationTest.await(barrier); return create("k" + n, body); });
      var closed = CompletableFuture.supplyAsync(() -> {
        PaymentCreateIntegrationTest.await(barrier); return close(order, "c" + n, withReason(body, "đua")); });
      var c = created.join();
      assertThat(closed.join().statusCode()).isEqualTo(200);
      assertThat(jdbc.queryForObject("select status from payments where order_id = cast(? as uuid)", String.class, order)).isEqualTo("FAILED");
      if (c.statusCode() != 200) assertThat(problem(c, 409)).isEqualTo("PAYMENT_CLOSED");
      assertThat(count("select count(*) from payments where order_id = cast(? as uuid) and closed_at is null", order)).isZero();
    }
  }
}
