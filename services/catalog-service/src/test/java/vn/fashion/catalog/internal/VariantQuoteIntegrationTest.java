package vn.fashion.catalog.internal;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;
import vn.fashion.catalog.product.PublicCatalogFixtures;
import vn.fashion.platform.security.ServiceTokenIssuer;

import static org.assertj.core.api.Assertions.assertThat;

class VariantQuoteIntegrationTest extends CatalogAdminTestSupport {
  private static final String PATH = "/internal/api/v1/catalog/variants/quote";
  private static final Map<String, ECKey> KEYS = Map.of(
      "cart-service", serviceKey(), "order-service", serviceKey(), "notification-service", serviceKey());
  private PublicCatalogFixtures fixtures;
  private UUID category;

  @DynamicPropertySource static void callers(DynamicPropertyRegistry registry) {
    registry.add("fashion.catalog.internal-callers", () -> KEYS.entrySet().stream().map(entry -> {
      try { return entry.getKey() + ":" + Base64.getEncoder().encodeToString(entry.getValue().toECPublicKey().getEncoded()); }
      catch (Exception e) { throw new AssertionError(e); }
    }).collect(java.util.stream.Collectors.joining(",")));
  }

  @BeforeEach void setup() {
    fixtures = new PublicCatalogFixtures(jdbc);
    category = fixtures.category(null, "quote-shirts", "ACTIVE");
  }

  @Test void missingOrInvalidTokenIs401() {
    var tokens = new ArrayList<String>();
    tokens.add(null);
    tokens.add("broken");
    tokens.add(issue("cart-service", KEYS.get("cart-service"), "user-service", Clock.systemUTC()));
    tokens.add(issue("cart-service", serviceKey(), "catalog-service", Clock.systemUTC()));
    tokens.add(issue("cart-service", KEYS.get("cart-service"), "catalog-service",
        Clock.fixed(Instant.now().minusSeconds(120), ZoneOffset.UTC)));
    for (String token : tokens) {
      error(quote(token, List.of(UUID.randomUUID().toString())), 401, "UNAUTHORIZED", "INVALID_SERVICE_TOKEN");
    }
    error(quote(token("catalog.write"), List.of(UUID.randomUUID().toString())),
        401, "UNAUTHORIZED", "INVALID_SERVICE_TOKEN");
  }

  @Test void unknownCallerIs403() {
    error(quote(serviceToken("notification-service"), List.of(UUID.randomUUID().toString())),
        403, "FORBIDDEN", "CALLER_NOT_ALLOWED");
  }

  @Test @ExtendWith(OutputCaptureExtension.class)
  void unknownFieldsAre400WithoutLeakingValues(CapturedOutput output) {
    for (String key : List.of("extra", "items", "unknown_fields", "unknownFields")) {
      for (String value : List.of("null", "0", "{\"nested\":\"synthetic-unknown-value\"}")) {
        String body = "{\"variant_ids\":[\"abcdefab-1234-4321-8765-abcdefabcdef\"],\"" + key + "\":" + value + "}";
        var response = send("POST", PATH, serviceToken("cart-service"), null, body);
        error(response, 400, "VALIDATION_ERROR", "INVALID_FIELDS");
        JsonNode errors = json(response).path("errors");
        assertThat(errors.size()).isEqualTo(1);
        assertThat(errors.get(0).path("field").asText()).isEqualTo(key);
        assertThat(errors.get(0).path("message").asText()).isEqualTo("unknown field");
        assertThat(json(response).size()).isEqualTo(4);
        assertThat(json(response).path("metadata").path("request_id").asText())
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(response.body()).doesNotContain("synthetic-unknown-value", "nested");
      }
    }
    assertThat(output.getAll()).doesNotContain("synthetic-unknown-value");
    assertThat(jdbc.queryForObject("select count(*) from audit_logs", Integer.class)).isZero();
  }

  @Test void serviceAuthPrecedesUnknownFieldValidation() {
    String body = "{\"variant_ids\":[],\"extra\":\"synthetic-unknown-value\"}";
    for (String token : java.util.Arrays.asList(null, "broken")) {
      var response = send("POST", PATH, token, null, body);
      error(response, 401, "UNAUTHORIZED", "INVALID_SERVICE_TOKEN");
      assertThat(json(response).path("errors").isEmpty()).isTrue();
      assertThat(response.body()).doesNotContain("extra", "synthetic-unknown-value");
    }
    var response = send("POST", PATH, serviceToken("notification-service"), null, body);
    error(response, 403, "FORBIDDEN", "CALLER_NOT_ALLOWED");
    assertThat(json(response).path("errors").isEmpty()).isTrue();
    assertThat(response.body()).doesNotContain("extra", "synthetic-unknown-value");
  }

  @Test @ExtendWith(OutputCaptureExtension.class)
  void serviceAuthPrecedesBodyBinding(CapturedOutput output) {
    for (String body : List.of("{\"variant_ids\":{},\"extra\":\"synthetic-binding-value\"}",
        "{\"extra\":\"synthetic-binding-value\",", "", "null", "[]")) {
      for (String callerToken : java.util.Arrays.asList(null, "broken", serviceToken("notification-service"))) {
        var response = send("POST", PATH, callerToken, null, body);
        boolean forbidden = callerToken != null && !callerToken.equals("broken");
        error(response, forbidden ? 403 : 401, forbidden ? "FORBIDDEN" : "UNAUTHORIZED",
            forbidden ? "CALLER_NOT_ALLOWED" : "INVALID_SERVICE_TOKEN");
        assertThat(json(response).path("errors").isEmpty()).isTrue();
        assertThat(response.body()).doesNotContain("extra", "synthetic-binding-value");
      }
    }
    assertThat(output.getAll()).doesNotContain("synthetic-binding-value");
  }

  @Test void cartAndOrderGetItemsInRequestOrder() {
    UUID product = product("ordered", category, null, "ACTIVE");
    UUID v1 = fixtures.variant(product, "QUOTE-1", "M", "Black", null, "ACTIVE");
    UUID v2 = fixtures.variant(product, "QUOTE-2", "L", "White", 150000L, "ACTIVE");
    UUID missing = UUID.randomUUID();
    jdbc.update("update products set version=7 where id=?", product);
    for (String caller : List.of("cart-service", "order-service")) {
      var response = quote(serviceToken(caller), List.of(v2.toString(), missing.toString(), v1.toString()));
      noStore(response);
      JsonNode items = data(response, 200);
      assertThat(items.size()).isEqualTo(3);
      assertThat(items.get(0).path("variant_id").asText()).isEqualTo(v2.toString());
      assertThat(items.get(1).path("variant_id").asText()).isEqualTo(missing.toString());
      assertThat(items.get(2).path("variant_id").asText()).isEqualTo(v1.toString());
      assertThat(items.get(1).path("found").asBoolean()).isFalse();
      assertThat(items.get(1).size()).isEqualTo(2);
      JsonNode item = items.get(2);
      assertThat(item.path("found").asBoolean()).isTrue();
      assertThat(item.path("active").asBoolean()).isTrue();
      assertThat(item.path("sku").asText()).isEqualTo("QUOTE-1");
      assertThat(item.path("product_id").asText()).isEqualTo(product.toString());
      assertThat(item.path("name_vi").asText()).isEqualTo("Áo");
      assertThat(item.path("name_en").asText()).isEqualTo("English shirt");
      assertThat(item.path("size").asText()).isEqualTo("M");
      assertThat(item.path("color").asText()).isEqualTo("Black");
      assertThat(item.path("base_price").asLong()).isEqualTo(200000);
      assertThat(item.has("price_override")).isFalse();
      assertThat(item.path("effective_price").asLong()).isEqualTo(200000);
      assertThat(item.path("weight_grams").asInt()).isEqualTo(100);
      assertThat(item.path("product_version").asLong()).isEqualTo(7);
      assertThat(item.has("image")).isFalse();
    }
    assertThat(jdbc.queryForObject("select count(*) from audit_logs", Integer.class)).isZero();
  }

  @Test void unsellableVariantIsFoundButInactive() {
    UUID inactiveCategory = fixtures.category(null, "inactive-category", "INACTIVE");
    UUID inactiveBrand = fixtures.brand("Inactive brand", "INACTIVE");
    UUID variantProduct = product("inactive-variant", category, null, "ACTIVE");
    UUID draft = product("draft", category, null, "DRAFT");
    UUID inactiveProduct = product("inactive-product", category, null, "INACTIVE");
    UUID categoryProduct = product("inactive-category-product", inactiveCategory, null, "ACTIVE");
    UUID brandProduct = product("inactive-brand-product", category, inactiveBrand, "ACTIVE");
    List<UUID> variants = List.of(
        fixtures.variant(variantProduct, "INACTIVE-VARIANT", "M", "Black", null, "INACTIVE"),
        fixtures.variant(draft, "DRAFT", "M", "Black", null, "ACTIVE"),
        fixtures.variant(inactiveProduct, "INACTIVE-PRODUCT", "M", "Black", null, "ACTIVE"),
        fixtures.variant(categoryProduct, "INACTIVE-CATEGORY", "M", "Black", null, "ACTIVE"),
        fixtures.variant(brandProduct, "INACTIVE-BRAND", "M", "Black", null, "ACTIVE"));
    JsonNode items = data(quote(serviceToken("cart-service"), variants.stream().map(UUID::toString).toList()), 200);
    assertThat(items.size()).isEqualTo(5);
    for (JsonNode item : items) {
      assertThat(item.path("found").asBoolean()).isTrue();
      assertThat(item.path("active").asBoolean()).isFalse();
      assertThat(item.path("effective_price").asLong()).isEqualTo(200000);
    }
  }

  @Test void effectivePriceUsesOverrideIncludingZeroAndLongMoney() {
    UUID product = product("override", category, fixtures.brand("Active brand", "ACTIVE"), "ACTIVE");
    UUID discounted = fixtures.variant(product, "DISCOUNT", "M", "Black", 150000L, "ACTIVE");
    UUID free = fixtures.variant(product, "ZERO", "L", "Black", 0L, "ACTIVE");
    UUID largeProduct = fixtures.product("long-price", "Long money", category, null, "ACTIVE", 5000000000L, null, 0);
    UUID large = fixtures.variant(largeProduct, "LONG", "M", "Black", null, "ACTIVE");
    JsonNode items = data(quote(serviceToken("order-service"), List.of(discounted.toString(), free.toString(), large.toString())), 200);
    assertThat(items.get(0).path("base_price").asLong()).isEqualTo(200000);
    assertThat(items.get(0).path("price_override").asLong()).isEqualTo(150000);
    assertThat(items.get(0).path("effective_price").asLong()).isEqualTo(150000);
    assertThat(items.get(0).path("active").asBoolean()).isTrue();
    assertThat(items.get(1).path("effective_price").asLong()).isZero();
    assertThat(items.get(2).path("effective_price").asLong()).isEqualTo(5000000000L);
  }

  @Test void imageUsesFirstAvailableAsset() {
    UUID product = product("images", category, null, "ACTIVE");
    UUID variant = fixtures.variant(product, "IMAGE", "M", "Black", null, "ACTIVE");
    fixtures.image(product, 0, "DELETING");
    fixtures.image(product, 1, "DELETED");
    fixtures.image(product, 3, "AVAILABLE");
    UUID first = fixtures.image(product, 2, "AVAILABLE");
    JsonNode image = data(quote(serviceToken("cart-service"), List.of(variant.toString())), 200).get(0).path("image");
    assertThat(image.path("asset_id").asText()).isEqualTo(first.toString());
    assertThat(image.path("alt_vi").asText()).isEqualTo("Ảnh");
    assertThat(image.path("alt_en").asText()).isEqualTo("Image");
  }

  @Test void invalidVariantIdsAre400() {
    String id = "abcdefab-1234-4321-8765-abcdefabcdef";
    var invalid = new ArrayList<List<String>>();
    invalid.add(null);
    invalid.add(List.of());
    invalid.add(java.util.stream.IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString()).toList());
    invalid.add(List.of(id, id));
    invalid.add(List.of(id, id.toUpperCase(Locale.ROOT)));
    invalid.add(List.of("x"));
    invalid.add(List.of("0-0-4000-8000-1"));
    invalid.add(java.util.Arrays.asList((String) null));
    for (List<String> ids : invalid) {
      var response = quote(serviceToken("cart-service"), ids);
      data(response, 400);
      noStore(response);
      assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
      assertThat(json(response).path("errors").get(0).path("field").asText()).isEqualTo("variant_ids");
    }
  }

  @Test void maximumBatchAndUppercaseUuidAreAccepted() {
    var ids = java.util.stream.IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID().toString().toUpperCase(Locale.ROOT)).toList();
    JsonNode items = data(quote(serviceToken("cart-service"), ids), 200);
    assertThat(items.size()).isEqualTo(100);
    for (int i = 0; i < 100; i++) {
      assertThat(items.get(i).path("variant_id").asText()).isEqualTo(ids.get(i).toLowerCase(Locale.ROOT));
      assertThat(items.get(i).path("found").asBoolean()).isFalse();
    }
  }

  @Test void bodyErrorsAreNoStoreAfterAuthentication() {
    for (String body : List.of("{", "", "null", "[]", "{\"variant_ids\":{}}")) {
      var response = send("POST", PATH, serviceToken("cart-service"), null, body);
      data(response, 400);
      noStore(response);
      assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
  }

  @Test @ExtendWith(OutputCaptureExtension.class)
  void httpErrorsAreNoStore(CapturedOutput output) throws Exception {
    var jsonRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
        .header("Content-Type", "application/vnd.fashion.quote+json")
        .header("Authorization", "Bearer " + serviceToken("cart-service"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"variant_ids\":[\"abcdefab-1234-4321-8765-abcdefabcdef\"]}"))
        .build();
    var jsonResponse = HttpClient.newHttpClient().send(jsonRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(data(jsonResponse, 200).size()).isEqualTo(1);
    noStore(jsonResponse);
    for (String[] headers : List.of(new String[]{"text/plain", "application/json"},
        new String[]{"synthetic-invalid-media", "application/json"},
        new String[]{"application/json", "text/plain"})) {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
          .header("Content-Type", headers[0]).header("Accept", headers[1])
          .header("Authorization", "Bearer " + serviceToken("cart-service"))
          .POST(HttpRequest.BodyPublishers.ofString("{\"variant_ids\":[\"abcdefab-1234-4321-8765-abcdefabcdef\"]}"))
          .build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(headers[0].equals("application/json") ? 406 : 415);
      if (response.statusCode() == 415) {
        assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        error(response, 415, "VALIDATION_ERROR", "INVALID_HTTP_REQUEST");
        assertThat(json(response).path("errors").isEmpty()).isTrue();
        assertThat(json(response).path("metadata").path("request_id").asText())
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(response.headers().firstValue("Accept").orElse("")).contains("application/json", "application/*+json");
        assertThat(response.body()).doesNotContain("synthetic-invalid-media");
      }
      noStore(response);
    }
    var response = send("GET", PATH, serviceToken("cart-service"), null, null);
    assertThat(response.statusCode()).isEqualTo(405);
    noStore(response);
    assertThat(output.getAll()).doesNotContain("synthetic-invalid-media");
  }

  @Test void authPrecedesMediaValidation() throws Exception {
    for (String type : List.of("text/plain", "synthetic-invalid-media")) {
      for (String callerToken : java.util.Arrays.asList(null, "broken", serviceToken("notification-service"))) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
            .header("Content-Type", type)
            .POST(HttpRequest.BodyPublishers.ofString("{\"extra\":\"synthetic-media-payload\"}"));
        if (callerToken != null) request.header("Authorization", "Bearer " + callerToken);
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        boolean forbidden = callerToken != null && !callerToken.equals("broken");
        error(response, forbidden ? 403 : 401, forbidden ? "FORBIDDEN" : "UNAUTHORIZED",
            forbidden ? "CALLER_NOT_ALLOWED" : "INVALID_SERVICE_TOKEN");
        assertThat(json(response).path("errors").isEmpty()).isTrue();
        assertThat(response.body()).doesNotContain(type, "extra", "synthetic-media-payload");
      }
    }
  }

  private UUID product(String slug, UUID categoryId, UUID brand, String status) {
    return fixtures.product(slug, "Áo", categoryId, brand, status, 200000L, null, 0);
  }
  private HttpResponse<String> quote(String token, List<String> ids) {
    return send("POST", PATH, token, null, ids == null ? "{\"variant_ids\":null}"
        : mapper.writeValueAsString(Map.of("variant_ids", ids)));
  }
  private void error(HttpResponse<String> response, int status, String code, String message) {
    data(response, status);
    noStore(response);
    assertThat(json(response).path("code").asText()).isEqualTo(code);
    assertThat(json(response).path("message").asText()).isEqualTo(message);
  }
  private static void noStore(HttpResponse<String> response) {
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
  }
  private static ECKey serviceKey() {
    try { return new ECKeyGenerator(Curve.P_256).generate(); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  private static String serviceToken(String caller) {
    return issue(caller, KEYS.get(caller), "catalog-service", Clock.systemUTC());
  }
  private static String issue(String caller, ECKey key, String audience, Clock clock) {
    try { return new ServiceTokenIssuer(caller, key.toECPublicKey(), key.toECPrivateKey(), clock).issue(audience); }
    catch (Exception e) { throw new AssertionError(e); }
  }
}
