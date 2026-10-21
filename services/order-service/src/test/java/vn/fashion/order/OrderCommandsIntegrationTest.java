package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.saga.SagaRepository;
import vn.fashion.order.saga.SagaWorker;

/** TASK:ORD-03 spec section 3: OPS confirm-cod hands the order to the saga; OPS cancel compensates through ORD-02. */
class OrderCommandsIntegrationTest extends OrderTestSupport {
  static final UUID OPS = UUID.randomUUID();
  @Autowired SagaWorker worker;
  @Autowired SagaRepository sagas;

  private String ops() { return tokenWith(OPS, "order.read", "order.fulfill", "order.cancel"); }

  /** A COD order the ORD-02 saga confirmed (WAIT_OPS); returns its id. */
  private UUID confirmed() {
    UUID id = placeOrder();
    stubSagaOk();
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CONFIRMED");
    assertThat(saga(id)).containsEntry("step", "WAIT_OPS");
    return id;
  }
  private String no(UUID id) { return jdbc.queryForObject("select order_no from orders where id = ?", String.class, id); }
  private long version(UUID id) { return jdbc.queryForObject("select version from orders where id = ?", Long.class, id); }
  private HttpResponse<String> post(UUID id, String action, String key, String body, String token) {
    var headers = new ArrayList<String>();
    if (token != null) { headers.add("Authorization"); headers.add(token); }
    if (key != null) { headers.add("Idempotency-Key"); headers.add(key); }
    return send("POST", "/admin/api/v1/orders/" + no(id) + "/" + action, body, headers.toArray(String[]::new));
  }
  private HttpResponse<String> confirm(UUID id, String key, long version, String note) {
    return post(id, "confirm-cod", key, "{\"expected_version\":" + version + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}", ops());
  }
  private HttpResponse<String> cancel(UUID id, String key, long version, String reason) {
    return post(id, "cancel", key, "{\"expected_version\":" + version + ",\"reason\":\"" + reason + "\"}", ops());
  }
  /** Past the deadline by moving expires_at: ShiftClock would also expire the admin JWT (AdminAuth reads the same clock). */
  private void expire(UUID id) { jdbc.update("update orders set expires_at = now() - interval '1 minute' where id = ?", id); }
  private int audits(String action) { return count("select count(*) from audit_logs where action = ?", action); }

  @Test void anOpsConfirmHandsTheOrderToTheSaga() {
    UUID id = confirmed();
    long v = version(id);
    var confirmedAt = jdbc.queryForObject("select confirmed_at from orders where id = ?", java.sql.Timestamp.class, id);
    int history = count("select count(*) from order_status_history where order_id = ?", id);
    var d = data(confirm(id, "c1", v, "Da goi khach"), 202);
    assertThat(d.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(d.path("version").asLong()).isEqualTo(v + 1);
    assertThat(d.path("processing_status").asString()).isEqualTo("PROCESSING");
    assertThat(version(id)).isEqualTo(v + 1);
    assertThat(saga(id)).containsEntry("step", "COMMIT_STOCK").containsEntry("status", "RUNNING").containsEntry("retry_count", 0)
        .containsEntry("lease_token", null);
    assertThat(count("select count(*) from order_sagas where order_id = ? and next_retry_at <= now() + interval '5 seconds'", id)).isEqualTo(1);
    var audit = jdbc.queryForMap("select * from audit_logs where action = 'ORDER_CONFIRM_COD'");
    assertThat(audit).containsEntry("reason", "Da goi khach").containsEntry("actor_id", OPS).containsEntry("resource_type", "order")
        .containsEntry("resource_id", no(id));
    assertThat(audits("ORDER_CONFIRM_COD")).isEqualTo(1);
    assertThat(count("select count(*) from order_status_history where order_id = ?", id)).isEqualTo(history);
    assertThat(jdbc.queryForObject("select confirmed_at from orders where id = ?", java.sql.Timestamp.class, id)).isEqualTo(confirmedAt);
    // The customer GET uses the same rule.
    assertThat(data(send("GET", "/api/v1/orders/" + no(id), null, "Authorization", memberToken()), 200).path("processing_status").asString())
        .isEqualTo("PROCESSING");
  }

  @Test void confirmRefusals() {
    UUID id = confirmed();
    long v = version(id);
    assertThat(problem(confirm(id, null, v, null), 400)).isEqualTo("INVALID_KEY");
    assertThat(problem(post(id, "confirm-cod", "k", "{\"expected_version\":" + v + "}", tokenWith(OPS, "order.read", "order.cancel")), 403))
        .isEqualTo("PERMISSION_REQUIRED");
    assertThat(problem(post(id, "confirm-cod", "k", "{}", ops()), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(confirm(id, "k", v, "x".repeat(501)), 400)).isEqualTo("INVALID_FIELDS");
    var stale = confirm(id, "k", v - 1, null);
    assertThat(problem(stale, 409)).isEqualTo("VERSION_CONFLICT");
    // A worker holding the lease: the OPS command waits for it to finish.
    jdbc.update("update order_sagas set lease_token = gen_random_uuid(), lease_until = now() + interval '30 seconds' where order_id = ?", id);
    assertThat(problem(confirm(id, "k", v, null), 409)).isEqualTo("SAGA_BUSY");
    jdbc.update("update order_sagas set lease_token = null, lease_until = null where order_id = ?", id);
    expire(id);
    assertThat(problem(confirm(id, "k", v, null), 409)).isEqualTo("ORDER_EXPIRED");
    jdbc.update("update orders set expires_at = now() + interval '1 day' where id = ?", id);
    data(confirm(id, "k", v, null), 202);
    var again = confirm(id, "k2", v + 1, null);
    assertThat(problem(again, 409)).isEqualTo("INVALID_TRANSITION");
    assertThat(json(again).path("errors").get(0).path("field").asString()).isEqualTo("status");
    assertThat(json(again).path("errors").get(0).path("message").asString()).isEqualTo("current=CONFIRMED");
    // ORD-04: a cancel after confirm-cod (COMMIT_STOCK) is now accepted.
    data(post(id, "cancel", "k3", "{\"expected_version\":" + (v + 1) + ",\"reason\":\"x\"}", ops()), 202);
    assertThat(status(id)).isEqualTo("CANCELLING");
    assertThat(audits("ORDER_CONFIRM_COD")).isEqualTo(1);
  }

  private void seedSaga(UUID id, String status, String step, String sagaStatus) {
    jdbc.update("update orders set status = ? where id = ?", status, id);
    jdbc.update("update order_sagas set intent = 'FULFILL', step = ?, status = ? where order_id = ?", step, sagaStatus, id);
  }

  @Test void opsCancelsAPackingOrder() {
    UUID id = confirmed();
    seedSaga(id, "PACKING", "DONE", "DONE");
    jdbc.update("update order_sagas set shipment_id = gen_random_uuid() where order_id = ?", id);
    var d = data(cancel(id, "p1", version(id), "Khach doi y"), 202);
    assertThat(d.path("status").asString()).isEqualTo("CANCELLING");
    assertThat(saga(id)).containsEntry("intent", "CANCEL").containsEntry("step", "COMPENSATE").containsEntry("status", "COMPENSATING")
        .containsEntry("retry_count", 0).containsEntry("lease_token", null);
    var history = jdbc.queryForMap("select * from order_status_history where order_id = ? and to_status = 'CANCELLING'", id);
    assertThat(history).containsEntry("from_status", "PACKING").containsEntry("actor_type", "ADMIN").containsEntry("actor_id", OPS);
    assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id)).isEqualTo("OPS_CANCELLED");
    assertThat(audits("ORDER_CANCEL")).isEqualTo(1);
    worker.runOnce();   // Task 3 owns the compensation; here it must only not crash
  }

  @Test void opsCancelsAStuckSaga() {
    UUID packing = confirmed();
    seedSaga(packing, "PACKING", "CREATE_SHIPMENT", "MANUAL");
    jdbc.update("update order_sagas set retry_count = 7, last_error = 'REJECTED QUOTE_MISMATCH' where order_id = ?", packing);
    data(cancel(packing, "s1", version(packing), "x"), 202);
    assertThat(status(packing)).isEqualTo("CANCELLING");
    assertThat(saga(packing)).containsEntry("status", "COMPENSATING").containsEntry("retry_count", 0).containsEntry("last_error", null);
    UUID commit = confirmed();
    seedSaga(commit, "CONFIRMED", "COMMIT_STOCK", "RUNNING");
    data(cancel(commit, "s2", version(commit), "x"), 202);
    assertThat(status(commit)).isEqualTo("CANCELLING");
    assertThat(saga(commit)).containsEntry("step", "COMPENSATE");
  }

  @Test void opsCancelAdmissionRefusals() {
    UUID shipping = confirmed();
    seedSaga(shipping, "SHIPPING", "DONE", "DONE");
    var r = cancel(shipping, "r1", version(shipping), "x");
    assertThat(problem(r, 409)).isEqualTo("INVALID_TRANSITION");
    assertThat(json(r).path("errors").get(0).path("message").asString()).isEqualTo("current=SHIPPING");
    UUID cancelling = confirmed();
    seedSaga(cancelling, "CANCELLING", "COMPENSATE", "COMPENSATING");
    jdbc.update("update order_sagas set intent = 'CANCEL' where order_id = ?", cancelling);
    assertThat(problem(cancel(cancelling, "r2", version(cancelling), "x"), 409)).isEqualTo("INVALID_TRANSITION");
    UUID busy = confirmed();
    seedSaga(busy, "PACKING", "CREATE_SHIPMENT", "RUNNING");
    jdbc.update("update order_sagas set lease_token = gen_random_uuid(), lease_until = now() + interval '30 seconds' where order_id = ?", busy);
    assertThat(problem(cancel(busy, "r3", version(busy), "x"), 409)).isEqualTo("SAGA_BUSY");
  }

  @Test void aReplayReturnsTheStoredResponse() {
    UUID id = confirmed();
    long v = version(id);
    var first = data(confirm(id, "same", v, "n"), 202);
    var replay = data(confirm(id, "same", v, "n"), 202);
    assertThat(replay).isEqualTo(first);
    assertThat(audits("ORDER_CONFIRM_COD")).isEqualTo(1);
    assertThat(problem(confirm(id, "same", v, "other"), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test void anOpsCancelReleasesAndCloses() throws Exception {
    UUID id = confirmed();
    long v = version(id);
    var d = data(cancel(id, "x1", v, "Khách không nghe máy"), 202);
    assertThat(d.path("status").asString()).isEqualTo("CANCELLING");
    assertThat(saga(id)).containsEntry("intent", "CANCEL").containsEntry("step", "COMPENSATE").containsEntry("status", "COMPENSATING");
    var history = jdbc.queryForMap("select * from order_status_history where order_id = ? and to_status = 'CANCELLING'", id);
    assertThat(history).containsEntry("actor_type", "ADMIN").containsEntry("actor_id", OPS).containsEntry("reason", "Khách không nghe máy");
    var audit = jdbc.queryForMap("select * from audit_logs where action = 'ORDER_CANCEL'");
    assertThat(audit).containsEntry("reason", "Khách không nghe máy").containsEntry("request_id", history.get("request_id"));
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(StubServers.INVENTORY.seen.stream().filter(s -> s.path().endsWith("/release")).count()).isEqualTo(1);
    assertThat(StubServers.PAYMENT.seen.stream().filter(s -> s.path().endsWith("/close")).count()).isEqualTo(1);
    assertThat(data(send("GET", "/api/v1/orders/" + no(id), null, "Authorization", memberToken()), 200).path("reason_code").asString())
        .isEqualTo("OPS_CANCELLED");
    var event = mapper.readTree(jdbc.queryForObject("select payload::text from outbox_events where event_type = 'ORDER_CANCELLED'", String.class));
    assertThat(event.path("reason").asString()).isEqualTo("OPS_CANCELLED");
  }

  @Test void cancelRefusals() {
    UUID id = confirmed();
    long v = version(id);
    assertThat(problem(post(id, "cancel", "k", "{\"expected_version\":" + v + "}", ops()), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(cancel(id, "k", v, " "), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(post(id, "cancel", "k", "{\"expected_version\":" + v + ",\"reason\":\"x\"}", tokenWith(OPS, "order.read", "order.fulfill")), 403))
        .isEqualTo("PERMISSION_REQUIRED");
    // Expired, but the worker has not run yet: OPS still cancels (same outcome).
    expire(id);
    data(cancel(id, "k", v, "Het han"), 202);
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id)).isEqualTo("OPS_CANCELLED");
  }

  @Test void aCancelWithoutOrderFulfillSeesNoContact() {
    UUID id = confirmed();
    var d = data(post(id, "cancel", "k", "{\"expected_version\":" + version(id) + ",\"reason\":\"x\"}", tokenWith(OPS, "order.cancel")), 202);
    // Keys omitted, the same shape as the admin GETs.
    for (String field : new String[] {"contact", "shipping_address", "note"}) assertThat(d.has(field)).as(field).isFalse();
    assertThat(d.path("status").asString()).isEqualTo("CANCELLING");
  }

  @Test void aWorkerClaimTakenBeforeTheConfirmLosesItsWrite() {
    UUID id = confirmed();
    long v = version(id);
    asOwner("update order_sagas set next_retry_at = now() - interval '1 minute' where order_id = '" + id + "'");
    var claim = sagas.claimDue(10, Duration.ofSeconds(30)).stream().filter(c -> c.orderId().equals(id)).findFirst().orElseThrow();
    assertThat(problem(confirm(id, "k", v, null), 409)).isEqualTo("SAGA_BUSY");   // lease still live (PR #34 review M4)
    asOwner("update order_sagas set lease_until = now() - interval '1 second' where order_id = '" + id + "'");
    data(confirm(id, "k", v, null), 202);
    assertThat(sagas.write(claim, jdbc -> jdbc.sql("update order_sagas set step = 'WAIT_OPS' where order_id = :id").param("id", id).update(), true))
        .isFalse();
    assertThat(saga(id)).containsEntry("step", "COMMIT_STOCK");
  }

  @Test void confirmAndCancelRaceHasOneWinner() throws Exception {
    UUID id = confirmed();
    long v = version(id);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> { start.await(); return confirm(id, "race-a", v, null); });
      var b = pool.submit(() -> { start.await(); return cancel(id, "race-b", v, "r"); });
      start.countDown();
      var codes = new ArrayList<Integer>(java.util.List.of(a.get().statusCode(), b.get().statusCode()));
      assertThat(codes).containsExactlyInAnyOrder(202, 409);
      var loser = a.get().statusCode() == 409 ? a.get() : b.get();
      assertThat(problem(loser, 409)).isEqualTo("VERSION_CONFLICT");
    } finally { pool.shutdown(); }
    var saga = saga(id);
    assertThat(saga.get("intent") + "/" + saga.get("step")).isIn("FULFILL/COMMIT_STOCK", "CANCEL/COMPENSATE");
    assertThat(version(id)).isEqualTo(v + 1);
    assertThat(count("select count(*) from audit_logs")).isEqualTo(1);
  }
}
