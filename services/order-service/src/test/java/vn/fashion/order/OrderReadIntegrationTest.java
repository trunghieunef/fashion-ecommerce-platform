package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TASK:ORD-01 spec section 8: owner-only reads (T21, REQ ORD-05). */
class OrderReadIntegrationTest extends OrderTestSupport {
  static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(100000);
  /** Inserts an order directly; minutesAgo orders the list. Returns the order_no. */
  private String seed(UUID userId, String accessHash, String status, int minutesAgo) {
    UUID id = UUID.randomUUID();
    String orderNo = "TST" + SEQ.incrementAndGet();  // PR review M3: unique, never the ORD shape of the unknown number
    jdbc.update("""
        insert into orders(id, order_no, user_id, actor_key, guest_access_hash, checkout_key, checkout_hash, cart_id, cart_version,
          status, payment_method, subtotal, shipping_fee, cod_fee, total_amount, contact_phone, contact_email, shipping_address,
          shipping_quote, expires_at, created_at)
        values (?, ?, ?, ?, ?, ?, repeat('a', 64), gen_random_uuid(), 1, ?, 'COD', 100000, 20000, 5000, 125000, '0912345678',
          'a@example.test', '{"recipient_name":"Synthetic Person","phone":"0912345678","province_code":"79","ward_code":"26734",
          "address_line":"1 Synthetic St"}', '{}', now() + interval '1 day', now() - make_interval(mins => ?))""",
        id, orderNo, userId, userId == null ? "guest:" + GUEST_HASH : "user:" + userId, accessHash, id.toString(), status, minutesAgo);
    jdbc.update("""
        insert into order_items(id, order_id, variant_id, sku, name, size, color, quantity, unit_price, line_total, catalog_version,
          cart_item_id, cart_item_version) values (gen_random_uuid(), ?, ?, 'TEE-M', 'Ao thun', 'M', 'Den', 1, 100000, 100000, 7,
          gen_random_uuid(), 1)""", id, V1);
    if (accessHash != null) jdbc.update("insert into order_guest_access(access_hash, order_id) values (?, ?)", accessHash, id);
    return orderNo;
  }
  static String sha256(String value) { return OrderCreateIntegrationTest.sha256(value); }
  private HttpResponse<String> get(String path, String... headers) { return send("GET", path, null, headers); }

  @Test void membersReadOnlyTheirOwnOrders() {
    String mine = seed(MEMBER, null, "PENDING", 1);
    var data = data(get("/api/v1/orders/" + mine, "Authorization", memberToken()), 200);
    assertThat(data.path("order_no").asString()).isEqualTo(mine);
    assertThat(data.path("processing_status").asString()).isEqualTo("PROCESSING");
    assertThat(data.path("allowed_actions")).isEmpty();
    assertThat(data.path("items")).hasSize(1);
    assertThat(data.path("totals").path("total_amount").asLong()).isEqualTo(125000);
    assertThat(data.path("shipping_address").path("ward_code").asString()).isEqualTo("26734");
    assertThat(data.path("contact").path("email").asString()).isEqualTo("a@example.test");
    // Another member, no credential and an unknown number all look the same (T21).
    var other = get("/api/v1/orders/" + mine, "Authorization", memberToken(UUID.randomUUID()));
    var unknown = get("/api/v1/orders/ORD202610999999", "Authorization", memberToken());
    assertThat(problem(other, 404)).isEqualTo("ORDER_NOT_FOUND");
    assertThat(problem(unknown, 404)).isEqualTo("ORDER_NOT_FOUND");
    assertThat(problem(get("/api/v1/orders/" + mine), 404)).isEqualTo("ORDER_NOT_FOUND");
    assertThat(problem(get("/api/v1/orders/" + mine, "Authorization", "Bearer nope"), 401)).isEqualTo("INVALID_ACCESS_TOKEN");
  }

  @Test void processingStatusFollowsTheSaga() {
    // ORD-02 spec section 8: CONFIRMED is READY, CANCELLED is FINISHED with its reason; only PROCESSING asks to poll.
    String confirmed = seed(MEMBER, null, "CONFIRMED", 1), cancelled = seed(MEMBER, null, "CANCELLED", 2);
    jdbc.update("update orders set cancel_reason = 'OUT_OF_STOCK' where order_no = ?", cancelled);
    var c = data(get("/api/v1/orders/" + confirmed, "Authorization", memberToken()), 200);
    assertThat(c.path("processing_status").asString()).isEqualTo("READY");
    assertThat(c.path("retry_after_seconds").isNull()).isTrue();
    assertThat(c.path("reason_code").isNull()).isTrue();
    var x = data(get("/api/v1/orders/" + cancelled, "Authorization", memberToken()), 200);
    assertThat(x.path("processing_status").asString()).isEqualTo("FINISHED");
    assertThat(x.path("reason_code").asString()).isEqualTo("OUT_OF_STOCK");
    String cancelling = seed(MEMBER, null, "CANCELLING", 3);
    assertThat(data(get("/api/v1/orders/" + cancelling, "Authorization", memberToken()), 200).path("processing_status").asString())
        .isEqualTo("PROCESSING");
  }

  @Test void processingStatusFollowsFulfilment() {
    // ORD-03 spec section 3: PACKING waits for the shipment, SHIPPING is READY, DELIVERED is FINISHED; ORD-04: RETURNING is READY.
    String packing = seed(MEMBER, null, "PACKING", 1), packed = seed(MEMBER, null, "PACKING", 2), shipping = seed(MEMBER, null, "SHIPPING", 3),
        delivered = seed(MEMBER, null, "DELIVERED", 4), confirmed = seed(MEMBER, null, "CONFIRMED", 5),
        returning = seed(MEMBER, null, "RETURNING", 6);
    jdbc.update("""
        insert into order_sagas(order_id, step, status, shipment_id)
        select id, case when order_no = ? then 'COMMIT_STOCK' else 'DONE' end, case when order_no = ? then 'RUNNING' else 'DONE' end,
          case when order_no = ? then null else gen_random_uuid() end from orders where order_no in (?, ?, ?, ?, ?)""",
        confirmed, confirmed, packing, packing, packed, shipping, delivered, confirmed);
    var expected = new java.util.LinkedHashMap<String, String>();
    expected.put(packing, "PROCESSING"); expected.put(packed, "READY"); expected.put(shipping, "READY");
    expected.put(delivered, "FINISHED"); expected.put(confirmed, "PROCESSING"); expected.put(returning, "READY");
    expected.forEach((no, status) -> assertThat(data(get("/api/v1/orders/" + no, "Authorization", memberToken()), 200)
        .path("processing_status").asString()).as(no).isEqualTo(status));
  }

  @Test void allowedActionsOfferCancelOnlyWhileWaitingForTheShop() {
    // TASK:ORD-04 spec section 3: CANCEL only for CONFIRMED + saga FULFILL/WAIT_OPS.
    String waiting = seed(MEMBER, null, "CONFIRMED", 1), cancelled = seed(MEMBER, null, "CANCELLED", 2);
    jdbc.update("insert into order_sagas(order_id, step, status) select id, 'WAIT_OPS', 'WAITING' from orders where order_no in (?, ?)",
        waiting, cancelled);
    var actions = (java.util.function.Function<String, String>) no ->
        data(get("/api/v1/orders/" + no, "Authorization", memberToken()), 200).path("allowed_actions").toString();
    assertThat(actions.apply(waiting)).isEqualTo("[\"CANCEL\"]");
    assertThat(actions.apply(cancelled)).isEqualTo("[]");
    jdbc.update("update order_sagas set step = 'COMMIT_STOCK', status = 'RUNNING' where order_id = (select id from orders where order_no = ?)", waiting);
    assertThat(actions.apply(waiting)).isEqualTo("[]");
  }

  @Test void guestsReadOnlyTheOrderOfTheirCookie() {
    String token = "t".repeat(43), otherToken = "u".repeat(43);
    String order = seed(null, sha256(token), "PENDING", 1), other = seed(null, sha256(otherToken), "PENDING", 2);
    assertThat(data(get("/api/v1/orders/" + order, "Cookie", "order_access=" + token), 200).path("order_no").asString()).isEqualTo(order);
    assertThat(problem(get("/api/v1/orders/" + other, "Cookie", "order_access=" + token), 404)).isEqualTo("ORDER_NOT_FOUND");
    assertThat(problem(get("/api/v1/orders/" + order, "Cookie", "order_access=" + otherToken), 404)).isEqualTo("ORDER_NOT_FOUND");
    // A guest order is never readable by a member token, even the member's own (no user_id on it).
    assertThat(problem(get("/api/v1/orders/" + order, "Authorization", memberToken()), 404)).isEqualTo("ORDER_NOT_FOUND");
  }

  @Test void aGuestCredentialExpiresAfter90DaysOnTheServer() {
    // PR review I1 (O06): the 90 days are enforced by the server, not only by the cookie Max-Age.
    String token = "v".repeat(43);
    String order = seed(null, sha256(token), "PENDING", 1);
    asOwner("update order_guest_access set created_at = now() - interval '91 days'");
    assertThat(problem(get("/api/v1/orders/" + order, "Cookie", "order_access=" + token), 404)).isEqualTo("ORDER_NOT_FOUND");
    asOwner("update order_guest_access set created_at = now() - interval '89 days'");
    assertThat(data(get("/api/v1/orders/" + order, "Cookie", "order_access=" + token), 200).path("order_no").asString()).isEqualTo(order);
  }

  @Test void aReissuedCookieIsAddedAndEarlierCookiesStayValid() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String checkout = checkoutToken(GUEST_HASH, cart), body = createBody(cart, 4, quoteToken(cart, "Checkout-Token", checkout));
    var first = create("g1", body, "Checkout-Token", checkout);
    String orderNo = data(first, 202).path("order_no").asString();
    String old = cookieValue(first), fresh = cookieValue(create("g1", body, "Checkout-Token", checkout));
    assertThat(fresh).isNotEqualTo(old);
    // Review I1: whichever Set-Cookie the browser keeps last, it can read its order.
    assertThat(data(get("/api/v1/orders/" + orderNo, "Cookie", "order_access=" + old), 200).path("order_no").asString()).isEqualTo(orderNo);
    assertThat(data(get("/api/v1/orders/" + orderNo, "Cookie", "order_access=" + fresh), 200).path("order_no").asString()).isEqualTo(orderNo);
  }

  @Test void parallelGuestSubmitsBothGetAWorkingCookie() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String checkout = checkoutToken(GUEST_HASH, cart), body = createBody(cart, 4, quoteToken(cart, "Checkout-Token", checkout));
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    var a = java.util.concurrent.CompletableFuture.supplyAsync(() -> { OrderCreateIntegrationTest.await(barrier); return create("same", body, "Checkout-Token", checkout); });
    var b = java.util.concurrent.CompletableFuture.supplyAsync(() -> { OrderCreateIntegrationTest.await(barrier); return create("same", body, "Checkout-Token", checkout); });
    var ra = a.join(); var rb = b.join();
    String orderNo = data(ra, 202).path("order_no").asString();
    assertThat(data(rb, 202).path("order_no").asString()).isEqualTo(orderNo);
    for (var response : java.util.List.of(ra, rb))
      assertThat(data(get("/api/v1/orders/" + orderNo, "Cookie", "order_access=" + cookieValue(response)), 200).path("order_no").asString())
          .isEqualTo(orderNo);
    assertThat(count("select count(*) from orders")).isEqualTo(1);
  }
  static String cookieValue(HttpResponse<String> response) {
    String set = OrderCreateIntegrationTest.cookie(response);
    return set.substring("order_access=".length(), set.indexOf(';'));
  }

  @Test void mineListsTheMembersOrdersNewestFirstWithACursor() {
    var expected = new ArrayList<String>();
    for (int i = 0; i < 25; i++) expected.add(seed(MEMBER, null, i % 5 == 0 ? "CANCELLED" : "PENDING", i));
    seed(UUID.randomUUID(), null, "PENDING", 0);
    String member = memberToken();
    var page1 = data(get("/api/v1/orders/mine", "Authorization", member), 200);
    assertThat(page1.path("items")).hasSize(20);
    assertThat(page1.path("items").get(0).path("order_no").asString()).isEqualTo(expected.get(0));
    assertThat(page1.path("items").get(0).path("item_count").asInt()).isEqualTo(1);
    assertThat(page1.path("items").get(0).path("total_amount").asLong()).isEqualTo(125000);
    String cursor = page1.path("next_cursor").asString();
    var page2 = data(get("/api/v1/orders/mine?cursor=" + cursor, "Authorization", member), 200);
    assertThat(page2.path("items")).hasSize(5);
    assertThat(page2.path("items").get(4).path("order_no").asString()).isEqualTo(expected.get(24));
    assertThat(page2.path("next_cursor").isNull()).isTrue();
    var cancelled = data(get("/api/v1/orders/mine?status=CANCELLED&limit=2", "Authorization", member), 200);
    assertThat(cancelled.path("items")).hasSize(2);
    assertThat(cancelled.path("items").get(1).path("order_no").asString()).isEqualTo(expected.get(5));
    // A cursor is bound to its filter.
    assertThat(problem(get("/api/v1/orders/mine?status=PENDING&cursor=" + cancelled.path("next_cursor").asString(),
        "Authorization", member), 400)).isEqualTo("INVALID_CURSOR");
    assertThat(problem(get("/api/v1/orders/mine?cursor=abc", "Authorization", member), 400)).isEqualTo("INVALID_CURSOR");
    assertThat(problem(get("/api/v1/orders/mine?limit=101", "Authorization", member), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(get("/api/v1/orders/mine?status=LOST", "Authorization", member), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(get("/api/v1/orders/mine", "Cookie", "order_access=" + "t".repeat(43)).statusCode()).isEqualTo(401);
  }
}
