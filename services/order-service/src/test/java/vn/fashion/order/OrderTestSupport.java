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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"fashion.messaging.relay.enabled=false", "fashion.order.saga.enabled=false",
    "spring.kafka.listener.auto-startup=false"})
@org.springframework.context.annotation.Import(OrderTestSupport.Clocks.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class OrderTestSupport {
  /** Test clock: shift() moves "now" for quote expiry without sleeping; reset before each test. */
  public static final class ShiftClock extends java.time.Clock {
    private volatile java.time.Duration offset = java.time.Duration.ZERO;
    public void shift(java.time.Duration by) { offset = offset.plus(by); }
    public void reset() { offset = java.time.Duration.ZERO; }
    @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
    @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.now().plus(offset); }
  }
  @org.springframework.boot.test.context.TestConfiguration static class Clocks {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary ShiftClock testClock() { return new ShiftClock(); }
  }
  @Autowired protected ShiftClock clock;
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
    registry.add("fashion.order.inventory-url", StubServers.INVENTORY::url);
    registry.add("fashion.order.payment-url", StubServers.PAYMENT::url);
    registry.add("fashion.order.dependency-timeout", () -> "1s");
    registry.add("fashion.order.checkout-public-keys", () -> "cart-checkout-test:" + publicKey());
    registry.add("fashion.order.quote-secret", () -> QUOTE_SECRET);
  }
  /** Orders and their history are runtime-append-only, so cleanup runs as the superuser. */
  @BeforeEach protected void reset() throws Exception {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("""
          delete from background_tasks; delete from processed_events; delete from order_sagas; delete from order_guest_access; delete from order_status_history; delete from order_items; delete from orders;
          delete from outbox_events""");
    }
    StubServers.CART.reset(); StubServers.CATALOG.reset(); StubServers.SHIPPING.reset();
    StubServers.INVENTORY.reset(); StubServers.PAYMENT.reset();
    clock.reset();
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

  // Checkout fixtures (ORD-01): two variants, a cart holding both, catalog prices and a SELF shipping quote.
  protected static final UUID V1 = UUID.fromString("33333333-3333-4333-8333-000000000001");
  protected static final UUID V2 = UUID.fromString("33333333-3333-4333-8333-000000000002");
  protected static final UUID ITEM1 = UUID.fromString("22222222-2222-4222-8222-000000000001");
  protected static final UUID ITEM2 = UUID.fromString("22222222-2222-4222-8222-000000000002");
  protected static final UUID SHIP_QUOTE = UUID.fromString("44444444-4444-4444-8444-000000000001");
  protected static final String GUEST_HASH = "d".repeat(64);

  protected static String cartItem(UUID item, UUID variant, String sku, int quantity, long itemVersion) {
    return "{\"item_id\":\"" + item + "\",\"variant_id\":\"" + variant + "\",\"sku\":\"" + sku + "\",\"quantity\":" + quantity
        + ",\"item_version\":" + itemVersion + ",\"price_snapshot\":1,\"catalog_version\":1}";
  }
  protected static String cartSnapshot(UUID cart, long version, String... items) {
    return StubServers.ok("{\"cart_id\":\"" + cart + "\",\"version\":" + version + ",\"owner\":\"MEMBER\",\"items\":["
        + String.join(",", items) + "]}");
  }
  protected static String variant(UUID id, boolean active, String sku, long price) {
    return "{\"variant_id\":\"" + id + "\",\"found\":true,\"active\":" + active + ",\"sku\":\"" + sku
        + "\",\"product_id\":\"55555555-5555-4555-8555-555555555555\",\"name_vi\":\"Ao thun\",\"name_en\":\"Tee\",\"size\":\"M\","
        + "\"color\":\"Den\",\"base_price\":" + price + ",\"effective_price\":" + price + ",\"weight_grams\":300,\"product_version\":7,"
        + "\"image\":{\"asset_id\":\"66666666-6666-4666-8666-666666666666\",\"alt_vi\":\"a\",\"alt_en\":\"a\"}}";
  }
  protected static String shippingQuote(long fee, long codFee) {
    return StubServers.ok("{\"quote_id\":\"" + SHIP_QUOTE + "\",\"fee\":" + fee + ",\"cod_fee\":" + codFee + ",\"carrier\":\"SELF\","
        + "\"weight_grams\":900,\"rule_id\":\"77777777-7777-4777-8777-777777777777\",\"rule_version\":1,"
        + "\"expires_at\":\"" + Instant.now().plusSeconds(900) + "\"}");
  }
  /** Happy dependencies: cart v4 with V1 x2 + V2 x1, prices p1/p2, shipping fee 30000 + COD fee 10000. */
  protected void stubCheckout(UUID cart, long p1, long p2) {
    StubServers.CART.reply(s -> new StubServers.Reply(200, cartSnapshot(cart, 4, cartItem(ITEM1, V1, "TEE-M", 2, 1),
        cartItem(ITEM2, V2, "TEE-L", 1, 3))));
    StubServers.CATALOG.reply(s -> new StubServers.Reply(200, StubServers.ok("[" + variant(V1, true, "TEE-M", p1) + ","
        + variant(V2, true, "TEE-L", p2) + "]")));
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(200, shippingQuote(30000, 10000)));
  }
  protected static String quoteBody(UUID cart, long version, String method, String email) {
    return "{\"cart_id\":\"" + cart + "\",\"cart_version\":" + version + ",\"method\":\"" + method + "\","
        + "\"shipping_address\":{\"recipient_name\":\"Synthetic Person\",\"phone\":\"0912345678\",\"province_code\":\"79\","
        + "\"ward_code\":\"26734\",\"address_line\":\"1 Synthetic St\"},\"contact\":{\"phone\":\"0912345678\""
        + (email == null ? "" : ",\"email\":\"" + email + "\"") + "},\"note\":\"Giao gio hanh chinh\"}";
  }
  protected HttpResponse<String> quote(String body, String... auth) {
    return send("POST", "/api/v1/orders/quote", body, auth);
  }

  protected static String createBody(UUID cart, long version, String quoteToken) {
    return "{\"cart_id\":\"" + cart + "\",\"cart_version\":" + version + ",\"quote_token\":\"" + quoteToken + "\"}";
  }
  protected HttpResponse<String> create(String key, String body, String... auth) {
    var headers = new java.util.ArrayList<>(List.of(auth));
    if (key != null) { headers.add("Idempotency-Key"); headers.add(key); }
    return send("POST", "/api/v1/orders", body, headers.toArray(String[]::new));
  }
  /** A fresh quote_token for the happy fixture (stubCheckout must be set). */
  protected String quoteToken(UUID cart, String... auth) {
    return data(quote(quoteBody(cart, 4, "COD", auth[0].equals("Checkout-Token") ? "guest@example.test" : null), auth), 200)
        .path("quote_token").asString();
  }

  /** ORD-02: a member COD order created through the ORD-01 API; returns its order_id. */
  protected UUID placeOrder() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String member = memberToken();
    return UUID.fromString(data(create(UUID.randomUUID().toString(), createBody(cart, 4, quoteToken(cart, "Authorization", member)),
        "Authorization", member), 202).path("order_id").asString());
  }
  /** Inventory and payment answer OK (reservation ACTIVE, payment COD_PENDING); cart cleanup OK. */
  protected void stubSagaOk() {
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok(s.path().endsWith("/release")
        ? "{\"status\":\"RELEASED\"}" : "{\"status\":\"ACTIVE\"}")));
    StubServers.PAYMENT.reply(s -> new StubServers.Reply(200, StubServers.ok(s.path().endsWith("/close")
        ? "{\"status\":\"FAILED\"}" : "{\"payment_id\":\"55555555-5555-4555-8555-555555555591\",\"status\":\"COD_PENDING\"}")));
  }
  protected java.util.Map<String, Object> saga(UUID orderId) {
    return jdbc.queryForMap("select * from order_sagas where order_id = ?", orderId);
  }
  protected String status(UUID orderId) { return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId); }

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
