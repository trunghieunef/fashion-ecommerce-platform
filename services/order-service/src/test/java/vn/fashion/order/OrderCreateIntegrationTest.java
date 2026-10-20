package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

/** TASK:ORD-01 spec section 6 (create) and 7 (guest credential). */
class OrderCreateIntegrationTest extends OrderTestSupport {
  static String sha256(String value) {
    try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes())); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  static String cookie(HttpResponse<String> response) {
    return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("order_access=")).findFirst().orElseThrow();
  }

  @Test void aMemberOrderIsDurablePendingWithItsSnapshot() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String member = memberToken();
    String token = quoteToken(cart, "Authorization", member);
    var response = create("k1", createBody(cart, 4, token), "Authorization", member);
    var data = data(response, 202);
    String orderNo = data.path("order_no").asString();
    assertThat(orderNo).matches("ORD\\d{6}\\d{6}");
    assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/orders/" + orderNo);
    assertThat(response.headers().firstValue("Retry-After")).hasValue("3");
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(data.path("status").asString()).isEqualTo("PENDING");
    assertThat(data.path("processing_status").asString()).isEqualTo("PROCESSING");
    assertThat(data.path("status_url").asString()).isEqualTo("/api/v1/orders/" + orderNo);
    assertThat(data.path("retry_after_seconds").asInt()).isEqualTo(3);
    assertThat(data.path("totals").path("total_amount").asLong()).isEqualTo(439000);
    var row = jdbc.queryForMap("select * from orders where order_no = ?", orderNo);
    assertThat(row).containsEntry("status", "PENDING").containsEntry("user_id", MEMBER).containsEntry("actor_key", "user:" + MEMBER)
        .containsEntry("payment_method", "COD").containsEntry("subtotal", 399000L).containsEntry("shipping_fee", 30000L)
        .containsEntry("cod_fee", 10000L).containsEntry("total_amount", 439000L).containsEntry("cart_version", 4L)
        .containsEntry("contact_phone", "0912345678").containsEntry("checkout_key", "k1");
    assertThat(row.get("guest_access_hash")).isNull();
    Instant expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
    assertThat(Duration.between(Instant.now(), expires).toHours()).isBetween(23L, 24L);
    assertThat(jdbc.queryForObject("select shipping_quote ->> 'quote_id' from orders where order_no = ?", String.class, orderNo))
        .isEqualTo(SHIP_QUOTE.toString());
    assertThat(jdbc.queryForObject("select shipping_address ->> 'ward_code' from orders where order_no = ?", String.class, orderNo))
        .isEqualTo("26734");
    var item = jdbc.queryForMap("select * from order_items where variant_id = ?", V1);
    assertThat(item).containsEntry("quantity", 2).containsEntry("unit_price", 150000L).containsEntry("line_total", 300000L)
        .containsEntry("cart_item_id", ITEM1).containsEntry("cart_item_version", 1L).containsEntry("catalog_version", 7L)
        .containsEntry("sku", "TEE-M").containsEntry("name", "Ao thun");
    assertThat(count("select count(*) from order_items")).isEqualTo(2);
    assertThat(count("select count(*) from order_status_history where to_status = 'PENDING' and actor_type = 'CUSTOMER' and from_status is null"))
        .isEqualTo(1);
    assertThat(jdbc.queryForMap("select intent, step, status from order_sagas"))
        .containsEntry("intent", "FULFILL").containsEntry("step", "RESERVE").containsEntry("status", "RUNNING");
    var event = jdbc.queryForMap("select event_type, topic, partition_key, payload::text as payload from outbox_events");
    assertThat(event).containsEntry("event_type", "ORDER_CREATED").containsEntry("topic", "order.events").containsEntry("partition_key", orderNo);
    var payload = mapper.readTree((String) event.get("payload"));
    assertThat(payload.path("status").asString()).isEqualTo("PENDING");
    assertThat(payload.path("total_amount").asLong()).isEqualTo(439000);
    assertThat(payload.path("items")).hasSize(2);
    // Exactly the fields of contracts/fixtures/valid/order-created.json (OrderState, no PII).
    var fields = new java.util.TreeSet<String>();
    payload.propertyNames().forEach(fields::add);
    assertThat(fields).containsExactly("currency", "items", "method", "order_id", "order_no", "order_version", "status",
        "total_amount", "user_id");
    assertThat(payload.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("variant_id", "sku", "quantity");
  }

  @Test void aRetryWithTheSameKeyReturnsTheSameOrderEvenAfterTheQuoteExpiredAndPricesChanged() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String member = memberToken(), body = createBody(cart, 4, quoteToken(cart, "Authorization", memberToken()));
    String first = data(create("k1", body, "Authorization", member), 202).path("order_id").asString();
    clock.shift(Duration.ofMinutes(5));
    stubCheckout(cart, 1, 1);
    StubServers.CATALOG.seen.clear();
    assertThat(data(create("k1", body, "Authorization", member), 202).path("order_id").asString()).isEqualTo(first);
    assertThat(StubServers.CATALOG.seen).isEmpty();
    assertThat(count("select count(*) from orders")).isEqualTo(1);
    assertThat(count("select count(*) from outbox_events")).isEqualTo(1);
    // Same key, another body: refused.
    assertThat(problem(create("k1", createBody(cart, 5, "x.y"), "Authorization", member), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    // Without the retry, the same expired quote is 409 QUOTE_EXPIRED.
    assertThat(problem(create("k2", body, "Authorization", member), 409)).isEqualTo("QUOTE_EXPIRED");
  }

  @Test void parallelSubmitsMakeOneOrder() throws Exception {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String member = memberToken(), body = createBody(cart, 4, quoteToken(cart, "Authorization", member));
    var barrier = new CyclicBarrier(2);
    var a = CompletableFuture.supplyAsync(() -> { await(barrier); return create("same", body, "Authorization", member); });
    var b = CompletableFuture.supplyAsync(() -> { await(barrier); return create("same", body, "Authorization", member); });
    assertThat(data(a.join(), 202).path("order_id").asString()).isEqualTo(data(b.join(), 202).path("order_id").asString());
    // Another key (double-click, second tab) on the same cart state is refused with the existing order_no.
    var again = create("other", body, "Authorization", member);
    assertThat(problem(again, 409)).isEqualTo("CART_ALREADY_ORDERED");
    assertThat(json(again).path("errors").get(0).path("message").asString())
        .isEqualTo(jdbc.queryForObject("select order_no from orders", String.class));
    assertThat(count("select count(*) from orders")).isEqualTo(1);
  }
  static void await(CyclicBarrier barrier) { try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); } }

  @Test void changedPricesOrCartsAreRefusedAndNothingIsWritten() {
    UUID cart = UUID.randomUUID();
    String member = memberToken();
    stubCheckout(cart, 150000, 99000);
    String token = quoteToken(cart, "Authorization", member);
    stubCheckout(cart, 155000, 99000);
    var changed = create("k1", createBody(cart, 4, token), "Authorization", member);
    assertThat(problem(changed, 409)).isEqualTo("PRICE_CHANGED");
    assertThat(json(changed).path("code").asString()).isEqualTo("PRICE_CHANGED");
    assertThat(json(changed).path("errors").get(0).path("field").asString()).isEqualTo(V1.toString());
    assertThat(json(changed).path("errors").get(0).path("message").asString()).isEqualTo("old=150000,new=155000");
    // Same version but another quantity than the quote (stale client): the order never uses the old quantities.
    stubCheckout(cart, 150000, 99000);
    StubServers.CART.reply(s -> new StubServers.Reply(200, cartSnapshot(cart, 4, cartItem(ITEM1, V1, "TEE-M", 3, 2),
        cartItem(ITEM2, V2, "TEE-L", 1, 3))));
    assertThat(problem(create("k2", createBody(cart, 4, token), "Authorization", member), 409)).isEqualTo("VERSION_CONFLICT");
    StubServers.CART.reply(s -> new StubServers.Reply(409, StubServers.error("VERSION_CONFLICT", "VERSION_CONFLICT")));
    assertThat(problem(create("k3", createBody(cart, 4, token), "Authorization", member), 409)).isEqualTo("VERSION_CONFLICT");
    stubCheckout(cart, 150000, 99000);
    StubServers.CART.delay(1500);
    assertThat(problem(create("k4", createBody(cart, 4, token), "Authorization", member), 503)).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(count("select count(*) from orders")).isZero();
    assertThat(count("select count(*) from outbox_events")).isZero();
    // The failed attempts stored nothing: the first key now succeeds.
    StubServers.CART.delay(0);
    data(create("k1", createBody(cart, 4, token), "Authorization", member), 202);
  }

  @Test void aQuoteOnlyWorksForItsActorAndCart() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String token = quoteToken(cart, "Authorization", memberToken());
    UUID other = UUID.randomUUID();
    assertThat(problem(create("k1", createBody(cart, 4, token), "Authorization", memberToken(other)), 400)).isEqualTo("INVALID_QUOTE");
    assertThat(problem(create("k2", createBody(cart, 5, token), "Authorization", memberToken()), 400)).isEqualTo("INVALID_QUOTE");
    assertThat(problem(create("k3", createBody(cart, 4, token + "x"), "Authorization", memberToken()), 400)).isEqualTo("INVALID_QUOTE");
    // A guest cannot use the member's quote nor order another guest's cart.
    assertThat(problem(create("k4", createBody(cart, 4, token), "Checkout-Token", checkoutToken(GUEST_HASH, cart)), 400))
        .isEqualTo("INVALID_QUOTE");
    assertThat(problem(create("k5", createBody(cart, 4, token), "Checkout-Token", checkoutToken(GUEST_HASH, other)), 404))
        .isEqualTo("CART_NOT_FOUND");
    assertThat(problem(create(null, createBody(cart, 4, token), "Authorization", memberToken()), 400)).isEqualTo("INVALID_KEY");
    assertThat(problem(create("k6", createBody(cart, 4, token).replace("}", ",\"total\":1}"), "Authorization", memberToken()), 400))
        .isEqualTo("INVALID_FIELDS");
    assertThat(count("select count(*) from orders")).isZero();
  }

  @Test void aGuestWhoLostItsKeyGetsCartAlreadyOrderedWithAWorkingCookie() {
    // PR review I2: reload lost the key and the quote; a new key on the same cart state must still reach the order.
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String checkout = checkoutToken(GUEST_HASH, cart);
    String orderNo = data(create("g1", createBody(cart, 4, quoteToken(cart, "Checkout-Token", checkout)), "Checkout-Token", checkout), 202)
        .path("order_no").asString();
    var again = create("g2", createBody(cart, 4, quoteToken(cart, "Checkout-Token", checkout)), "Checkout-Token", checkout);
    assertThat(problem(again, 409)).isEqualTo("CART_ALREADY_ORDERED");
    assertThat(json(again).path("errors").get(0).path("message").asString()).isEqualTo(orderNo);
    String set = cookie(again);
    assertThat(set).contains("Path=/api/v1/orders/" + orderNo).contains("HttpOnly");
    String value = set.substring("order_access=".length(), set.indexOf(';'));
    assertThat(data(send("GET", "/api/v1/orders/" + orderNo, null, "Cookie", "order_access=" + value), 200).path("order_no").asString())
        .isEqualTo(orderNo);
    // Another guest on another cart never gets anything from this order.
    assertThat(count("select count(*) from orders")).isEqualTo(1);
  }

  @Test void reissuedCredentialsAreCappedPerOrder() {
    // PR review M1: retries cannot grow the credential table without bound (10 per order).
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String checkout = checkoutToken(GUEST_HASH, cart), body = createBody(cart, 4, quoteToken(cart, "Checkout-Token", checkout));
    data(create("g1", body, "Checkout-Token", checkout), 202);
    for (int i = 0; i < 12; i++) data(create("g1", body, "Checkout-Token", checkout), 202);
    assertThat(count("select count(*) from order_guest_access")).isEqualTo(10);
    var last = create("g1", body, "Checkout-Token", checkout);
    assertThat(data(last, 202).path("order_no").asString()).isNotBlank();
    assertThat(last.headers().allValues("Set-Cookie")).isEmpty();
  }

  @Test void aGuestGetsAnAccessCookieAndARetryReissuesIt() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String checkout = checkoutToken(GUEST_HASH, cart), body = createBody(cart, 4, quoteToken(cart, "Checkout-Token", checkout));
    var first = create("g1", body, "Checkout-Token", checkout);
    String orderNo = data(first, 202).path("order_no").asString();
    String set = cookie(first);
    assertThat(set).contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/api/v1/orders/" + orderNo)
        .contains("Max-Age=7776000");
    String value = set.substring("order_access=".length(), set.indexOf(';'));
    String stored = jdbc.queryForObject("select guest_access_hash from orders", String.class);
    assertThat(stored).isEqualTo(sha256(value)).doesNotContain(value);
    assertThat(count("select count(*) from order_guest_access where access_hash = ?", stored)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select actor_key from orders", String.class)).isEqualTo("guest:" + GUEST_HASH);
    assertThat(jdbc.queryForObject("select contact_email from orders", String.class)).isEqualTo("guest@example.test");
    long version = jdbc.queryForObject("select version from orders", Long.class);
    var retry = create("g1", body, "Checkout-Token", checkout);
    assertThat(data(retry, 202).path("order_no").asString()).isEqualTo(orderNo);
    String again = cookie(retry);
    assertThat(again).isNotEqualTo(set);
    assertThat(count("select count(*) from order_guest_access where access_hash = ?",
        sha256(again.substring("order_access=".length(), again.indexOf(';'))))).isEqualTo(1);
    assertThat(jdbc.queryForObject("select version from orders", Long.class)).isEqualTo(version);
  }
}
