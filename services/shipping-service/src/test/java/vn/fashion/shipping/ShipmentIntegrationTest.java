package vn.fashion.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

/** SHP-01 spec section 5: one SELF shipment per order from one quote; idempotent; read by order and by OPS. */
class ShipmentIntegrationTest extends ShippingTestSupport {
  static final String SHIPMENTS = "/internal/api/v1/shipping/shipments";

  String quote(String province, boolean cod) {
    String v = variant(700);
    return data(send("POST", "/internal/api/v1/shipping/quotes", feeBody(province, cod, v, 1), "Authorization", serviceToken("order-service"),
        "Idempotency-Key", UUID.randomUUID().toString()), 200).path("quote_id").asString();
  }
  static String body(String order, String orderNo, String quote, String province, long cod) {
    return "{\"order_id\":\"" + order + "\",\"order_no\":\"" + orderNo + "\",\"quote_id\":\"" + quote + "\",\"cod_amount\":" + cod
        + ",\"to_address\":{\"recipient_name\":\"Khách mẫu\",\"phone\":\"0900000000\",\"province_code\":\"" + province
        + "\",\"ward_code\":\"26734\",\"address_line\":\"1 Đường Mẫu\"}}";
  }
  HttpResponse<String> create(String key, String body) {
    return send("POST", SHIPMENTS, body, "Authorization", serviceToken("order-service"), "Idempotency-Key", key);
  }

  @Test void createsSelfShipmentWithHistoryAndEvent() {
    String order = UUID.randomUUID().toString(), quote = quote("79", true);
    var s = data(create("k1", body(order, "ORD1", quote, "79", 250000)), 200);
    assertThat(s.path("status").asString()).isEqualTo("CREATED");
    assertThat(s.path("carrier").asString()).isEqualTo("SELF");
    assertThat(s.path("tracking_code").asString()).isEqualTo("SELF-ORD1");
    assertThat(s.path("fee").asLong()).isEqualTo(25000);
    assertThat(s.path("cod_amount").asLong()).isEqualTo(250000);
    assertThat(s.path("handed_over_at").isNull()).isTrue();
    assertThat(s.path("version").asLong()).isEqualTo(1);
    assertThat(s.has("to_address")).isFalse();
    assertThat(jdbc.queryForList("select to_status from shipment_status_history order by created_at, id", String.class))
        .containsExactly("PENDING", "CREATED");
    var event = jdbc.queryForMap("select event_type, topic, partition_key, aggregate_id, payload::text as payload from outbox_events");
    assertThat(event).containsEntry("event_type", "SHIPMENT_CREATED").containsEntry("topic", "shipping.events")
        .containsEntry("partition_key", "ORD1").containsEntry("aggregate_id", s.path("shipment_id").asString());
    var payload = mapper.readTree((String) event.get("payload"));
    assertThat(payload.path("status").asString()).isEqualTo("CREATED");
    assertThat(payload.path("shipment_version").asLong()).isEqualTo(1);
    assertThat(payload.toString()).doesNotContain("0900000000");
    var byOrder = data(send("GET", SHIPMENTS + "/by-order/" + order, null, "Authorization", serviceToken("order-service")), 200);
    assertThat(byOrder.path("shipment_id").asString()).isEqualTo(s.path("shipment_id").asString());
  }

  @Test void retryAndConflicts() {
    String order = UUID.randomUUID().toString(), quote = quote("79", false);
    var first = data(create("k1", body(order, "ORD2", quote, "79", 0)), 200);
    assertThat(data(create("k1", body(order, "ORD2", quote, "79", 0)), 200)).isEqualTo(first);
    // Another key, same order and body (order retry after losing its key) -> same shipment, nothing new.
    assertThat(data(create("k2", body(order, "ORD2", quote, "79", 0)), 200).path("shipment_id")).isEqualTo(first.path("shipment_id"));
    assertThat(problem(create("k3", body(order, "ORD2", quote("79", false), "79", 0)), 409)).isEqualTo("SHIPMENT_CONFLICT");
    assertThat(problem(create("k1", body(order, "ORD2", quote, "79", 1)), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(problem(create("k4", body(UUID.randomUUID().toString(), "ORD3", quote, "79", 0)), 409)).isEqualTo("QUOTE_ALREADY_USED");
    assertThat(jdbc.queryForObject("select count(*) from shipments", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(1);
  }

  @Test void quoteMustMatchTheOrder() {
    String quote = quote("79", true);
    assertThat(problem(create("k1", body(UUID.randomUUID().toString(), "ORD4", quote, "01", 100)), 409)).isEqualTo("QUOTE_MISMATCH");
    assertThat(problem(create("k2", body(UUID.randomUUID().toString(), "ORD4", quote, "79", 0)), 409)).isEqualTo("QUOTE_MISMATCH");
    assertThat(problem(create("k3", body(UUID.randomUUID().toString(), "ORD4", UUID.randomUUID().toString(), "79", 0)), 404)).isEqualTo("QUOTE_NOT_FOUND");
    assertThat(jdbc.queryForObject("select count(*) from shipments", Integer.class)).isZero();
  }

  @Test void parallelCreatesOfOneOrderMakeOneShipment() throws Exception {
    String order = UUID.randomUUID().toString(), quote = quote("79", false);
    var barrier = new CyclicBarrier(3);
    var calls = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < 3; i++) {
      String key = "p" + i;
      calls.add(CompletableFuture.supplyAsync(() -> {
        try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); }
        return create(key, body(order, "ORD5", quote, "79", 0));
      }));
    }
    var ids = new java.util.HashSet<String>();
    for (var c : calls) ids.add(data(c.get(), 200).path("shipment_id").asString());
    assertThat(ids).hasSize(1);
    assertThat(jdbc.queryForObject("select count(*) from shipments", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(1);
  }

  @Test void validationAndAuth() {
    String quote = quote("79", false);
    String order = UUID.randomUUID().toString();
    for (String bad : new String[] {"{}", body("x", "ORD6", quote, "79", 0), body(order, "", quote, "79", 0), body(order, "ORD6", quote, "79", -1),
        body(order, "ORD6", quote, "79", 0).replace("0900000000", "call me"), body(order, "ORD6", quote, "79", 0).replace("\"ward_code\":\"26734\",", "")}) {
      var r = create("kv", bad);
      assertThat(r.statusCode()).withFailMessage(bad + " -> " + r.body()).isEqualTo(400);
    }
    assertThat(problem(send("POST", SHIPMENTS, "{bad", "Idempotency-Key", "k"), 401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("POST", SHIPMENTS, "{bad", "Authorization", serviceToken("cart-service"), "Idempotency-Key", "k"), 403))
        .isEqualTo("CALLER_NOT_ALLOWED");
    assertThat(problem(send("POST", SHIPMENTS, body(order, "ORD6", quote, "79", 0), "Authorization", serviceToken("order-service")), 400))
        .isEqualTo("INVALID_KEY");
    assertThat(problem(send("GET", SHIPMENTS + "/by-order/" + order, null, "Authorization", serviceToken("order-service")), 404))
        .isEqualTo("SHIPMENT_NOT_FOUND");
  }

  @Test void opsListAndDetail() {
    for (int i = 0; i < 3; i++) data(create("o" + i, body(UUID.randomUUID().toString(), "ORDL" + i, quote("79", false), "79", 0)), 200);
    String read = opsToken("shipping.read");
    var page = data(send("GET", "/admin/api/v1/shipping/shipments?limit=2", null, "Authorization", read), 200);
    assertThat(page.path("items")).hasSize(2);
    var next = data(send("GET", "/admin/api/v1/shipping/shipments?limit=2&cursor=" + page.path("next_cursor").asString(), null, "Authorization", read), 200);
    assertThat(next.path("items")).hasSize(1);
    assertThat(next.path("next_cursor").isNull()).isTrue();
    assertThat(data(send("GET", "/admin/api/v1/shipping/shipments?status=DELIVERED", null, "Authorization", read), 200).path("items")).isEmpty();
    String id = page.path("items").get(0).path("shipment_id").asString();
    var detail = data(send("GET", "/admin/api/v1/shipping/shipments/" + id, null, "Authorization", read), 200);
    assertThat(detail.path("to_address").path("recipient_name").asString()).isEqualTo("Khách mẫu");
    assertThat(detail.path("history")).hasSize(2);
    assertThat(problem(send("GET", "/admin/api/v1/shipping/shipments?status=NOPE", null, "Authorization", read), 400)).isEqualTo("INVALID_PARAMETER");
    assertThat(problem(send("GET", "/admin/api/v1/shipping/shipments", null, "Authorization", opsToken("shipping.rules")), 403)).isEqualTo("PERMISSION_REQUIRED");
    assertThat(problem(send("GET", "/admin/api/v1/shipping/shipments/" + UUID.randomUUID(), null, "Authorization", read), 404)).isEqualTo("SHIPMENT_NOT_FOUND");
  }

  @Test void runtimeCannotDeleteShipments() {
    data(create("d", body(UUID.randomUUID().toString(), "ORDD", quote("79", false), "79", 0)), 200);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("delete from shipments"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update shipment_status_history set reason = 'x'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test void parallelCreatesOfOneOrderWithDifferentQuotes() throws Exception {
    String order = UUID.randomUUID().toString(), q1 = quote("79", false), q2 = quote("79", false);
    var barrier = new CyclicBarrier(2);
    var a = CompletableFuture.supplyAsync(() -> { try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); }
      return create("pa", body(order, "ORD7", q1, "79", 0)); });
    var b = CompletableFuture.supplyAsync(() -> { try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); }
      return create("pb", body(order, "ORD7", q2, "79", 0)); });
    var codes = java.util.List.of(a.get().statusCode(), b.get().statusCode());
    assertThat(codes).containsExactlyInAnyOrder(200, 409);
    assertThat(jdbc.queryForObject("select count(*) from shipments", Integer.class)).isEqualTo(1);
  }

  @Test void bodyHashIsInjective() {
    // Review 4: moving text between address fields is another body, not a retry.
    String order = UUID.randomUUID().toString(), quote = quote("79", false);
    String withDistrict = body(order, "ORD8", quote, "79", 0).replace("\"ward_code\"", "\"district_code\":\"5\",\"ward_code\"");
    String folded = body(order, "ORD8", quote, "79", 0).replace("1 Đường Mẫu", "1 Đường Mẫu, district_code=5");
    data(create("h1", withDistrict), 200);
    assertThat(problem(create("h2", folded), 409)).isEqualTo("SHIPMENT_CONFLICT");
  }

  @Test void existingOrderWinsOverUnknownQuote() {
    // Review 5: same order, other body -> 409 even when the new quote does not exist.
    String order = UUID.randomUUID().toString();
    data(create("e1", body(order, "ORD9", quote("79", false), "79", 0)), 200);
    assertThat(problem(create("e2", body(order, "ORD9", UUID.randomUUID().toString(), "79", 0)), 409)).isEqualTo("SHIPMENT_CONFLICT");
  }

  @Test void keyIsCheckedBeforeBody() {
    assertThat(problem(send("POST", SHIPMENTS, "{}", "Authorization", serviceToken("order-service")), 400)).isEqualTo("INVALID_KEY");
  }
}
