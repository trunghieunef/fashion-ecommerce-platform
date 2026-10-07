package vn.fashion.catalog.admin;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaxonomyIntegrationTest extends CatalogAdminTestSupport {
  @Test void mutationsShareAuditRequestIdAndReplayDoesNotAuditAgain() {
    String key = UUID.randomUUID().toString(), body = mapper.writeValueAsString(category("audit"));
    var first = send("POST", "/admin/api/v1/catalog/categories", token("catalog.write"), key, body);
    var category = audited(first, 201, "catalog.category.create");
    var update = new HashMap<String,Object>(category("audit")); update.put("expected_version", 0); update.put("status", "ACTIVE");
    audited(call("PUT", "/categories/" + category.path("id").asText(), update), 200, "catalog.category.update");
    var brand = audited(call("POST", "/brands", Map.of("name", "Synthetic")), 201, "catalog.brand.create");
    audited(call("PUT", "/brands/" + brand.path("id").asText(), Map.of("name", "Synthetic", "status", "ACTIVE", "expected_version", 0)), 200, "catalog.brand.update");
    var input = productInput("audit"); input.put("category_id", category.path("id").asText()); input.put("brand_id", brand.path("id").asText());
    var product = audited(call("POST", "/products", input), 201, "catalog.product.create");
    String productId = product.path("id").asText(); input.put("expected_version", 0);
    audited(call("PUT", "/products/" + productId, input), 200, "catalog.product.update");
    var variant = audited(call("POST", "/products/" + productId + "/variants", variantInput("AUDIT")), 201, "catalog.variant.create");
    audited(call("PUT", "/variants/" + variant.path("id").asText(), Map.of("weight_grams", 150, "status", "ACTIVE", "expected_version", 0)), 200, "catalog.variant.update");
    audited(call("POST", "/products/" + productId + "/publish", Map.of("expected_version", 2)), 200, "catalog.product.publish");
    audited(call("POST", "/products/" + productId + "/unpublish", Map.of("expected_version", 3)), 200, "catalog.product.unpublish");
    var retry = send("POST", "/admin/api/v1/catalog/categories", token("catalog.write"), key, body);
    assertThat(data(retry, 201)).isEqualTo(data(first, 201));
    assertThat(json(retry).path("metadata").path("request_id")).isNotEqualTo(json(first).path("metadata").path("request_id"));
    assertThat(jdbc.queryForObject("select count(*) from audit_logs", Integer.class)).isEqualTo(10);
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where request_id=?", Integer.class,
        UUID.fromString(json(retry).path("metadata").path("request_id").asText()))).isZero();
  }
  @Test void missingOrInvalidTokenIs401() {
    for (String token : new String[]{null, "x", signed(key(), Instant.now(), "catalog.write"), signed(KEY, Instant.now().minusSeconds(1000), "catalog.write")}) {
      var response = send("GET", "/admin/api/v1/catalog/categories", token, null, null);
      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(json(response).path("code").asText()).isEqualTo("UNAUTHORIZED");
    }
  }
  @Test void tokenWithoutCatalogWriteIs403() {
    var response = send("GET", "/admin/api/v1/catalog/categories", token("user.manage"), null, null);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(json(response).path("code").asText()).isEqualTo("FORBIDDEN");
  }
  @Test void createCategoryReturns201AndAudits() {
    var body = data(call("POST", "/categories", category("ao")), 201);
    assertThat(body.path("version").asLong()).isZero();
    assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='catalog.category.create'", Integer.class)).isEqualTo(1);
  }
  @Test void sameKeySameBodyReplaysWithoutSecondRow() {
    String key = UUID.randomUUID().toString();
    String body = mapper.writeValueAsString(category("ao"));
    var first = send("POST", "/admin/api/v1/catalog/categories", token("catalog.write"), key, body);
    var second = send("POST", "/admin/api/v1/catalog/categories", token("catalog.write"), key, body);
    assertThat(data(second, 201)).isEqualTo(data(first, 201));
    assertThat(jdbc.queryForObject("select count(*) from categories where slug='ao'", Integer.class)).isEqualTo(1);
  }
  @Test void sameKeyDifferentBodyIs409() {
    String key = UUID.randomUUID().toString();
    send("POST", "/admin/api/v1/catalog/categories", token("catalog.write"), key, mapper.writeValueAsString(category("ao")));
    var r = send("POST", "/admin/api/v1/catalog/categories", token("catalog.write"), key, mapper.writeValueAsString(category("quan")));
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(json(r).path("message").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }
  @Test void missingIdempotencyKeyIs400() {
    var r = send("POST", "/admin/api/v1/catalog/categories", token("catalog.write"), null, mapper.writeValueAsString(category("ao")));
    assertThat(r.statusCode()).isEqualTo(400);
  }
  @Test void duplicateSlugIs409WithField() {
    createCategory("ao");
    var r = call("POST", "/categories", category("ao"));
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(json(r).path("errors").get(0).path("field").asText()).isEqualTo("slug");
  }
  @Test void invalidFieldsAre400() {
    var r = call("POST", "/categories", Map.of("name_vi", "", "name_en", "x".repeat(256), "slug", "Áo Đẹp"));
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(json(r).path("errors").toString()).contains("name_vi", "name_en", "slug");
  }
  @Test void categoryDepthIsAtMostTwo() {
    UUID root = createCategory("root");
    var body = new HashMap<String,Object>(category("child")); body.put("parent_id", root);
    UUID child = UUID.fromString(data(call("POST", "/categories", body), 201).path("id").asText());
    body.put("slug", "grandchild"); body.put("parent_id", child);
    assertThat(call("POST", "/categories", body).statusCode()).isEqualTo(400);
    body.put("parent_id", UUID.randomUUID());
    assertThat(call("POST", "/categories", body).statusCode()).isEqualTo(400);
  }
  @Test void categoryWithChildrenCannotBecomeChild() {
    UUID root = createCategory("root"), other = createCategory("other");
    var body = new HashMap<String,Object>(category("child")); body.put("parent_id", root);
    data(call("POST", "/categories", body), 201);
    body = new HashMap<>(category("root")); body.put("parent_id", other); body.put("status", "ACTIVE"); body.put("expected_version", 0);
    assertThat(call("PUT", "/categories/" + root, body).statusCode()).isEqualTo(400);
  }
  @Test void updateNeedsCurrentVersion() {
    UUID id = createCategory("ao");
    var body = new HashMap<String,Object>(category("ao")); body.put("status", "ACTIVE");
    assertThat(call("PUT", "/categories/" + id, body).statusCode()).isEqualTo(400);
    body.put("expected_version", 5);
    var stale = call("PUT", "/categories/" + id, body);
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(json(stale).path("code").asText()).isEqualTo("VERSION_CONFLICT");
    body.put("expected_version", 0);
    assertThat(data(call("PUT", "/categories/" + id, body), 200).path("version").asLong()).isEqualTo(1);
  }
  @Test void concurrentUpdatesWithSameVersionOnlyOneWins() throws Exception {
    UUID id = createCategory("ao");
    var body = new HashMap<String,Object>(category("ao")); body.put("status", "ACTIVE"); body.put("expected_version", 0);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> call("PUT", "/categories/" + id, body).statusCode());
      var b = pool.submit(() -> call("PUT", "/categories/" + id, body).statusCode());
      assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
    }
  }
  @Test void brandCreateUpdateAndInactivate() {
    var brand = data(call("POST", "/brands", Map.of("name", "Synthetic")), 201);
    var updated = data(call("PUT", "/brands/" + brand.path("id").asText(), Map.of("name", "Synthetic", "status", "INACTIVE", "expected_version", 0)), 200);
    assertThat(updated.path("status").asText()).isEqualTo("INACTIVE");
    assertThat(updated.path("version").asLong()).isEqualTo(1);
  }
}
