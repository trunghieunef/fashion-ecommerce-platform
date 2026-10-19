package vn.fashion.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SHP-01 spec sections 4-5 against the sample rules of V002 (HN/HCM weight 10, nationwide weight 50, FLAT 1000). */
class FeeIntegrationTest extends ShippingTestSupport {
  static final String ESTIMATE = "/api/v1/shipping/fee/estimate";
  static final String QUOTES = "/internal/api/v1/shipping/quotes";

  @Test void ruleByPriorityProvinceAndWeightTier() {
    String light = variant(500), heavy = variant(1600);
    // HCM, 1000 g -> HN/HCM rule tier <= 3000: 25000; COD adds 10000.
    var hcm = data(send("POST", ESTIMATE, feeBody("79", true, light, 2)), 200);
    assertThat(hcm.path("fee").asLong()).isEqualTo(25000);
    assertThat(hcm.path("cod_fee").asLong()).isEqualTo(10000);
    assertThat(hcm.path("weight_grams").asInt()).isEqualTo(1000);
    assertThat(hcm.path("carrier").asString()).isEqualTo("SELF");
    assertThat(hcm.path("estimate").asBoolean()).isTrue();
    // Exactly the 3000 g boundary stays in the first tier; 3200 g goes to the unbounded tier.
    assertThat(data(send("POST", ESTIMATE, feeBody("01", false, light, 6)), 200).path("fee").asLong()).isEqualTo(25000);
    assertThat(data(send("POST", ESTIMATE, feeBody("01", false, heavy, 2)), 200).path("fee").asLong()).isEqualTo(40000);
    // Other province -> nationwide rule; no COD -> cod_fee 0.
    var other = data(send("POST", ESTIMATE, feeBody("48", false, light, 2)), 200);
    assertThat(other.path("fee").asLong()).isEqualTo(30000);
    assertThat(other.path("cod_fee").asLong()).isZero();
    assertThat(data(send("POST", ESTIMATE, feeBody("48", true, heavy, 1)), 200).path("fee").asLong()).isEqualTo(45000);
    assertThat(jdbc.queryForObject("select count(*) from shipping_quotes", Integer.class)).isZero();
    // The weight came from catalog with the shipping-service token.
    assertThat(CatalogStub.AUTH).allMatch(a -> a.startsWith("Bearer "));
  }

  @Test void flatFallbackAndNoRule() {
    String v = variant(500);
    jdbc.update("update shipping_rules set active = false where rule_type = 'WEIGHT'");
    var flat = data(send("POST", ESTIMATE, feeBody("48", true, v, 1)), 200);
    assertThat(flat.path("fee").asLong()).isEqualTo(35000);
    assertThat(flat.path("cod_fee").asLong()).isEqualTo(15000);
    jdbc.update("update shipping_rules set active = false");
    var none = send("POST", ESTIMATE, feeBody("48", true, v, 1));
    assertThat(problem(none, 422)).isEqualTo("NO_SHIPPING_RULE");
  }

  @Test void unavailableVariantsAndCatalogDown() {
    String ok = variant(500), inactive = UUID.randomUUID().toString();
    CatalogStub.VARIANTS.put(inactive, new CatalogStub.Variant(500, false));
    var r = send("POST", ESTIMATE, feeBody("79", false, ok, 1, inactive, 1, UUID.randomUUID().toString(), 1));
    assertThat(problem(r, 422)).isEqualTo("VARIANT_NOT_AVAILABLE");
    assertThat(json(r).path("errors").get(0).path("field").asString()).isEqualTo("items[1].variant_id");
    assertThat(json(r).path("errors")).hasSize(2);
    CatalogStub.status = 503;
    assertThat(problem(send("POST", ESTIMATE, feeBody("79", false, ok, 1)), 503)).isEqualTo("DEPENDENCY_UNAVAILABLE");
  }

  @Test void weightOverflowIs400() {
    String huge = variant(Integer.MAX_VALUE);
    assertThat(problem(send("POST", ESTIMATE, feeBody("79", false, huge, 2)), 400)).isEqualTo("WEIGHT_OUT_OF_RANGE");
  }

  @Test void validation() {
    String v = variant(100);
    for (String bad : new String[] {"{}", feeBody("79 ", false, v, 1), feeBody("79", false, v, 0), feeBody("79", false, v, 100),
        feeBody("79", false, v, 1, v, 1), feeBody("79", false, v.toUpperCase(), 1), "{\"province_code\":\"79\",\"cod\":\"yes\",\"items\":[]}"}) {
      var r = send("POST", ESTIMATE, bad);
      assertThat(r.statusCode()).withFailMessage(bad + " -> " + r.body()).isEqualTo(400);
    }
    assertThat(problem(send("POST", ESTIMATE, "[1]"), 400)).isEqualTo("MALFORMED_JSON");
  }

  @Test void quoteIsStoredAndReplayedByKey() {
    String v = variant(500);
    String key = UUID.randomUUID().toString();
    var first = data(send("POST", QUOTES, feeBody("79", true, v, 2), "Authorization", serviceToken("order-service"), "Idempotency-Key", key), 200);
    assertThat(first.path("quote_id").asString()).matches("[0-9a-f-]{36}");
    assertThat(first.path("fee").asLong()).isEqualTo(25000);
    assertThat(first.path("cod_fee").asLong()).isEqualTo(10000);
    assertThat(first.path("rule_id").asString()).isEqualTo("5b0e7c1a-0000-4000-8000-000000000001");
    assertThat(first.path("rule_version").asLong()).isZero();
    var row = jdbc.queryForMap("select province_code, weight_grams, cod, fee, cod_fee, expires_at - created_at as ttl from shipping_quotes");
    assertThat(row).containsEntry("province_code", "79").containsEntry("weight_grams", 1000).containsEntry("cod", true);
    assertThat(row.get("ttl").toString()).contains("15");
    var again = data(send("POST", QUOTES, feeBody("79", true, v, 2), "Authorization", serviceToken("order-service"), "Idempotency-Key", key), 200);
    assertThat(again).isEqualTo(first);
    assertThat(jdbc.queryForObject("select count(*) from shipping_quotes", Integer.class)).isEqualTo(1);
    assertThat(problem(send("POST", QUOTES, feeBody("79", false, v, 2), "Authorization", serviceToken("order-service"), "Idempotency-Key", key), 409))
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test void quoteAuthBeforeBody() {
    assertThat(problem(send("POST", QUOTES, "{bad", "Idempotency-Key", "k"), 401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("POST", QUOTES, "{bad", "Authorization", serviceToken("cart-service"), "Idempotency-Key", "k"), 403))
        .isEqualTo("CALLER_NOT_ALLOWED");
    assertThat(problem(send("POST", QUOTES, feeBody("79", false, variant(1), 1), "Authorization", serviceToken("order-service")), 400))
        .isEqualTo("INVALID_KEY");
  }

  @Test void quoteReplayDoesNotNeedCatalogOrRules() {
    // Review I1: an UNKNOWN retry must get the stored quote even if catalog is down or the rule changed since.
    String v = variant(500), key = UUID.randomUUID().toString();
    var first = data(send("POST", QUOTES, feeBody("79", false, v, 1), "Authorization", serviceToken("order-service"), "Idempotency-Key", key), 200);
    CatalogStub.status = 503;
    jdbc.update("update shipping_rules set active = false");
    assertThat(data(send("POST", QUOTES, feeBody("79", false, v, 1), "Authorization", serviceToken("order-service"), "Idempotency-Key", key), 200))
        .isEqualTo(first);
    assertThat(problem(send("POST", QUOTES, feeBody("79", true, v, 1), "Authorization", serviceToken("order-service"), "Idempotency-Key", key), 409))
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test void runtimeCannotRewriteQuotes() {
    data(send("POST", QUOTES, feeBody("79", false, variant(100), 1), "Authorization", serviceToken("order-service"), "Idempotency-Key", "q"), 200);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update shipping_quotes set fee = 0"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
