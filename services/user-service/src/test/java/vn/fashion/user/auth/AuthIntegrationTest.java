package vn.fashion.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** TASK:USR-01a · REQ: USR-01/03/05, USR-07 (03 section 2.1, 05 section 4, 06 section 2.1). */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("users")
      .withUsername("postgres")
      .withPassword("postgres")
      .withInitScript("user-test-init.sql");

  static final KeyPair SIGNING_KEY = ecKeyPair();
  static final String PASSWORD = "Synthetic-pass-1"; // gitleaks:allow synthetic test password

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
  }

  @BeforeEach
  void clean() {
    jdbc.execute("delete from refresh_tokens");
    jdbc.execute("delete from outbox_events");
    jdbc.execute("delete from users");
  }

  @Test
  void registerReturnsAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
    var response = register("person@example.test");

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode data = body(response).path("data");
    assertThat(data.path("user").path("email").asText()).isEqualTo("person@example.test");
    assertThat(data.path("token_type").asText()).isEqualTo("Bearer");
    assertThat(data.path("expires_in").asInt()).isEqualTo(900);
    assertThat(data.has("refresh_token")).as("refresh token never in JSON").isFalse();

    var jwt = SignedJWT.parse(data.path("access_token").asText());
    assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
    assertThat(jwt.getHeader().getKeyID()).isEqualTo("user-test");
    assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) SIGNING_KEY.getPublic()))).isTrue();
    var claims = jwt.getJWTClaimsSet();
    assertThat(claims.getIssuer()).isEqualTo("user-service");
    assertThat(claims.getAudience()).containsExactly("fashion-api");
    assertThat(claims.getSubject()).isEqualTo(data.path("user").path("id").asText());
    assertThat(claims.getLongClaim("auth_version")).isZero();
    assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(900_000);

    String cookie = setCookie(response);
    assertThat(cookie).startsWith("refresh_token=").contains("HttpOnly").contains("Secure")
        .contains("SameSite=Strict").contains("Path=/api/v1/auth").contains("Max-Age=2592000");
  }

  @Test
  void emailIsNormalizedAndDuplicateRegistrationConflicts() {
    assertThat(register("  Person@Example.TEST ").statusCode()).isEqualTo(201);
    var duplicate = register("person@example.test");

    assertThat(duplicate.statusCode()).isEqualTo(409);
    assertThat(body(duplicate).path("code").asText()).isEqualTo("CONFLICT");
    assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo("person@example.test");
  }

  @Test
  void concurrentCaseInsensitiveRegistrationCreatesOneAccount() {
    var first = CompletableFuture.supplyAsync(() -> register("Race@Example.test"));
    var second = CompletableFuture.supplyAsync(() -> register("race@example.TEST"));
    var statuses = List.of(first.join().statusCode(), second.join().statusCode());

    assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
  }

  @Test
  void invalidRegistrationIsRejectedWithFieldErrors() {
    var shortPassword = post("/api/v1/auth/register",
        "{\"email\":\"a@example.test\",\"password\":\"short\",\"full_name\":\"A\",\"locale\":\"vi\"}", null);
    var badEmail = post("/api/v1/auth/register",
        "{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\",\"full_name\":\"A\",\"locale\":\"vi\"}", null);
    var badLocale = post("/api/v1/auth/register",
        "{\"email\":\"b@example.test\",\"password\":\"" + PASSWORD + "\",\"full_name\":\"A\",\"locale\":\"fr\"}", null);
    var tooLong = post("/api/v1/auth/register",
        "{\"email\":\"c@example.test\",\"password\":\"" + "x".repeat(73) + "\",\"full_name\":\"A\",\"locale\":\"vi\"}", null);

    assertThat(fieldError(shortPassword)).isEqualTo("password");
    assertThat(fieldError(badEmail)).isEqualTo("email");
    assertThat(fieldError(badLocale)).isEqualTo("locale");
    assertThat(fieldError(tooLong)).isEqualTo("password");
    assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
  }

  @Test
  void passwordIsStoredAsBcryptWithCostAtLeastTen() {
    register("hash@example.test");

    String hash = jdbc.queryForObject("select password_hash from users", String.class);
    assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
    assertThat(Integer.parseInt(hash.split("\\$")[2])).isGreaterThanOrEqualTo(10);
  }

  @Test
  void registrationWritesUserCreatedToTheOutboxWithoutContactData() throws Exception {
    String userId = body(register("event@example.test")).path("data").path("user").path("id").asText();

    var row = jdbc.queryForMap("select event_type, topic, partition_key, aggregate_id, payload::text as payload from outbox_events");
    assertThat(row).containsEntry("event_type", "USER_CREATED").containsEntry("topic", "user.events")
        .containsEntry("partition_key", userId).containsEntry("aggregate_id", userId);
    JsonNode payload = json.readTree((String) row.get("payload"));
    assertThat(payload.propertyNames()).containsExactlyInAnyOrder("user_id", "locale");
    assertThat(payload.path("user_id").asText()).isEqualTo(userId);
  }

  @Test
  void wrongPasswordAndUnknownEmailGetTheSameGenericError() {
    register("login@example.test");

    var wrong = login("login@example.test", "Wrong-pass-123");
    var unknown = login("nobody@example.test", PASSWORD);

    assertThat(wrong.statusCode()).isEqualTo(401);
    assertThat(unknown.statusCode()).isEqualTo(401);
    assertThat(body(wrong).path("message").asText()).isEqualTo("INVALID_CREDENTIALS")
        .isEqualTo(body(unknown).path("message").asText());
  }

  @Test
  void loginWithCorrectPasswordReturnsTokenAndCookie() {
    register("ok@example.test");
    var response = login("OK@example.test", PASSWORD);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(body(response).path("data").path("access_token").asText()).isNotBlank();
    assertThat(setCookie(response)).startsWith("refresh_token=");
  }

  @Test
  void fiveFailedLoginsLockTheAccountForFifteenMinutes() {
    register("lock@example.test");
    for (int i = 0; i < 5; i++) {
      assertThat(login("lock@example.test", "Wrong-pass-123").statusCode()).isEqualTo(401);
    }

    var lockedWithCorrectPassword = login("lock@example.test", PASSWORD);
    assertThat(lockedWithCorrectPassword.statusCode()).isEqualTo(401);
    assertThat(body(lockedWithCorrectPassword).path("message").asText()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(jdbc.queryForObject(
        "select locked_until > now() + interval '14 minutes' from users", Boolean.class)).isTrue();

    jdbc.update("update users set locked_until = now() - interval '1 second'");
    assertThat(login("lock@example.test", PASSWORD).statusCode()).isEqualTo(200);
    assertThat(jdbc.queryForObject("select failed_login_attempts from users", Integer.class)).isZero();
  }

  @Test
  void refreshRotatesAndReplayOfTheOldTokenRevokesTheFamily() {
    String first = refreshToken(setCookie(register("rotate@example.test")));

    var rotated = refresh(first);
    assertThat(rotated.statusCode()).isEqualTo(200);
    assertThat(body(rotated).path("data").path("access_token").asText()).isNotBlank();
    String second = refreshToken(setCookie(rotated));
    assertThat(second).isNotEqualTo(first);

    assertThat(refresh(first).statusCode()).as("replay of a rotated token").isEqualTo(401);
    assertThat(refresh(second).statusCode()).as("family revoked after replay").isEqualTo(401);
  }

  @Test
  void refreshResponseCarriesOnlyTheTokenFields() {
    var rotated = refresh(refreshToken(setCookie(register("shape@example.test"))));

    assertThat(body(rotated).path("data").propertyNames())
        .containsExactlyInAnyOrder("access_token", "token_type", "expires_in");
  }

  @Test
  void logoutDuringARotationAlsoRevokesTheRotatedToken() throws Exception {
    String token = refreshToken(setCookie(register("race-logout@example.test")));

    try (var pause = new InsertPause()) {
      var rotation = CompletableFuture.supplyAsync(() -> refresh(token));
      pause.awaitWaiters(1);
      var logout = CompletableFuture.supplyAsync(() -> post("/api/v1/auth/logout", "", "refresh_token=" + token));
      pause.awaitWaiters(2);
      pause.release();

      String rotated = refreshToken(setCookie(rotation.join()));
      assertThat(logout.join().statusCode()).isEqualTo(204);
      assertThat(refresh(rotated).statusCode()).as("descendant of a logged-out family").isEqualTo(401);
    }
  }

  @Test
  void replayOfAPredecessorDuringARotationRevokesTheNewToken() throws Exception {
    String first = refreshToken(setCookie(register("race-replay@example.test")));
    String second = refreshToken(setCookie(refresh(first)));

    try (var pause = new InsertPause()) {
      var rotation = CompletableFuture.supplyAsync(() -> refresh(second));
      pause.awaitWaiters(1);
      var replay = CompletableFuture.supplyAsync(() -> refresh(first));
      pause.awaitWaiters(2);
      pause.release();

      String third = refreshToken(setCookie(rotation.join()));
      assertThat(replay.join().statusCode()).isEqualTo(401);
      assertThat(refresh(third).statusCode()).as("descendant of a replayed family").isEqualTo(401);
    }
  }

  /**
   * Pauses the next refresh_tokens insert (i.e. a rotation that already locked its token) until
   * released, via a test-only trigger created with the container superuser.
   */
  static final class InsertPause implements AutoCloseable {
    private static final long KEY = 7_421_337L;
    private final java.sql.Connection admin;
    private boolean released;

    InsertPause() throws java.sql.SQLException {
      admin = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres");
      try (var statement = admin.createStatement()) {
        statement.execute("""
            create or replace function test_pause_insert() returns trigger language plpgsql as $$
            begin perform pg_advisory_lock(%d); perform pg_advisory_unlock(%d); return new; end $$
            """.formatted(KEY, KEY));
        statement.execute("drop trigger if exists test_pause on refresh_tokens");
        statement.execute("create trigger test_pause before insert on refresh_tokens "
            + "for each row execute function test_pause_insert()");
        statement.execute("select pg_advisory_lock(" + KEY + ")");
      }
    }

    void awaitWaiters(int count) throws Exception {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        try (var statement = admin.createStatement();
             var rs = statement.executeQuery("select count(*) from pg_locks where not granted")) {
          rs.next();
          if (rs.getInt(1) >= count) {
            return;
          }
        }
        Thread.sleep(50);
      }
      throw new AssertionError("expected " + count + " sessions waiting on a lock");
    }

    void release() throws java.sql.SQLException {
      try (var statement = admin.createStatement()) {
        statement.execute("select pg_advisory_unlock(" + KEY + ")");
      }
      released = true;
    }

    @Override
    public void close() throws java.sql.SQLException {
      if (!released) {
        release();
      }
      try (var statement = admin.createStatement()) {
        statement.execute("drop trigger if exists test_pause on refresh_tokens");
      }
      admin.close();
    }
  }

  @Test
  void concurrentRefreshWithTheSameTokenSucceedsAtMostOnce() {
    String token = refreshToken(setCookie(register("parallel@example.test")));

    var results = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < 4; i++) {
      results.add(CompletableFuture.supplyAsync(() -> refresh(token)));
    }

    assertThat(results.stream().map(CompletableFuture::join).filter(r -> r.statusCode() == 200)).hasSizeLessThanOrEqualTo(1);
  }

  @Test
  void expiredOrMissingRefreshTokenIsRejected() {
    String token = refreshToken(setCookie(register("expired@example.test")));
    jdbc.update("update refresh_tokens set expires_at = now() - interval '1 second'");

    assertThat(refresh(token).statusCode()).isEqualTo(401);
    assertThat(post("/api/v1/auth/refresh", "", null).statusCode()).isEqualTo(401);
  }

  @Test
  void logoutRevokesTheFamilyAndClearsTheCookie() {
    String token = refreshToken(setCookie(register("logout@example.test")));

    var logout = post("/api/v1/auth/logout", "", "refresh_token=" + token);

    assertThat(logout.statusCode()).isEqualTo(204);
    assertThat(setCookie(logout)).startsWith("refresh_token=;").contains("Max-Age=0");
    assertThat(refresh(token).statusCode()).isEqualTo(401);
  }

  @Test
  void refreshTokensAreStoredOnlyAsSha256Hashes() {
    String token = refreshToken(setCookie(register("stored@example.test")));

    String hash = jdbc.queryForObject("select token_hash from refresh_tokens", String.class);
    assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(token);
  }

  @Test
  void logsNeverContainPasswordsOrTokens(CapturedOutput output) {
    var registered = register("logs@example.test");
    String token = refreshToken(setCookie(registered));
    refresh(token);
    login("logs@example.test", "Wrong-pass-123");

    assertThat(output.getAll()).doesNotContain(PASSWORD, "Wrong-pass-123", token) // gitleaks:allow synthetic test passwords
        .doesNotContain(body(registered).path("data").path("access_token").asText());
  }

  @Test
  void runtimeRoleCannotCreateTables() {
    assertThatThrownBy(() -> jdbc.execute("create table forbidden(id bigint)"))
        .hasStackTraceContaining("permission denied");
  }

  private HttpResponse<String> register(String email) {
    return post("/api/v1/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
        + "\",\"full_name\":\"Synthetic Person\",\"locale\":\"vi\"}", null);
  }

  private HttpResponse<String> login(String email, String password) {
    return post("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null);
  }

  private HttpResponse<String> refresh(String token) {
    return post("/api/v1/auth/refresh", "", "refresh_token=" + token);
  }

  private HttpResponse<String> post(String path, String jsonBody, String cookie) {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    if (cookie != null) {
      request.header("Cookie", cookie);
    }
    try {
      return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private JsonNode body(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private String fieldError(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode body = body(response);
    assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
    return body.path("errors").get(0).path("field").asText();
  }

  private static String setCookie(HttpResponse<String> response) {
    return response.headers().firstValue("Set-Cookie").orElseThrow(() -> new AssertionError("no Set-Cookie"));
  }

  private static String refreshToken(String setCookie) {
    return setCookie.substring("refresh_token=".length(), setCookie.indexOf(';'));
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
