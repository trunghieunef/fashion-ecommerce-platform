package vn.fashion.catalog.admin;

import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class VariantAdminIntegrationTest extends CatalogAdminTestSupport {
  int events() { return jdbc.queryForObject("select count(*) from outbox_events", Integer.class); }
  @Test void createVariantWritesExactlyOneOutboxEvent() {
    UUID p = createProduct("shirt");
    var response = call("POST", "/products/" + p + "/variants", variantInput("SHIRT-M"));
    var variant = data(response, 201);
    assertThat(events()).isEqualTo(1);
    var event = jdbc.queryForMap("select * from outbox_events");
    assertThat(event.get("event_type")).isEqualTo("VARIANT_CREATED");
    assertThat(event.get("topic")).isEqualTo("catalog.events"); assertThat(event.get("partition_key")).isEqualTo(p.toString());
    assertThat(((Number)event.get("aggregate_sequence")).longValue()).isEqualTo(1);
    assertThat(event.get("correlation_id")).isEqualTo(json(response).path("metadata").path("trace_id").asText());
    var payload = mapper.readTree(event.get("payload").toString());
    assertThat(payload.size()).isEqualTo(4);
    assertThat(payload.path("product_id").asText()).isEqualTo(p.toString());
    assertThat(payload.path("variant_id").asText()).isEqualTo(variant.path("id").asText());
    assertThat(payload.path("sku").asText()).isEqualTo("SHIRT-M"); assertThat(payload.path("version").asLong()).isZero();
  }
  @Test void productVersionBumpsAndSequenceIncrements() {
    UUID p = createProduct("shirt"); createVariant(p, "A");
    var body = variantInput("B"); body.put("size", "L"); data(call("POST", "/products/" + p + "/variants", body), 201);
    assertThat(data(call("GET", "/products/" + p, null), 200).path("version").asLong()).isEqualTo(2);
    assertThat(jdbc.queryForList("select aggregate_sequence from outbox_events order by aggregate_sequence", Long.class)).containsExactly(1L, 2L);
  }
  @Test void retrySameKeyAddsNoVariantOrEvent() {
    UUID p = createProduct("shirt"); String key = UUID.randomUUID().toString(), path = "/admin/api/v1/catalog/products/" + p + "/variants";
    String body = mapper.writeValueAsString(variantInput("RETRY"));
    var a = send("POST", path, token("catalog.write"), key, body);
    var b = send("POST", path, token("catalog.write"), key, body);
    assertThat(data(b, 201)).isEqualTo(data(a, 201)); assertThat(events()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from product_variants", Integer.class)).isEqualTo(1);
  }
  @Test void newSkuIsCanonicalInResponseDatabaseAuditAndOutbox() {
    UUID p = createProduct("canonical"); var previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      var variant = createVariant(p, " mini-i._1 ");
      assertThat(variant.path("sku").asText()).isEqualTo("MINI-I._1");
      assertThat(jdbc.queryForObject("select sku from product_variants", String.class)).isEqualTo("MINI-I._1");
      assertThat(jdbc.queryForObject("select after_data->>'sku' from audit_logs where action='catalog.variant.create'", String.class)).isEqualTo("MINI-I._1");
      assertThat(jdbc.queryForObject("select payload->>'sku' from outbox_events", String.class)).isEqualTo("MINI-I._1");
      for (String invalid : List.of("ß", "ı"))
        assertThat(call("POST", "/products/" + p + "/variants", variantInput(invalid)).statusCode()).isEqualTo(400);
      assertThat(events()).isEqualTo(1);
    } finally { Locale.setDefault(previous); }
  }
  @Test void retryWithDifferentSkuCasingReplaysCanonicalResponse() {
    UUID p = createProduct("canonical-retry"); String key = UUID.randomUUID().toString();
    String path = "/admin/api/v1/catalog/products/" + p + "/variants";
    var first = send("POST", path, token("catalog.write"), key, mapper.writeValueAsString(variantInput(" shirt-m ")));
    var replay = send("POST", path, token("catalog.write"), key, mapper.writeValueAsString(variantInput("SHIRT-M")));
    assertThat(data(replay, 201)).isEqualTo(data(first, 201));
    assertThat(data(first, 201).path("sku").asText()).isEqualTo("SHIRT-M");
    var changed = variantInput("shirt-m"); changed.put("weight_grams", 200);
    assertThat(send("POST", path, token("catalog.write"), key, mapper.writeValueAsString(changed)).statusCode()).isEqualTo(409);
    assertThat(events()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='catalog.variant.create'", Integer.class)).isEqualTo(1);
  }
  @Test void legacySkuAndCompletedKeyRemainUnchanged() throws Exception {
    UUID p = createProduct("legacy"), id = UUID.randomUUID(); String key = UUID.randomUUID().toString();
    // Snapshot of a V002 request/response, before canonical SKU normalization existed.
    String oldRequest = "{\"sku\":\"legacy-m\",\"size\":\"M\",\"color\":\"Blue\",\"price_override\":null,\"weight_grams\":100}";
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oldRequest.getBytes(StandardCharsets.UTF_8)));
    String cached = "{\"id\":\"" + id + "\",\"product_id\":\"" + p + "\",\"sku\":\"legacy-m\",\"size\":\"M\",\"color\":\"Blue\",\"price_override\":null,\"weight_grams\":100,\"status\":\"ACTIVE\",\"version\":0}";
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("set local session_replication_role=replica"); // Test-only V002 snapshot; V002→V003 upgrade is tested separately.
      try (var insert = c.prepareStatement("insert into product_variants(id,product_id,sku,size,color,weight_grams) values (?,?,'legacy-m','M','Blue',100)")) {
        insert.setObject(1, id); insert.setObject(2, p); insert.executeUpdate();
      }
      c.commit();
    }
    jdbc.update("insert into idempotency_requests(actor_key,operation,key,request_hash,resource_id,status,response_code,response_body) values (?,?,?,?,?,'COMPLETED',201,?::jsonb)",
        "user:" + ACTOR, "catalog.variant.create:" + p, key, hash, id, cached);
    String path = "/admin/api/v1/catalog/products/" + p + "/variants";
    for (String sku : List.of("legacy-m", "LEGACY-M")) {
      var replay = send("POST", path, token("catalog.write"), key, mapper.writeValueAsString(variantInput(sku)));
      assertThat(data(replay, 201)).isEqualTo(mapper.readTree(cached));
    }
    var changed = variantInput("LEGACY-M"); changed.put("weight_grams", 200);
    assertThat(send("POST", path, token("catalog.write"), key, mapper.writeValueAsString(changed)).statusCode()).isEqualTo(409);
    assertThat(jdbc.queryForObject("select request_hash from idempotency_requests where key=?", String.class, key)).isEqualTo(hash);
    assertThat(events()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='catalog.variant.create'", Integer.class)).isZero();
    var update = data(call("PUT", "/variants/" + id, Map.of("weight_grams", 200, "status", "INACTIVE", "expected_version", 0)), 200);
    assertThat(update.path("sku").asText()).isEqualTo("legacy-m");
    assertThat(update.path("version").asLong()).isEqualTo(1);
    var duplicate = call("POST", "/products/" + createProduct("legacy-duplicate") + "/variants", variantInput("LEGACY-M"));
    assertThat(duplicate.statusCode()).isEqualTo(409);
    assertThat(json(duplicate).path("errors").get(0).path("field").asText()).isEqualTo("sku");
    assertThat(jdbc.queryForObject("select sku from product_variants", String.class)).isEqualTo("legacy-m");
    assertThat(events()).isZero();
  }
  @Test void duplicateSkuIs409AndLeavesNoEvent() {
    createVariant(createProduct("a"), "same"); UUID p = createProduct("b");
    var response = call("POST", "/products/" + p + "/variants", variantInput("SaMe"));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(json(response).path("errors").get(0).path("field").asText()).isEqualTo("sku"); assertThat(events()).isEqualTo(1);
  }
  @Test void duplicateSizeColorIs409() {
    UUID p = createProduct("shirt"); createVariant(p, "A");
    var response = call("POST", "/products/" + p + "/variants", variantInput("B"));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(json(response).path("errors").get(0).path("field").asText()).isEqualTo("size");
  }
  @Test void concurrentSameSkuOnTwoProducts() throws Exception {
    UUID a = createProduct("a"), b = createProduct("b"); var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> { gate.await(); return call("POST", "/products/" + a + "/variants", variantInput("race")).statusCode(); });
      var second = pool.submit(() -> { gate.await(); return call("POST", "/products/" + b + "/variants", variantInput("RaCe")).statusCode(); });
      gate.countDown(); assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
    }
    assertThat(events()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select sku from product_variants", String.class)).isEqualTo("RACE");
  }
  @Test void failureAfterInsertRollsBackVariantAndEvent(CapturedOutput output) throws Exception {
    UUID p = createProduct("shirt");
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("revoke insert on audit_logs from catalog_runtime");
      try {
        var response = call("POST", "/products/" + p + "/variants", variantInput("ROLLBACK"));
        assertThat(response.statusCode()).isEqualTo(500);
        var error = json(response);
        assertThat(error.size()).isEqualTo(3);
        assertThat(error.path("code").asText()).isEqualTo("INTERNAL");
        assertThat(error.path("message").asText()).isEqualTo("INTERNAL_ERROR");
        UUID.fromString(error.path("metadata").path("request_id").asText());
        assertThat(error.path("metadata").path("trace_id").asText()).matches("[0-9a-f]{32}");
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains(error.path("metadata").path("trace_id").asText());
        assertThat(response.body()).doesNotContain("audit_logs", "permission denied", "BadSqlGrammarException", "insert into");
        assertThat(output.getAll()).contains("Catalog admin request failed", "BadSqlGrammarException");
        assertThat(jdbc.queryForObject("select count(*) from product_variants", Integer.class)).isZero(); assertThat(events()).isZero();
        assertThat(jdbc.queryForObject("select version from products where id=?", Long.class, p)).isZero();
      } finally { s.execute("grant insert on audit_logs to catalog_runtime"); }
    }
  }
  @Test void validationOfSkuSizeColorWeightPrice() {
    UUID p = createProduct("shirt"); var body = variantInput("a b");
    body.put("size", ""); body.put("weight_grams", 0); body.put("price_override", -1);
    var response = call("POST", "/products/" + p + "/variants", body);
    assertThat(response.statusCode()).isEqualTo(400); assertThat(json(response).path("errors").toString()).contains("sku", "size", "weight_grams", "price_override");
    body = variantInput("x".repeat(65)); assertThat(call("POST", "/products/" + p + "/variants", body).statusCode()).isEqualTo(400);
  }
  @Test void updateChangesOnlyMutableFields() {
    UUID p = createProduct("shirt"); var variant = createVariant(p, "OLD");
    var updated = data(call("PUT", "/variants/" + variant.path("id").asText(),
        Map.of("sku", "NEW", "size", "XL", "price_override", 200000, "weight_grams", 200, "status", "INACTIVE", "expected_version", 0)), 200);
    assertThat(updated.path("sku").asText()).isEqualTo("OLD"); assertThat(updated.path("size").asText()).isEqualTo("M");
    assertThat(updated.path("price_override").asLong()).isEqualTo(200000); assertThat(updated.path("weight_grams").asInt()).isEqualTo(200);
    assertThat(updated.path("status").asText()).isEqualTo("INACTIVE"); assertThat(updated.path("version").asLong()).isEqualTo(1);
    assertThat(events()).isEqualTo(1);
  }
  @Test void variantOfUnknownProductIs404() {
    var response = call("POST", "/products/" + UUID.randomUUID() + "/variants", variantInput("UNKNOWN"));
    assertThat(response.statusCode()).isEqualTo(404); assertThat(json(response).path("code").asText()).isEqualTo("NOT_FOUND");
  }
}
