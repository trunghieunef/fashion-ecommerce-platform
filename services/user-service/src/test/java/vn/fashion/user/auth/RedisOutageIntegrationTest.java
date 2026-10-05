package vn.fashion.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TASK:USR-01 part 1b-i: Redis failing after a connection exists (paused container: command
 * timeouts) or failing only during cleanup. Committed outcomes stand; nothing reveals accounts.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisOutageIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("users")
      .withUsername("postgres")
      .withPassword("postgres")
      .withInitScript("user-test-init.sql");

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>("redis:8.2.9")
      .withExposedPorts(6379)
      .withCommand("redis-server", "--requirepass", "redis_test"); // gitleaks:allow synthetic test password

  static final KeyPair SIGNING_KEY = ecKeyPair();
  static final String PASSWORD = "Synthetic-pass-1"; // gitleaks:allow synthetic test password
  static final String NEW_PASSWORD = "Synthetic-pass-2"; // gitleaks:allow synthetic test password

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ObjectMapper json;

  @MockitoSpyBean
  private SecretVault vault;

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "user_runtime");
    registry.add("spring.datasource.password", () -> "user_runtime_test");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "user_migration");
    registry.add("spring.flyway.password", () -> "user_migration_test");
    registry.add("USER_JWT_PRIVATE_KEY",
        () -> Base64.getEncoder().encodeToString(SIGNING_KEY.getPrivate().getEncoded()));
    registry.add("USER_JWT_KEY_ID", () -> "user-test");
    registry.add("USER_JWT_PUBLIC_KEYS",
        () -> "user-test:" + Base64.getEncoder().encodeToString(SIGNING_KEY.getPublic().getEncoded()));
    registry.add("USER_REDIS_HOST", redis::getHost);
    registry.add("USER_REDIS_PORT", () -> redis.getMappedPort(6379));
    registry.add("USER_REDIS_PASSWORD", () -> "redis_test");
  }

  @BeforeEach
  void clean() {
    reset(vault);
    jdbc.execute("delete from user_action_tokens");
    jdbc.execute("delete from refresh_tokens");
    jdbc.execute("delete from outbox_events");
    jdbc.execute("delete from users");
  }

  @Test
  void resetSucceedsWhenRedisIsDownAfterTheTokenWasIssued() {
    register("reset-down@example.test");
    assertThat(forgot("reset-down@example.test").statusCode()).isEqualTo(202);
    UUID challengeId = jdbc.queryForObject("select id from user_action_tokens", UUID.class);
    String secret = vault.get(challengeId).orElseThrow().value();

    HttpResponse<String> reset;
    pauseRedis();
    try {
      reset = post("/api/v1/auth/password/reset", "{\"token\":\"" + secret + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}");
    } finally {
      unpauseRedis();
    }

    assertThat(reset.statusCode()).as("the password changed; cleanup is best effort").isEqualTo(204);
    assertThat(login("reset-down@example.test", NEW_PASSWORD).statusCode()).isEqualTo(200);
  }

  @Test
  void forgotKeepsItsCommittedResultWhenCleanupFails() {
    register("cleanup@example.test");
    forgot("cleanup@example.test");
    doThrow(new RedisConnectionFailureException("synthetic outage")).when(vault).delete(any());

    var second = forgot("cleanup@example.test");

    assertThat(second.statusCode()).isEqualTo(202);
    assertThat(jdbc.queryForObject("select count(*) from user_action_tokens where used_at is null", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'NOTIFY_RESET_PASSWORD'",
        Integer.class)).isEqualTo(2);
  }

  @Test
  void forgotAnswersAlikeForEveryEmailWhileRedisIsDown() {
    register("known@example.test");
    register("inactive@example.test");
    jdbc.update("update users set status = 'INACTIVE' where email = 'inactive@example.test'");
    forgot("known@example.test"); // the connection exists before the outage: commands time out

    HttpResponse<String> known;
    HttpResponse<String> unknown;
    HttpResponse<String> inactive;
    pauseRedis();
    try {
      known = forgot("known@example.test");
      unknown = forgot("nobody@example.test");
      inactive = forgot("inactive@example.test");
    } finally {
      unpauseRedis();
    }

    for (var response : java.util.List.of(known, unknown, inactive)) {
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(body(response).path("code").asText()).isEqualTo("TEMPORARILY_UNAVAILABLE");
      assertThat(body(response).path("message").asText()).isEqualTo(body(unknown).path("message").asText());
    }
    assertThat(jdbc.queryForObject("select count(*) from user_action_tokens", Integer.class))
        .as("only the token issued before the outage").isEqualTo(1);
  }

  private static void pauseRedis() {
    redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
  }

  private static void unpauseRedis() {
    redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
  }

  private void register(String email) {
    var response = post("/api/v1/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
        + "\",\"full_name\":\"Synthetic Person\",\"locale\":\"vi\"}");
    assertThat(response.statusCode()).isEqualTo(201);
  }

  private HttpResponse<String> forgot(String email) {
    return post("/api/v1/auth/password/forgot", "{\"email\":\"" + email + "\"}");
  }

  private HttpResponse<String> login(String email, String password) {
    return post("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
  }

  private HttpResponse<String> post(String path, String jsonBody) {
    try {
      return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private JsonNode body(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private static KeyPair ecKeyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
