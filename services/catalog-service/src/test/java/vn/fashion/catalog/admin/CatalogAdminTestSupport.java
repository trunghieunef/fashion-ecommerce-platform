package vn.fashion.catalog.admin;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class CatalogAdminTestSupport {
  static final UUID ACTOR = UUID.randomUUID();
  static final UUID SEED = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final ECKey KEY = key();
  @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("catalog").withUsername("postgres").withPassword("postgres")
      .withInitScript("catalog-test-init.sql");
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ObjectMapper mapper;
  @LocalServerPort int port;
  private final HttpClient http = HttpClient.newHttpClient();

  static ECKey key() {
    try { return new ECKeyGenerator(Curve.P_256).keyID("test").generate(); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  public static String publicKeys() {
    try { return "test:" + Base64.getEncoder().encodeToString(KEY.toECPublicKey().getEncoded()); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "catalog_runtime");
    registry.add("spring.datasource.password", () -> "catalog_runtime_test");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "catalog_migration");
    registry.add("spring.flyway.password", () -> "catalog_migration_test");
    registry.add("fashion.catalog.jwt.public-keys", CatalogAdminTestSupport::publicKeys);
  }
  @BeforeEach void clearCatalog() throws Exception {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("delete from audit_logs; delete from outbox_events; delete from idempotency_requests; delete from collection_items; delete from collections; delete from size_guides; delete from product_variants; delete from products; delete from brands; delete from categories where id <> '" + SEED + "'");
    }
  }
  String token(String... permissions) { return signed(KEY, Instant.now(), permissions); }
  String signed(ECKey key, Instant now, String... permissions) {
    try {
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test").build(),
          new JWTClaimsSet.Builder().issuer("user-service").audience("fashion-api").subject(ACTOR.toString())
              .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900)))
              .claim("auth_version", 0).claim("permissions", List.of(permissions)).build());
      jwt.sign(new ECDSASigner(key));
      return jwt.serialize();
    } catch (Exception e) { throw new AssertionError(e); }
  }
  HttpResponse<String> send(String method, String path, String token, String key, String body) {
    try {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
          .header("Content-Type", "application/json");
      if (token != null) request.header("Authorization", "Bearer " + token);
      if (key != null) request.header("Idempotency-Key", key);
      return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) { throw new AssertionError(e); }
  }
  HttpResponse<String> call(String method, String path, Object body) {
    return send(method, "/admin/api/v1/catalog" + path, token("catalog.write"), UUID.randomUUID().toString(),
        body == null ? null : mapper.writeValueAsString(body));
  }
  JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
  JsonNode data(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    JsonNode result = json(response);
    assertThat(result.path("metadata").path("trace_id").asText()).matches("[0-9a-f]{32}");
    assertThat(response.headers().firstValue("X-Correlation-Id")).contains(result.path("metadata").path("trace_id").asText());
    return result.path("data");
  }
  Map<String,Object> category(String slug) {
    return Map.of("name_vi", "Áo", "name_en", "Shirt", "slug", slug, "sort_order", 1);
  }
  JsonNode audited(HttpResponse<String> response, int status, String action) {
    var result = data(response, status);
    var requestId = UUID.fromString(json(response).path("metadata").path("request_id").asText());
    assertThat(jdbc.queryForObject("select request_id from audit_logs where resource_id=? and action=?",
        UUID.class, result.path("id").asText(), action)).isEqualTo(requestId);
    return result;
  }
  UUID createCategory(String slug) { return UUID.fromString(data(call("POST", "/categories", category(slug)), 201).path("id").asText()); }
  Map<String,Object> productInput(String slug) {
    return new java.util.HashMap<>(Map.of("category_id", SEED, "name_vi", "Áo", "name_en", "Shirt", "slug", slug,
        "base_price", 100000L, "description_vi", "<p>Safe<strong>text</strong></p>", "tags", List.of("cotton")));
  }
  UUID createProduct(String slug) { return UUID.fromString(data(call("POST", "/products", productInput(slug)), 201).path("id").asText()); }
  Map<String,Object> variantInput(String sku) { return new java.util.HashMap<>(Map.of("sku", sku, "size", "M", "color", "Blue", "weight_grams", 100)); }
  JsonNode createVariant(UUID product, String sku) { return data(call("POST", "/products/" + product + "/variants", variantInput(sku)), 201); }
}
