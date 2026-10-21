package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.admin.AdminAuth;
import vn.fashion.order.admin.OrderCommands;
import vn.fashion.order.saga.SagaWorker;

/** TASK:ORD-04 spec section 4: compensation cancels the shipment, then restores committed stock, then closes the payment. */
class SagaCancelAfterConfirmIntegrationTest extends OrderTestSupport {
  static final AdminAuth.AdminActor OPS = new AdminAuth.AdminActor(UUID.randomUUID(), Set.of("order.read", "order.fulfill", "order.cancel"));
  static final String SHIPMENT = "44444444-4444-4444-8444-444444444444";
  @Autowired SagaWorker worker;
  @Autowired OrderCommands commands;
  @Autowired MeterRegistry meters;
  @Autowired vn.fashion.order.orders.OrderService orders;
  /** Every dependency call in order, as "service path-suffix". */
  final List<String> calls = new CopyOnWriteArrayList<>();
  volatile StubServers.Reply shipmentCancel = new StubServers.Reply(200, StubServers.ok("{\"status\":\"CANCELLED\"}"));
  volatile boolean restoreRequired = true;

  private String no(UUID id) { return jdbc.queryForObject("select order_no from orders where id = ?", String.class, id); }
  private long version(UUID id) { return jdbc.queryForObject("select version from orders where id = ?", Long.class, id); }
  private String last(String path) { return path.substring(path.lastIndexOf('/') + 1); }

  /** Inventory/payment/shipping answer OK; release reports restore_required, shipment-cancel answers shipmentCancel. */
  private void stubAll() {
    StubServers.INVENTORY.reply(s -> {
      calls.add("inventory " + last(s.path()));
      if (s.path().endsWith("/release")) return new StubServers.Reply(200, StubServers.ok(restoreRequired
          ? "{\"status\":\"COMMITTED\",\"restore_required\":true}" : "{\"status\":\"RELEASED\",\"restore_required\":false}"));
      if (s.path().endsWith("/commit")) return new StubServers.Reply(200, StubServers.ok("{\"status\":\"COMMITTED\"}"));
      if (s.path().endsWith("/returns")) return new StubServers.Reply(200, StubServers.ok("{\"items\":[]}"));
      return new StubServers.Reply(200, StubServers.ok("{\"status\":\"ACTIVE\"}"));
    });
    StubServers.PAYMENT.reply(s -> {
      calls.add("payment " + last(s.path()));
      return new StubServers.Reply(200, StubServers.ok(s.path().endsWith("/close")
          ? "{\"status\":\"FAILED\"}" : "{\"payment_id\":\"55555555-5555-4555-8555-555555555591\",\"status\":\"COD_PENDING\"}"));
    });
    StubServers.SHIPPING.reply(s -> {
      if (!s.path().endsWith("/cancel")) return new StubServers.Reply(200, StubServers.ok("{\"shipment_id\":\"" + SHIPMENT + "\"}"));
      calls.add("shipping cancel");
      return shipmentCancel;
    });
  }

  /** OPS confirmed, stock committed, shipment created: order PACKING, saga DONE. */
  private UUID packedWithShipment() {
    UUID id = placeOrder();
    stubAll();
    worker.runOnce();
    commands.confirmCod(OPS, no(id), "confirm-" + id, version(id), null);
    worker.runOnce();   // commit
    worker.runOnce();   // shipment
    assertThat(saga(id)).containsEntry("step", "DONE").containsEntry("shipment_state", "CREATED");
    assertThat(status(id)).isEqualTo("PACKING");
    calls.clear();
    StubServers.INVENTORY.seen.clear(); StubServers.SHIPPING.seen.clear(); StubServers.PAYMENT.seen.clear();
    return id;
  }
  private void opsCancel(UUID id) { commands.cancel(OPS, no(id), "cancel-" + id, version(id), "Khach doi y"); }
  private void runAll() { for (int i = 0; i < 6; i++) { worker.runOnce(); clock.shift(Duration.ofMinutes(10)); } }
  private long count(StubServers.Stub stub, String suffix) { return stub.seen.stream().filter(s -> s.path().endsWith(suffix)).count(); }

  @Test void cancellingAPackedOrderCancelsTheShipmentThenRestoresThenCloses() throws Exception {
    UUID id = packedWithShipment();
    opsCancel(id);
    runAll();
    assertThat(calls).containsExactly("shipping cancel", "inventory release", "inventory returns", "payment close");
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id)).isEqualTo("OPS_CANCELLED");
    assertThat(saga(id)).containsEntry("shipment_state", "CANCELLED").containsEntry("inventory_state", "RESTORED")
        .containsEntry("payment_state", "CLOSED").containsEntry("status", "DONE");
    var cancel = StubServers.SHIPPING.seen.stream().filter(s -> s.path().endsWith("/cancel")).findFirst().orElseThrow();
    assertThat(cancel.path()).isEqualTo("/internal/api/v1/shipping/shipments/by-order/" + id + "/cancel");
    assertThat(cancel.idempotencyKey()).isEqualTo("shipment-cancel:" + id);
    assertThat(mapper.readTree(cancel.body()).path("reason").asString()).isEqualTo("OPS_CANCELLED");   // the code, not the OPS text
    var returns = StubServers.INVENTORY.seen.stream().filter(s -> s.path().endsWith("/returns")).findFirst().orElseThrow();
    assertThat(returns.idempotencyKey()).isEqualTo("cancel:" + id);
    var body = mapper.readTree(returns.body());
    assertThat(body.path("operation_key").asString()).isEqualTo("cancel:" + id);
    assertThat(body.path("evidence_ref").asString()).isEqualTo("order-cancel:" + id);
    assertThat(body.path("reason").asString()).isEqualTo("OPS_CANCELLED");
    assertThat(body.path("order_id").asString()).isEqualTo(id.toString());
    assertThat(mapper.readTree(body.path("items").toString())).isEqualTo(mapper.readTree(
        "[{\"sku\":\"TEE-L\",\"quantity\":1},{\"sku\":\"TEE-M\",\"quantity\":2}]"));
  }

  @Test void aSagaStuckOnARefusedQuoteCanStillBeCancelled() {
    // PR #35 review I1: create got 409 QUOTE_MISMATCH (saga MANUAL); shipping's cancel runs the same quote checks while no
    // shipment row exists. That refusal proves nothing can ship, so compensation goes on: restore, close, CANCELLED.
    UUID id = placeOrder();
    stubAll();
    worker.runOnce();
    commands.confirmCod(OPS, no(id), "confirm-" + id, version(id), null);
    worker.runOnce();   // commit
    StubServers.SHIPPING.reply(s -> {
      calls.add(s.path().endsWith("/cancel") ? "shipping cancel" : "shipping create");
      return new StubServers.Reply(409, StubServers.error("CONFLICT", "QUOTE_MISMATCH"));
    });
    worker.runOnce();   // create refused
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("shipment_state", "NONE");
    calls.clear();
    opsCancel(id);
    runAll();
    assertThat(calls).containsExactly("shipping cancel", "inventory release", "inventory returns", "payment close");
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(saga(id)).containsEntry("shipment_state", "CANCELLED").containsEntry("inventory_state", "RESTORED")
        .containsEntry("status", "DONE");
  }

  @Test void otherShipmentCancelRefusalsStillGoManual() {
    UUID id = packedWithShipment();
    shipmentCancel = new StubServers.Reply(409, StubServers.error("CONFLICT", "SHIPMENT_CONFLICT"));
    opsCancel(id);
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("inventory_state", "COMMITTED");
    assertThat(count(StubServers.INVENTORY, "/release") + count(StubServers.INVENTORY, "/returns")).isZero();
  }

  @Test void aShipmentCancelInDoubtNeverRestoresStock() {
    // T16: until shipping confirms the cancel the goods may still leave, so no release/returns.
    UUID id = packedWithShipment();
    shipmentCancel = new StubServers.Reply(503, StubServers.error("TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE"));
    opsCancel(id);
    worker.runOnce();
    assertThat(count(StubServers.INVENTORY, "/release") + count(StubServers.INVENTORY, "/returns")).isZero();
    assertThat(saga(id)).containsEntry("inventory_state", "COMMITTED").containsEntry("shipment_state", "CREATED")
        .containsEntry("status", "COMPENSATING").containsEntry("retry_count", 1);
    assertThat(status(id)).isEqualTo("CANCELLING");
    shipmentCancel = new StubServers.Reply(200, StubServers.ok("{\"status\":\"CANCELLED\"}"));
    runAll();
    assertThat(calls).containsExactly("shipping cancel", "shipping cancel", "inventory release", "inventory returns", "payment close");
    assertThat(status(id)).isEqualTo("CANCELLED");
  }

  @Test void aHandedOverShipmentWaitsForTheReturn() {
    UUID id = packedWithShipment();
    shipmentCancel = new StubServers.Reply(409, StubServers.error("CONFLICT", "SHIPMENT_HANDED_OVER"));
    double before = meters.counter("order.saga.handed_over").count();
    opsCancel(id);
    worker.runOnce();
    assertThat(count(StubServers.INVENTORY, "/release") + count(StubServers.INVENTORY, "/returns")).isZero();
    assertThat(saga(id)).containsEntry("shipment_state", "HANDED_OVER").containsEntry("status", "WAITING").containsEntry("step", "WAIT_RETURN")
        .containsEntry("inventory_state", "COMMITTED").containsEntry("lease_token", null);
    assertThat(((Timestamp) saga(id).get("next_retry_at")).toInstant()).isAfter(clock.instant().plus(Duration.ofDays(29)));
    assertThat(status(id)).isEqualTo("CANCELLING");
    // PR #35 review M1: waiting days for the goods to come back is READY (no polling), not PROCESSING.
    assertThat(orders.view(id).processingStatus()).isEqualTo("READY");
    assertThat(meters.counter("order.saga.handed_over").count()).isEqualTo(before + 1);
    // Parked: a due WAIT_RETURN only re-parks, no call (shipping events move it, Task 4).
    calls.clear();
    clock.shift(Duration.ofDays(31));
    worker.runOnce();
    assertThat(calls).isEmpty();
    assertThat(saga(id)).containsEntry("status", "WAITING").containsEntry("step", "WAIT_RETURN");
    assertThat(((Timestamp) saga(id).get("next_retry_at")).toInstant()).isAfter(clock.instant().plus(Duration.ofDays(29)));
  }

  @Test void anotherShipmentCancelRefusalGoesManual() {
    UUID id = packedWithShipment();
    shipmentCancel = new StubServers.Reply(409, StubServers.error("CONFLICT", "SHIPMENT_CONFLICT"));
    opsCancel(id);
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("inventory_state", "COMMITTED");
    assertThat((String) saga(id).get("last_error")).contains("SHIPMENT_CONFLICT");
    assertThat(count(StubServers.INVENTORY, "/release")).isZero();
  }

  @Test void aCancelDuringALostShipmentCreateCancelsTheShipmentFirst() {
    // CREATE_SHIPMENT intent written, response lost: shipment_id null, stock COMMITTED, the shipment may exist.
    UUID id = placeOrder();
    stubAll();
    worker.runOnce();
    commands.confirmCod(OPS, no(id), "confirm-" + id, version(id), null);
    worker.runOnce();
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(503, StubServers.error("TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE")));
    worker.runOnce();
    assertThat(saga(id)).containsEntry("step", "CREATE_SHIPMENT").containsEntry("shipment_id", null).containsEntry("shipment_state", "NONE");
    stubAll();
    calls.clear();
    opsCancel(id);
    runAll();
    assertThat(calls).containsExactly("shipping cancel", "inventory release", "inventory returns", "payment close");
    assertThat(status(id)).isEqualTo("CANCELLED");
  }

  @Test void aCommitInFlightIsArbitratedByTheRelease() {
    // OPS cancels at COMMIT_STOCK (inventory_state HELD): inventory decides; no shipment was ever created.
    UUID released = placeOrder();
    stubAll();
    worker.runOnce();
    commands.confirmCod(OPS, no(released), "confirm-" + released, version(released), null);
    restoreRequired = false;
    calls.clear();
    opsCancel(released);
    runAll();
    assertThat(calls).containsExactly("inventory release", "payment close");
    assertThat(saga(released)).containsEntry("inventory_state", "RELEASED").containsEntry("shipment_state", "NONE");
    assertThat(status(released)).isEqualTo("CANCELLED");

    clock.reset();   // ShiftClock would expire the member JWT
    UUID committed = placeOrder();
    stubAll();
    worker.runOnce();
    commands.confirmCod(OPS, no(committed), "confirm-" + committed, version(committed), null);
    restoreRequired = true;
    calls.clear();
    opsCancel(committed);
    runAll();
    // The commit landed: recorded first, so the shipment step (a tombstone here) runs before the restore (T16).
    assertThat(calls).containsExactly("inventory release", "shipping cancel", "inventory release", "inventory returns", "payment close");
    assertThat(saga(committed)).containsEntry("inventory_state", "RESTORED").containsEntry("shipment_state", "CANCELLED");
    assertThat(status(committed)).isEqualTo("CANCELLED");
  }

  @Test void aRefusedRestoreGoesManual() {
    UUID id = packedWithShipment();
    opsCancel(id);
    StubServers.INVENTORY.reply(s -> s.path().endsWith("/release")
        ? new StubServers.Reply(200, StubServers.ok("{\"status\":\"COMMITTED\",\"restore_required\":true}"))
        : new StubServers.Reply(409, StubServers.error("CONFLICT", "RETURN_CONFLICT")));
    runAll();
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("inventory_state", "COMMITTED").containsEntry("shipment_state", "CANCELLED");
    assertThat((String) saga(id).get("last_error")).contains("RETURN_CONFLICT");
    assertThat(meters.counter("order.saga.manual", "step", "COMPENSATE").count()).isPositive();
    assertThat(status(id)).isEqualTo("CANCELLING");
  }
}
