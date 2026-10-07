package vn.fashion.catalog.admin;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class SizeGuideAdminIntegrationTest extends CatalogAdminTestSupport {
  private Map<String,Object> input(long version) {
    return new HashMap<>(Map.of("expected_version", version, "table_json", Map.of("columns", List.of("Size"), "rows", List.of(List.of("M")))));
  }
  private String path(UUID category, String locale) { return "/size-guides/" + category + "/" + locale; }
  private HttpResponse<String> put(UUID category, String locale, String key, Map<String,Object> body) {
    return send("PUT", "/admin/api/v1/catalog" + path(category, locale), token("catalog.write"), key, mapper.writeValueAsString(body));
  }
  private HttpResponse<String> put(String key, Map<String,Object> body) { return put(SEED, "vi", key, body); }
  private void invalid(HttpResponse<String> response, String field) {
    data(response, 400); assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    var fields = new ArrayList<String>(); json(response).path("errors").forEach(e -> fields.add(e.path("field").asText())); assertThat(fields).contains(field);
  }
  private int audits() { return jdbc.queryForObject("select count(*) from audit_logs where action like 'catalog.size-guide.%'", Integer.class); }
  private List<HttpResponse<String>> race(String key1, Map<String,Object> a, String key2, Map<String,Object> b) throws Exception {
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var x = pool.submit(() -> { gate.await(); return put(key1, a); }); var y = pool.submit(() -> { gate.await(); return put(key2, b); }); gate.countDown();
      return List.of(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS));
    }
  }
  @Test void guideCreateThenGetThenUpdateUsesVersionsOneTwo() {
    var first = audited(put("create", input(0)), 201, "catalog.size-guide.create"); assertThat(first.path("version").asLong()).isEqualTo(1);
    var read = data(call("GET", path(SEED, "vi"), null), 200); assertThat(read).isEqualTo(first);
    var body = input(read.path("version").asLong()); body.put("guideline_html", "<p>Updated</p>");
    var next = audited(put("update", body), 200, "catalog.size-guide.update"); assertThat(next.path("version").asLong()).isEqualTo(2);
    assertThat(data(call("GET", path(SEED, "vi"), null), 200)).isEqualTo(next);
    assertThat(audits()).isEqualTo(2); assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isZero();
    data(put("create-again", input(0)), 409);
  }
  @Test void guidePutRequiresCatalogWrite() {
    String path = "/admin/api/v1/catalog" + path(SEED, "vi"); String body = mapper.writeValueAsString(input(0));
    data(send("PUT", path, null, "auth-key", body), 401); data(send("PUT", path, token("member"), "auth-key", body), 403);
    assertThat(jdbc.queryForObject("select count(*) from size_guides", Integer.class)).isZero(); assertThat(audits()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from idempotency_requests", Integer.class)).isZero();
  }
  @Test void keyVersionLocaleAndCategoryValidationOrder() {
    UUID missing = UUID.randomUUID();
    for (String key : new String[]{null, " ", "x".repeat(129)}) invalid(put(missing, "vi", key, input(0)), "Idempotency-Key");
    for (Object value : new Object[]{null, -1, 1.5, "0"}) {
      var body = input(0); body.put("expected_version", value); invalid(put(missing, "vi", "invalid-version", body), "expected_version");
    }
    var noVersion = input(0); noVersion.remove("expected_version"); invalid(put(missing, "vi", "no-version", noVersion), "expected_version");
    var nullTable = input(0); nullTable.put("table_json", null); invalid(put(missing, "vi", "null-table", nullTable), "table_json");
    nullTable.remove("table_json"); invalid(put(missing, "vi", "missing-table", nullTable), "table_json");
    for (String locale : List.of("VI", "fr")) invalid(put(missing, locale, "invalid-locale", input(0)), "locale");
    var absent = put(missing, "vi", "valid", input(0)); data(absent, 404); assertThat(json(absent).path("message").asText()).isEqualTo("CATEGORY_NOT_FOUND");
    var positive = put("absent-positive", input(1)); data(positive, 409); assertThat(json(positive).path("code").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(jdbc.queryForObject("select count(*) from idempotency_requests", Integer.class)).isZero();
  }
  @Test void missingCategoryIs404BeforeCompletedReplay() throws Exception {
    UUID category = createCategory("removed-category"); var body = input(0); data(put(category, "vi", "completed", body), 201);
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("delete from size_guides where category_id='" + category + "'"); s.execute("delete from categories where id='" + category + "'");
    }
    var retry = put(category, "vi", "completed", body); data(retry, 404); assertThat(json(retry).path("message").asText()).isEqualTo("CATEGORY_NOT_FOUND");
    assertThat(audits()).isEqualTo(1);
  }
  @Test void replayBeforeVersionAndChangedBodyConflict() {
    var create = input(0); var firstResponse = put("replay", create); var first = data(firstResponse, 201);
    data(put("later-update", input(1)), 200); var replay = put("replay", create);
    assertThat(data(replay, 201)).isEqualTo(first); assertThat(json(replay).path("metadata").path("request_id").asText()).isNotEqualTo(json(firstResponse).path("metadata").path("request_id").asText());
    UUID other = createCategory("other");
    for (var response : List.of(put("replay", input(1)), put(SEED, "en", "replay", create), put(other, "vi", "replay", create))) {
      data(response, 409); assertThat(json(response).path("message").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }
    var changed = input(0); changed.put("guideline_html", "changed"); var conflict = put("replay", changed); data(conflict, 409);
    assertThat(json(conflict).path("message").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED"); assertThat(audits()).isEqualTo(2);
  }
  @Test void independentConcurrentCreatesYieldOne201AndVersionConflict() throws Exception {
    var responses = race("create-a", input(0), "create-b", input(0));
    assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
    var loser = responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(); assertThat(json(loser).path("code").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(jdbc.queryForObject("select count(*) from size_guides", Integer.class)).isEqualTo(1); assertThat(audits()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where operation='catalog.size-guide.put' and status='COMPLETED'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where status='PROCESSING'", Integer.class)).isZero();
  }
  @Test void sameKeyConcurrentCreateReplaysSingleEffect() throws Exception {
    var responses = race("same-key", input(0), "same-key", input(0));
    assertThat(data(responses.get(0), 201)).isEqualTo(data(responses.get(1), 201)); assertThat(audits()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from size_guides", Integer.class)).isEqualTo(1);
  }
  @Test void concurrentUpdatesHaveOneVersionWinner() throws Exception {
    data(put("original", input(0)), 201); var a = input(1); a.put("guideline_html", "First"); var b = input(1); b.put("guideline_html", "Second");
    var responses = race("update-a", a, "update-b", b);
    assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
    var winner = responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
    var loser = responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(); assertThat(json(loser).path("code").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(data(call("GET", path(SEED, "vi"), null), 200)).isEqualTo(data(winner, 200)); assertThat(audits()).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where status='PROCESSING'", Integer.class)).isZero();
  }
  @Test void optionalGuidelineAndSanitizedLength() {
    var body = input(0); var first = data(put("omitted", body), 201); assertThat(first.path("guideline_html").asText()).isEmpty();
    body = input(1); body.put("guideline_html", null); assertThat(data(put("null", body), 200).path("guideline_html").asText()).isEmpty();
    body = input(2); body.put("guideline_html", ""); assertThat(data(put("empty", body), 200).path("guideline_html").asText()).isEmpty();
    body = input(3); body.put("guideline_html", "<p onclick='bad'>Safe<script>bad</script><img src=x onerror=bad><a href='javascript:bad'>Link</a><a href='https://example.test'>Web</a></p>");
    var html = data(put("sanitize", body), 200).path("guideline_html").asText(); assertThat(html).contains("Safe", "https://example.test", "nofollow", "noopener").doesNotContain("script", "onclick", "img", "javascript:");
    body = input(4); body.put("guideline_html", "<script>" + "x".repeat(21000) + "</script>"); assertThat(data(put("raw-large", body), 200).path("guideline_html").asText()).isEmpty();
    body = input(5); body.put("guideline_html", "x".repeat(20000)); assertThat(data(put("html-limit", body), 200).path("guideline_html").asText()).hasSize(20000);
    body = input(6); body.put("guideline_html", "x".repeat(20001)); invalid(put("too-long", body), "guideline_html");
  }
  @Test void tableTypesShapeRowsAndBoundaries() {
    for (String value : List.of("[]", "{}", "{\"columns\":[],\"rows\":[[\"M\"]]}", "{\"columns\":[\"Size\"],\"rows\":[]}",
        "{\"columns\":[null],\"rows\":[[\"M\"]]}", "{\"columns\":[1],\"rows\":[[\"M\"]]}", "{\"columns\":[\" \"],\"rows\":[[\"M\"]]}",
        "{\"columns\":[\"Size\"],\"rows\":[null]}", "{\"columns\":[\"Size\"],\"rows\":[[null]]}", "{\"columns\":[\"Size\"],\"rows\":[[1]]}",
        "{\"columns\":[\"Size\"],\"rows\":[[\"M\"]],\"extra\":null}")) {
      var body = input(0); body.put("table_json", mapper.readTree(value)); data(put(UUID.randomUUID().toString(), body), 400);
    }
    var headers = new ArrayList<String>(); for (int i = 0; i < 21; i++) headers.add("C" + i);
    var body = input(0); body.put("table_json", Map.of("columns", headers, "rows", List.of(java.util.Collections.nCopies(21, "")))); data(put("21-columns", body), 400);
    body.put("table_json", Map.of("columns", List.of("Size"), "rows", java.util.Collections.nCopies(101, List.of("M")))); data(put("101-rows", body), 400);
    body.put("table_json", Map.of("columns", List.of("x".repeat(101)), "rows", List.of(List.of("M")))); data(put("101-header", body), 400);
    body.put("table_json", Map.of("columns", List.of("Size"), "rows", List.of(List.of("x".repeat(101))))); data(put("101-cell", body), 400);
    headers.removeLast(); body.put("table_json", Map.of("columns", headers, "rows", java.util.Collections.nCopies(100, java.util.Collections.nCopies(20, "")))); data(put("max-shape", body), 201);
    var next = input(1); next.put("table_json", Map.of("columns", List.of(" " + "x".repeat(100) + " "), "rows", List.of(List.of(" " + "y".repeat(100) + " "), List.of("   "), List.of("<b>M</b>"))));
    var table = data(put("strip-text", next), 200).path("table_json"); assertThat(table.path("columns").get(0).asText()).hasSize(100);
    assertThat(table.path("rows").get(0).get(0).asText()).hasSize(100); assertThat(table.path("rows").get(1).get(0).asText()).isEmpty(); assertThat(table.path("rows").get(2).get(0).asText()).isEqualTo("<b>M</b>");
  }
  @Test void rowWidthMustMatchColumns() {
    var body = input(0); body.put("table_json", Map.of("columns", List.of("Size"), "rows", List.of(List.of("M", "L"))));
    invalid(put("width", body), "table_json.rows[0]"); assertThat(audits()).isZero();
  }
  @Test void invalidHeadersReportOnlyTheirOwnValidationErrors() {
    var body = input(0);
    body.put("table_json", mapper.readTree("{\"columns\":[1,null,\"\",\" \"],\"rows\":[[\"\",\"\",\"\",\"\"]]}"));
    var response = put("invalid-headers", body); invalid(response, "table_json.columns[0]");
    var fields = new ArrayList<String>(); json(response).path("errors").forEach(e -> fields.add(e.path("field").asText()));
    assertThat(fields).containsExactly("table_json.columns[0]", "table_json.columns[1]", "table_json.columns[2]", "table_json.columns[3]");
    assertThat(audits()).isZero(); assertThat(jdbc.queryForObject("select count(*) from idempotency_requests", Integer.class)).isZero();
  }
  @Test void headersAreUniqueIgnoringCaseWithRootLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR")); var body = input(0);
      body.put("table_json", Map.of("columns", List.of(" I ", "i"), "rows", List.of(List.of("1", "2")))); data(put("root-case", body), 400);
      body.put("table_json", Map.of("columns", List.of(" Size ", "size"), "rows", List.of(List.of("M", "L")))); data(put("duplicate", body), 400);
      body.put("table_json", Map.of("columns", List.of(" Size ", "Chest"), "rows", List.of(List.of(" M ", "96"))));
      var table = data(put("display-case", body), 201).path("table_json"); assertThat(table.path("columns").get(0).asText()).isEqualTo("Size"); assertThat(table.path("rows").get(0).get(0).asText()).isEqualTo("M");
    } finally { Locale.setDefault(original); }
  }
  private Map<String,Object> asciiTable(int bytes) {
    var columns = new ArrayList<String>(); for (int i = 0; i < 10; i++) columns.add("C" + i);
    var rows = new ArrayList<List<String>>(); int remaining = bytes - 1352; // hand-counted compact overhead: 10 columns, 40x10 empty cells
    for (int r = 0; r < 40; r++) {
      var cells = new ArrayList<String>(); for (int c = 0; c < 10; c++) { int count = Math.min(100, remaining); cells.add("x".repeat(count)); remaining -= count; } rows.add(cells);
    }
    var table = new LinkedHashMap<String,Object>(); table.put("columns", columns); table.put("rows", rows);
    assertThat(remaining).isZero(); assertThat(mapper.writeValueAsString(table).getBytes(StandardCharsets.UTF_8)).hasSize(bytes); return table;
  }
  @Test void serializedUtf8BoundaryIsMeasuredAfterNormalization() {
    var body = input(0); var table = asciiTable(32768);
    @SuppressWarnings("unchecked") var rows = (List<List<String>>)table.get("rows"); rows.get(39).set(9, "   ");
    body.put("table_json", table); data(put("exact-bytes", body), 201);
    body = input(1); body.put("table_json", asciiTable(32769)); invalid(put("one-byte-over", body), "table_json");
    var columns = new ArrayList<String>(); for (int i = 0; i < 10; i++) columns.add("C" + i);
    var unicode = Map.of("columns", columns, "rows", java.util.Collections.nCopies(40, java.util.Collections.nCopies(10, "é".repeat(60))));
    String serialized = mapper.writeValueAsString(unicode); assertThat(serialized.length()).isLessThan(32768); assertThat(serialized.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(32768);
    body.put("table_json", unicode); invalid(put("unicode-bytes", body), "table_json");
  }
  @Test void unknownGuideFieldsReportKeyName() {
    for (String key : List.of("x_future", "unknown_fields", "category_id")) {
      var body = input(0); body.put(key, null); invalid(put(UUID.randomUUID().toString(), body), key);
    }
    var nested = input(0); nested.put("table_json", mapper.readTree("{\"columns\":[\"Size\"],\"rows\":[[\"M\"]],\"extra\":null}"));
    invalid(put("nested-unknown", nested), "table_json.extra");
    assertThat(audits()).isZero(); assertThat(jdbc.queryForObject("select count(*) from size_guides", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from idempotency_requests", Integer.class)).isZero();
  }
  @Test void guideAuditFailureRollsBackWriteAndKeyWithSafe500() throws Exception {
    var before = data(put("original", input(0)), 201);
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("alter table audit_logs add constraint test_guide_audit check (action not like 'catalog.size-guide.%') not valid");
      try {
        var changed = input(1); changed.put("guideline_html", "<p>Safe changed</p>");
        for (var failed : List.of(put("failed-update", changed), put(SEED, "en", "failed-create", input(0)))) {
          data(failed, 500); assertThat(json(failed).size()).isEqualTo(3); assertThat(json(failed).path("code").asText()).isEqualTo("INTERNAL");
          assertThat(failed.body()).doesNotContain("audit_logs", "test_guide_audit", "insert into", "Safe changed");
        }
        assertThat(data(call("GET", path(SEED, "vi"), null), 200)).isEqualTo(before); assertThat(jdbc.queryForObject("select count(*) from size_guides", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_requests where key in ('failed-update','failed-create')", Integer.class)).isZero(); assertThat(audits()).isEqualTo(1);
      } finally { s.execute("alter table audit_logs drop constraint test_guide_audit"); }
    }
  }
}
