package vn.fashion.shipping;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "fashion.messaging.relay.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class ShippingTestSupport {
  protected static final ECKey KEY = key();
  @Container protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("shipping").withUsername("postgres").withPassword("postgres").withInitScript("shipping-test-init.sql");
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ObjectMapper mapper;
  @LocalServerPort protected int port;
  protected final HttpClient http = HttpClient.newHttpClient();

  static ECKey key() {
    try { return new ECKeyGenerator(Curve.P_256).keyID("test").generate(); } catch (Exception e) { throw new AssertionError(e); }
  }
  static String publicKey() {
    try { return Base64.getEncoder().encodeToString(KEY.toECPublicKey().getEncoded()); } catch (Exception e) { throw new AssertionError(e); }
  }
  static String privateKey() {
    try { return Base64.getEncoder().encodeToString(KEY.toECPrivateKey().getEncoded()); } catch (Exception e) { throw new AssertionError(e); }
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "shipping_runtime");
    registry.add("spring.datasource.password", () -> "shipping_runtime_test");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "shipping_migration");
    registry.add("spring.flyway.password", () -> "shipping_migration_test");
    registry.add("fashion.shipping.jwt.public-keys", () -> "test:" + publicKey());
    registry.add("fashion.shipping.internal-callers", () -> "order-service:" + publicKey() + ",cart-service:" + publicKey());
    registry.add("fashion.shipping.service-private-key", ShippingTestSupport::privateKey);
    registry.add("fashion.shipping.service-public-key", ShippingTestSupport::publicKey);
    registry.add("fashion.shipping.catalog-url", CatalogStub::url);
  }
  /** Shipments/quotes/history are runtime-append-only, so cleanup runs as the superuser; sample rules are restored. */
  @BeforeEach protected void reset() throws Exception {
    CatalogStub.reset();
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("""
          delete from shipment_status_history; delete from shipments; delete from shipping_quotes; delete from idempotency_requests;
          delete from outbox_events; delete from shipping_rule_changes;
          delete from shipping_rules where id not in ('5b0e7c1a-0000-4000-8000-000000000001','5b0e7c1a-0000-4000-8000-000000000002','5b0e7c1a-0000-4000-8000-000000000003');
          update shipping_rules set active = true""");
    }
  }
  protected String serviceToken(String caller) {
    try { return "Bearer " + new vn.fashion.platform.security.ServiceTokenIssuer(caller, KEY.toECPublicKey(), KEY.toECPrivateKey(),
        java.time.Clock.systemUTC()).issue("shipping-service"); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  protected String opsToken(String... permissions) {
    try {
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test").build(),
          new JWTClaimsSet.Builder().issuer("user-service").audience("fashion-api").subject(UUID.randomUUID().toString())
              .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .claim("auth_version", 0).claim("permissions", List.of(permissions)).build());
      jwt.sign(new ECDSASigner(KEY));
      return "Bearer " + jwt.serialize();
    } catch (Exception e) { throw new AssertionError(e); }
  }
  /** headers: alternating name, value. */
  protected HttpResponse<String> send(String method, String path, String body, String... headers) {
    try {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
      for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
      return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) { throw new AssertionError(e); }
  }
  protected JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
  protected JsonNode data(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    return json(response).path("data");
  }
  protected String problem(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    return json(response).path("message").asString();
  }
  protected String variant(int weightGrams) {
    String id = UUID.randomUUID().toString();
    CatalogStub.VARIANTS.put(id, new CatalogStub.Variant(weightGrams, true));
    return id;
  }
  protected static String feeBody(String province, boolean cod, Object... variantQty) {
    var items = new StringBuilder();
    for (int i = 0; i < variantQty.length; i += 2)
      items.append(i == 0 ? "" : ",").append("{\"variant_id\":\"").append(variantQty[i]).append("\",\"quantity\":").append(variantQty[i + 1]).append("}");
    return "{\"province_code\":\"" + province + "\",\"cod\":" + cod + ",\"items\":[" + items + "]}";
  }
}
