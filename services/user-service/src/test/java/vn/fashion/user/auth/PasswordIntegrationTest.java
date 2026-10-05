package vn.fashion.user.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.security.ServiceTokenIssuer;

/** TASK:USR-01 part 1b-i · REQ: USR-03, USR-05 (03 sections 2.1/4/5, 05 section 4, 06 section 2.1). */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordIntegrationTest {
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
  static final KeyPair NOTIFICATION_KEY = ecKeyPair();
  static final KeyPair STRANGER_KEY = ecKeyPair();
  static final String PASSWORD = "Synthetic-pass-1"; // gitleaks:allow synthetic test password
  static final String NEW_PASSWORD = "Synthetic-pass-2"; // gitleaks:allow synthetic test password

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private StringRedisTemplate redisTemplate;

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
    registry.add("USER_REDIS_HOST", redis::getHost);
    registry.add("USER_REDIS_PORT", () -> redis.getMappedPort(6379));
    registry.add("USER_REDIS_PASSWORD", () -> "redis_test");
    registry.add("USER_INTERNAL_CALLERS", () -> "notification-service:"
        + Base64.getEncoder().encodeToString(NOTIFICATION_KEY.getPublic().getEncoded())
        + ",catalog-service:" + Base64.getEncoder().encodeToString(STRANGER_KEY.getPublic().getEncoded()));
  }

  @BeforeEach
  void clean() {
    jdbc.execute("delete from user_action_tokens");
    jdbc.execute("delete from refresh_tokens");
    jdbc.execute("delete from outbox_events");
    jdbc.execute("delete from users");
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Test
  void changePasswordNeedsTheCurrentPasswordAndRevokesEverySession() {
    var registered = register("change@example.test");
    String access = body(registered).path("data").path("access_token").asText();
    String cookie = refreshCookie(registered);

    assertThat(post("/api/v1/auth/password/change", null, "{\"current_password\":\"x\",\"new_password\":\""
        + NEW_PASSWORD + "\"}").statusCode()).as("no token").isEqualTo(401);
    var wrong = post("/api/v1/auth/password/change", access,
        "{\"current_password\":\"Wrong-pass-123\",\"new_password\":\"" + NEW_PASSWORD + "\"}");
    assertThat(fields(wrong)).containsExactly("current_password");
    assertThat(jdbc.queryForObject("select failed_login_attempts from users", Integer.class))
        .as("a wrong current password counts toward the lockout").isEqualTo(1);
    assertThat(fields(post("/api/v1/auth/password/change", access,
        "{\"current_password\":\"" + PASSWORD + "\",\"new_password\":\"short\"}"))).containsExactly("new_password");

    var changed = post("/api/v1/auth/password/change", access,
        "{\"current_password\":\"" + PASSWORD + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}");

    assertThat(changed.statusCode()).isEqualTo(204);
    assertThat(changed.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
    assertThat(get("/api/v1/users/me", access).statusCode()).as("access token after the change").isEqualTo(401);
    assertThat(refresh(cookie).statusCode()).as("refresh token after the change").isEqualTo(401);
    assertThat(login("change@example.test", PASSWORD).statusCode()).isEqualTo(401);
    assertThat(login("change@example.test", NEW_PASSWORD).statusCode()).isEqualTo(200);
  }

  @Test
  void forgotPasswordAnswersTheSameForUnknownAndKnownEmails() {
    register("known@example.test");

    var unknown = post("/api/v1/auth/password/forgot", null, "{\"email\":\"nobody@example.test\"}");
    var known = post("/api/v1/auth/password/forgot", null, "{\"email\":\" Known@Example.test \"}");

    assertThat(unknown.statusCode()).isEqualTo(202);
    assertThat(known.statusCode()).isEqualTo(202);
    assertThat(body(known).path("data")).isEqualTo(body(unknown).path("data"));
    assertThat(jdbc.queryForObject("select count(*) from user_action_tokens", Integer.class)).isEqualTo(1);
  }

  @Test
  void forgotPasswordStoresOnlyAHashAndQueuesAReferenceNotTheSecret() throws Exception {
    register("queue@example.test");

    post("/api/v1/auth/password/forgot", null, "{\"email\":\"queue@example.test\"}");

    var token = jdbc.queryForMap("""
        select id, purpose, token_hash, target_email,
               expires_at between now() + interval '29 minutes' and now() + interval '31 minutes' as thirty_minutes
        from user_action_tokens
        """);
    assertThat(token).containsEntry("purpose", "RESET_PASSWORD").containsEntry("thirty_minutes", true)
        .containsEntry("target_email", "queue@example.test");
    assertThat((String) token.get("token_hash")).matches("[0-9a-f]{64}");
    String challengeId = token.get("id").toString();

    var event = jdbc.queryForMap("""
        select topic, partition_key, aggregate_id, payload::text as payload from outbox_events
        where event_type = 'NOTIFY_RESET_PASSWORD'
        """);
    assertThat(event).containsEntry("topic", "notification.events")
        .containsEntry("partition_key", "RESET_PASSWORD:" + challengeId);
    JsonNode payload = json.readTree((String) event.get("payload"));
    assertThat(payload.path("challenge_id").asText()).isEqualTo(challengeId);
    assertThat(payload.path("recipient").path("email").asText()).isEqualTo("queue@example.test");
    assertThat(payload.propertyNames()).containsExactlyInAnyOrder("dedupe_key", "user_id", "recipient", "channel",
        "locale", "template_key", "challenge_id", "expires_at");

    String secret = secret(challengeId);
    assertThat((String) event.get("payload")).doesNotContain(secret);
    assertThat(redisTemplate.keys("*")).hasSize(1).allSatisfy(key ->
        assertThat(redisTemplate.opsForValue().get(key)).as("stored encrypted").doesNotContain(secret));
    assertThat(AuthService.sha256(secret)).isEqualTo(token.get("token_hash"));
  }

  @Test
  void onlyTheNotificationServiceCanFetchTheSecretAndNeverCached() {
    register("fetch@example.test");
    post("/api/v1/auth/password/forgot", null, "{\"email\":\"fetch@example.test\"}");
    String challengeId = jdbc.queryForObject("select id::text from user_action_tokens", String.class);
    String path = "/internal/api/v1/users/notification-secrets/" + challengeId;

    assertThat(internal(path, null).statusCode()).isEqualTo(401);
    assertThat(internal(path, serviceToken(STRANGER_KEY, "catalog-service", "user-service")).statusCode())
        .as("valid caller outside the operation allowlist").isEqualTo(403);
    assertThat(internal(path, serviceToken(NOTIFICATION_KEY, "notification-service", "order-service")).statusCode())
        .as("token for another audience").isEqualTo(401);
    assertThat(internal("/internal/api/v1/users/notification-secrets/" + UUID.randomUUID(),
        serviceToken(NOTIFICATION_KEY, "notification-service", "user-service")).statusCode()).isEqualTo(404);

    var ok = internal(path, serviceToken(NOTIFICATION_KEY, "notification-service", "user-service"));
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    var data = body(ok).path("data");
    assertThat(data.path("purpose").asText()).isEqualTo("RESET_PASSWORD");
    assertThat(data.path("challenge_id").asText()).isEqualTo(challengeId);
    assertThat(data.path("secret").asText()).isNotBlank();
  }

  @Test
  void resetConsumesTheTokenOnceAndRevokesEverySession() {
    var registered = register("reset@example.test");
    String access = body(registered).path("data").path("access_token").asText();
    String cookie = refreshCookie(registered);
    post("/api/v1/auth/password/forgot", null, "{\"email\":\"reset@example.test\"}");
    String challengeId = jdbc.queryForObject("select id::text from user_action_tokens", String.class);
    String secret = secret(challengeId);

    assertThat(fields(post("/api/v1/auth/password/reset", null,
        "{\"token\":\"" + secret + "\",\"new_password\":\"short\"}"))).containsExactly("new_password");
    var reset = post("/api/v1/auth/password/reset", null,
        "{\"token\":\"" + secret + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}");

    assertThat(reset.statusCode()).isEqualTo(204);
    assertThat(login("reset@example.test", NEW_PASSWORD).statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/users/me", access).statusCode()).isEqualTo(401);
    assertThat(refresh(cookie).statusCode()).isEqualTo(401);
    assertThat(fields(post("/api/v1/auth/password/reset", null,
        "{\"token\":\"" + secret + "\",\"new_password\":\"Synthetic-pass-3\"}"))) // gitleaks:allow synthetic
        .as("token used twice").containsExactly("token");
    assertThat(internal("/internal/api/v1/users/notification-secrets/" + challengeId,
        serviceToken(NOTIFICATION_KEY, "notification-service", "user-service")).statusCode())
        .as("secret removed after use").isEqualTo(404);
  }

  @Test
  void expiredOrSupersededTokensAreRejected() {
    register("old@example.test");
    post("/api/v1/auth/password/forgot", null, "{\"email\":\"old@example.test\"}");
    String first = secret(jdbc.queryForObject("select id::text from user_action_tokens", String.class));
    post("/api/v1/auth/password/forgot", null, "{\"email\":\"old@example.test\"}");
    String second = secret(jdbc.queryForObject(
        "select id::text from user_action_tokens order by created_at desc limit 1", String.class));

    assertThat(fields(post("/api/v1/auth/password/reset", null,
        "{\"token\":\"" + first + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}")))
        .as("an earlier token is invalidated by a new request").containsExactly("token");
    jdbc.update("update user_action_tokens set expires_at = now() - interval '1 second'");
    assertThat(fields(post("/api/v1/auth/password/reset", null,
        "{\"token\":\"" + second + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}"))).containsExactly("token");
    assertThat(login("old@example.test", PASSWORD).statusCode()).isEqualTo(200);
  }

  @Test
  void logsNeverContainPasswordsOrResetSecrets(CapturedOutput output) {
    register("logs@example.test");
    post("/api/v1/auth/password/forgot", null, "{\"email\":\"logs@example.test\"}");
    String secret = secret(jdbc.queryForObject("select id::text from user_action_tokens", String.class));
    post("/api/v1/auth/password/reset", null, "{\"token\":\"" + secret + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}");

    assertThat(output.getAll()).doesNotContain(secret, NEW_PASSWORD, PASSWORD);
  }

  private String secret(String challengeId) {
    var response = internal("/internal/api/v1/users/notification-secrets/" + challengeId,
        serviceToken(NOTIFICATION_KEY, "notification-service", "user-service"));
    assertThat(response.statusCode()).isEqualTo(200);
    return body(response).path("data").path("secret").asText();
  }

  private static String serviceToken(KeyPair keys, String caller, String audience) {
    return new ServiceTokenIssuer(caller, (ECPublicKey) keys.getPublic(), (ECPrivateKey) keys.getPrivate(),
        Clock.systemUTC()).issue(audience);
  }

  private HttpResponse<String> register(String email) {
    var response = post("/api/v1/auth/register", null, "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
        + "\",\"full_name\":\"Synthetic Person\",\"locale\":\"vi\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return response;
  }

  private HttpResponse<String> login(String email, String password) {
    return post("/api/v1/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
  }

  private HttpResponse<String> refresh(String cookie) {
    return send(HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
        .header("Cookie", "refresh_token=" + cookie).POST(HttpRequest.BodyPublishers.noBody()));
  }

  private HttpResponse<String> get(String path, String bearer) {
    return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + bearer).GET());
  }

  private HttpResponse<String> internal(String path, String serviceToken) {
    var request = HttpRequest.newBuilder(uri(path)).GET();
    if (serviceToken != null) {
      request.header("Authorization", "Bearer " + serviceToken);
    }
    return send(request);
  }

  private HttpResponse<String> post(String path, String bearer, String jsonBody) {
    var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return send(request);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) {
    try {
      return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static String refreshCookie(HttpResponse<String> response) {
    String header = response.headers().firstValue("Set-Cookie").orElseThrow();
    return header.substring("refresh_token=".length(), header.indexOf(';'));
  }

  private java.util.List<String> fields(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(400);
    var names = new java.util.ArrayList<String>();
    body(response).path("errors").forEach(e -> names.add(e.path("field").asText()));
    return names;
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
