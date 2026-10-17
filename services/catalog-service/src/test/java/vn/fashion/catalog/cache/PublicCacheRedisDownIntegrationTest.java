package vn.fashion.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;
import vn.fashion.catalog.product.PublicCatalogFixtures;

/** Redis refusing connections (nothing listens on port 1): public reads come from PostgreSQL, readiness stays UP. */
class PublicCacheRedisDownIntegrationTest extends CatalogAdminTestSupport {
  @DynamicPropertySource static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("fashion.catalog.cache.enabled", () -> "true");
    registry.add("spring.data.redis.host", () -> "127.0.0.1");
    registry.add("spring.data.redis.port", () -> "1");
  }

  @Test void redisDownFallsBackToDatabase() {
    var fixtures = new PublicCatalogFixtures(jdbc);
    UUID category = fixtures.category(null, "shirts", "ACTIVE");
    UUID product = fixtures.product("down", "Áo", category, null, "ACTIVE", 100000, Instant.now(), 0);
    fixtures.variant(product, "DOWN-M", "M", "Blue", null, "ACTIVE");
    UUID collection = fixtures.collection("down", "ACTIVE", Instant.now().minusSeconds(60), null);
    fixtures.item(collection, product, 0);
    fixtures.sizeGuide(category, "vi");
    for (String path : new String[] {"/products", "/search?q=a", "/products/" + product, "/categories", "/brands",
        "/collections", "/collections/down", "/size-guides/" + category}) {
      assertThat(send("GET", "/api/v1/catalog" + path, null, null, null).statusCode()).as(path).isEqualTo(200);
    }
    assertThat(data(send("GET", "/api/v1/catalog/products/" + product, null, null, null), 200).path("slug").asText()).isEqualTo("down");
    var readiness = send("GET", "/actuator/health/readiness", null, null, null);
    assertThat(readiness.statusCode()).isEqualTo(200);
    assertThat(readiness.body()).contains("\"UP\"");
  }
}
