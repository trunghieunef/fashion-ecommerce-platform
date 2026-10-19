package vn.fashion.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SHP-02 spec sections 2-3: SELF evidence events, COD collected/remitted, version and idempotency (T15). */
class SelfEventIntegrationTest extends ShippingTestSupport {
  static final String ADMIN = "/admin/api/v1/shipping/shipments/";
  final String ops = opsToken("shipping.self_event", "shipping.read");

  String shipment(long cod) {
    String order = UUID.randomUUID().toString(), no = "S" + order.substring(0, 8);
    return data(create(UUID.randomUUID().toString(), body(order, no, quote("79", cod > 0), "79", cod)), 200).path("shipment_id").asString();
  }
  HttpResponse<String> event(String id, String key, String body) {
    return send("POST", ADMIN + id + "/self-events", body, "Authorization", ops, "Idempotency-Key", key);
  }
  JsonNode ok(String id, String body) { return data(event(id, UUID.randomUUID().toString(), body), 200); }
  static String ev(String type, long version, String extra) {
    return "{\"event_type\":\"" + type + "\",\"expected_version\":" + version + extra + "}";
  }
  static final String ASSIGN = ",\"assigned_to\":\"11111111-1111-4111-8111-111111111111\"";
  static String evidence(String ref) { return ",\"evidence_ref\":\"" + ref + "\""; }
  java.util.List<String> types() {
    return jdbc.queryForList("select event_type from outbox_events order by created_at, aggregate_sequence", String.class);
  }

  @Test void fullSelfPathWithCodAndEvents() {
    String id = shipment(250000);
    var picking = ok(id, ev("ASSIGN", 1, ASSIGN));
    assertThat(picking.path("status").asString()).isEqualTo("PICKING");
    assertThat(picking.path("assigned_to").asString()).isEqualTo("11111111-1111-4111-8111-111111111111");
    assertThat(picking.path("handed_over_at").isNull()).isTrue(); // T15: assigned is not handed over
    var delivering = ok(id, ev("HANDED_OVER", 2, evidence("BG-1")));
    assertThat(delivering.path("status").asString()).isEqualTo("DELIVERING");
    assertThat(delivering.path("handed_over_at").isNull()).isFalse();
    ok(id, ev("DELIVERED", 3, evidence("GH-1")));
    var collected = ok(id, ev("COD_COLLECTED", 4, ",\"amount\":250000" + evidence("THU-1")));
    assertThat(collected.path("status").asString()).isEqualTo("DELIVERED");
    assertThat(collected.path("cod_collected_amount").asLong()).isEqualTo(250000);
    var remitted = ok(id, ev("COD_REMITTED", 5, ",\"amount\":250000" + evidence("NOP-1")));
    assertThat(remitted.path("cod_remitted_amount").asLong()).isEqualTo(250000);
    assertThat(remitted.path("version").asLong()).isEqualTo(6);

    assertThat(types()).containsExactly("SHIPMENT_CREATED", "SHIPMENT_STATUS_UPDATED", "SHIPMENT_STATUS_UPDATED",
        "SHIPMENT_STATUS_UPDATED", "COD_COLLECTED", "COD_REMITTED");
    var payloads = jdbc.queryForList("select payload::text from outbox_events order by created_at, aggregate_sequence", String.class)
        .stream().map(mapper::readTree).toList();
    assertThat(payloads.get(1).path("status").asString()).isEqualTo("PICKING");
    assertThat(payloads.get(1).path("handed_over_at").isNull()).isTrue();
    assertThat(payloads.get(2).path("handed_over_at").isNull()).isFalse();
    var cod = payloads.get(4);
    assertThat(cod.path("gross_amount").asLong()).isEqualTo(250000);
    assertThat(cod.path("net_amount").asLong()).isEqualTo(250000);
    assertThat(cod.path("fee_amount").asLong()).isZero();
    assertThat(cod.path("currency").asString()).isEqualTo("VND");
    assertThat(cod.path("evidence_ref").asString()).isEqualTo("THU-1");
    assertThat(cod.path("payment_id").isNull()).isTrue();
    assertThat(jdbc.queryForList("select aggregate_sequence from outbox_events order by aggregate_sequence", Long.class))
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);

    // History keeps actor and evidence, never the address or phone.
    var history = remitted.path("history");
    assertThat(history).hasSize(7);
    assertThat(history.get(3).path("evidence").path("evidence_ref").asString()).isEqualTo("BG-1");
    assertThat(history.get(3).path("evidence").path("actor_id").asString()).isNotEmpty();
    assertThat(jdbc.queryForList("select sanitized_payload::text from shipment_status_history", String.class))
        .noneMatch(p -> p.contains("0900000000") || p.contains("Đường Mẫu"));
    // Order reads the hand-over through the internal by-order view.
    String order = remitted.path("order_id").asString();
    var byOrder = data(send("GET", "/internal/api/v1/shipping/shipments/by-order/" + order, null, "Authorization", serviceToken("order-service")), 200);
    assertThat(byOrder.path("handed_over_at").isNull()).isFalse();
  }

  @Test void failedThenRedeliveredOrReturned() {
    String id = shipment(0);
    ok(id, ev("ASSIGN", 1, ASSIGN));
    String handedOver = ok(id, ev("HANDED_OVER", 2, evidence("BG-2") + ",\"occurred_at\":\"" + Instant.now().plusSeconds(1) + "\""))
        .path("handed_over_at").asString();
    assertThat(ok(id, ev("DELIVERY_FAILED", 3, ",\"reason\":\"khách vắng nhà\"")).path("status").asString()).isEqualTo("FAILED");
    assertThat(ok(id, ev("REDELIVER", 4, "")).path("status").asString()).isEqualTo("DELIVERING"); // T15: valid re-delivery branch
    ok(id, ev("DELIVERY_FAILED", 5, ",\"reason\":\"không liên lạc được\""));
    ok(id, ev("RETURN_STARTED", 6, ",\"reason\":\"hoàn về shop\""));
    var returned = ok(id, ev("RETURNED", 7, evidence("HV-1")));
    assertThat(returned.path("status").asString()).isEqualTo("RETURNED");
    assertThat(returned.path("handed_over_at").asString()).isEqualTo(handedOver); // re-delivery keeps the first hand-over
    for (String type : new String[] {"REDELIVER", "DELIVERED", "RETURN_STARTED", "RETURNED"})
      assertThat(problem(event(id, UUID.randomUUID().toString(), ev(type, 8, type.equals("RETURN_STARTED") ? ",\"reason\":\"x\""
          : type.equals("REDELIVER") ? "" : evidence("x"))), 409)).isEqualTo("INVALID_TRANSITION");
  }

  @Test void wrongEdgesAreRefused() {
    String id = shipment(100000);
    // T15: no hand-over, delivery or COD straight from CREATED.
    var r = event(id, "e1", ev("HANDED_OVER", 1, evidence("x")));
    assertThat(problem(r, 409)).isEqualTo("INVALID_TRANSITION");
    assertThat(json(r).path("errors").get(0).path("message").asString()).isEqualTo("current=CREATED");
    assertThat(problem(event(id, "e2", ev("DELIVERED", 1, evidence("x"))), 409)).isEqualTo("INVALID_TRANSITION");
    assertThat(problem(event(id, "e3", ev("COD_COLLECTED", 1, ",\"amount\":100000" + evidence("x"))), 409)).isEqualTo("INVALID_TRANSITION");
    ok(id, ev("ASSIGN", 1, ASSIGN));
    // Re-assigning while PICKING changes the shipper only: no status event.
    var again = ok(id, ev("ASSIGN", 2, ",\"assigned_to\":\"22222222-2222-4222-8222-222222222222\""));
    assertThat(again.path("status").asString()).isEqualTo("PICKING");
    assertThat(again.path("assigned_to").asString()).isEqualTo("22222222-2222-4222-8222-222222222222");
    assertThat(types()).containsExactly("SHIPMENT_CREATED", "SHIPMENT_STATUS_UPDATED");
    assertThat(jdbc.queryForObject("select count(*) from shipment_status_history where shipment_id = cast(? as uuid)", Integer.class, id)).isEqualTo(4);
  }

  @Test void versionAndIdempotency() {
    String id = shipment(0);
    var stale = event(id, "k0", ev("ASSIGN", 0, ASSIGN));
    assertThat(problem(stale, 409)).isEqualTo("VERSION_CONFLICT");
    assertThat(json(stale).path("errors").get(0).path("message").asString()).isEqualTo("current=1");
    var first = data(event(id, "k1", ev("ASSIGN", 1, ASSIGN)), 200);
    assertThat(data(event(id, "k1", ev("ASSIGN", 1, ASSIGN)), 200)).isEqualTo(first);
    assertThat(problem(event(id, "k1", ev("ASSIGN", 1, ",\"assigned_to\":\"22222222-2222-4222-8222-222222222222\"")), 409))
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select version from shipments where id = cast(? as uuid)", Long.class, id)).isEqualTo(2);
  }

  @Test void parallelEventsOnOneVersionApplyOnce() throws Exception {
    String id = shipment(0);
    ok(id, ev("ASSIGN", 1, ASSIGN));
    var barrier = new CyclicBarrier(2);
    var calls = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (String key : new String[] {"p1", "p2"})
      calls.add(CompletableFuture.supplyAsync(() -> {
        try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); }
        return event(id, key, ev("HANDED_OVER", 2, evidence(key)));
      }));
    var codes = calls.stream().map(CompletableFuture::join).map(HttpResponse::statusCode).sorted().toList();
    assertThat(codes).containsExactly(200, 409);
    assertThat(jdbc.queryForObject("select count(*) from shipment_status_history where to_status = 'DELIVERING'", Integer.class)).isEqualTo(1);
  }

  @Test void codRules() {
    String plain = shipment(0);
    deliver(plain);
    assertThat(problem(event(plain, "c0", ev("COD_COLLECTED", 4, ",\"amount\":1" + evidence("x"))), 409)).isEqualTo("COD_NOT_COLLECTABLE");
    String cod = shipment(300000);
    deliver(cod);
    assertThat(problem(event(cod, "c1", ev("COD_REMITTED", 4, ",\"amount\":300000" + evidence("x"))), 409)).isEqualTo("COD_NOT_REMITTABLE");
    var wrong = event(cod, "c2", ev("COD_COLLECTED", 4, ",\"amount\":299000" + evidence("x")));
    assertThat(problem(wrong, 400)).isEqualTo("INVALID_FIELDS");
    assertThat(json(wrong).path("errors").get(0).path("message").asString()).isEqualTo("must equal cod_amount=300000");
    ok(cod, ev("COD_COLLECTED", 4, ",\"amount\":300000" + evidence("THU")));
    assertThat(problem(event(cod, "c3", ev("COD_COLLECTED", 5, ",\"amount\":300000" + evidence("THU2"))), 409)).isEqualTo("COD_NOT_COLLECTABLE");
    assertThat(problem(event(cod, "c4", ev("COD_REMITTED", 5, ",\"amount\":1" + evidence("x"))), 400)).isEqualTo("INVALID_FIELDS");
    ok(cod, ev("COD_REMITTED", 5, ",\"amount\":300000" + evidence("NOP")));
    assertThat(problem(event(cod, "c5", ev("COD_REMITTED", 6, ",\"amount\":300000" + evidence("NOP2"))), 409)).isEqualTo("COD_NOT_REMITTABLE");
  }
  void deliver(String id) {
    ok(id, ev("ASSIGN", 1, ASSIGN));
    ok(id, ev("HANDED_OVER", 2, evidence("BG")));
    ok(id, ev("DELIVERED", 3, evidence("GH")));
  }

  @Test void validationAndAuth() {
    String id = shipment(0);
    String path = ADMIN + id + "/self-events";
    assertThat(problem(send("POST", path, "{bad", "Idempotency-Key", "k"), 401)).isEqualTo("INVALID_ACCESS_TOKEN");
    assertThat(problem(send("POST", path, "{bad", "Authorization", opsToken("shipping.read"), "Idempotency-Key", "k"), 403))
        .isEqualTo("PERMISSION_REQUIRED");
    assertThat(problem(send("POST", path, "{bad", "Authorization", ops), 400)).isEqualTo("INVALID_KEY");
    assertThat(problem(event(id, "v1", "{bad"), 400)).isEqualTo("MALFORMED_JSON");
    for (String bad : new String[] {
        ev("SHIPPED", 1, ""), ev("ASSIGN", 1, ""), ev("ASSIGN", 1, ASSIGN + ",\"extra\":1"), ev("DELIVERED", 1, ""),
        ev("DELIVERED", 1, evidence("x") + ",\"amount\":1"), ev("DELIVERY_FAILED", 1, ""), ev("COD_COLLECTED", 1, evidence("x")),
        ev("COD_COLLECTED", 1, ",\"amount\":0" + evidence("x")), ev("COD_COLLECTED", 1, ",\"amount\":\"1\"" + evidence("x")),
        ev("ASSIGN", 1, ",\"assigned_to\":\"NOT-A-UUID\""), ev("ASSIGN", 1, ASSIGN + ",\"occurred_at\":\"yesterday\""),
        "{\"event_type\":\"ASSIGN\"" + ASSIGN + "}"})
      assertThat(problem(event(id, UUID.randomUUID().toString(), bad), 400)).as(bad).isEqualTo("INVALID_FIELDS");
    assertThat(problem(event(id, "t1", ev("ASSIGN", 1, ASSIGN + ",\"occurred_at\":\"" + Instant.now().plusSeconds(3600) + "\"")), 400))
        .isEqualTo("INVALID_FIELDS");
    assertThat(problem(event(id, "t2", ev("ASSIGN", 1, ASSIGN + ",\"occurred_at\":\"2020-01-01T00:00:00Z\"")), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(event(UUID.randomUUID().toString(), "n1", ev("ASSIGN", 1, ASSIGN)), 404)).isEqualTo("SHIPMENT_NOT_FOUND");
    assertThat(jdbc.queryForObject("select version from shipments where id = cast(? as uuid)", Long.class, id)).isEqualTo(1);
  }

  @Test void databaseRefusesStatusAfterHandOverWithoutIt() {
    String id = shipment(0);
    assertThatThrownBy(() -> jdbc.update("update shipments set status = 'DELIVERING' where id = cast(? as uuid)", id))
        .hasMessageContaining("shipments_handed_over_check");
  }
}
