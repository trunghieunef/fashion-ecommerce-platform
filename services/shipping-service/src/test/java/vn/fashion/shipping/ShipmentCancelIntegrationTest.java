package vn.fashion.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

/** SHP-02 spec section 3.2: cancel before hand-over, cancel-before-create tombstone, refusal after hand-over (T16, SHP-06). */
class ShipmentCancelIntegrationTest extends ShippingTestSupport {
  final String ops = opsToken("shipping.self_event");

  HttpResponse<String> cancel(String order, String key, String body) {
    return send("POST", "/internal/api/v1/shipping/shipments/by-order/" + order + "/cancel", body,
        "Authorization", serviceToken("order-service"), "Idempotency-Key", key);
  }
  static String withReason(String body, String reason) {
    return body.substring(0, body.length() - 1) + ",\"reason\":\"" + reason + "\"}";
  }
  HttpResponse<String> selfEvent(String id, String body) {
    return send("POST", "/admin/api/v1/shipping/shipments/" + id + "/self-events", body, "Authorization", ops,
        "Idempotency-Key", UUID.randomUUID().toString());
  }

  @Test void cancelBeforeHandOver() {
    String order = UUID.randomUUID().toString(), quote = quote("79", true), body = body(order, "C1", quote, "79", 200000);
    data(create("k1", body), 200);
    var cancelled = data(cancel(order, "c1", withReason(body, "khách hủy")), 200);
    assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(cancelled.path("version").asLong()).isEqualTo(2);
    assertThat(cancelled.path("handed_over_at").isNull()).isTrue();
    // Retried with another key and reason: still the same CANCELLED shipment, nothing new written.
    assertThat(data(cancel(order, "c2", withReason(body, "lần nữa")), 200).path("version").asLong()).isEqualTo(2);
    assertThat(jdbc.queryForList("select to_status || ':' || coalesce(reason, '') from shipment_status_history order by created_at, id", String.class))
        .containsExactly("PENDING:", "CREATED:", "CANCELLED:khách hủy");
    var last = mapper.readTree(jdbc.queryForObject("select payload::text from outbox_events order by aggregate_sequence desc limit 1", String.class));
    assertThat(last.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForList("select event_type from outbox_events order by aggregate_sequence", String.class))
        .containsExactly("SHIPMENT_CREATED", "SHIPMENT_STATUS_UPDATED");
    // A late create, even the same body under the first key's twin, is refused.
    assertThat(problem(create("k3", body), 409)).isEqualTo("SHIPMENT_CANCELLED");
    // Review I1: the order's UNKNOWN retry with its original key must not replay the stale CREATED answer.
    assertThat(problem(create("k1", body), 409)).isEqualTo("SHIPMENT_CANCELLED");
  }

  @Test void pickingCanBeCancelledButNotAfterHandOver() {
    String order = UUID.randomUUID().toString(), body = body(order, "C2", quote("79", false), "79", 0);
    String id = data(create("k1", body), 200).path("shipment_id").asString();
    data(selfEvent(id, "{\"event_type\":\"ASSIGN\",\"expected_version\":1,\"assigned_to\":\"11111111-1111-4111-8111-111111111111\"}"), 200);
    data(selfEvent(id, "{\"event_type\":\"HANDED_OVER\",\"expected_version\":2,\"evidence_ref\":\"BG\"}"), 200);
    var refused = cancel(order, "c1", withReason(body, "khách hủy"));
    assertThat(problem(refused, 409)).isEqualTo("SHIPMENT_HANDED_OVER");
    assertThat(json(refused).path("errors").get(0).path("message").asString()).isEqualTo("current=DELIVERING");
    // Review M5: the hand-over fact wins over a snapshot mismatch, so the order starts the return flow.
    assertThat(problem(cancel(order, "c9", withReason(body.replace("1 Đường Mẫu", "2 Khác"), "x")), 409)).isEqualTo("SHIPMENT_HANDED_OVER");
    assertThat(jdbc.queryForObject("select status from shipments where id = cast(? as uuid)", String.class, id)).isEqualTo("DELIVERING");

    String other = UUID.randomUUID().toString(), otherBody = body(other, "C3", quote("79", false), "79", 0);
    String otherId = data(create("k2", otherBody), 200).path("shipment_id").asString();
    data(selfEvent(otherId, "{\"event_type\":\"ASSIGN\",\"expected_version\":1,\"assigned_to\":\"11111111-1111-4111-8111-111111111111\"}"), 200);
    assertThat(data(cancel(other, "c2", withReason(otherBody, "hết hàng")), 200).path("status").asString()).isEqualTo("CANCELLED");
    // A cancelled shipment takes no more SELF events.
    assertThat(problem(selfEvent(otherId, "{\"event_type\":\"HANDED_OVER\",\"expected_version\":3,\"evidence_ref\":\"BG\"}"), 409))
        .isEqualTo("INVALID_TRANSITION");
  }

  @Test void cancelBeforeCreateLeavesATombstone() {
    String order = UUID.randomUUID().toString(), quote = quote("01", true), body = body(order, "C4", quote, "01", 150000);
    var tombstone = data(cancel(order, "c1", withReason(body, "hết hạn")), 200);
    assertThat(tombstone.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(tombstone.path("version").asLong()).isZero();
    assertThat(tombstone.path("tracking_code").isNull()).isTrue();
    assertThat(problem(create("k1", body), 409)).isEqualTo("SHIPMENT_CANCELLED");
    assertThat(problem(create("k2", body(order, "C4", quote("01", true), "01", 150000)), 409)).isEqualTo("SHIPMENT_CANCELLED");
    // The tombstone used its quote; another order cannot take it.
    assertThat(problem(create("k3", body(UUID.randomUUID().toString(), "C5", quote, "01", 150000)), 409)).isEqualTo("QUOTE_ALREADY_USED");
    assertThat(jdbc.queryForObject("select count(*) from shipments", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForList("select event_type from outbox_events", String.class)).containsExactly("SHIPMENT_STATUS_UPDATED");
  }

  @Test void parallelCreateAndCancelOfOneQuote() throws Exception {
    // T16: whichever wins, the order ends CANCELLED with exactly one row and no live shipment.
    for (int i = 0; i < 4; i++) {
      String order = UUID.randomUUID().toString(), body = body(order, "P" + i, quote("79", false), "79", 0), n = String.valueOf(i);
      var barrier = new CyclicBarrier(2);
      var created = CompletableFuture.supplyAsync(() -> { await(barrier); return create("k" + n, body); });
      var cancelled = CompletableFuture.supplyAsync(() -> { await(barrier); return cancel(order, "c" + n, withReason(body, "đua")); });
      int createStatus = created.join().statusCode();
      assertThat(cancelled.join().statusCode()).isEqualTo(200);
      assertThat(createStatus).isIn(200, 409);
      assertThat(jdbc.queryForList("select status from shipments where order_id = cast(? as uuid)", String.class, order)).containsExactly("CANCELLED");
    }
  }
  private static void await(CyclicBarrier barrier) {
    try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); }
  }

  @Test void validationConflictsAndAuth() {
    String order = UUID.randomUUID().toString(), quote = quote("79", false), body = body(order, "C6", quote, "79", 0);
    data(create("k1", body), 200);
    assertThat(problem(cancel(order, "c1", withReason(body(order, "C6", quote, "79", 0).replace("1 Đường Mẫu", "2 Khác"), "x")), 409))
        .isEqualTo("SHIPMENT_CONFLICT");
    assertThat(problem(cancel(UUID.randomUUID().toString(), "c2", withReason(body, "x")), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(cancel(order, "c3", body), 400)).isEqualTo("INVALID_FIELDS");
    data(cancel(order, "c4", withReason(body, "một")), 200);
    assertThat(problem(cancel(order, "c4", withReason(body, "hai")), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    String unknown = UUID.randomUUID().toString();
    assertThat(problem(cancel(unknown, "c5", withReason(body(unknown, "C7", UUID.randomUUID().toString(), "79", 0), "x")), 404))
        .isEqualTo("QUOTE_NOT_FOUND");
    String path = "/internal/api/v1/shipping/shipments/by-order/" + order + "/cancel";
    assertThat(problem(send("POST", path, "{bad", "Idempotency-Key", "k"), 401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("POST", path, "{bad", "Authorization", serviceToken("cart-service"), "Idempotency-Key", "k"), 403))
        .isEqualTo("CALLER_NOT_ALLOWED");
    assertThat(problem(send("POST", path, "{bad", "Authorization", serviceToken("order-service")), 400)).isEqualTo("INVALID_KEY");
  }
}
