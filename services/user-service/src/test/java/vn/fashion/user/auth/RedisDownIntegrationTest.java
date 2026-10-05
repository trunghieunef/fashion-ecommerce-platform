package vn.fashion.user.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** TASK:USR-01 part 1b-i: Redis unavailable means forgot fails closed with nothing queued. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisDownIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("users")
      .withUsername("postgres")
      .withPassword("postgres")
      .withInitScript("user-test-init.sql");

  static final KeyPair SIGNING_KEY = ecKeyPair();

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ObjectMapper json;

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
    // Nothing listens on port 1: every Redis call fails to connect.
    registry.add("USER_REDIS_HOST", () -> "127.0.0.1");
    registry.add("USER_REDIS_PORT", () -> 1);
  }

  @Test
  void forgotPasswordFailsClosedWithoutQueueingAnything() {
    var registered = post("/api/v1/auth/register", "{\"email\":\"down@example.test\",\"password\":\""
        + "Synthetic-pass-1\",\"full_name\":\"Synthetic Person\",\"locale\":\"vi\"}"); // gitleaks:allow synthetic
    assertThat(registered.statusCode()).isEqualTo(201);

    var response = post("/api/v1/auth/password/forgot", "{\"email\":\"down@example.test\"}");

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("TEMPORARILY_UNAVAILABLE");
    assertThat(jdbc.queryForObject("select count(*) from user_action_tokens", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from outbox_events where event_type = 'NOTIFY_RESET_PASSWORD'", Integer.class)).isZero();
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
