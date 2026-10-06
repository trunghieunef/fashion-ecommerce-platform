package vn.fashion.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** TASK:USR-01b-ii · REQ: USR-03/05, XCT-02. Real HTTP, PostgreSQL and Redis. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("users").withUsername("postgres").withPassword("postgres")
      .withInitScript("user-test-init.sql");
  @Container
  static final GenericContainer<?> redis = new GenericContainer<>("redis:8.2.9")
      .withExposedPorts(6379);
  static final KeyPair KEY = keyPair();
  static final HttpClient HTTP = HttpClient.newHttpClient();
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate counters;
  @Autowired ObjectMapper json;
  @Autowired AuthRateLimiter limits;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", () -> "user_runtime");
    r.add("spring.datasource.password", () -> "user_runtime_test");
    r.add("spring.flyway.url", postgres::getJdbcUrl);
    r.add("spring.flyway.user", () -> "user_migration");
    r.add("spring.flyway.password", () -> "user_migration_test");
    r.add("USER_JWT_PRIVATE_KEY", () -> Base64.getEncoder().encodeToString(KEY.getPrivate().getEncoded()));
    r.add("USER_JWT_PUBLIC_KEYS", () -> "user-local:" + Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()));
    r.add("USER_REDIS_HOST", redis::getHost);
    r.add("USER_REDIS_PORT", () -> redis.getMappedPort(6379));
  }

  @BeforeEach
  void clean() {
    try (var connection = counters.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
    jdbc.execute("delete from user_action_tokens");
    jdbc.execute("delete from refresh_tokens");
    jdbc.execute("delete from outbox_events");
    jdbc.execute("delete from users");
  }

  @Test
  void loginLimitsAllAttemptsAndForgedIpCannotOpenAnotherBucket() {
    for (int i = 0; i < 20; i++) {
      assertThat(post("/login", "{}", "198.51.100." + (i + 1)).statusCode()).isEqualTo(401);
    }
    limited(post("/login", "{}", "203.0.113.7"), 300);
    assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isZero();
  }

  @Test
  void trustedGatewayHostnameSeparatesClientsAndCanonicalizesIpv6() {
    // Real Redis; missing credentials stop before any auth/response dependencies are needed.
    var controller = new AuthController(null, null, null, null, limits, "localhost");
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Client-IP", "2001:db8::7");
    var credentials = new AuthController.LoginRequest(null, null);
    for (int i = 0; i < 20; i++) {
      assertThatThrownBy(() -> controller.login(credentials, request))
          .isInstanceOf(AuthService.InvalidCredentialsException.class);
    }
    request.removeHeader("X-Client-IP");
    request.addHeader("X-Client-IP", "2001:db8:0:0:0:0:0:7");
    assertThatThrownBy(() -> controller.login(credentials, request)).isInstanceOf(AuthRateLimiter.Limited.class);
    request.removeHeader("X-Client-IP");
    request.addHeader("X-Client-IP", "203.0.113.8");
    assertThatThrownBy(() -> controller.login(credentials, request))
        .isInstanceOf(AuthService.InvalidCredentialsException.class);
    // Ambiguous headers use the socket peer, even when the peer is trusted.
    request.addHeader("X-Client-IP", "2001:db8::7");
    for (int i = 0; i < 20; i++) {
      assertThatThrownBy(() -> controller.login(credentials, request))
          .isInstanceOf(AuthService.InvalidCredentialsException.class);
    }
    assertThatThrownBy(() -> controller.login(credentials, request)).isInstanceOf(AuthRateLimiter.Limited.class);
    assertThat(counters.keys("user:rate:login:*")).hasSize(3);
  }

  @Test
  void loginWindowExpiresAndBlockedAttemptsDoNotExtendIt() {
    for (int i = 0; i < 20; i++) {
      assertThat(post("/login", "{}", "198.51.100.7").statusCode()).isEqualTo(401);
    }
    var keys = counters.keys("user:rate:*");
    assertThat(keys).hasSize(1);
    String key = keys.iterator().next();
    assertThat(counters.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 300_000L);
    counters.expire(key, Duration.ofSeconds(2));
    limited(post("/login", "{}", "198.51.100.7"), 2);
    assertThat(counters.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 2_000L);
    await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(counters.hasKey(key)));
    assertThat(post("/login", "{}", "198.51.100.7").statusCode()).isEqualTo(401);
  }

  @Test
  void concurrentLoginRequestsCannotExceedTwentyAdmissions() {
    List<CompletableFuture<Integer>> requests = IntStream.range(0, 30)
        .mapToObj(i -> CompletableFuture.supplyAsync(() -> post("/login", "{}", "198.51.100.7").statusCode()))
        .toList();
    var statuses = requests.stream().map(CompletableFuture::join).toList();
    assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(20);
    assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(10);
  }

  @Test
  void forgotEmailQuotaNormalizesEmailAndDoesNotExposeItsExistence() {
    register("known@example.test");
    for (String email : List.of("known@example.test", "unknown@example.test")) {
      for (int i = 0; i < 5; i++) {
        assertThat(forgot(email).statusCode()).isEqualTo(202);
      }
      var blocked = forgot("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ");
      limited(blocked, 3600);
      assertThat(blocked.body()).doesNotContain(email, "known", "unknown");
    }
    assertThat(jdbc.queryForObject("select count(*) from user_action_tokens", Integer.class)).isEqualTo(5);
    assertThat(counters.keys("user:rate:*")).allSatisfy(key ->
        assertThat(key).doesNotContain("@", "known", "unknown", "127.0.0.1"));
  }

  @Test
  void forgotIpQuotaBlocksTheTwentyFirstDistinctEmail() {
    for (int i = 0; i < 20; i++) {
      assertThat(forgot("unknown-" + i + "@example.test").statusCode()).isEqualTo(202);
    }
    limited(forgot("another@example.test"), 3600);
    assertThat(jdbc.queryForObject("select count(*) from user_action_tokens", Integer.class)).isZero();
  }

  private void limited(HttpResponse<String> response, int maxSeconds) {
    assertThat(response.statusCode()).isEqualTo(429);
    JsonNode body = json.readTree(response.body());
    assertThat(body.path("code").asText()).isEqualTo("RATE_LIMITED");
    assertThat(body.path("errors").isEmpty()).isTrue();
    assertThat(body.path("metadata").path("request_id").asText()).isNotBlank();
    assertThat(body.path("metadata").path("trace_id").asText()).isNotBlank();
    assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()))
        .isBetween(1, maxSeconds);
  }

  private void register(String email) {
    assertThat(post("/register", json.writeValueAsString(java.util.Map.of("email", email,
        "password", "Synthetic-pass-1", "full_name", "Synthetic Person", "locale", "vi")), "198.51.100.7") // gitleaks:allow synthetic fixture password
        .statusCode()).isEqualTo(201);
  }

  private HttpResponse<String> forgot(String email) {
    return post("/password/forgot", json.writeValueAsString(java.util.Map.of("email", email)), "198.51.100.7");
  }

  private HttpResponse<String> post(String path, String body, String forgedIp) {
    try {
      return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth" + path))
          .header("Content-Type", "application/json").header("X-Client-IP", forgedIp)
          .header("X-Forwarded-For", forgedIp).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static KeyPair keyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
