package vn.fashion.catalog.admin;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

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
  @Test void duplicateSkuIs409AndLeavesNoEvent() {
    createVariant(createProduct("a"), "SAME"); UUID p = createProduct("b");
    var response = call("POST", "/products/" + p + "/variants", variantInput("SAME"));
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
      var first = pool.submit(() -> { gate.await(); return call("POST", "/products/" + a + "/variants", variantInput("RACE")).statusCode(); });
      var second = pool.submit(() -> { gate.await(); return call("POST", "/products/" + b + "/variants", variantInput("RACE")).statusCode(); });
      gate.countDown(); assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
    }
    assertThat(events()).isEqualTo(1);
  }
  @Test void failureAfterInsertRollsBackVariantAndEvent() throws Exception {
    UUID p = createProduct("shirt");
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("revoke insert on audit_logs from catalog_runtime");
      try {
        assertThat(call("POST", "/products/" + p + "/variants", variantInput("ROLLBACK")).statusCode()).isBetween(500, 599);
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
