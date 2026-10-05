package vn.fashion.user.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** TASK:USR-02 part 2b · REQ: USR-08, ADM-06 (03 section 3, 05 section 4, 06 section 2.1, 13 section 2). */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminIntegrationTest {
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

  @Autowired
  private AdminBootstrap bootstrap;

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
  }

  @BeforeEach
  void clean() {
    // audit_logs is append-only for the runtime role; the superuser clears it between tests.
    try (var admin = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres");
         var statement = admin.createStatement()) {
      statement.execute("truncate audit_logs, idempotency_requests, user_roles, user_addresses, user_action_tokens, "
          + "refresh_tokens, outbox_events, users");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void seededRolesGrantThePermissionMatrixOf13() {
    var matrix = new TreeMap<String, Set<String>>();
    jdbc.query("""
            select r.code as role, p.code as permission from roles r
            join role_permissions rp on rp.role_id = r.id join permissions p on p.id = rp.permission_id
            """,
        rs -> {
          matrix.computeIfAbsent(rs.getString("role"), k -> new TreeSet<>()).add(rs.getString("permission"));
        });

    assertThat(matrix).isEqualTo(Map.of(
        "SUPER_ADMIN", Set.of("user.manage"),
        "OPS", Set.of("catalog.write", "order.read", "order.fulfill", "order.cancel", "order.return",
            "inventory.read", "inventory.adjust", "shipping.read", "shipping.self_event", "shipping.rules",
            "review.moderate", "report.orders", "report.stock"),
        "FINANCE", Set.of("order.read", "payment.read", "payment.refund", "payment.settle",
            "payment.reconcile", "report.cash"),
        "MARKETING", Set.of("promotion.manage", "notification.template", "notification.campaign")));
  }

  @Test
  void auditLogIsAppendOnlyForTheRuntimeRole() {
    jdbc.update("""
        insert into audit_logs(id, action, resource_type, resource_id, request_id)
        values (gen_random_uuid(), 'test', 'user', 'x', gen_random_uuid())
        """);

    assertThatThrownBy(() -> jdbc.update("update audit_logs set reason = 'edited'"))
        .hasStackTraceContaining("permission denied");
    assertThatThrownBy(() -> jdbc.update("delete from audit_logs"))
        .hasStackTraceContaining("permission denied");
  }

  @Test
  void bootstrapGrantsTheFirstSuperAdminOnceWithAnAudit() {
    String first = register("first@example.test");
    register("second@example.test");

    assertThat(bootstrap.grant("missing@example.test")).isFalse();
    assertThat(bootstrap.grant(" First@Example.test ")).isTrue();
    assertThat(bootstrap.grant("second@example.test")).as("a super admin already exists").isFalse();

    assertThat(rolesOf("first@example.test")).containsExactly("SUPER_ADMIN");
    assertThat(rolesOf("second@example.test")).isEmpty();
    var audit = jdbc.queryForMap("select actor_id, action, resource_id, after_data::text as after from audit_logs");
    assertThat(audit.get("actor_id")).as("SYSTEM").isNull();
    assertThat(audit).containsEntry("action", "user.bootstrap_super_admin");
    assertThat((String) audit.get("after")).contains("SUPER_ADMIN");
    assertThat(call("GET", "/api/v1/users/me", first, null, null).statusCode())
        .as("the grant bumps auth_version").isEqualTo(401);
  }

  @Test
  void accessTokensCarryThePermissionsOfTheUsersRoles() {
    register("member@example.test");
    String admin = superAdmin("boss@example.test");

    assertThat(permissions(login("member@example.test"))).isEmpty();
    assertThat(permissions(admin)).containsExactly("user.manage");
  }

  @Test
  void adminUserApiRequiresUserManage() {
    String member = register("member@example.test");
    String admin = superAdmin("boss@example.test");

    assertThat(call("GET", "/admin/api/v1/users", null, null, null).statusCode()).isEqualTo(401);
    var forbidden = call("GET", "/admin/api/v1/users", member, null, null);
    assertThat(forbidden.statusCode()).isEqualTo(403);
    assertThat(body(forbidden).path("code").asText()).isEqualTo("FORBIDDEN");

    var page = call("GET", "/admin/api/v1/users?page=1&size=1", admin, null, null);
    assertThat(page.statusCode()).isEqualTo(200);
    var data = body(page).path("data");
    assertThat(data.path("total").asInt()).isEqualTo(2);
    assertThat(data.path("items")).hasSize(1);
    assertThat(data.path("items").get(0).has("password_hash")).isFalse();
    assertThat(call("GET", "/admin/api/v1/users?size=101", admin, null, null).statusCode()).isEqualTo(400);
  }

  @Test
  void changingRolesBumpsAuthVersionAndIsAudited() {
    String target = register("staff@example.test");
    String admin = superAdmin("boss@example.test");
    UUID id = idOf("staff@example.test");

    var response = call("PUT", "/admin/api/v1/users/" + id + "/roles", admin, null,
        "{\"roles\":[\"OPS\"],\"reason\":\"Joins warehouse team\",\"expected_version\":0}");

    assertThat(response.statusCode()).isEqualTo(200);
    var user = body(response).path("data");
    assertThat(user.path("roles").get(0).asText()).isEqualTo("OPS");
    assertThat(user.path("version").asLong()).isEqualTo(1);
    assertThat(call("GET", "/api/v1/users/me", target, null, null).statusCode())
        .as("old token after the role change").isEqualTo(401);
    assertThat(permissions(login("staff@example.test"))).contains("catalog.write").doesNotContain("payment.refund");

    var audit = jdbc.queryForMap("""
        select actor_id, action, reason, request_id::text as request_id,
               before_data::text as before, after_data::text as after
        from audit_logs where action = 'user.roles_changed'
        """);
    assertThat(audit.get("actor_id")).isEqualTo(idOf("boss@example.test"));
    assertThat(audit).containsEntry("reason", "Joins warehouse team")
        .containsEntry("request_id", body(response).path("metadata").path("request_id").asText());
    assertThat((String) audit.get("before")).contains("[]");
    assertThat((String) audit.get("after")).contains("OPS");
  }

  @Test
  void roleChangeIsValidatedAndVersionGuarded() {
    register("staff@example.test");
    String admin = superAdmin("boss@example.test");
    String path = "/admin/api/v1/users/" + idOf("staff@example.test") + "/roles";

    assertThat(call("PUT", path, admin, null, "{\"roles\":[\"OPS\"],\"reason\":\"r\",\"expected_version\":7}")
        .statusCode()).isEqualTo(409);
    assertThat(body(call("PUT", path, admin, null, "{\"roles\":[\"OPS\"],\"reason\":\"r\",\"expected_version\":7}"))
        .path("code").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(fields(call("PUT", path, admin, null, "{\"roles\":[\"ROOT\"],\"expected_version\":0}")))
        .containsExactlyInAnyOrder("roles", "reason");
    assertThat(fields(call("PUT", path, admin, null, "{\"roles\":[],\"reason\":\"r\"}")))
        .containsExactly("expected_version");
    assertThat(fields(call("PUT", path, admin, null, "{\"roles\":[],\"reason\":\"r\",\"expected_version\":-1}")))
        .as("negative version even when the roles are unchanged").containsExactly("expected_version");
    assertThat(fields(call("POST", "/admin/api/v1/users/" + idOf("staff@example.test") + "/lock", admin, "neg",
        "{\"reason\":\"r\",\"expected_version\":-1}"))).containsExactly("expected_version");
    assertThat(call("PUT", "/admin/api/v1/users/" + UUID.randomUUID() + "/roles", admin, null,
        "{\"roles\":[],\"reason\":\"r\",\"expected_version\":0}").statusCode()).isEqualTo(404);
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'user.roles_changed'",
        Integer.class)).isZero();
  }

  @Test
  void anAdminCannotChangeOrLockThemselves() {
    String admin = superAdmin("boss@example.test");
    UUID self = idOf("boss@example.test");

    var roles = call("PUT", "/admin/api/v1/users/" + self + "/roles", admin, null,
        "{\"roles\":[],\"reason\":\"oops\",\"expected_version\":1}");
    var lock = call("POST", "/admin/api/v1/users/" + self + "/lock", admin, "k-self",
        "{\"reason\":\"oops\",\"expected_version\":1}");

    assertThat(roles.statusCode()).isEqualTo(403);
    assertThat(lock.statusCode()).isEqualTo(403);
    assertThat(rolesOf("boss@example.test")).containsExactly("SUPER_ADMIN");
  }

  @Test
  void lockingRevokesSessionsAndReplaysByIdempotencyKey() {
    String target = register("staff@example.test");
    String admin = superAdmin("boss@example.test");
    String path = "/admin/api/v1/users/" + idOf("staff@example.test") + "/lock";
    String lockBody = "{\"reason\":\"Suspicious activity\",\"expected_version\":0}";

    assertThat(call("POST", path, admin, null, lockBody).statusCode()).as("Idempotency-Key required").isEqualTo(400);
    var first = call("POST", path, admin, "lock-1", lockBody);
    var replay = call("POST", path, admin, "lock-1", lockBody);
    var otherBody = call("POST", path, admin, "lock-1", "{\"reason\":\"Different\",\"expected_version\":0}");

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(body(first).path("data").path("status").asText()).isEqualTo("LOCKED");
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(body(replay).path("data")).isEqualTo(body(first).path("data"));
    assertThat(otherBody.statusCode()).isEqualTo(409);
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'user.locked'", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null and user_id = ?",
        Integer.class, idOf("staff@example.test"))).as("sessions of the locked user").isZero();
    assertThat(call("GET", "/api/v1/users/me", target, null, null).statusCode()).isEqualTo(401);
    assertThat(call("POST", "/api/v1/auth/login", null, null,
        "{\"email\":\"staff@example.test\",\"password\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(401);
  }

  @Test
  void lockDuringARefreshRotationAlsoRevokesTheRotatedToken() throws Exception {
    var registered = call("POST", "/api/v1/auth/register", null, null, registration("staff@example.test"));
    String cookie = refreshCookie(registered);
    String admin = superAdmin("boss@example.test");
    String path = "/admin/api/v1/users/" + idOf("staff@example.test") + "/lock";

    try (var pause = new InsertPause()) {
      var rotation = CompletableFuture.supplyAsync(() -> refresh(cookie));
      pause.awaitWaiters(1);
      var lock = CompletableFuture.supplyAsync(() ->
          call("POST", path, admin, "race", "{\"reason\":\"Race\",\"expected_version\":0}"));
      pause.awaitWaiters(2);
      pause.release();

      var rotated = rotation.join();
      assertThat(lock.join().statusCode()).isEqualTo(200);
      assertThat(rotated.statusCode()).as("the rotation read the account before the lock").isEqualTo(200);
      assertThat(refresh(refreshCookie(rotated)).statusCode()).as("token rotated during the lock").isEqualTo(401);
    }
  }

  private String superAdmin(String email) {
    register(email);
    assertThat(bootstrap.grant(email)).isTrue();
    return login(email);
  }

  private String register(String email) {
    var response = call("POST", "/api/v1/auth/register", null, null, registration(email));
    assertThat(response.statusCode()).isEqualTo(201);
    return body(response).path("data").path("access_token").asText();
  }

  private static String registration(String email) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
        + "\",\"full_name\":\"Synthetic Person\",\"locale\":\"vi\"}";
  }

  private String login(String email) {
    var response = call("POST", "/api/v1/auth/login", null, null,
        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    return body(response).path("data").path("access_token").asText();
  }

  private HttpResponse<String> refresh(String cookie) {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/refresh"))
        .header("Cookie", "refresh_token=" + cookie).POST(HttpRequest.BodyPublishers.noBody());
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

  private List<String> permissions(String accessToken) {
    String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
    var codes = new ArrayList<String>();
    json.readTree(payload).path("permissions").forEach(code -> codes.add(code.asText()));
    return codes;
  }

  private List<String> rolesOf(String email) {
    return jdbc.queryForList("""
        select r.code from user_roles ur join roles r on r.id = ur.role_id
        join users u on u.id = ur.user_id where u.email = ?
        """, String.class, email);
  }

  private UUID idOf(String email) {
    return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
  }

  private List<String> fields(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(400);
    var names = new ArrayList<String>();
    body(response).path("errors").forEach(e -> names.add(e.path("field").asText()));
    return names;
  }

  private HttpResponse<String> call(String method, String path, String token, String idempotencyKey,
                                    String jsonBody) {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
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

  /** Pauses the next refresh_tokens insert until released (same technique as AuthIntegrationTest). */
  static final class InsertPause implements AutoCloseable {
    private static final long KEY = 7_421_338L;
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
