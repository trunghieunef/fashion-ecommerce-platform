package vn.fashion.catalog.admin;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PublishIntegrationTest extends CatalogAdminTestSupport {
  UUID ready() { UUID p = createProduct("shirt"); createVariant(p, "SHIRT-M"); return p; }
  @Test void publishWithoutActiveVariantIs400AndStaysDraft() {
    UUID p = createProduct("shirt"); var r = call("POST", "/products/" + p + "/publish", Map.of("expected_version", 0));
    assertThat(r.statusCode()).isEqualTo(400); assertThat(json(r).path("errors").toString()).contains("variants");
    var current = data(call("GET", "/products/" + p, null), 200);
    assertThat(current.path("status").asText()).isEqualTo("DRAFT"); assertThat(current.path("version").asLong()).isZero();
  }
  @Test void publishWithInactiveCategoryOrBrandIs400() {
    UUID c = createCategory("clothes"); var brand = data(call("POST", "/brands", Map.of("name", "Synthetic")), 201);
    var input = productInput("shirt"); input.put("category_id", c); input.put("brand_id", brand.path("id").asText());
    UUID p = UUID.fromString(data(call("POST", "/products", input), 201).path("id").asText()); createVariant(p, "SHIRT-M");
    var category = new java.util.HashMap<String,Object>(category("clothes")); category.put("status", "INACTIVE"); category.put("expected_version", 0);
    data(call("PUT", "/categories/" + c, category), 200);
    var r = call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1));
    assertThat(r.statusCode()).isEqualTo(400); assertThat(json(r).path("errors").toString()).contains("category_id");
    category.put("status", "ACTIVE"); category.put("expected_version", 1); data(call("PUT", "/categories/" + c, category), 200);
    data(call("PUT", "/brands/" + brand.path("id").asText(), Map.of("name", "Synthetic", "status", "INACTIVE", "expected_version", 0)), 200);
    r = call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1));
    assertThat(r.statusCode()).isEqualTo(400); assertThat(json(r).path("errors").toString()).contains("brand_id");
  }
  @Test void publishSetsActiveAndPublishedAtOnce() {
    UUID p = ready(); var published = data(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1)), 200);
    assertThat(published.path("status").asText()).isEqualTo("ACTIVE"); assertThat(published.path("published_at").isNull()).isFalse();
    assertThat(data(call("POST", "/products/" + p + "/unpublish", Map.of("expected_version", 2)), 200).path("status").asText()).isEqualTo("INACTIVE");
    assertThat(data(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 3)), 200).path("published_at")).isEqualTo(published.path("published_at"));
  }
  @Test void publishAfterLastVariantDeactivatedIs400() {
    UUID p = createProduct("shirt"); var v = createVariant(p, "SHIRT-M");
    data(call("PUT", "/variants/" + v.path("id").asText(), Map.of("weight_grams", 100, "status", "INACTIVE", "expected_version", 0)), 200);
    assertThat(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1)).statusCode()).isEqualTo(400);
  }
  @Test void legacyActiveWithoutPublishedAtKeepsNullUntilRepublished() {
    UUID p = ready();
    jdbc.update("update products set status='ACTIVE',published_at=null where id=?", p);
    var inactive = data(call("POST", "/products/" + p + "/unpublish", Map.of("expected_version", 1)), 200);
    assertThat(inactive.path("status").asText()).isEqualTo("INACTIVE");
    assertThat(inactive.path("published_at").isNull()).isTrue();
    var active = data(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 2)), 200);
    assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(active.path("published_at").isNull()).isFalse();
  }
  @Test void invalidTransitionIs409() {
    UUID p = createProduct("shirt"); var r = call("POST", "/products/" + p + "/unpublish", Map.of("expected_version", 0));
    assertThat(r.statusCode()).isEqualTo(409); assertThat(json(r).path("message").asText()).isEqualTo("INVALID_TRANSITION");
    createVariant(p, "SHIRT-M"); data(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1)), 200);
    assertThat(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 2)).statusCode()).isEqualTo(409);
  }
  @Test void publishNeedsVersionAndKey() {
    UUID p = createProduct("shirt"); var r = call("POST", "/products/" + p + "/publish", Map.of());
    assertThat(r.statusCode()).isEqualTo(400); assertThat(json(r).path("errors").toString()).contains("expected_version");
    r = call("POST", "/products/" + p + "/publish", Map.of("expected_version", 99));
    assertThat(r.statusCode()).isEqualTo(409); assertThat(json(r).path("code").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(send("POST", "/admin/api/v1/catalog/products/" + p + "/publish", token("catalog.write"), null, "{\"expected_version\":0}").statusCode()).isEqualTo(400);
  }
  @Test void publishWithoutReasonSucceeds() {
    UUID p = ready(); data(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1)), 200);
    assertThat(jdbc.queryForObject("select reason from audit_logs where action='catalog.product.publish'", String.class)).isNull();
  }
  @Test void retryPublishSameKeyReturnsSameResult() {
    UUID p = ready(); String key = UUID.randomUUID().toString(), path = "/admin/api/v1/catalog/products/" + p + "/publish", body = "{\"expected_version\":1,\"reason\":\"Synthetic publish\"}";
    var a = send("POST", path, token("catalog.write"), key, body);
    var b = send("POST", path, token("catalog.write"), key, body);
    assertThat(data(b, 200)).isEqualTo(data(a, 200));
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='catalog.product.publish'", Integer.class)).isEqualTo(1);
  }
  @Test void publicListShowsProductOnlyWhileActive() {
    UUID p = ready(); String path = "/api/v1/catalog/products";
    assertThat(json(send("GET", path, null, null, null)).path("data").path("items").toString()).doesNotContain(p.toString());
    data(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1)), 200);
    assertThat(json(send("GET", path, null, null, null)).path("data").path("items").toString()).contains(p.toString());
    data(call("POST", "/products/" + p + "/unpublish", Map.of("expected_version", 2)), 200);
    assertThat(json(send("GET", path, null, null, null)).path("data").path("items").toString()).doesNotContain(p.toString());
  }
}
