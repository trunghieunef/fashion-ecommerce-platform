package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.saga.SagaWorker;

/** ORD-02 spec section 3: CANCEL releases what is held and closes the payment before CANCELLED; never earlier. */
class SagaCompensationIntegrationTest extends OrderTestSupport {
  @Autowired SagaWorker worker;

  private long calls(StubServers.Stub stub, String pathEnd) { return stub.seen.stream().filter(s -> s.path().endsWith(pathEnd)).count(); }

  @Test void outOfStockCancelsWithoutTouchingTheCart() throws Exception {
    UUID id = placeOrder();
    UUID cart = jdbc.queryForObject("select cart_id from orders where id = ?", UUID.class, id);
    stubSagaOk();
    StubServers.INVENTORY.reply(s -> s.path().endsWith("/release")
        ? new StubServers.Reply(200, StubServers.ok("{\"status\":\"RELEASED\"}"))
        : new StubServers.Reply(409, StubServers.error("OUT_OF_STOCK", "OUT_OF_STOCK")));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id)).isEqualTo("OUT_OF_STOCK");
    assertThat(saga(id)).containsEntry("intent", "CANCEL").containsEntry("status", "DONE").containsEntry("payment_state", "CLOSED");
    assertThat(calls(StubServers.INVENTORY, "/release")).isEqualTo(1);       // tombstone: a reserve still in flight cannot hold
    assertThat(calls(StubServers.PAYMENT, "/close")).isEqualTo(1);           // tombstone blocks a late create (PAY-08)
    assertThat(StubServers.PAYMENT.seen.stream().filter(s -> s.path().endsWith("/payments")).count()).isZero();
    assertThat(count("select count(*) from background_tasks")).isZero();
    assertThat(count("select count(*) from order_status_history where order_id = ? and to_status in ('CANCELLING', 'CANCELLED')", id)).isEqualTo(2);
    var event = mapper.readTree(jdbc.queryForObject("select payload::text from outbox_events where event_type = 'ORDER_CANCELLED'", String.class));
    assertThat(event.path("reason").asString()).isEqualTo("OUT_OF_STOCK");
    assertThat(event.path("status").asString()).isEqualTo("CANCELLED");
    // The same cart state can be ordered again (V002 partial unique index).
    stubCheckout(cart, 150000, 99000);
    String member = memberToken();
    data(create("again", createBody(cart, 4, quoteToken(cart, "Authorization", member)), "Authorization", member), 202);
    // Review I1: a cancelled and an open order now share (cart_id, cart_version); losing the insert is still a 409, not 500.
    String other = memberToken(UUID.randomUUID());
    var lost = create("other-actor", createBody(cart, 4, quoteToken(cart, "Authorization", other)), "Authorization", other);
    assertThat(problem(lost, 409)).isEqualTo("CART_ALREADY_ORDERED");
    assertThat(json(lost).path("errors").get(0).path("message").asString()).isEmpty();   // another actor's order_no stays hidden
  }

  @Test void aPaymentCompletedDuringCompensationGoesManualForARefund() {
    // Review M5: PAYMENT_COMPLETED lands while the order is cancelling; never overwritten by CLOSED, never a plain CANCELLED.
    UUID id = placeOrder();
    stubSagaOk();
    StubServers.PAYMENT.reply(s -> new StubServers.Reply(409, StubServers.error("CONFLICT", "PAYMENT_CLOSED")));
    StubServers.INVENTORY.reply(s -> {
      if (s.path().endsWith("/release")) jdbc.update("update order_sagas set payment_state = 'PAID' where order_id = ?", id);
      return new StubServers.Reply(200, StubServers.ok(s.path().endsWith("/release") ? "{\"status\":\"RELEASED\"}" : "{\"status\":\"ACTIVE\"}"));
    });
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLING");
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("payment_state", "PAID").containsEntry("last_error", "PAID_NEEDS_REFUND");
  }

  @Test void aReserveStillInFlightCannotHoldStockForACancelledOrder() {
    // PR #33 review I2: reserve #1 timed out (UNKNOWN), retry #2 says OUT_OF_STOCK; #1 may still land, so release (tombstone).
    UUID id = placeOrder();
    stubSagaOk();
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(503, StubServers.error("TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE")));
    worker.runOnce();
    StubServers.INVENTORY.reply(s -> s.path().endsWith("/release")
        ? new StubServers.Reply(200, StubServers.ok("{\"status\":\"RELEASED\"}"))
        : new StubServers.Reply(409, StubServers.error("OUT_OF_STOCK", "OUT_OF_STOCK")));
    clock.shift(Duration.ofMinutes(10));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(saga(id)).containsEntry("inventory_state", "RELEASED");
    var release = StubServers.INVENTORY.seen.stream().filter(s -> s.path().endsWith("/release")).toList();
    assertThat(release).hasSize(1);
    assertThat(release.get(0).idempotencyKey()).isEqualTo("release:" + id);
  }

  @Test void anUnknownSkuThatWasNeverHeldNeedsNoRelease() {
    // Inventory answers OUT_OF_STOCK for an unknown SKU and refuses its tombstone with 400 INVALID_INPUT: nothing to free.
    UUID id = placeOrder();
    stubSagaOk();
    StubServers.INVENTORY.reply(s -> s.path().endsWith("/release")
        ? new StubServers.Reply(400, StubServers.error("VALIDATION_ERROR", "INVALID_INPUT"))
        : new StubServers.Reply(409, StubServers.error("OUT_OF_STOCK", "OUT_OF_STOCK")));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id)).isEqualTo("OUT_OF_STOCK");
  }

  @Test void contractConflictsAndMissingRoutesNeverCancelTheOrder() {
    // PR #33 review I1: 404 (wrong URL, old deploy) or a *_CONFLICT on our own key/hash is not an answer about the order.
    UUID a = placeOrder();
    stubSagaOk();
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(404, StubServers.error("NOT_FOUND", "NOT_FOUND")));
    worker.runOnce();
    assertThat(status(a)).isEqualTo("PENDING");
    assertThat(saga(a)).containsEntry("intent", "FULFILL").containsEntry("retry_count", 1);
    UUID b = placeOrder();
    stubSagaOk();
    StubServers.PAYMENT.reply(s -> new StubServers.Reply(409, StubServers.error("CONFLICT", "PAYMENT_CONFLICT")));
    worker.runOnce();
    assertThat(status(b)).isEqualTo("PENDING");
    assertThat(saga(b)).containsEntry("intent", "FULFILL").containsEntry("step", "CREATE_PAYMENT").containsEntry("retry_count", 1);
  }

  @Test void committedStockIsNeverTreatedAsReleased() {
    // PR #33 review M3: COMMITTED/RESTORED stock needs return/restore (ORD-04), not CANCELLED.
    UUID id = placeOrder();
    asOwner("update orders set status = 'CANCELLING', cancel_reason = 'EXPIRED' where id = '" + id + "'");
    asOwner("update order_sagas set intent = 'CANCEL', step = 'COMPENSATE', status = 'COMPENSATING', inventory_state = 'COMMITTED' where order_id = '" + id + "'");
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "MANUAL");
    assertThat(status(id)).isEqualTo("CANCELLING");
    assertThat(StubServers.INVENTORY.seen).isEmpty();
  }

  @Test void aContractErrorGoesManualInsteadOfCancellingTheOrder() {
    // Review I3: a 400 (our own request is wrong: hash, fields, clock drift) is not an answer about the order.
    UUID id = placeOrder();
    stubSagaOk();
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(400, StubServers.error("INVALID_INPUT", "REQUEST_HASH_MISMATCH")));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(saga(id)).containsEntry("intent", "FULFILL").containsEntry("inventory_state", "UNKNOWN").containsEntry("retry_count", 1);
  }

  @Test void aRefusedPaymentReleasesTheReservation() throws Exception {
    UUID id = placeOrder();
    stubSagaOk();
    StubServers.PAYMENT.reply(s -> s.path().endsWith("/close")
        ? new StubServers.Reply(200, StubServers.ok("{\"status\":\"FAILED\"}"))
        : new StubServers.Reply(409, StubServers.error("CONFLICT", "PAYMENT_CLOSED")));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id)).isEqualTo("PAYMENT_FAILED");
    assertThat(saga(id)).containsEntry("inventory_state", "RELEASED").containsEntry("payment_state", "CLOSED");
    var reserve = mapper.readTree(StubServers.INVENTORY.seen.get(0).body());
    var release = StubServers.INVENTORY.seen.stream().filter(s -> s.path().endsWith("/release")).findFirst().orElseThrow();
    assertThat(release.idempotencyKey()).isEqualTo("release:" + id);
    assertThat(mapper.readTree(release.body()).path("original_request_hash").asString()).isEqualTo(reserve.path("request_hash").asString());
    assertThat(count("select count(*) from background_tasks")).isZero();     // never CONFIRMED: the cart is kept
  }

  @Test void anExpiredConfirmedOrderIsCancelledOnlyOnceEverythingIsReleased() {
    UUID id = placeOrder();
    stubSagaOk();
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CONFIRMED");
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(503, StubServers.error("TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE")));
    clock.shift(Duration.ofHours(25));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLING");                        // release not confirmed: never CANCELLED
    assertThat(saga(id)).containsEntry("status", "COMPENSATING").containsEntry("inventory_state", "HELD");
    stubSagaOk();
    clock.shift(Duration.ofMinutes(10));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id)).isEqualTo("EXPIRED");
    assertThat(saga(id)).containsEntry("inventory_state", "RELEASED").containsEntry("payment_state", "CLOSED");
    assertThat(count("select count(*) from order_status_history where order_id = ? and from_status = 'CONFIRMED' and to_status = 'CANCELLING'", id))
        .isEqualTo(1);
  }

  @Test void anOrderExpiringWhileThePaymentIsUnknownStillClosesIt() {
    // Review focus: deadline passes while payment create is UNKNOWN -> CANCEL closes (tombstone) and releases.
    UUID id = placeOrder();
    stubSagaOk();
    StubServers.PAYMENT.reply(s -> s.path().endsWith("/close")
        ? new StubServers.Reply(200, StubServers.ok("{\"status\":\"FAILED\"}"))
        : new StubServers.Reply(503, StubServers.error("TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE")));
    worker.runOnce();
    assertThat(saga(id)).containsEntry("payment_state", "UNKNOWN").containsEntry("inventory_state", "HELD");
    clock.shift(Duration.ofHours(25));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id)).isEqualTo("EXPIRED");
    assertThat(calls(StubServers.PAYMENT, "/close")).isEqualTo(1);
    assertThat(calls(StubServers.INVENTORY, "/release")).isEqualTo(1);
  }

  @Test void aReleaseThatFindsCommittedStockGoesManual() {
    UUID id = placeOrder();
    stubSagaOk();
    worker.runOnce();
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"COMMITTED\"}")));
    clock.shift(Duration.ofHours(25));
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "MANUAL");
    assertThat(saga(id).get("last_error").toString()).contains("COMMITTED");
    assertThat(status(id)).isEqualTo("CANCELLING");
    assertThat(((Timestamp) saga(id).get("next_retry_at")).toInstant()).isNotNull();
  }
}
