package vn.fashion.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import tools.jackson.core.type.TypeReference;
import vn.fashion.catalog.ProjectionEvents;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;
import vn.fashion.catalog.product.PublicCatalogFixtures;
import vn.fashion.catalog.stock.StockObservations;

/** CAT-02b spec section 2 on a real Redis with the compose ACL (catalog user limited to local:catalog:*). */
class PublicCacheIntegrationTest extends CatalogAdminTestSupport {
  static final String PASSWORD = "catalog-test-password";
  @Container static final GenericContainer<?> redis = new GenericContainer<>("redis:8.2.9").withExposedPorts(6379)
      .withCommand("redis-server", "--save", "", "--appendonly", "no",
          "--user", "catalog", "on", ">" + PASSWORD, "~local:catalog:*", "+@connection", "+get", "+set", "+incr");
  @DynamicPropertySource static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("fashion.catalog.cache.enabled", () -> "true");
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("spring.data.redis.username", () -> "catalog");
    registry.add("spring.data.redis.password", () -> PASSWORD);
  }
  @Autowired PublicCache cache;
  @Autowired StockObservations stock;
  @Autowired MeterRegistry meters;
  private UUID product;

  /** redis-cli as the default user, for assertions the catalog ACL user may not run. */
  private static String cli(String... args) {
    try {
      var command = new String[args.length + 1]; command[0] = "redis-cli"; System.arraycopy(args, 0, command, 1, args.length);
      return redis.execInContainer(command).getStdout().trim();
    } catch (Exception e) { throw new AssertionError(e); }
  }
  private Set<String> keys(String pattern) {
    return Arrays.stream(cli("--scan", "--pattern", pattern).split("\\R")).filter(k -> !k.isBlank()).collect(Collectors.toSet());
  }
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
    var stale = CompletableFuture.supplyAsync(() -> cache.read("race", Duration.ofSeconds(300), type, () -> {
      entered.countDown();
      try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); }
      return Optional.of("old");
    }, "k"));
    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
    cache.bumpGeneration();
    release.countDown();
    assertThat(stale.get(10, TimeUnit.SECONDS)).contains("old");
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
    var docker = redis.getDockerClient();
    docker.pauseContainerCmd(redis.getContainerId()).exec();
    try {
      long start = System.nanoTime();
      var response = send("GET", detailPath(), null, null, null);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
      assertThat(count("product", "error")).isPositive();
    } finally {
      docker.unpauseContainerCmd(redis.getContainerId()).exec();
    }
  }

  @Test void aclUserCannotTouchOtherPrefixes() {
    assertThat(cli("--user", "catalog", "--pass", PASSWORD, "--no-auth-warning", "SET", "local:catalog:probe", "1")).isEqualTo("OK");
    assertThat(cli("--user", "catalog", "--pass", PASSWORD, "--no-auth-warning", "GET", "local:user:secret")).contains("NOPERM");
  }
}
