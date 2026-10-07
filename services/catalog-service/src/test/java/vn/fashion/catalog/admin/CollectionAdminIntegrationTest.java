package vn.fashion.catalog.admin;

import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionAdminIntegrationTest extends CatalogAdminTestSupport {
  private Map<String,Object> input(String slug) {
    return new HashMap<>(Map.of("name_vi", "Bộ sưu tập", "name_en", "Collection", "slug", slug, "items", List.of()));
  }
  private Map<String,Object> item(UUID product, Object sort) {
    var result = new HashMap<String,Object>(); result.put("product_id", product); result.put("sort_order", sort); return result;
  }
  private HttpResponse<String> post(String key, Map<String,Object> body) {
    return send("POST", "/admin/api/v1/catalog/collections", token("catalog.write"), key, mapper.writeValueAsString(body));
  }
  private HttpResponse<String> put(UUID id, Map<String,Object> body) {
    return send("PUT", "/admin/api/v1/catalog/collections/" + id, token("catalog.write"), null, mapper.writeValueAsString(body));
  }
  private UUID create(String slug) { return UUID.fromString(data(post(UUID.randomUUID().toString(), input(slug)), 201).path("id").asText()); }
  private Map<String,Object> update(String slug, long version) {
    var body = input(slug); body.put("status", "ACTIVE"); body.put("expected_version", version); return body;
  }
  private void invalid(HttpResponse<String> response, String field) {
    data(response, 400); assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    var fields = new ArrayList<String>(); json(response).path("errors").forEach(e -> fields.add(e.path("field").asText()));
    assertThat(fields).contains(field);
  }
  private int audits(String action) { return jdbc.queryForObject("select count(*) from audit_logs where action=?", Integer.class, action); }

  @Test void createIsDraftVersionZeroAndReplayDoesNotAudit() {
    var body = input("create"); String key = UUID.randomUUID().toString();
    var first = post(key, body); var result = audited(first, 201, "catalog.collection.create");
    assertThat(result.path("status").asText()).isEqualTo("DRAFT"); assertThat(result.path("version").asLong()).isZero();
    assertThat(result.path("cover_url").isNull()).isTrue();
    var replay = post(key, body); assertThat(data(replay, 201)).isEqualTo(result);
    assertThat(json(replay).path("metadata").path("request_id").asText()).isNotEqualTo(json(first).path("metadata").path("request_id").asText());
    assertThat(audits("catalog.collection.create")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isZero();
    invalid(post(null, input("missing-key")), "Idempotency-Key");
  }
  @Test void collectionMutationsRequireCatalogWrite() {
    for (String method : List.of("POST", "PUT")) {
      String path = "/admin/api/v1/catalog/collections" + (method.equals("PUT") ? "/" + UUID.randomUUID() : "");
      String body = mapper.writeValueAsString(method.equals("PUT") ? update("auth", 0) : input("auth"));
      data(send(method, path, null, "auth-key", body), 401);
      data(send(method, path, token("member"), "auth-key", body), 403);
    }
    assertThat(jdbc.queryForObject("select count(*) from collections", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from audit_logs", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from idempotency_requests", Integer.class)).isZero();
  }
  @Test void collectionNamesSlugAndDuplicateSlugAreValidated() {
    var body = input(" " + "a".repeat(160) + " "); body.put("name_vi", " " + "x".repeat(255) + " "); body.put("name_en", " Collection ");
    var result = data(post("normalize", body), 201);
    assertThat(result.path("name_vi").asText()).isEqualTo("x".repeat(255)); assertThat(result.path("name_en").asText()).isEqualTo("Collection");
    assertThat(result.path("slug").asText()).isEqualTo("a".repeat(160));
    var duplicate = post("duplicate", body); data(duplicate, 409); assertThat(json(duplicate).path("code").asText()).isEqualTo("CONFLICT");
    for (String field : List.of("name_vi", "name_en", "slug", "items")) {
      var missing = input("missing-" + field.replace('_','-')); missing.remove(field); invalid(post(UUID.randomUUID().toString(), missing), field);
    }
    for (String value : List.of("", " ", "x".repeat(256))) {
      var bad = input("bad-name"); bad.put("name_vi", value); invalid(post(UUID.randomUUID().toString(), bad), "name_vi");
    }
    for (String value : List.of("A", "bad_slug", "a".repeat(161))) {
      var bad = input(value); invalid(post(UUID.randomUUID().toString(), bad), "slug");
    }
  }
  @Test void equivalentDateOffsetsReplayTheSameKey() {
    var body = input("offset-replay"); body.put("start_at", "2026-10-08T09:00:00+07:00");
    var first = data(post("offset-key", body), 201); body.put("start_at", "2026-10-08T02:00:00Z");
    assertThat(data(post("offset-key", body), 201)).isEqualTo(first); assertThat(audits("catalog.collection.create")).isEqualTo(1);
  }
  @Test void changedBodyAndReorderedItemsReuseKeyIs409() {
    UUID a = createProduct("a"), b = createProduct("b");
    var body = input("order"); body.put("items", List.of(item(a, 1), item(b, 0)));
    var first = data(post("same-key", body), 201); assertThat(first.path("items").get(0).path("product_id").asText()).isEqualTo(b.toString());
    body.put("items", List.of(item(b, 0), item(a, 1))); var reordered = post("same-key", body);
    data(reordered, 409); assertThat(json(reordered).path("message").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    body.put("name_en", "Changed"); data(post("same-key", body), 409); assertThat(audits("catalog.collection.create")).isEqualTo(1);
  }
  @Test void putReplacesItemsWithoutKeyAndRequiresVersion() {
    UUID a = createProduct("a"), b = createProduct("b"), c = createProduct("c");
    var body = input("replace"); body.put("items", List.of(item(a, 0), item(b, 1)));
    UUID id = UUID.fromString(data(post("replace", body), 201).path("id").asText());
    var change = update("replace", 0); change.put("items", List.of(item(b, 9), item(c, 2)));
    var response = put(id, change); var result = audited(response, 200, "catalog.collection.update");
    assertThat(result.path("version").asLong()).isEqualTo(1);
    assertThat(result.path("items").size()).isEqualTo(2); assertThat(result.path("items").get(0).path("product_id").asText()).isEqualTo(c.toString());
    assertThat(jdbc.queryForObject("select count(*) from collection_items where collection_id=? and product_id=?", Integer.class, id, a)).isZero();
    assertThat(data(call("GET", "/collections/" + id, null), 200)).isEqualTo(result);
    data(put(id, change), 409); change.remove("expected_version"); invalid(put(id, change), "expected_version");
    change.put("expected_version", 0); data(put(UUID.randomUUID(), change), 404);
  }
  @Test void unknownFieldsIncludingNullAreRejected() {
    for (String key : List.of("cover_url", "lookbook", "lookbook_images", "x_future", "status", "expected_version", "unknown_fields", "unknownFields")) {
      var body = input("unknown"); body.put(key, null); invalid(post(UUID.randomUUID().toString(), body), key);
    }
    UUID id = create("put-unknown");
    for (String key : List.of("cover_url", "lookbook", "x_future")) {
      var body = update("put-unknown", 0); body.put(key, null); invalid(put(id, body), key);
    }
    UUID p = createProduct("unknown-item"); var item = item(p, 0); item.put("item_extra", null);
    var body = input("unknown-item"); body.put("items", List.of(item)); invalid(post("unknown-item", body), "item_extra");
    assertThat(audits("catalog.collection.create")).isEqualTo(1); assertThat(audits("catalog.collection.update")).isZero();
    assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where operation='catalog.collection.create'", Integer.class)).isEqualTo(1);
  }
  @Test void missingProductKeepsOriginalItemIndex() {
    UUID p = createProduct("exists"); var body = input("missing-product"); body.put("items", List.of(item(p, 9), item(UUID.randomUUID(), 0)));
    invalid(post("missing-product", body), "items[1].product_id");
    assertThat(jdbc.queryForObject("select count(*) from collections", Integer.class)).isZero();
  }
  @Test void duplicateAndInvalidItemValuesAre400() {
    UUID p = createProduct("values"); var body = input("values"); body.put("items", List.of(item(p, 1), item(p, 0)));
    invalid(post("duplicate", body), "items[1].product_id");
    for (Object bad : new Object[]{null, -1, 2147483648L, 1.5, "1"}) {
      body.put("items", List.of(item(p, bad))); invalid(post(UUID.randomUUID().toString(), body), "items[0].sort_order");
    }
    body.put("items", List.of(Map.of("product_id", p))); invalid(post("missing-sort", body), "items[0].sort_order");
    for (Object bad : new Object[]{null, "bad-uuid", "1-1-1-1-1"}) {
      var row = item(p, 0); row.put("product_id", bad); body.put("items", List.of(row)); invalid(post(UUID.randomUUID().toString(), body), "items[0].product_id");
    }
    body.put("items", Collections.nCopies(1001, item(p, 0))); invalid(post("too-many", body), "items");
    body.put("items", List.of(item(p, Integer.MAX_VALUE), item(createProduct("second"), Integer.MAX_VALUE)));
    UUID id = UUID.fromString(data(post("maximum", body), 201).path("id").asText());
    var change = update("values", 0); change.put("expected_version", null); invalid(put(id, change), "expected_version");
    for (Object bad : new Object[]{-1, 1.5, "0"}) { change.put("expected_version", bad); invalid(put(id, change), "expected_version"); }
  }
  @Test void timeOffsetsNormalizeAndEquivalentBoundsAreRejected() {
    var body = input("times"); body.put("start_at", "2026-10-08T09:00:00+07:00"); body.put("end_at", "2026-10-08T03:00:00Z");
    var result = data(post("times", body), 201); assertThat(result.path("start_at").asText()).isEqualTo("2026-10-08T02:00:00Z");
    body.put("end_at", "2026-10-08T02:00:00Z"); invalid(post("equal", body), "end_at");
    body.put("end_at", "2026-10-08T01:00:00Z"); invalid(post("before", body), "end_at");
    body.put("start_at", "2026-10-08T09:00:00"); invalid(post("no-offset", body), "start_at");
    body.put("start_at", null); body.put("end_at", null); body.put("slug", "unbounded");
    UUID id = UUID.fromString(data(post("unbounded", body), 201).path("id").asText());
    var changed = data(put(id, update("unbounded", 0)), 200); assertThat(changed.path("start_at").isNull()).isTrue(); assertThat(changed.path("end_at").isNull()).isTrue();
  }
  @Test void activateEmptyUnpublishedOrFutureCollections() {
    for (int scenario = 0; scenario < 3; scenario++) {
      UUID id = create("launch-" + scenario); var body = update("launch-" + scenario, 0);
      if (scenario > 0) body.put("items", List.of(item(createProduct("draft-" + scenario), 0)));
      if (scenario == 2) body.put("start_at", "2099-01-01T00:00:00Z");
      assertThat(data(put(id, body), 200).path("status").asText()).isEqualTo("ACTIVE");
    }
  }
  @Test void twoPutsSameVersionHaveOneWinner() throws Exception {
    UUID id = create("race"), a = createProduct("race-a"), b = createProduct("race-b");
    var first = update("race", 0); first.put("items", List.of(item(a, 1)));
    var second = update("race", 0); second.put("items", List.of(item(b, 2)));
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var x = pool.submit(() -> { gate.await(); return put(id, first); }); var y = pool.submit(() -> { gate.await(); return put(id, second); }); gate.countDown();
      var r1 = x.get(20, TimeUnit.SECONDS); var r2 = y.get(20, TimeUnit.SECONDS);
      assertThat(List.of(r1.statusCode(), r2.statusCode())).containsExactlyInAnyOrder(200, 409);
      var winner = r1.statusCode() == 200 ? r1 : r2; var loser = r1.statusCode() == 409 ? r1 : r2;
      assertThat(json(loser).path("code").asText()).isEqualTo("VERSION_CONFLICT");
      assertThat(data(call("GET", "/collections/" + id, null), 200)).isEqualTo(data(winner, 200));
    }
    assertThat(audits("catalog.collection.update")).isEqualTo(1); assertThat(jdbc.queryForObject("select version from collections where id=?", Long.class, id)).isEqualTo(1);
  }
  @Test void auditFailureRollsBackCollectionItemsVersionAndCreateKey() throws Exception {
    UUID a = createProduct("old"), b = createProduct("new"); var body = input("rollback"); body.put("items", List.of(item(a, 0)));
    var before = data(post("original", body), 201); UUID id = UUID.fromString(before.path("id").asText());
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("alter table audit_logs add constraint test_collection_audit check (action not like 'catalog.collection.%') not valid");
      try {
        var change = update("rollback", 0); change.put("items", List.of(item(b, 2)));
        var failed = put(id, change); data(failed, 500); var error = json(failed);
        assertThat(error.size()).isEqualTo(3); assertThat(error.path("code").asText()).isEqualTo("INTERNAL");
        assertThat(failed.body()).doesNotContain("audit_logs", "test_collection_audit", "insert into", "rollback");
        assertThat(data(call("GET", "/collections/" + id, null), 200)).isEqualTo(before);
        data(post("failed-key", input("failed-create")), 500);
        assertThat(jdbc.queryForObject("select count(*) from collections", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where key='failed-key'", Integer.class)).isZero();
        assertThat(audits("catalog.collection.create")).isEqualTo(1); assertThat(audits("catalog.collection.update")).isZero();
      } finally { s.execute("alter table audit_logs drop constraint test_collection_audit"); }
    }
  }
}
