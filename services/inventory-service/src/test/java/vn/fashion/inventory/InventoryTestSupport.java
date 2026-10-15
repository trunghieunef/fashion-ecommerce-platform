package vn.fashion.inventory;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "fashion.messaging.relay.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class InventoryTestSupport {
  protected static final UUID ACTOR = UUID.randomUUID();
  protected static final ECKey KEY = key();
  @Container protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("inventory").withUsername("postgres").withPassword("postgres")
      .withInitScript("inventory-test-init.sql");
  @Container protected static final org.testcontainers.kafka.KafkaContainer kafka =
      new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.1.2");
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ObjectMapper mapper;
  @LocalServerPort protected int port;
  protected final HttpClient http = HttpClient.newHttpClient();

  protected static ECKey key() {
    try { return new ECKeyGenerator(Curve.P_256).keyID("test").generate(); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  public static String publicKeys() {
    try { return "test:" + Base64.getEncoder().encodeToString(KEY.toECPublicKey().getEncoded()); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("fashion.inventory.internal-callers", InventoryTestSupport::internalKeys);
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "inventory_runtime");
    registry.add("spring.datasource.password", () -> "inventory_runtime_test");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "inventory_migration");
    registry.add("spring.flyway.password", () -> "inventory_migration_test");
    registry.add("fashion.inventory.jwt.public-keys", InventoryTestSupport::publicKeys);
  }
  @BeforeEach void clearInventory() throws Exception {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("delete from stock_transactions; delete from stock_items; delete from outbox_events; delete from processed_events; delete from idempotency_requests");
    }
  }
  protected String token(String... permissions) { return signed(KEY, Instant.now(), permissions); }
  protected String tokenFor(UUID subject, String... permissions) { return signed(KEY, Instant.now(), subject, permissions); }
  protected String signed(ECKey key, Instant now, String... permissions) { return signed(key, now, ACTOR, permissions); }
  protected String signed(ECKey key, Instant now, UUID subject, String... permissions) {
    try {
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test").build(),
          new JWTClaimsSet.Builder().issuer("user-service").audience("fashion-api").subject(subject.toString())
              .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900)))
              .claim("auth_version", 0).claim("permissions", List.of(permissions)).build());
      jwt.sign(new ECDSASigner(key));
      return jwt.serialize();
    } catch (Exception e) { throw new AssertionError(e); }
  }
  protected HttpResponse<String> send(String method, String path, String token, String key, String body) {
    try {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
          .header("Content-Type", "application/json");
      if (token != null) request.header("Authorization", "Bearer " + token);
      if (key != null) request.header("Idempotency-Key", key);
      return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) { throw new AssertionError(e); }
  }
  protected HttpResponse<String> call(String method, String path, Object body) {
    return send(method, "/admin/api/v1/inventory" + path, token("inventory.read", "inventory.adjust"), UUID.randomUUID().toString(),
        body == null ? null : mapper.writeValueAsString(body));
  }
  protected JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
  protected JsonNode data(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    JsonNode result = json(response);
    assertThat(result.path("metadata").path("trace_id").asText()).matches("[0-9a-f]{32}");
    assertThat(response.headers().firstValue("X-Correlation-Id")).contains(result.path("metadata").path("trace_id").asText());
    return result.path("data");
  }
  protected String serviceToken(String caller) {
    try { return new vn.fashion.platform.security.ServiceTokenIssuer(caller, KEY.toECPublicKey(),
        KEY.toECPrivateKey(),java.time.Clock.systemUTC()).issue("inventory-service"); }
    catch(Exception e) { throw new AssertionError(e); }
  }
  static String internalKeys() {
    String value=publicKeys().substring("test:".length());
    return "catalog-service:"+value+",cart-service:"+value+",order-service:"+value;
  }
  protected void seedStock(String sku,int onHand,int reserved) {
    jdbc.update("insert into stock_items(sku,product_id,variant_id,on_hand,reserved) values (?,?,?,?,?)",
        sku,UUID.randomUUID(),UUID.randomUUID(),onHand,reserved);
  }
}
