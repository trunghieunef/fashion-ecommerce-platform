package vn.fashion.payment;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"fashion.messaging.relay.enabled=false", "spring.kafka.listener.auto-startup=false",
        "fashion.payment.vnpay.tmn-code=TESTTMN1", "fashion.payment.vnpay.hash-secret=TEST-HASH-SECRET"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PaymentTestSupport {
  protected static final ECKey KEY = key();
  @Container protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("payment").withUsername("postgres").withPassword("postgres").withInitScript("payment-test-init.sql");
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
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "payment_runtime");
    registry.add("spring.datasource.password", () -> "payment_runtime_test");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "payment_migration");
    registry.add("spring.flyway.password", () -> "payment_migration_test");
    registry.add("fashion.payment.jwt.public-keys", () -> "test:" + publicKey());
    registry.add("fashion.payment.internal-callers", () -> "order-service:" + publicKey() + ",cart-service:" + publicKey());
  }
  /** Money tables are runtime-append-only, so cleanup runs as the superuser. */
  @BeforeEach protected void reset() throws Exception {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("""
          delete from reconciliation_items; delete from cod_settlements; delete from payment_transactions; delete from payments;
          delete from idempotency_requests; delete from outbox_events; delete from processed_events""");
    }
  }
  /** Runs SQL as the superuser (fixtures the runtime role may not write). */
  protected void asOwner(String sql) {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) { s.execute(sql); }
    catch (java.sql.SQLException e) { throw new AssertionError(e); }
  }
  protected String serviceToken(String caller) {
    try { return "Bearer " + new vn.fashion.platform.security.ServiceTokenIssuer(caller, KEY.toECPublicKey(), KEY.toECPrivateKey(),
        java.time.Clock.systemUTC()).issue("payment-service"); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  protected String staffToken(String... permissions) {
    try {
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test").build(),
          new JWTClaimsSet.Builder().issuer("user-service").audience("fashion-api").subject(UUID.randomUUID().toString())
              .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .claim("auth_version", 0).claim("permissions", List.of(permissions)).build());
      jwt.sign(new ECDSASigner(KEY));
      return "Bearer " + jwt.serialize();
    } catch (Exception e) { throw new AssertionError(e); }
  }

  // Order snapshot helpers shared by the PAY-01 tests.
  protected static String snapshot(String order, String orderNo, String method, long amount) {
    return "{\"order_id\":\"" + order + "\",\"order_no\":\"" + orderNo + "\",\"user_id\":null,\"method\":\"" + method
        + "\",\"amount\":" + amount + ",\"currency\":\"VND\",\"expires_at\":\"2030-01-01T00:00:00Z\"}";
  }
  /** PAY-02: snapshot plus the order's client_ip. */
  protected static String snapshot(String order, String orderNo, String method, long amount, String clientIp) {
    String base = snapshot(order, orderNo, method, amount);
    return base.substring(0, base.length() - 1) + ",\"client_ip\":\"" + clientIp + "\"}";
  }
  protected HttpResponse<String> create(String key, String body) {
    return send("POST", "/internal/api/v1/payments", body, "Authorization", serviceToken("order-service"), "Idempotency-Key", key);
  }
  protected HttpResponse<String> close(String order, String key, String body) {
    return send("POST", "/internal/api/v1/payments/by-order/" + order + "/close", body, "Authorization", serviceToken("order-service"),
        "Idempotency-Key", key);
  }
  /** Snapshot body plus a reason field. */
  protected static String withReason(String snapshot, String reason) {
    return snapshot.substring(0, snapshot.length() - 1) + ",\"reason\":\"" + reason + "\"}";
  }
  /** A fresh COD payment; returns its payment_id. */
  protected String cod(String order, long amount) {
    return data(create(UUID.randomUUID().toString(), snapshot(order, "P" + order.substring(0, 8), "COD", amount)), 200)
        .path("payment_id").asString();
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
  protected int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
}
