package vn.fashion.cart;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class CartTestSupport {
  protected static final UUID MEMBER = UUID.randomUUID();
  protected static final ECKey KEY = key();
  @Container protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("cart").withUsername("postgres").withPassword("postgres").withInitScript("cart-test-init.sql");
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ObjectMapper mapper;
  @LocalServerPort protected int port;
  protected final HttpClient http = HttpClient.newHttpClient();

  protected static ECKey key() {
    try { return new ECKeyGenerator(Curve.P_256).keyID("test").generate(); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  protected static String publicKey() {
    try { return Base64.getEncoder().encodeToString(KEY.toECPublicKey().getEncoded()); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  protected static String privateKey() {
    try { return Base64.getEncoder().encodeToString(KEY.toECPrivateKey().getEncoded()); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "cart_runtime");
    registry.add("spring.datasource.password", () -> "cart_runtime_test");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "cart_migration");
    registry.add("spring.flyway.password", () -> "cart_migration_test");
    registry.add("fashion.cart.jwt.public-keys", () -> "test:" + publicKey());
    registry.add("fashion.cart.service-private-key", CartTestSupport::privateKey);
    registry.add("fashion.cart.service-public-key", CartTestSupport::publicKey);
    registry.add("fashion.cart.catalog-url", StubServers.CATALOG::url);
    registry.add("fashion.cart.inventory-url", StubServers.INVENTORY::url);
    registry.add("fashion.cart.checkout.private-key", CartTestSupport::privateKey);
    registry.add("fashion.cart.checkout.kid", () -> "cart-checkout-test");
    registry.add("fashion.cart.internal-callers", () -> "order-service:" + publicKey() + ",cart-service:" + publicKey());
  }
  @BeforeEach protected void clearCarts() throws Exception {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("delete from cart_items; delete from carts; delete from idempotency_requests");
    }
  }
  protected String memberToken() { return memberToken(MEMBER); }
  protected String memberToken(UUID subject) {
    try {
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test").build(),
          new JWTClaimsSet.Builder().issuer("user-service").audience("fashion-api").subject(subject.toString())
              .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .claim("auth_version", 0).claim("permissions", List.of()).build());
      jwt.sign(new ECDSASigner(KEY));
      return jwt.serialize();
    } catch (Exception e) { throw new AssertionError(e); }
  }
  /** ADR-19 "Bearer ..." header value for a caller of the cart internal API (CART-03). */
  protected String serviceToken(String caller) {
    try { return "Bearer " + new vn.fashion.platform.security.ServiceTokenIssuer(caller, KEY.toECPublicKey(), KEY.toECPrivateKey(),
        java.time.Clock.systemUTC()).issue("cart-service"); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  /** Inserts a cart row directly; returns its id. */
  protected UUID seedCart(UUID userId, String guestHash, String expiresIn) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into carts(id, user_id, guest_token_hash, expires_at, version) values (?, ?, ?, now() + cast(? as interval), 3)",
        id, userId, guestHash, expiresIn);
    return id;
  }
  protected UUID seedItem(UUID cartId, String sku, int quantity, long version) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into cart_items(id, cart_id, variant_id, sku, quantity, price_snapshot, catalog_version, version, created_at)
        values (?, ?, ?, ?, ?, 100000, 7, ?, clock_timestamp())""", id, cartId, UUID.randomUUID(), sku, quantity, version);
    return id;
  }
  /** headers: alternating name, value (e.g. "Cookie", "cart_token=..."). */
  protected HttpResponse<String> send(String method, String path, String body, String... headers) {
    try {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
      for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
      return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) { throw new AssertionError(e); }
  }
  protected JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
  protected JsonNode data(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    return json(response).path("data");
  }
  protected String problem(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    return json(response).path("message").asString();
  }
}
