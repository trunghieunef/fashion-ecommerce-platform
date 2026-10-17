package vn.fashion.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** CAT-02b spec section 3: admin changes enqueue durable work in their transaction; the worker bumps the generation. */
class CacheInvalidationIntegrationTest extends RedisCacheTestSupport {
  @Autowired CacheInvalidation invalidation;
  private UUID product;
  private String variant;

  @BeforeEach void seed() {
    cli("FLUSHALL");
    product = createProduct("inv");
    variant = createVariant(product, "INV-M").path("id").asText();
    attachSyntheticImage(product);
    data(call("POST", "/products/" + product + "/publish", Map.of("expected_version", 1)), 200);
    invalidation.runOnce();
  }

  private int tasks() {
    return jdbc.queryForObject("select count(*) from background_tasks where kind='catalog.cache.invalidate'", Integer.class);
  }
  private JsonNode detail() { return data(send("GET", "/api/v1/catalog/products/" + product, null, null, null), 200); }
  private String gen() { return cli("GET", "local:catalog:gen"); }
  private Map<String, Object> rename(String nameVi, long version) {
    var input = new HashMap<>(productInput("inv")); input.put("name_vi", nameVi); input.put("expected_version", version);
    return input;
  }

  @Test void adminChangesBecomeVisibleAfterWorker() {
    assertThat(detail().path("name_vi").asText()).isEqualTo("Áo");
    data(call("PUT", "/products/" + product, rename("Áo mới", 2)), 200);
    assertThat(detail().path("name_vi").asText()).as("still cached before the worker").isEqualTo("Áo");
    assertThat(invalidation.runOnce()).isEqualTo(1);
    assertThat(detail().path("name_vi").asText()).isEqualTo("Áo mới");

    data(call("PUT", "/variants/" + variant, Map.of("weight_grams", 100, "status", "ACTIVE", "price_override", 50000, "expected_version", 0)), 200);
    invalidation.runOnce();
    assertThat(detail().path("min_price").asLong()).isEqualTo(50000);

    UUID category = createCategory("inv-cat");
    invalidation.runOnce();
    assertThat(data(send("GET", "/api/v1/catalog/categories", null, null, null), 200).toString()).contains("inv-cat");
    data(call("PUT", "/categories/" + category, Map.of("name_vi", "Đổi tên", "name_en", "Shirt", "slug", "inv-cat",
        "sort_order", 1, "status", "ACTIVE", "expected_version", 0)), 200);
    invalidation.runOnce();
    assertThat(data(send("GET", "/api/v1/catalog/categories", null, null, null), 200).toString()).contains("Đổi tên");

    detail(); // re-cache under the current generation
    data(call("POST", "/products/" + product + "/unpublish", Map.of("expected_version", 3)), 200);
    assertThat(send("GET", "/api/v1/catalog/products/" + product, null, null, null).statusCode()).isEqualTo(200);
    invalidation.runOnce();
    assertThat(send("GET", "/api/v1/catalog/products/" + product, null, null, null).statusCode()).isEqualTo(404);
  }

  @Test void replayDoesNotEnqueue() {
    String key = UUID.randomUUID().toString();
    String body = mapper.writeValueAsString(Map.of("name", "Replay"));
    assertThat(send("POST", "/admin/api/v1/catalog/brands", token("catalog.write"), key, body).statusCode()).isEqualTo(201);
    int after = tasks();
    assertThat(send("POST", "/admin/api/v1/catalog/brands", token("catalog.write"), key, body).statusCode()).isEqualTo(201);
    assertThat(tasks()).isEqualTo(after);
  }

  @Test void failedAdminCommandEnqueuesNothing() {
    int before = tasks();
    assertThat(call("PUT", "/products/" + product, rename("Sai", 0)).statusCode()).isEqualTo(409);
    assertThat(tasks()).isEqualTo(before);
  }

  @Test void redisDownKeepsTaskThenRecovers() {
    detail();
    data(call("PUT", "/products/" + product, rename("Khi Redis treo", 2)), 200);
    pause(true);
    try { assertThat(invalidation.runOnce()).isZero(); }
    finally { pause(false); }
    var task = jdbc.queryForMap("select status,last_error from background_tasks where kind='catalog.cache.invalidate' and status<>'DONE'");
    assertThat(task.get("status")).isEqualTo("PENDING");
    assertThat(task.get("last_error")).isNotNull();
    jdbc.update("update background_tasks set next_attempt_at=now() where status='PENDING'");
    assertThat(invalidation.runOnce()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from background_tasks where status<>'DONE'", Integer.class)).isZero();
    assertThat(detail().path("name_vi").asText()).isEqualTo("Khi Redis treo");
  }

  @Test void exhaustedTaskBecomesManualWithMetric() {
    // Review F5: spec section 3 "MANUAL kèm metric".
    data(call("PUT", "/products/" + product, rename("Hết lượt", 2)), 200);
    jdbc.update("update background_tasks set attempts=9 where status='PENDING'");
    double before = manual();
    pause(true);
    try { assertThat(invalidation.runOnce()).isZero(); }
    finally { pause(false); }
    assertThat(jdbc.queryForObject("select count(*) from background_tasks where status='MANUAL'", Integer.class)).isEqualTo(1);
    assertThat(manual()).isEqualTo(before + 1);
  }
  @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry meters;
  private double manual() {
    var c = meters.find("catalog.cache.invalidation").tag("outcome", "manual").counter();
    return c == null ? 0 : c.count();
  }

  @Test void oneBumpForManyTasks() {
    String before = gen();
    data(call("PUT", "/products/" + product, rename("Một", 2)), 200);
    data(call("PUT", "/products/" + product, rename("Hai", 3)), 200);
    data(call("PUT", "/products/" + product, rename("Ba", 4)), 200);
    assertThat(invalidation.runOnce()).isEqualTo(3);
    assertThat(Long.parseLong(gen())).isEqualTo(Long.parseLong(before) + 1);
  }
}
