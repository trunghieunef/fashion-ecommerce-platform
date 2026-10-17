package vn.fashion.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import vn.fashion.catalog.ProjectionEvents;
import vn.fashion.catalog.product.PublicCatalogFixtures;
import vn.fashion.catalog.stock.StockObservations;

/** CAT-02b spec section 2 on a real Redis with the compose ACL (catalog user limited to local:catalog:*). */
class PublicCacheIntegrationTest extends RedisCacheTestSupport {
  @Autowired PublicCache cache;
  @Autowired StockObservations stock;
  @Autowired MeterRegistry meters;
  private UUID product;

  private long ttl(String key) { return Long.parseLong(cli("TTL", key)); }
  private double count(String route, String outcome) {
    var c = meters.find("catalog.cache").tag("route", route).tag("outcome", outcome).counter();
    return c == null ? 0 : c.count();
  }

  @BeforeEach void seed() {
    cli("FLUSHALL");
    var fixtures = new PublicCatalogFixtures(jdbc);
    UUID category = fixtures.category(null, "shirts", "ACTIVE");
    product = fixtures.product("cached", "Áo", category, null, "ACTIVE", 100000, Instant.now(), 0);
    fixtures.variant(product, "CACHE-M", "M", "Blue", null, "ACTIVE");
  }

  private String detailPath() { return "/api/v1/catalog/products/" + product; }

  @Test void secondReadIsHitWithFreshMetadata() {
    double hits = count("product", "hit");
    var first = json(send("GET", detailPath(), null, null, null));
    var second = json(send("GET", detailPath(), null, null, null));
    assertThat(second.path("data")).isEqualTo(first.path("data"));
    assertThat(second.path("metadata").path("request_id").asText()).isNotEqualTo(first.path("metadata").path("request_id").asText());
    assertThat(count("product", "hit")).isEqualTo(hits + 1);
    var key = keys("local:catalog:v1:g0:product:*");
    assertThat(key).hasSize(1);
    assertThat(ttl(key.iterator().next())).isBetween(1L, 300L);
    send("GET", "/api/v1/catalog/products", null, null, null);
    assertThat(ttl(keys("local:catalog:v1:g0:products:*").iterator().next())).isBetween(1L, 30L);
  }

  @Test void cacheDoesNotStoreErrors() {
    assertThat(send("GET", "/api/v1/catalog/products/" + UUID.randomUUID(), null, null, null).statusCode()).isEqualTo(404);
    assertThat(send("GET", "/api/v1/catalog/products?limit=0", null, null, null).statusCode()).isEqualTo(400);
    assertThat(keys("local:catalog:v1:*")).isEmpty();
  }

  @Test void stockIsNotCached() {
    data(send("GET", detailPath(), null, null, null), 200);
    stock.apply(ProjectionEvents.envelope(mapper, "INVENTORY_UPDATED", "CACHE-M",
        ProjectionEvents.stock("CACHE-M", 7, 1, Instant.parse("2026-10-17T03:00:00Z"))));
    var detail = data(send("GET", detailPath(), null, null, null), 200);
    assertThat(detail.path("variants").get(0).path("available").asInt()).isEqualTo(7);
    assertThat(detail.path("available").asInt()).isEqualTo(7);
  }

  @Test void staleReaderWritesOnlyOldGeneration() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var type = new TypeReference<String>() { };
    // Warm the lazy Lettuce connection: a cold connect over 200 ms falls back without caching and proves nothing.
    cache.read("warm", Duration.ofSeconds(5), type, () -> Optional.of("w"));
    var stale = CompletableFuture.supplyAsync(() -> cache.read("race", Duration.ofSeconds(300), type, () -> {
      entered.countDown();
      try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); }
      return Optional.of("old");
    }, "k"));
    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
    cache.bumpGeneration();
    release.countDown();
    assertThat(stale.get(10, TimeUnit.SECONDS)).contains("old");
    assertThat(keys("local:catalog:v1:g0:race:*")).as("stale value written under the generation read before load").hasSize(1);
    assertThat(cache.read("race", Duration.ofSeconds(300), type, () -> Optional.of("new"), "k")).contains("new");
  }

  @Test void undecodableValueIsMiss() {
    data(send("GET", detailPath(), null, null, null), 200);
    for (String k : keys("local:catalog:v1:*:product:*")) cli("SET", k, "{bad");
    var response = send("GET", detailPath(), null, null, null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).path("data").path("id").asText()).isEqualTo(product.toString());
  }

  @Test void pausedRedisFallsBackWithinBudget() {
    data(send("GET", detailPath(), null, null, null), 200);
    pause(true);
    try {
      long start = System.nanoTime();
      var response = send("GET", detailPath(), null, null, null);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
      assertThat(count("product", "error")).isPositive();
    } finally {
      pause(false);
    }
  }

  @Test void aclUserCannotTouchOtherPrefixes() {
    assertThat(cli("--user", "catalog", "--pass", PASSWORD, "--no-auth-warning", "SET", "local:catalog:probe", "1")).isEqualTo("OK");
    assertThat(cli("--user", "catalog", "--pass", PASSWORD, "--no-auth-warning", "GET", "local:user:secret")).contains("NOPERM");
    // Review F1: @connection alone allows CLIENT PAUSE/KILL/LIST, which would reach user-service connections.
    assertThat(cli("--user", "catalog", "--pass", PASSWORD, "--no-auth-warning", "CLIENT", "PAUSE", "10")).contains("NOPERM");
    assertThat(cli("--user", "catalog", "--pass", PASSWORD, "--no-auth-warning", "CLIENT", "LIST")).contains("NOPERM");
    assertThat(cli("--user", "catalog", "--pass", PASSWORD, "--no-auth-warning", "PING")).isEqualTo("PONG");
  }

  @Test void oldSchemaValueIsMiss() {
    // Review F3: valid JSON of another record shape must be a miss, not a detail with null variants (500).
    data(send("GET", detailPath(), null, null, null), 200);
    data(send("GET", detailPath(), null, null, null), 200); // second call caches even if the first hit a cold connect
    var cached = keys("local:catalog:v1:*:product:*");
    assertThat(cached).hasSize(1);
    for (String k : cached) {
      String renamed = cli("GET", k).replace("\"variants\":", "\"variants_old\":");
      assertThat(renamed).contains("variants_old");
      cli("SET", k, renamed);
    }
    var detail = data(send("GET", detailPath(), null, null, null), 200);
    assertThat(detail.path("variants")).hasSize(1);
  }
}
