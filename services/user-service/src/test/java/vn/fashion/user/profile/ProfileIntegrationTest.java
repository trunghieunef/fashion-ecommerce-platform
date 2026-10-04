package vn.fashion.user.profile;

import static org.assertj.core.api.Assertions.assertThat;

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

/** TASK:USR-02 part 2a · REQ: USR-06, USR-07 (03 section 2.1, 05 section 4, 06 section 2.1). */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileIntegrationTest {
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
  }

  @BeforeEach
  void clean() {
    jdbc.execute("delete from user_addresses");
    jdbc.execute("delete from refresh_tokens");
    jdbc.execute("delete from outbox_events");
    jdbc.execute("delete from users");
  }

  @Test
  void profileRequiresAValidCurrentAccessToken() {
    String token = registerAndToken("me@example.test");

    assertThat(call("GET", "/api/v1/users/me", null, null).statusCode()).isEqualTo(401);
    assertThat(call("GET", "/api/v1/users/me", "garbage", null).statusCode()).isEqualTo(401);
    var me = call("GET", "/api/v1/users/me", token, null);
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(body(me).path("data").path("email").asText()).isEqualTo("me@example.test");

    jdbc.update("update users set auth_version = auth_version + 1");
    assertThat(call("GET", "/api/v1/users/me", token, null).statusCode())
        .as("token minted before auth_version changed").isEqualTo(401);
  }

  @Test
  void lockedOrInactiveAccountIsRejectedEvenWithAFreshToken() {
    String token = registerAndToken("inactive@example.test");
    jdbc.update("update users set status = 'INACTIVE'");

    assertThat(call("GET", "/api/v1/users/me", token, null).statusCode()).isEqualTo(401);
  }

  @Test
  void profileUpdateChangesOnlyNameAndLocale() {
    String token = registerAndToken("update@example.test");

    var updated = call("PUT", "/api/v1/users/me", token,
        "{\"full_name\":\"New Name\",\"locale\":\"en\",\"email\":\"evil@example.test\",\"auth_version\":99,\"roles\":[\"SUPER_ADMIN\"]}");

    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(body(updated).path("data").path("full_name").asText()).isEqualTo("New Name");
    var row = jdbc.queryForMap("select email, locale, auth_version from users");
    assertThat(row).containsEntry("email", "update@example.test").containsEntry("locale", "en")
        .containsEntry("auth_version", 0L);
    assertThat(call("PUT", "/api/v1/users/me", token, "{\"full_name\":\"\",\"locale\":\"fr\"}").statusCode())
        .isEqualTo(400);
  }

  @Test
  void firstAddressBecomesDefaultAndExactlyOneDefaultIsKept() {
    String token = registerAndToken("addr@example.test");

    String first = createAddress(token, "A", false);
    String second = createAddress(token, "B", false);
    assertThat(defaults()).containsExactly(first);

    String third = createAddress(token, "C", true);
    assertThat(defaults()).containsExactly(third);

    var list = body(call("GET", "/api/v1/users/me/addresses", token, null)).path("data");
    assertThat(list).hasSize(3);
    assertThat(list.get(0).path("id").asText()).as("default listed first").isEqualTo(third);
    assertThat(second).isNotBlank();
  }

  @Test
  void concurrentRequestsToSetTheDefaultLeaveExactlyOneDefault() {
    String token = registerAndToken("race@example.test");
    var ids = new ArrayList<String>();
    for (int i = 0; i < 4; i++) {
      ids.add(createAddress(token, "R" + i, false));
    }

    var updates = ids.stream().map(id -> CompletableFuture.supplyAsync(() ->
        call("PUT", "/api/v1/users/me/addresses/" + id, token, addressJson("R", true)))).toList();
    assertThat(updates.stream().map(f -> f.join().statusCode())).as("no request fails under contention")
        .containsOnly(200);
    assertThat(defaults()).hasSize(1);
  }

  @Test
  void deletingTheDefaultPromotesAnotherAddress() {
    String token = registerAndToken("delete@example.test");
    String first = createAddress(token, "A", true);
    String second = createAddress(token, "B", false);

    assertThat(call("DELETE", "/api/v1/users/me/addresses/" + first, token, null).statusCode()).isEqualTo(204);
    assertThat(defaults()).containsExactly(second);
    assertThat(call("DELETE", "/api/v1/users/me/addresses/" + second, token, null).statusCode()).isEqualTo(204);
    assertThat(defaults()).isEmpty();
  }

  @Test
  void theDefaultCannotBeUnsetWithoutChoosingAnother() {
    String token = registerAndToken("unset@example.test");
    String only = createAddress(token, "A", true);

    var response = call("PUT", "/api/v1/users/me/addresses/" + only, token, addressJson("A", false));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(defaults()).containsExactly(only);
  }

  @Test
  void anotherUsersAddressIsNotFound() {
    String owner = registerAndToken("owner@example.test");
    String other = registerAndToken("other@example.test");
    String address = createAddress(owner, "Mine", true);

    assertThat(call("PUT", "/api/v1/users/me/addresses/" + address, other, addressJson("X", true)).statusCode())
        .isEqualTo(404);
    assertThat(call("DELETE", "/api/v1/users/me/addresses/" + address, other, null).statusCode()).isEqualTo(404);
    assertThat(body(call("GET", "/api/v1/users/me/addresses", other, null)).path("data")).isEmpty();
  }

  @Test
  void invalidAddressIsRejectedWithFieldErrors() {
    String token = registerAndToken("invalid@example.test");

    var response = call("POST", "/api/v1/users/me/addresses", token,
        "{\"recipient_name\":\"\",\"phone\":\"123\",\"province_code\":\"79\",\"ward_code\":\"26734\",\"address_line\":\"1 Synthetic St\"}");

    assertThat(response.statusCode()).isEqualTo(400);
    var fields = new ArrayList<String>();
    body(response).path("errors").forEach(e -> fields.add(e.path("field").asText()));
    assertThat(fields).containsExactlyInAnyOrder("recipient_name", "phone");
  }

  private String registerAndToken(String email) {
    var response = call("POST", "/api/v1/auth/register", null, "{\"email\":\"" + email
        + "\",\"password\":\"Synthetic-pass-1\",\"full_name\":\"Synthetic Person\",\"locale\":\"vi\"}"); // gitleaks:allow synthetic test password
    assertThat(response.statusCode()).isEqualTo(201);
    return body(response).path("data").path("access_token").asText();
  }

  private String createAddress(String token, String name, boolean isDefault) {
    var response = call("POST", "/api/v1/users/me/addresses", token, addressJson(name, isDefault));
    assertThat(response.statusCode()).isEqualTo(201);
    return body(response).path("data").path("id").asText();
  }

  private static String addressJson(String name, boolean isDefault) {
    return "{\"recipient_name\":\"" + name + "\",\"phone\":\"0912345678\",\"province_code\":\"79\","
        + "\"ward_code\":\"26734\",\"address_line\":\"1 Synthetic St\",\"is_default\":" + isDefault + "}";
  }

  private List<String> defaults() {
    return jdbc.queryForList("select id::text from user_addresses where is_default", String.class);
  }

  private HttpResponse<String> call(String method, String path, String token, String jsonBody) {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
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
