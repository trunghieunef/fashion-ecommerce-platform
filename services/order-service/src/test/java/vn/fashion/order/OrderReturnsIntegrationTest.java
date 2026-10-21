package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.admin.OrderCommands;
import vn.fashion.order.admin.OrderReturns;
import vn.fashion.order.saga.OrderReturnWorker;
import vn.fashion.order.saga.SagaWorker;

/**
 * TASK:ORD-04 spec sections 5/6: OPS return slips (order.return), the ORDER_RETURN task restocking once (key return:{id}),
 * the saga CLOSE_PAYMENT step ending RETURNING -> RETURNED, and returns after delivery that keep the order DELIVERED.
 */
class OrderReturnsIntegrationTest extends OrderTestSupport {
  static final UUID OPS = UUID.randomUUID();
  @Autowired SagaWorker worker;
  @Autowired OrderReturnWorker returns;
  @Autowired MeterRegistry meters;

  private String ops() { return tokenWith(OPS, "order.read", "order.fulfill", "order.return"); }

  /** A placed order (TEE-M x2, TEE-L x1, stock committed, COD payment created) forced to `status`. */
  private UUID inStatus(String status) {
    UUID id = placeOrder();
    boolean returning = status.equals("RETURNING");
    jdbc.update("update orders set status = ?, cancel_reason = ? where id = ?", status, returning ? "RETURNED" : null, id);
    jdbc.update("""
        update order_sagas set intent = ?, step = ?, status = ?, next_retry_at = now() + interval '30 days', inventory_state = 'COMMITTED',
          payment_state = 'CREATED', payment_id = '55555555-5555-4555-8555-555555555591', shipment_id = gen_random_uuid(),
          shipment_state = 'HANDED_OVER', retry_count = 0 where order_id = ?""",
        returning ? "CANCEL" : "FULFILL", returning ? "WAIT_RETURN" : "DONE", returning ? "WAITING" : "DONE", id);
    return id;
  }
  private String no(UUID id) { return jdbc.queryForObject("select order_no from orders where id = ?", String.class, id); }
  private long version(UUID id) { return jdbc.queryForObject("select version from orders where id = ?", Long.class, id); }
  private UUID item(UUID id, String sku) {
    return jdbc.queryForObject("select id from order_items where order_id = ? and sku = ?", UUID.class, id, sku);
  }
  private static String line(UUID item, int received, int restock, String condition) {
    return "{\"order_item_id\":\"" + item + "\",\"received_quantity\":" + received + ",\"restock_quantity\":" + restock
        + (condition == null ? "" : ",\"condition\":\"" + condition + "\"") + "}";
  }
  private static String body(long version, String... lines) {
    return "{\"expected_version\":" + version + ",\"reason\":\"Hang hoan ve\",\"evidence_ref\":\"count-1\",\"items\":["
        + String.join(",", lines) + "]}";
  }
  private HttpResponse<String> slip(UUID id, String key, String body, String token) {
    var headers = new ArrayList<String>(List.of("Authorization", token));
    if (key != null) { headers.add("Idempotency-Key"); headers.add(key); }
    return send("POST", "/admin/api/v1/orders/" + no(id) + "/returns", body, headers.toArray(String[]::new));
  }
  private List<StubServers.Seen> calls(StubServers.Stub stub, String suffix) {
    return stub.seen.stream().filter(s -> s.path().endsWith(suffix)).toList();
  }
  private String slipStatus(UUID returnId) {
    return jdbc.queryForObject("select status from order_returns where id = ?", String.class, returnId);
  }
  private String taskStatus(UUID returnId) {
    return jdbc.queryForObject("select status from background_tasks where kind = 'ORDER_RETURN' and business_key = ?", String.class,
        returnId.toString());
  }
  private int completed() { return count("select count(*) from outbox_events where event_type = 'ORDER_COMPLETED'"); }
  /** Inventory /returns answers status/body; payment close FAILED (stubSagaOk). */
  private void inventoryReturns(int status, String body) {
    stubSagaOk();
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(status, body));
  }

  @Test void aCountedReturnRestocksAndFinishesTheOrder() throws Exception {
    UUID id = inStatus("RETURNING");
    long v = version(id);
    var d = data(slip(id, "ret-1", body(v, line(item(id, "TEE-M"), 2, 1, "rách")), ops()), 202);
    UUID returnId = UUID.fromString(d.path("return").path("return_id").asString());
    assertThat(d.path("status").asString()).isEqualTo("RETURNING");
    assertThat(d.path("return").path("status").asString()).isEqualTo("REQUESTED");
    assertThat(d.path("return").path("items")).hasSize(1);
    assertThat(jdbc.queryForMap("select * from order_returns where id = ?", returnId)).containsEntry("operation_key", "ret-1")
        .containsEntry("status", "REQUESTED").containsEntry("evidence_ref", "count-1").containsEntry("actor_id", OPS);
    assertThat(jdbc.queryForMap("select * from order_return_items where return_id = ?", returnId))
        .containsEntry("received_quantity", 2).containsEntry("restock_quantity", 1).containsEntry("condition", "rách");
    assertThat(count("select count(*) from audit_logs where action = 'ORDER_RETURN'")).isEqualTo(1);
    assertThat(version(id)).isEqualTo(v + 1);
    assertThat(taskStatus(returnId)).isEqualTo("PENDING");

    inventoryReturns(200, StubServers.ok("{}"));
    assertThat(returns.runOnce()).isEqualTo(1);
    var sent = calls(StubServers.INVENTORY, "/returns");
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0).idempotencyKey()).isEqualTo("return:" + returnId);
    var request = mapper.readTree(sent.get(0).body());
    assertThat(request.path("operation_key").asString()).isEqualTo("return:" + returnId);
    assertThat(request.path("reason").asString()).isEqualTo("Hang hoan ve");
    assertThat(request.path("evidence_ref").asString()).isEqualTo("count-1");
    assertThat(request.path("items")).hasSize(1);
    assertThat(request.path("items").get(0).path("sku").asString()).isEqualTo("TEE-M");
    assertThat(request.path("items").get(0).path("quantity").asInt()).isEqualTo(1);
    assertThat(slipStatus(returnId)).isEqualTo("COMPLETED");
    assertThat(taskStatus(returnId)).isEqualTo("DONE");
    assertThat(saga(id)).containsEntry("step", "CLOSE_PAYMENT").containsEntry("status", "RUNNING").containsEntry("retry_count", 0);

    worker.runOnce();
    assertThat(calls(StubServers.PAYMENT, "/close")).hasSize(1);
    assertThat(calls(StubServers.PAYMENT, "/close").get(0).idempotencyKey()).isEqualTo("close:" + id);
    assertThat(status(id)).isEqualTo("RETURNED");
    assertThat(saga(id)).containsEntry("status", "DONE").containsEntry("step", "DONE").containsEntry("payment_state", "CLOSED");
    assertThat(count("select count(*) from order_status_history where order_id = ? and from_status = 'RETURNING' and to_status = 'RETURNED'",
        id)).isEqualTo(1);
    assertThat(data(send("GET", "/api/v1/orders/" + no(id), null, "Authorization", memberToken()), 200)
        .path("processing_status").asString()).isEqualTo("FINISHED");
    var detail = data(send("GET", "/admin/api/v1/orders/" + no(id), null, "Authorization", ops()), 200);
    assertThat(detail.path("returns")).hasSize(1);
    assertThat(detail.path("returns").get(0).path("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.path("returns").get(0).path("items").get(0).path("restock_quantity").asInt()).isEqualTo(1);
    assertThat(detail.path("items").get(0).path("order_item_id").asString()).isNotEmpty();
  }

  @Test void aReturnAfterDeliveryKeepsTheOrderDelivered() {
    UUID id = inStatus("DELIVERED");
    var d = data(slip(id, "ret-d", body(version(id), line(item(id, "TEE-L"), 1, 1, null)), ops()), 202);
    UUID returnId = UUID.fromString(d.path("return").path("return_id").asString());
    inventoryReturns(200, StubServers.ok("{}"));
    returns.runOnce();
    assertThat(calls(StubServers.INVENTORY, "/returns")).hasSize(1);
    assertThat(slipStatus(returnId)).isEqualTo("COMPLETED");
    worker.runOnce();
    assertThat(status(id)).isEqualTo("DELIVERED");
    assertThat(saga(id)).containsEntry("step", "DONE").containsEntry("status", "DONE");
    assertThat(StubServers.PAYMENT.seen).isEmpty();
    assertThat(completed()).isZero();
  }

  @Test void aLateSlipOnAReturnedOrderOnlyRestocks() {
    UUID id = inStatus("RETURNED");
    jdbc.update("update order_sagas set intent = 'CANCEL', payment_state = 'CLOSED' where order_id = ?", id);
    var d = data(slip(id, "ret-late", body(version(id), line(item(id, "TEE-M"), 1, 1, null)), ops()), 202);
    inventoryReturns(200, StubServers.ok("{}"));
    returns.runOnce();
    assertThat(slipStatus(UUID.fromString(d.path("return").path("return_id").asString()))).isEqualTo("COMPLETED");
    assertThat(calls(StubServers.INVENTORY, "/returns")).hasSize(1);
    assertThat(status(id)).isEqualTo("RETURNED");
    assertThat(saga(id)).containsEntry("step", "DONE").containsEntry("status", "DONE");
  }

  /** T17 and the admission refusals: nothing written, nothing stored for a business 4xx. */
  @Test void slipReplaysAndRefusals() {
    UUID id = inStatus("RETURNING");
    UUID teeM = item(id, "TEE-M");
    long v = version(id);
    String first = body(v, line(teeM, 2, 2, null));
    var a = data(slip(id, "k1", first, ops()), 202);
    var b = data(slip(id, "k1", first, ops()), 202);
    assertThat(b.path("return").path("return_id")).isEqualTo(a.path("return").path("return_id"));
    assertThat(count("select count(*) from order_returns")).isEqualTo(1);
    assertThat(problem(slip(id, "k1", body(v, line(teeM, 1, 1, null)), ops()), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    inventoryReturns(200, StubServers.ok("{}"));
    returns.runOnce();
    returns.runOnce();
    assertThat(calls(StubServers.INVENTORY, "/returns")).hasSize(1);

    long v1 = version(id);
    int keys = count("select count(*) from idempotency_requests");
    var over = slip(id, "k2", body(v1, line(teeM, 1, 0, null)), ops());
    assertThat(problem(over, 409)).isEqualTo("RETURN_EXCEEDS_ORDERED");
    assertThat(json(over).path("errors").get(0).path("field").asString()).isEqualTo("items[" + teeM + "]");
    assertThat(json(over).path("errors").get(0).path("message").asString()).isEqualTo("ordered=2 received=2 requested=1");
    assertThat(count("select count(*) from order_returns")).isEqualTo(1);
    assertThat(count("select count(*) from idempotency_requests")).isEqualTo(keys);
    assertThat(version(id)).isEqualTo(v1);

    UUID teeL = item(id, "TEE-L");
    assertThat(problem(slip(id, "k3", body(v1, line(teeL, 1, 2, null)), ops()), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(slip(id, "k3", body(v1, line(UUID.randomUUID(), 1, 1, null)), ops()), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(slip(id, "k3", body(v1, line(teeL, 1, 1, null), line(teeL, 1, 1, null)), ops()), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(slip(id, "k3", body(v1, line(teeL, 0, 0, null)), ops()), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(slip(id, "k3", body(v1), ops()), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(slip(id, "k3", body(v1 - 1, line(teeL, 1, 1, null)), ops()), 409)).isEqualTo("VERSION_CONFLICT");
    assertThat(problem(slip(id, null, body(v1, line(teeL, 1, 1, null)), ops()), 400)).isEqualTo("INVALID_KEY");
    assertThat(problem(slip(id, "k3", body(v1, line(teeL, 1, 1, null)), tokenWith(OPS, "order.read", "order.fulfill", "order.cancel")), 403))
        .isEqualTo("PERMISSION_REQUIRED");
    assertThat(problem(slip(id, "k3", body(v1, line(teeL, 1, 1, null)), tokenWith(UUID.randomUUID(), "order.read")), 403))
        .isEqualTo("PERMISSION_REQUIRED");
    for (String status : List.of("SHIPPING", "CANCELLED")) {
      UUID other = inStatus(status);
      var refused = slip(other, "k-" + status, body(version(other), line(item(other, "TEE-L"), 1, 1, null)), ops());
      assertThat(problem(refused, 409)).as(status).isEqualTo("INVALID_TRANSITION");
    }
    assertThat(count("select count(*) from order_returns")).isEqualTo(1);
    assertThat(count("select count(*) from audit_logs where action = 'ORDER_RETURN'")).isEqualTo(1);
  }

  /** The idempotency hash must be the same on every JVM/replica: its input is a fixed string (no Map.of ordering). */
  @Test void theSlipHashInputIsStable() {
    var line = new OrderReturns.Line(UUID.fromString("22222222-2222-4222-8222-000000000009"), 2, 1, "torn");
    var c = new OrderCommands.Command("admin:x", "return", "ORD1", "k", 3, "Hang hoan ve", null, null, null, null,
        OrderReturns.payload("count-1", List.of(line)));
    assertThat(OrderCommands.hashInput(mapper, c)).isEqualTo("[\"ORD1\",3,\"Hang hoan ve\",[\"count-1\",[{\"order_item_id\":\"22222222-2222-4222-8222-000000000009\","
        + "\"received_quantity\":2,\"restock_quantity\":1,\"condition\":\"torn\"}]]]");
  }

  @Test void concurrentSlipsNeverExceedTheQuantity() throws Exception {
    UUID id = inStatus("RETURNING");
    UUID teeM = item(id, "TEE-M");
    long v = version(id);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> { start.await(); return slip(id, "race-a", body(v, line(teeM, 2, 2, null)), ops()); });
      var b = pool.submit(() -> { start.await(); return slip(id, "race-b", body(v, line(teeM, 2, 1, null)), ops()); });
      start.countDown();
      assertThat(List.of(a.get().statusCode(), b.get().statusCode())).containsExactlyInAnyOrder(202, 409);
      // Same expected_version: the loser fails the version check first (the sum guard is pinned in slipReplaysAndRefusals).
      assertThat(problem(a.get().statusCode() == 409 ? a.get() : b.get(), 409)).isEqualTo("VERSION_CONFLICT");
    } finally { pool.shutdown(); }
    assertThat(count("select count(*) from order_returns")).isEqualTo(1);
    assertThat(count("select coalesce(sum(received_quantity), 0)::int from order_return_items where order_item_id = ?", teeM)).isEqualTo(2);
  }

  @Test void aSlipRestockingNothingCompletesWithoutInventory() {
    UUID id = inStatus("RETURNING");
    var d = data(slip(id, "ret-0", body(version(id), line(item(id, "TEE-M"), 2, 0, "hong"), line(item(id, "TEE-L"), 1, 0, null)), ops()), 202);
    returns.runOnce();
    assertThat(StubServers.INVENTORY.seen).isEmpty();
    UUID returnId = UUID.fromString(d.path("return").path("return_id").asString());
    assertThat(slipStatus(returnId)).isEqualTo("COMPLETED");
    assertThat(taskStatus(returnId)).isEqualTo("DONE");
    assertThat(saga(id)).containsEntry("step", "CLOSE_PAYMENT").containsEntry("status", "RUNNING");
  }

  @Test void anInventoryRefusalParksTheSlipForOps() {
    UUID id = inStatus("RETURNING");
    var d = data(slip(id, "ret-x", body(version(id), line(item(id, "TEE-M"), 1, 1, null)), ops()), 202);
    inventoryReturns(409, StubServers.error("CONFLICT", "RETURN_EXCEEDS_COMMITTED"));
    returns.runOnce();
    UUID returnId = UUID.fromString(d.path("return").path("return_id").asString());
    assertThat(slipStatus(returnId)).isEqualTo("MANUAL");
    assertThat(taskStatus(returnId)).isEqualTo("MANUAL");
    assertThat(saga(id)).containsEntry("step", "WAIT_RETURN").containsEntry("status", "WAITING");
  }

  @Test void aManualSlipStillCountsUntilVoided() {
    UUID id = inStatus("RETURNING");
    UUID teeM = item(id, "TEE-M");
    var d = data(slip(id, "ret-m", body(version(id), line(teeM, 2, 2, null)), ops()), 202);
    inventoryReturns(409, StubServers.error("CONFLICT", "RETURN_EXCEEDS_COMMITTED"));
    double before = meters.counter("order.return.manual").count();
    returns.runOnce();
    UUID returnId = UUID.fromString(d.path("return").path("return_id").asString());
    assertThat(slipStatus(returnId)).isEqualTo("MANUAL");
    assertThat(meters.counter("order.return.manual").count()).isEqualTo(before + 1);
    assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, id)).isEqualTo("RETURNING");
    assertThat(saga(id)).containsEntry("step", "WAIT_RETURN").containsEntry("status", "WAITING");
    // The MANUAL slip still counts in the received sum until OPS voids it (opsVoidsAManualSlipAndCountsAgain).
    assertThat(problem(slip(id, "ret-m2", body(version(id), line(teeM, 1, 1, null)), ops()), 409)).isEqualTo("RETURN_EXCEEDS_ORDERED");
  }

  @Test void anUnavailableInventoryRetriesTheTask() {
    UUID id = inStatus("RETURNING");
    var d = data(slip(id, "ret-u", body(version(id), line(item(id, "TEE-M"), 1, 1, null)), ops()), 202);
    inventoryReturns(503, "{}");
    returns.runOnce();
    UUID returnId = UUID.fromString(d.path("return").path("return_id").asString());
    assertThat(slipStatus(returnId)).isEqualTo("REQUESTED");
    assertThat(jdbc.queryForMap("select status, attempts, last_error from background_tasks where business_key = ?", returnId.toString()))
        .containsEntry("status", "PENDING").containsEntry("attempts", 1).containsEntry("last_error", "inventory_503");
    assertThat(saga(id)).containsEntry("step", "WAIT_RETURN");
  }

  @Test void anInventoryOutageNeverParksTheSlipManual() {
    // PR #35 review I2: a long outage is retried with the same key for as long as it takes; past 30 attempts it is flagged.
    UUID id = inStatus("RETURNING");
    var d = data(slip(id, "ret-o", body(version(id), line(item(id, "TEE-M"), 1, 1, null)), ops()), 202);
    UUID returnId = UUID.fromString(d.path("return").path("return_id").asString());
    jdbc.update("update background_tasks set attempts = 40 where business_key = ?", returnId.toString());
    inventoryReturns(503, "{}");
    double before = meters.counter("order.return.stalled").count();
    returns.runOnce();
    assertThat(slipStatus(returnId)).isEqualTo("REQUESTED");
    assertThat(taskStatus(returnId)).isEqualTo("PENDING");
    assertThat(meters.counter("order.return.stalled").count()).isEqualTo(before + 1);
    // Inventory back: the same key restocks and the order finishes.
    jdbc.update("update background_tasks set next_attempt_at = now() where business_key = ?", returnId.toString());
    inventoryReturns(200, StubServers.ok("{\"items\":[]}"));
    returns.runOnce();
    assertThat(slipStatus(returnId)).isEqualTo("COMPLETED");
    assertThat(calls(StubServers.INVENTORY, "/returns")).allMatch(s -> s.idempotencyKey().equals("return:" + returnId));
  }

  @Test void opsVoidsAManualSlipAndCountsAgain() {
    // PR #35 review I2: a refused slip wrote no stock; voiding it frees the received sum so the order can still finish.
    UUID id = inStatus("RETURNING");
    UUID teeM = item(id, "TEE-M");
    var d = data(slip(id, "ret-v", body(version(id), line(teeM, 2, 2, null)), ops()), 202);
    UUID returnId = UUID.fromString(d.path("return").path("return_id").asString());
    inventoryReturns(409, StubServers.error("CONFLICT", "RETURN_EXCEEDS_COMMITTED"));
    returns.runOnce();
    assertThat(slipStatus(returnId)).isEqualTo("MANUAL");
    String path = "/admin/api/v1/orders/" + no(id) + "/returns/" + returnId + "/void";
    String voidBody = "{\"expected_version\":" + version(id) + ",\"reason\":\"Kho kiem lai, lap phieu moi\"}";
    assertThat(problem(send("POST", path, voidBody, "Authorization", tokenWith(OPS, "order.read")), 403)).isEqualTo("PERMISSION_REQUIRED");
    data(send("POST", path, voidBody, "Authorization", ops(), "Idempotency-Key", "void-1"), 202);
    assertThat(slipStatus(returnId)).isEqualTo("VOIDED");
    assertThat(count("select count(*) from audit_logs where action = 'ORDER_RETURN_VOID'")).isEqualTo(1);
    // Same key + body replays the stored 202, no second audit row.
    data(send("POST", path, voidBody, "Authorization", ops(), "Idempotency-Key", "void-1"), 202);
    assertThat(count("select count(*) from audit_logs where action = 'ORDER_RETURN_VOID'")).isEqualTo(1);
    // Only a MANUAL slip can be voided; an unknown slip is 404.
    String again = "{\"expected_version\":" + version(id) + ",\"reason\":\"x\"}";
    assertThat(problem(send("POST", path, again, "Authorization", ops(), "Idempotency-Key", "void-2"), 409)).isEqualTo("RETURN_NOT_MANUAL");
    assertThat(problem(send("POST", "/admin/api/v1/orders/" + no(id) + "/returns/" + UUID.randomUUID() + "/void", again,
        "Authorization", ops(), "Idempotency-Key", "void-3"), 404)).isEqualTo("RETURN_NOT_FOUND");
    // Counted again: the new slip fits and finishes the order.
    var d2 = data(slip(id, "ret-v2", body(version(id), line(teeM, 2, 2, null)), ops()), 202);
    UUID second = UUID.fromString(d2.path("return").path("return_id").asString());
    inventoryReturns(200, StubServers.ok("{\"items\":[]}"));
    returns.runOnce();
    assertThat(slipStatus(second)).isEqualTo("COMPLETED");
    worker.runOnce();
    assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, id)).isEqualTo("RETURNED");
  }

  @Test void anotherOpsReusingAKeyOnTheOrderGets409() {
    // PR #35 review M3: idempotency is per actor, the slip key is unique per order — 409, not 500.
    UUID id = inStatus("RETURNING");
    data(slip(id, "same-key", body(version(id), line(item(id, "TEE-M"), 1, 1, null)), ops()), 202);
    String other = tokenWith(UUID.randomUUID(), "order.read", "order.fulfill", "order.return");
    assertThat(problem(slip(id, "same-key", body(version(id), line(item(id, "TEE-L"), 1, 1, null)), other), 409))
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test void financeSeesNoFreeTextOfASlip() {
    // PR #35 review M6: evidence_ref and condition are OPS free text, hidden without order.fulfill like reason.
    UUID id = inStatus("RETURNING");
    data(slip(id, "ret-f", body(version(id), line(item(id, "TEE-M"), 1, 0, "rach tay ao")), ops()), 202);
    var slip = data(send("GET", "/admin/api/v1/orders/" + no(id), null, "Authorization", tokenWith(UUID.randomUUID(), "order.read")), 200)
        .path("returns").get(0);
    assertThat(slip.path("reason").isNull()).isTrue();
    assertThat(slip.path("evidence_ref").isNull()).isTrue();
    assertThat(slip.path("items").get(0).path("condition").isNull()).isTrue();
    assertThat(slip.path("items").get(0).path("received_quantity").asInt()).isEqualTo(1);
  }

  @Test void aCollectedReturnStillRestocksButNeedsARefund() {
    UUID id = inStatus("RETURNING");
    jdbc.update("update order_sagas set payment_state = 'PAID' where order_id = ?", id);
    data(slip(id, "ret-p", body(version(id), line(item(id, "TEE-M"), 2, 2, null)), ops()), 202);
    inventoryReturns(200, StubServers.ok("{}"));
    returns.runOnce();
    assertThat(calls(StubServers.INVENTORY, "/returns")).hasSize(1);
    worker.runOnce();
    assertThat(saga(id)).containsEntry("status", "MANUAL").containsEntry("last_error", "PAID_NEEDS_REFUND").containsEntry("step", "CLOSE_PAYMENT");
    assertThat(status(id)).isEqualTo("RETURNING");
    assertThat(StubServers.PAYMENT.seen).isEmpty();
  }
}
