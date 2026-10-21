package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import vn.fashion.order.admin.AdminAuth;
import vn.fashion.order.admin.OrderCommands;
import vn.fashion.order.saga.SagaRepository;
import vn.fashion.order.saga.SagaWorker;
import vn.fashion.order.web.Api;

/** TASK:ORD-03 spec section 4: COMMIT_STOCK after the OPS confirm; inventory, not the order deadline, decides (T09, T12). */
@TestPropertySource(properties = "fashion.order.saga.max-retries=3")
class SagaCommitIntegrationTest extends OrderTestSupport {
  static final AdminAuth.AdminActor OPS = new AdminAuth.AdminActor(UUID.randomUUID(), Set.of("order.read", "order.fulfill"));
  static final Duration PAST_DEADLINE = Duration.ofHours(25);   // COD expires_at is 24 h
  @Autowired SagaWorker worker;
  @Autowired SagaRepository sagas;
  @Autowired OrderCommands commands;

  private String no(UUID id) { return jdbc.queryForObject("select order_no from orders where id = ?", String.class, id); }
  private long version(UUID id) { return jdbc.queryForObject("select version from orders where id = ?", Long.class, id); }
  /** ORD-02 to WAIT_OPS, without the OPS confirm. */
  private UUID waitingForOps() {
    UUID id = placeOrder();
    stubSagaOk();
    worker.runOnce();
    assertThat(saga(id)).containsEntry("step", "WAIT_OPS");
    return id;
  }
  /** Confirmed by OPS through the command (no JWT, so ShiftClock may move later); saga at COMMIT_STOCK. */
  private UUID opsConfirmed() {
    UUID id = waitingForOps();
    commands.confirmCod(OPS, no(id), "confirm-" + id, version(id), null);
    assertThat(saga(id)).containsEntry("step", "COMMIT_STOCK");
    StubServers.INVENTORY.seen.clear();
    StubServers.PAYMENT.seen.clear();
    return id;
  }
  /** commit answers status/message; release and close keep the stubSagaOk answers. */
  private void commitAnswers(int status, String message) {
    StubServers.INVENTORY.reply(s -> !s.path().endsWith("/commit")
        ? new StubServers.Reply(200, StubServers.ok("{\"status\":\"RELEASED\"}"))
        : new StubServers.Reply(status, status == 200 ? StubServers.ok("{\"status\":\"COMMITTED\"}") : StubServers.error("CONFLICT", message)));
  }
  private long calls(StubServers.Stub stub, String suffix) { return stub.seen.stream().filter(s -> s.path().endsWith(suffix)).count(); }
  private int history(UUID id, String from, String to) {
    return count("select count(*) from order_status_history where order_id = ? and from_status = ? and to_status = ? and actor_type = 'SYSTEM'",
        id, from, to);
  }
  private String cancelReason(UUID id) { return jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id); }

  @Test void aConfirmedOrderIsCommittedAndPacked() {
    UUID id = opsConfirmed();
    long v = version(id);
    int outbox = count("select count(*) from outbox_events");
    commitAnswers(200, null);
    worker.runOnce();
    assertThat(calls(StubServers.INVENTORY, "/commit")).isEqualTo(1);
    assertThat(StubServers.INVENTORY.seen.get(0).idempotencyKey()).isEqualTo("commit:" + id);
    assertThat(status(id)).isEqualTo("PACKING");
    assertThat(version(id)).isEqualTo(v + 1);
    assertThat(history(id, "CONFIRMED", "PACKING")).isEqualTo(1);
    assertThat(saga(id)).containsEntry("inventory_state", "COMMITTED").containsEntry("step", "CREATE_SHIPMENT")
        .containsEntry("status", "RUNNING").containsEntry("retry_count", 0).containsEntry("last_error", null);
    assertThat(count("select count(*) from outbox_events")).isEqualTo(outbox);   // PACKING has no event (spec section 11.6)
  }

  @Test void inventorySaysExpiredSoTheOrderIsCancelled() {
    // T12a: inventory expired and released the reservation in its own transaction; nothing is left to release.
    UUID id = opsConfirmed();
    commitAnswers(409, "RESERVATION_EXPIRED");
    worker.runOnce();
    assertThat(calls(StubServers.INVENTORY, "/commit")).isEqualTo(1);
    assertThat(calls(StubServers.INVENTORY, "/release")).isZero();
    assertThat(calls(StubServers.PAYMENT, "/close")).isEqualTo(1);
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(cancelReason(id)).isEqualTo("EXPIRED");
    assertThat(history(id, "CONFIRMED", "CANCELLING")).isEqualTo(1);
    assertThat(count("select count(*) from order_status_history where order_id = ? and to_status = 'PACKING'", id)).isZero();
    assertThat(saga(id)).containsEntry("inventory_state", "RELEASED").containsEntry("status", "DONE");
  }

  @Test void aClosedReservationCancelsAsReserveFailed() {
    UUID id = opsConfirmed();
    commitAnswers(409, "RESERVATION_CLOSED");
    worker.runOnce();
    assertThat(calls(StubServers.INVENTORY, "/release")).isZero();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(cancelReason(id)).isEqualTo("RESERVE_FAILED");
    assertThat(saga(id)).containsEntry("inventory_state", "RELEASED");
  }

  @Test void aCommitAfterTheOrderDeadlineStillPacks() {
    // T12b: the order deadline no longer applies once OPS confirmed; inventory committed in time.
    UUID id = opsConfirmed();
    clock.shift(PAST_DEADLINE);
    commitAnswers(200, null);
    worker.runOnce();
    assertThat(status(id)).isEqualTo("PACKING");
    assertThat(calls(StubServers.INVENTORY, "/release")).isZero();
    assertThat(saga(id)).containsEntry("inventory_state", "COMMITTED");
  }

  @Test void aWorkerCancellingAtTheDeadlineBeatsALateConfirm() {
    // T12c: past the deadline at WAIT_OPS the worker claims the saga; the confirm loses and nothing is committed.
    UUID id = waitingForOps();
    StubServers.INVENTORY.seen.clear();
    clock.shift(PAST_DEADLINE);
    assertThat(sagas.claimDue(10, Duration.ofSeconds(30))).anyMatch(c -> c.orderId().equals(id));
    assertThatThrownBy(() -> commands.confirmCod(OPS, no(id), "late", version(id), null))
        .isInstanceOf(Api.Problem.class).hasMessage("ORDER_EXPIRED");
    assertThat(saga(id)).containsEntry("step", "WAIT_OPS");
    asOwner("update order_sagas set lease_until = now() - interval '1 second' where order_id = '" + id + "'");   // that worker goes on
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(cancelReason(id)).isEqualTo("EXPIRED");
    assertThat(calls(StubServers.INVENTORY, "/commit")).isZero();
    assertThat(calls(StubServers.INVENTORY, "/release")).isEqualTo(1);
  }

  @Test void aLostCommitResponseAfterTheDeadlineStillPacks() {
    // T09: the first answer is lost (timeout 1 s); the intent counts the attempt but keeps HELD, so nothing is released.
    UUID id = opsConfirmed();
    var n = new AtomicInteger();
    StubServers.INVENTORY.reply(s -> {
      if (s.path().endsWith("/commit") && n.incrementAndGet() == 1) {
        try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      }
      return new StubServers.Reply(200, StubServers.ok("{\"status\":\"COMMITTED\"}"));
    });
    worker.runOnce();
    assertThat(saga(id)).containsEntry("inventory_state", "HELD").containsEntry("retry_count", 1).containsEntry("step", "COMMIT_STOCK")
        .containsEntry("status", "RUNNING");
    assertThat(status(id)).isEqualTo("CONFIRMED");
    clock.shift(PAST_DEADLINE);
    worker.runOnce();
    assertThat(status(id)).isEqualTo("PACKING");
    assertThat(StubServers.INVENTORY.seen).extracting(StubServers.Seen::idempotencyKey).containsExactly("commit:" + id, "commit:" + id);
    assertThat(saga(id)).containsEntry("inventory_state", "COMMITTED").containsEntry("retry_count", 0);
  }

  /** Packed: OPS confirmed, inventory committed (saga at CREATE_SHIPMENT, order PACKING). */
  private UUID packed() {
    UUID id = opsConfirmed();
    commitAnswers(200, null);
    worker.runOnce();
    assertThat(status(id)).isEqualTo("PACKING");
    StubServers.SHIPPING.reset();
    return id;
  }
  private static final String SHIPMENT = "44444444-4444-4444-8444-444444444444";
  private String processing(UUID id) {
    return data(send("GET", "/api/v1/orders/" + no(id), null, "Authorization", memberToken()), 200).path("processing_status").asString();
  }

  @Test void aPackedOrderGetsItsSelfShipment() {
    UUID id = packed();
    assertThat(processing(id)).isEqualTo("PROCESSING");
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"shipment_id\":\"" + SHIPMENT + "\"}")));
    worker.runOnce();
    assertThat(StubServers.SHIPPING.seen).hasSize(1);
    assertThat(StubServers.SHIPPING.seen.get(0).idempotencyKey()).isEqualTo("shipment:" + id);
    assertThat(StubServers.SHIPPING.seen.get(0).body())
        .contains("\"cod_amount\":" + jdbc.queryForObject("select total_amount from orders where id = ?", Long.class, id))
        .contains("\"province_code\":\"79\"").doesNotContain("district_code");
    assertThat(saga(id)).containsEntry("shipment_id", UUID.fromString(SHIPMENT)).containsEntry("status", "DONE")
        .containsEntry("step", "DONE").containsEntry("retry_count", 0).containsEntry("last_error", null)
        .containsEntry("shipment_state", "CREATED");
    assertThat(status(id)).isEqualTo("PACKING");
    assertThat(processing(id)).isEqualTo("READY");
  }

  @Test void aRefusedShipmentGoesManualAndKeepsTheStock() {
    UUID id = packed();
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(409, StubServers.error("CONFLICT", "SHIPMENT_CANCELLED")));
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("inventory_state", "COMMITTED").containsEntry("step", "CREATE_SHIPMENT");
    assertThat((String) saga(id).get("last_error")).contains("SHIPMENT_CANCELLED");
    assertThat(status(id)).isEqualTo("PACKING");
    assertThat(calls(StubServers.INVENTORY, "/release")).isZero();
  }

  @Test void aLostShipmentResponseIsRetriedWithTheSameBody() {
    UUID id = packed();
    var n = new AtomicInteger();
    StubServers.SHIPPING.reply(s -> {
      if (n.incrementAndGet() == 1) {
        try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      }
      return new StubServers.Reply(200, StubServers.ok("{\"shipment_id\":\"" + SHIPMENT + "\"}"));
    });
    worker.runOnce();
    assertThat(saga(id)).containsEntry("retry_count", 1).containsEntry("step", "CREATE_SHIPMENT").containsEntry("status", "RUNNING");
    assertThat(saga(id).get("shipment_id")).isNull();
    clock.shift(Duration.ofMinutes(10));
    worker.runOnce();
    assertThat(StubServers.SHIPPING.seen).hasSize(2);
    assertThat(StubServers.SHIPPING.seen).extracting(StubServers.Seen::idempotencyKey).containsOnly("shipment:" + id);
    assertThat(StubServers.SHIPPING.seen.get(1).body()).isEqualTo(StubServers.SHIPPING.seen.get(0).body());
    assertThat(saga(id)).containsEntry("shipment_id", UUID.fromString(SHIPMENT)).containsEntry("status", "DONE");
  }

  @Test void otherCommitRefusalsRetryIntoManual() {
    // A conflict or a missing reservation is about our request, not the order: retried, then MANUAL; the order stays CONFIRMED.
    UUID id = opsConfirmed();
    var n = new AtomicInteger();
    StubServers.INVENTORY.reply(s -> n.incrementAndGet() == 1
        ? new StubServers.Reply(409, StubServers.error("CONFLICT", "RESERVATION_CONFLICT"))
        : new StubServers.Reply(404, StubServers.error("NOT_FOUND", "RESERVATION_NOT_FOUND")));
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "RUNNING").containsEntry("retry_count", 1)
        .containsEntry("last_error", "REJECTED RESERVATION_CONFLICT").containsEntry("inventory_state", "HELD");
    for (int i = 0; i < 2; i++) { clock.shift(Duration.ofMinutes(10)); worker.runOnce(); }
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("retry_count", 3)
        .containsEntry("last_error", "REJECTED RESERVATION_NOT_FOUND").containsEntry("inventory_state", "HELD");
    assertThat(status(id)).isEqualTo("CONFIRMED");
    assertThat(calls(StubServers.INVENTORY, "/release")).isZero();
  }
}
