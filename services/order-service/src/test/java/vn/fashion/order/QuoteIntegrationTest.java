package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.quotes.QuoteTokens;

/** TASK:ORD-01 spec section 5: server quote, signed, 2 minutes, nothing stored. */
class QuoteIntegrationTest extends OrderTestSupport {
  @Autowired QuoteTokens tokens;

  @Test void aMemberQuoteUsesCatalogPricesAndTheShippingQuote() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    Instant before = Instant.now();
    var data = data(quote(quoteBody(cart, 4, "COD", null), "Authorization", memberToken()), 200);
    var totals = data.path("totals");
    assertThat(totals.path("subtotal").asLong()).isEqualTo(2 * 150000 + 99000);
    assertThat(totals.path("shipping_fee").asLong()).isEqualTo(30000);
    assertThat(totals.path("cod_fee").asLong()).isEqualTo(10000);
    assertThat(totals.path("discount_amount").asLong()).isZero();
    assertThat(totals.path("total_amount").asLong()).isEqualTo(399000 + 30000 + 10000);
    assertThat(totals.path("currency").asString()).isEqualTo("VND");
    assertThat(data.path("cart_version").asLong()).isEqualTo(4);
    assertThat(data.path("items")).hasSize(2);
    assertThat(data.path("items").get(0).path("line_total").asLong()).isEqualTo(300000);
    assertThat(data.path("items").get(0).path("name").asString()).isEqualTo("Ao thun");
    Instant expires = Instant.parse(data.path("expires_at").asString());
    assertThat(Duration.between(before, expires).getSeconds()).isBetween(118L, 121L);
    var token = tokens.verify(data.path("quote_token").asString());
    assertThat(token.actorKey()).isEqualTo("user:" + MEMBER);
    assertThat(token.totals().totalAmount()).isEqualTo(439000);
    // The cart is read as the verified actor with the client's version; shipping gets COD and the province.
    var seen = StubServers.CART.seen.get(0);
    assertThat(seen.cartActor()).isEqualTo("user:" + MEMBER);
    assertThat(seen.query()).isEqualTo("expected_version=4");
    assertThat(seen.authorization()).startsWith("Bearer ");
    assertThat(StubServers.SHIPPING.seen.get(0).body()).contains("\"province_code\":\"79\"").contains("\"cod\":true");
    assertThat(StubServers.SHIPPING.seen.get(0).idempotencyKey()).isNotBlank();
    assertThat(count("select count(*) from orders")).isZero();
  }

  @Test void aGuestQuotesWithItsCheckoutToken() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 100000, 50000);
    var data = data(quote(quoteBody(cart, 4, "COD", "guest@example.test"), "Checkout-Token", checkoutToken(GUEST_HASH, cart)), 200);
    assertThat(tokens.verify(data.path("quote_token").asString()).actorKey()).isEqualTo("guest:" + GUEST_HASH);
    assertThat(StubServers.CART.seen.get(0).cartActor()).isEqualTo("guest:" + GUEST_HASH);
    // T21: a checkout token for another cart never reaches the cart service.
    StubServers.CART.seen.clear();
    assertThat(problem(quote(quoteBody(UUID.randomUUID(), 4, "COD", "guest@example.test"), "Checkout-Token",
        checkoutToken(GUEST_HASH, cart)), 404)).isEqualTo("CART_NOT_FOUND");
    assertThat(StubServers.CART.seen).isEmpty();
  }

  @Test void badCredentialsAreRefused() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 100000, 50000);
    String body = quoteBody(cart, 4, "COD", "guest@example.test");
    assertThat(quote(body).statusCode()).isEqualTo(401);
    assertThat(problem(quote(body, "Authorization", "Bearer nope"), 401)).isEqualTo("INVALID_ACCESS_TOKEN");
    // An invalid Bearer never falls back to the checkout token.
    assertThat(problem(quote(body, "Authorization", "Bearer nope", "Checkout-Token", checkoutToken(GUEST_HASH, cart)), 401))
        .isEqualTo("INVALID_ACCESS_TOKEN");
    assertThat(problem(quote(body, "Checkout-Token", checkoutToken(GUEST_HASH, cart, -5, "order-service")), 401))
        .isEqualTo("INVALID_CHECKOUT_TOKEN");
    assertThat(problem(quote(body, "Checkout-Token", checkoutToken(GUEST_HASH, cart, 900, "payment-service")), 401))
        .isEqualTo("INVALID_CHECKOUT_TOKEN");
    assertThat(problem(quote(body, "Checkout-Token", "garbage"), 401)).isEqualTo("INVALID_CHECKOUT_TOKEN");
    // PR review M2: a token living longer than 15 minutes (misconfigured cart TTL) is refused.
    assertThat(problem(quote(body, "Checkout-Token", checkoutToken(GUEST_HASH, cart, 86400, "order-service")), 401))
        .isEqualTo("INVALID_CHECKOUT_TOKEN");
  }

  @Test void cartAndCatalogAnswersMapToClientErrors() {
    UUID cart = UUID.randomUUID();
    String member = memberToken();
    stubCheckout(cart, 100000, 50000);
    StubServers.CART.reply(s -> new StubServers.Reply(404, StubServers.error("NOT_FOUND", "CART_NOT_FOUND")));
    assertThat(problem(quote(quoteBody(cart, 4, "COD", null), "Authorization", member), 404)).isEqualTo("CART_NOT_FOUND");
    StubServers.CART.reply(s -> new StubServers.Reply(409, StubServers.error("VERSION_CONFLICT", "VERSION_CONFLICT")));
    assertThat(problem(quote(quoteBody(cart, 3, "COD", null), "Authorization", member), 409)).isEqualTo("VERSION_CONFLICT");
    StubServers.CART.reply(s -> new StubServers.Reply(200, cartSnapshot(cart, 4)));
    assertThat(problem(quote(quoteBody(cart, 4, "COD", null), "Authorization", member), 422)).isEqualTo("CART_EMPTY");
    stubCheckout(cart, 100000, 50000);
    StubServers.CATALOG.reply(s -> new StubServers.Reply(200, StubServers.ok("[" + variant(V1, true, "TEE-M", 100000) + ","
        + variant(V2, false, "TEE-L", 50000) + "]")));
    var inactive = quote(quoteBody(cart, 4, "COD", null), "Authorization", member);
    assertThat(problem(inactive, 409)).isEqualTo("ITEM_UNAVAILABLE");
    assertThat(json(inactive).path("errors").get(0).path("field").asString()).isEqualTo(V2.toString());
    stubCheckout(cart, 100000, 50000);
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(422, StubServers.error("VALIDATION_ERROR", "NO_SHIPPING_RULE")));
    assertThat(problem(quote(quoteBody(cart, 4, "COD", null), "Authorization", member), 422)).isEqualTo("NO_SHIPPING_RULE");
  }

  @Test void inputAndAmountRules() {
    UUID cart = UUID.randomUUID();
    String member = memberToken();
    stubCheckout(cart, 100000, 50000);
    assertThat(problem(quote(quoteBody(cart, 4, "VNPAY", null), "Authorization", member), 422)).isEqualTo("PAYMENT_METHOD_UNAVAILABLE");
    assertThat(problem(quote(quoteBody(cart, 4, "CASH", null), "Authorization", member), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(quote(quoteBody(cart, 4, "COD", null).replace("}", ",\"extra\":1}"), "Authorization", member), 400))
        .isEqualTo("INVALID_FIELDS");
    assertThat(problem(quote(quoteBody(cart, 4, "COD", null).replace("0912345678\",\"province", "12ab\",\"province"),
        "Authorization", member), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(quote(quoteBody(cart, -1, "COD", null), "Authorization", member), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(quote("[]", "Authorization", member), 400)).isEqualTo("MALFORMED_JSON");
    // A guest must leave an email for the order notifications (spec section 5).
    assertThat(problem(quote(quoteBody(cart, 4, "COD", null), "Checkout-Token", checkoutToken(GUEST_HASH, cart)), 400))
        .isEqualTo("INVALID_FIELDS");
    stubCheckout(cart, Long.MAX_VALUE / 2, 50000);
    assertThat(problem(quote(quoteBody(cart, 4, "COD", null), "Authorization", member), 422)).isEqualTo("AMOUNT_OUT_OF_RANGE");
    assertThat(StubServers.SHIPPING.seen).isEmpty();
    stubCheckout(cart, 0, 0);
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(200, shippingQuote(0, 0)));
    assertThat(problem(quote(quoteBody(cart, 4, "COD", null), "Authorization", member), 422)).isEqualTo("PAYMENT_AMOUNT_UNSUPPORTED");
  }

  @Test void aSlowDependencyIs503AndWritesNothing() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 100000, 50000);
    StubServers.CATALOG.delay(1500);
    assertThat(problem(quote(quoteBody(cart, 4, "COD", null), "Authorization", memberToken()), 503)).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(count("select count(*) from orders")).isZero();
  }
}
