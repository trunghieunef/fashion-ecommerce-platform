package vn.fashion.order;

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
public abstract class OrderTestSupport {
  protected static final UUID MEMBER = UUID.randomUUID();
  protected static final ECKey KEY = key();
  protected static final String QUOTE_SECRET = Base64.getEncoder().encodeToString("order-quote-test-secret-32-bytes!".getBytes());
  @Container protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("orders").withUsername("postgres").withPassword("postgres").withInitScript("order-test-init.sql");
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
    registry.add("spring.datasource.username", () -> "order_runtime");
    registry.add("spring.datasource.password", () -> "order_runtime_test");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "order_migration");
    registry.add("spring.flyway.password", () -> "order_migration_test");
    registry.add("fashion.order.jwt.public-keys", () -> "test:" + publicKey());
    registry.add("fashion.order.service-private-key", OrderTestSupport::privateKey);
    registry.add("fashion.order.service-public-key", OrderTestSupport::publicKey);
    registry.add("fashion.order.cart-url", StubServers.CART::url);
    registry.add("fashion.order.catalog-url", StubServers.CATALOG::url);
    registry.add("fashion.order.shipping-url", StubServers.SHIPPING::url);
    registry.add("fashion.order.dependency-timeout", () -> "1s");
    registry.add("fashion.order.checkout-public-keys", () -> "cart-checkout-test:" + publicKey());
    registry.add("fashion.order.quote-secret", () -> QUOTE_SECRET);
  }
  /** Orders and their history are runtime-append-only, so cleanup runs as the superuser. */
  @BeforeEach protected void reset() throws Exception {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("""
          delete from order_sagas; delete from order_status_history; delete from order_items; delete from orders;
          delete from outbox_events""");
    }
    StubServers.CART.reset(); StubServers.CATALOG.reset(); StubServers.SHIPPING.reset();
  }
  /** Runs SQL as the superuser (fixtures the runtime role may not write). */
  protected void asOwner(String sql) {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) { s.execute(sql); }
    catch (java.sql.SQLException e) { throw new AssertionError(e); }
  }
  protected String memberToken() { return memberToken(MEMBER); }
  protected String memberToken(UUID subject) {
    try {
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test").build(),
          new JWTClaimsSet.Builder().issuer("user-service").audience("fashion-api").subject(subject.toString())
              .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .claim("auth_version", 0).claim("permissions", List.of()).build());
      jwt.sign(new ECDSASigner(KEY));
      return "Bearer " + jwt.serialize();
    } catch (Exception e) { throw new AssertionError(e); }
  }
  /** A cart-service checkout token (spec section 3) for guestHash and cartId; ttlSeconds may be negative (expired). */
  protected String checkoutToken(String guestHash, UUID cartId, long ttlSeconds, String audience) {
    try {
      Instant now = Instant.now();
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("cart-checkout-test").build(),
          new JWTClaimsSet.Builder().issuer("cart-service").audience(audience).subject("guest:" + guestHash)
              .claim("cart_id", cartId.toString()).jwtID(UUID.randomUUID().toString())
              .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(ttlSeconds))).build());
      jwt.sign(new ECDSASigner(KEY));
      return jwt.serialize();
    } catch (Exception e) { throw new AssertionError(e); }
  }
  protected String checkoutToken(String guestHash, UUID cartId) { return checkoutToken(guestHash, cartId, 900, "order-service"); }

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
  protected int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
}
