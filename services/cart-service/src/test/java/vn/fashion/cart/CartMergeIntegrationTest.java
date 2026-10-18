package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** CART-02 spec sections 2-3 / T19: guest cart folds into the member cart exactly once, adjustments reported. */
class CartMergeIntegrationTest extends CartTestSupport {
  final Set<String> inactive = ConcurrentHashMap.newKeySet();
  final Map<String, Integer> stockBySku = new ConcurrentHashMap<>();
  String guestCart, cookie, member;
  long guestVersion, memberVersion;

  static String sku(String variant) { return "M-" + variant.substring(0, 8).toUpperCase(); }

  @BeforeEach void setup() {
    StubServers.CATALOG.reset(); StubServers.INVENTORY.reset();
    StubServers.CATALOG.reply(s -> {
      var ids = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(s.body()).results()
          .map(m -> StubServers.quoteItem(m.group(), !inactive.contains(m.group()), sku(m.group()), 1000, 1)).toList();
      return new StubServers.Reply(200, StubServers.ok("[" + String.join(",", ids) + "]"));
    });
    StubServers.INVENTORY.reply(s -> {
      String sku = s.path().substring(s.path().lastIndexOf('/') + 1);
      return new StubServers.Reply(200, StubServers.stock(sku, stockBySku.getOrDefault(sku, 50)));
    });
    var r = send("POST", "/api/v1/carts", null);
    var cart = data(r, 201);
    guestCart = cart.path("cart_id").asString(); guestVersion = 0;
    cookie = "cart_token=" + CartIdentityIntegrationTest.token(r);
    member = memberToken(UUID.randomUUID());
    memberVersion = -1;
  }

  void openMember() {
    var cart = json(send("POST", "/api/v1/carts", null, "Authorization", "Bearer " + member)).path("data");
    memberVersion = cart.path("version").asLong();
  }
  String memberCartId() { return json(send("GET", "/api/v1/carts/mine", null, "Authorization", "Bearer " + member)).path("data").path("cart_id").asString(); }
  JsonNode addGuest(String variant, int qty) {
    var cart = data(send("POST", "/api/v1/carts/" + guestCart + "/items", "{\"variant_id\":\"" + variant + "\",\"quantity\":" + qty
        + ",\"expected_version\":" + guestVersion + "}", "Cookie", cookie, "Idempotency-Key", UUID.randomUUID().toString()), 200);
    guestVersion = cart.path("version").asLong();
    return cart;
  }
  JsonNode addMember(String variant, int qty) {
    if (memberVersion < 0) openMember();
    var cart = data(send("POST", "/api/v1/carts/" + memberCartId() + "/items", "{\"variant_id\":\"" + variant + "\",\"quantity\":" + qty
        + ",\"expected_version\":" + memberVersion + "}", "Authorization", "Bearer " + member, "Idempotency-Key", UUID.randomUUID().toString()), 200);
    memberVersion = cart.path("version").asLong();
    return cart;
  }
  String body(long gv, long mv) {
    return "{\"guest_cart_id\":\"" + guestCart + "\",\"guest_expected_version\":" + gv + ",\"member_expected_version\":" + mv + "}";
  }
  HttpResponse<String> merge(String key, String body, String... headers) {
    var all = new ArrayList<>(List.of(headers));
    if (key != null) { all.add("Idempotency-Key"); all.add(key); }
    return send("POST", "/api/v1/carts/merge", body, all.toArray(String[]::new));
  }
  HttpResponse<String> merge(String key) {
    return merge(key, body(guestVersion, Math.max(memberVersion, 0)), "Authorization", "Bearer " + member, "Cookie", cookie);
  }
  int qty(JsonNode cart, String variant) {
    for (var item : cart.path("items")) if (item.path("variant_id").asString().equals(variant)) return item.path("quantity").asInt();
    return 0;
  }
  JsonNode adjustment(JsonNode result, String variant) {
    for (var a : result.path("adjustments")) if (a.path("variant_id").asString().equals(variant)) return a;
    return null;
  }
  String status(String cartId) { return jdbc.queryForObject("select status from carts where id = cast(? as uuid)", String.class, cartId); }

  @Test void mergeSumsAndKeepsOlderCreatedAt() {
    String shared = UUID.randomUUID().toString();
    addGuest(shared, 2);
    addMember(shared, 3);
    var r = merge(UUID.randomUUID().toString());
    var result = data(r, 200);
    assertThat(qty(result.path("cart"), shared)).isEqualTo(5);
    assertThat(result.path("adjustments")).isEmpty();
    assertThat(result.path("merged_from").asString()).isEqualTo(guestCart);
    assertThat(result.path("already_merged").asBoolean()).isFalse();
    var created = jdbc.queryForList("select created_at from cart_items where variant_id = cast(? as uuid) order by created_at", java.sql.Timestamp.class, shared);
    var kept = jdbc.queryForObject("select i.created_at from cart_items i join carts c on c.id = i.cart_id where c.user_id is not null and i.variant_id = cast(? as uuid)",
        java.sql.Timestamp.class, shared);
    assertThat(kept).isEqualTo(created.get(0));
    assertThat(result.path("cart").path("version").asLong()).isEqualTo(memberVersion + 1);
  }

  @Test void newVariantAddsLineAndGuestIsMerged() {
    String v = UUID.randomUUID().toString();
    addGuest(v, 2);
    var r = merge(UUID.randomUUID().toString());
    var result = data(r, 200);
    assertThat(qty(result.path("cart"), v)).isEqualTo(2);
    assertThat(result.path("cart").path("owner").asString()).isEqualTo("MEMBER");
    assertThat(status(guestCart)).isEqualTo("MERGED");
    assertThat(jdbc.queryForObject("select merged_into::text from carts where id = cast(? as uuid)", String.class, guestCart))
        .isEqualTo(result.path("cart").path("cart_id").asString());
    assertThat(jdbc.queryForObject("select version from carts where id = cast(? as uuid)", Long.class, guestCart)).isEqualTo(guestVersion + 1);
    assertThat(r.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("cart_token=;") && c.contains("Max-Age=0") && c.contains("Path=/api/v1/carts"));
    assertThat(problem(send("GET", "/api/v1/carts/" + guestCart, null, "Cookie", cookie), 404)).isEqualTo("CART_NOT_FOUND");
  }

  @Test void memberWithoutCartGetsOne() {
    String v = UUID.randomUUID().toString();
    addGuest(v, 1);
    var result = data(merge(UUID.randomUUID().toString(), body(guestVersion, 0), "Authorization", "Bearer " + member, "Cookie", cookie), 200);
    assertThat(qty(result.path("cart"), v)).isEqualTo(1);
    assertThat(result.path("cart").path("version").asLong()).isEqualTo(1);
  }

  @Test void retrySameKeyReplays() {
    String v = UUID.randomUUID().toString();
    addGuest(v, 2);
    String key = UUID.randomUUID().toString();
    var first = data(merge(key), 200);
    assertThat(data(merge(key), 200)).isEqualTo(first);
    assertThat(qty(data(send("GET", "/api/v1/carts/mine", null, "Authorization", "Bearer " + member), 200), v)).isEqualTo(2);
  }

  @Test void retryOtherKeyAfterMerge() {
    String v = UUID.randomUUID().toString();
    addGuest(v, 2);
    data(merge(UUID.randomUUID().toString()), 200);
    int calls = StubServers.CATALOG.seen.size() + StubServers.INVENTORY.seen.size();
    var again = data(merge(UUID.randomUUID().toString()), 200);
    assertThat(again.path("already_merged").asBoolean()).isTrue();
    assertThat(again.path("adjustments")).isEmpty();
    assertThat(qty(again.path("cart"), v)).isEqualTo(2);
    assertThat(StubServers.CATALOG.seen.size() + StubServers.INVENTORY.seen.size()).isEqualTo(calls);
  }

  @Test void parallelMergeAddsOnce() throws Exception {
    String v = UUID.randomUUID().toString(), fresh = UUID.randomUUID().toString();
    addGuest(v, 2);
    addGuest(fresh, 1);
    addMember(v, 1);
    var barrier = new CyclicBarrier(3);
    var calls = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < 3; i++) calls.add(CompletableFuture.supplyAsync(() -> {
      try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); }
      return merge(UUID.randomUUID().toString());
    }));
    for (var c : calls) assertThat(c.get().statusCode()).withFailMessage(c.get().body()).isEqualTo(200);
    var mine = data(send("GET", "/api/v1/carts/mine", null, "Authorization", "Bearer " + member), 200);
    assertThat(qty(mine, v)).isEqualTo(3);
    assertThat(qty(mine, fresh)).isEqualTo(1);
    assertThat(mine.path("version").asLong()).isEqualTo(memberVersion + 1);
  }

  @Test void staleVersionsAre409() {
    addGuest(UUID.randomUUID().toString(), 1);
    openMember();
    var stale = merge(UUID.randomUUID().toString(), body(guestVersion - 1, memberVersion), "Authorization", "Bearer " + member, "Cookie", cookie);
    assertThat(problem(stale, 409)).isEqualTo("VERSION_CONFLICT");
    assertThat(json(stale).path("errors").get(0).path("field").asString()).isEqualTo("guest_expected_version");
    var staleMember = merge(UUID.randomUUID().toString(), body(guestVersion, memberVersion + 1), "Authorization", "Bearer " + member, "Cookie", cookie);
    assertThat(problem(staleMember, 409)).isEqualTo("VERSION_CONFLICT");
    assertThat(json(staleMember).path("errors").get(0).path("field").asString()).isEqualTo("member_expected_version");
    assertThat(status(guestCart)).isEqualTo("ACTIVE");
  }

  @Test void adjustments() {
    String gone = UUID.randomUUID().toString(), empty = UUID.randomUUID().toString(), capped = UUID.randomUUID().toString(),
        low = UUID.randomUUID().toString();
    stockBySku.put(sku(capped), 200);
    addGuest(gone, 1); addGuest(empty, 2); addGuest(capped, 60); addGuest(low, 5);
    addMember(capped, 50);
    inactive.add(gone); stockBySku.put(sku(empty), 0); stockBySku.put(sku(low), 3); stockBySku.put(sku(capped), 50);
    var result = data(merge(UUID.randomUUID().toString()), 200);
    var cart = result.path("cart");
    assertThat(qty(cart, gone)).isZero();
    assertThat(adjustment(result, gone).path("reason").asString()).isEqualTo("UNAVAILABLE");
    assertThat(adjustment(result, gone).path("applied").asInt()).isZero();
    assertThat(qty(cart, empty)).isZero();
    assertThat(adjustment(result, empty).path("reason").asString()).isEqualTo("OUT_OF_STOCK");
    assertThat(qty(cart, capped)).isEqualTo(50);
    assertThat(adjustment(result, capped).path("reason").asString()).isEqualTo("QUANTITY_CAPPED");
    assertThat(adjustment(result, capped).path("requested").asInt()).isEqualTo(60);
    assertThat(adjustment(result, capped).path("applied").asInt()).isZero();
    assertThat(qty(cart, low)).isEqualTo(3);
    assertThat(adjustment(result, low).path("reason").asString()).isEqualTo("QUANTITY_CAPPED");
    assertThat(adjustment(result, low).path("requested").asInt()).isEqualTo(5);
    assertThat(adjustment(result, low).path("applied").asInt()).isEqualTo(3);
    assertThat(adjustment(result, low).path("sku").asString()).isEqualTo(sku(low));
  }

  @Test void quantityCappedAt99() {
    String v = UUID.randomUUID().toString();
    stockBySku.put(sku(v), 500);
    addGuest(v, 60); addMember(v, 50);
    var result = data(merge(UUID.randomUUID().toString()), 200);
    assertThat(qty(result.path("cart"), v)).isEqualTo(99);
    assertThat(adjustment(result, v).path("applied").asInt()).isEqualTo(49);
  }

  @Test void memberQuantityNeverReduced() {
    String v = UUID.randomUUID().toString();
    addGuest(v, 2); addMember(v, 5);
    stockBySku.put(sku(v), 1);
    var result = data(merge(UUID.randomUUID().toString()), 200);
    assertThat(qty(result.path("cart"), v)).isEqualTo(5);
    assertThat(adjustment(result, v).path("reason").asString()).isEqualTo("QUANTITY_CAPPED");
  }

  @Test void multiSkuMergeFitsGatewayBudget() {
    // PR #27 review P2: 3 SKUs at 800 ms each took 2.4 s sequentially, past the Gateway 2 s response timeout.
    for (int i = 0; i < 3; i++) addGuest(UUID.randomUUID().toString(), 1);
    StubServers.INVENTORY.delay(800);
    long start = System.nanoTime();
    var result = data(merge(UUID.randomUUID().toString()), 200);
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofMillis(1800));
    assertThat(result.path("cart").path("items")).hasSize(3);
    assertThat(result.path("adjustments")).isEmpty();
  }

  @Test void cappedLineKeepsOlderCreatedAt() {
    // PR #27 review P3: applied = 0 must still keep the older created_at (spec section 3.5).
    String v = UUID.randomUUID().toString();
    addGuest(v, 2); addMember(v, 5);
    jdbc.update("update cart_items set created_at = timestamptz '2026-10-01T00:00:00Z' where cart_id = cast(? as uuid)", guestCart);
    stockBySku.put(sku(v), 1);
    var result = data(merge(UUID.randomUUID().toString()), 200);
    assertThat(adjustment(result, v).path("applied").asInt()).isZero();
    assertThat(jdbc.queryForObject("""
        select i.created_at from cart_items i join carts c on c.id = i.cart_id where c.user_id is not null and i.variant_id = cast(? as uuid)""",
        java.time.OffsetDateTime.class, v).toInstant()).isEqualTo(java.time.Instant.parse("2026-10-01T00:00:00Z"));
  }

  @Test void cartItemLimit() {
    openMember();
    String cartId = memberCartId();
    for (int i = 0; i < 100; i++) jdbc.update("""
        insert into cart_items(id, cart_id, variant_id, sku, quantity, price_snapshot, catalog_version)
        values (?, cast(? as uuid), ?, ?, 1, 1000, 1)""", UUID.randomUUID(), cartId, UUID.randomUUID(), "FILL-" + i);
    String v = UUID.randomUUID().toString();
    addGuest(v, 1);
    var result = data(merge(UUID.randomUUID().toString()), 200);
    assertThat(adjustment(result, v).path("reason").asString()).isEqualTo("CART_ITEM_LIMIT");
    assertThat(result.path("cart").path("items")).hasSize(100);
  }

  @Test void emptyGuestCart() {
    openMember();
    var result = data(merge(UUID.randomUUID().toString()), 200);
    assertThat(result.path("adjustments")).isEmpty();
    assertThat(result.path("cart").path("version").asLong()).isEqualTo(memberVersion + 1);
    assertThat(status(guestCart)).isEqualTo("MERGED");
  }

  @Test void noBearerIs401() {
    assertThat(problem(merge(UUID.randomUUID().toString(), body(0, 0), "Cookie", cookie), 401)).isEqualTo("INVALID_ACCESS_TOKEN");
    assertThat(problem(merge(UUID.randomUUID().toString(), body(0, 0), "Authorization", "Bearer nope", "Cookie", cookie), 401))
        .isEqualTo("INVALID_ACCESS_TOKEN");
  }

  @Test void noCookieOrOtherGuestCartIs404() {
    assertThat(problem(merge(UUID.randomUUID().toString(), body(0, 0), "Authorization", "Bearer " + member), 404)).isEqualTo("CART_NOT_FOUND");
    var other = send("POST", "/api/v1/carts", null);
    String otherCookie = "cart_token=" + CartIdentityIntegrationTest.token(other);
    assertThat(problem(merge(UUID.randomUUID().toString(), body(0, 0), "Authorization", "Bearer " + member, "Cookie", otherCookie), 404))
        .isEqualTo("CART_NOT_FOUND");
    assertThat(status(guestCart)).isEqualTo("ACTIVE");
  }

  @Test void expiredGuestIs404() {
    jdbc.update("update carts set expires_at = now() - interval '1 minute' where id = cast(? as uuid)", guestCart);
    assertThat(problem(merge(UUID.randomUUID().toString()), 404)).isEqualTo("CART_NOT_FOUND");
  }

  @Test void validation() {
    String h = "Bearer " + member;
    assertThat(problem(merge(null, body(0, 0), "Authorization", h, "Cookie", cookie), 400)).isEqualTo("INVALID_KEY");
    for (String bad : List.of("{}", "{\"guest_cart_id\":\"" + guestCart + "\",\"guest_expected_version\":0}",
        "{\"guest_cart_id\":\"x\",\"guest_expected_version\":0,\"member_expected_version\":0}",
        "{\"guest_cart_id\":\"" + guestCart + "\",\"guest_expected_version\":-1,\"member_expected_version\":0}")) {
      var r = merge(UUID.randomUUID().toString(), bad, "Authorization", h, "Cookie", cookie);
      assertThat(r.statusCode()).withFailMessage(bad + " -> " + r.body()).isEqualTo(400);
    }
    var reused = UUID.randomUUID().toString();
    openMember();
    addGuest(UUID.randomUUID().toString(), 1);
    var stale = merge(reused, body(guestVersion, memberVersion + 5), "Authorization", h, "Cookie", cookie);
    assertThat(stale.statusCode()).isEqualTo(409);
    data(merge(reused), 200);
    assertThat(problem(merge(reused, body(guestVersion, memberVersion + 9), "Authorization", h, "Cookie", cookie), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test void dependencyDownIs503NoWrite() {
    addGuest(UUID.randomUUID().toString(), 1);
    StubServers.CATALOG.reply(s -> new StubServers.Reply(503, "{}"));
    assertThat(problem(merge(UUID.randomUUID().toString()), 503)).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(status(guestCart)).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("select count(*) from carts where user_id is not null and version > 0", Integer.class)).isZero();
  }

  @Test void noLockDuringDependencyCall() throws Exception {
    addGuest(UUID.randomUUID().toString(), 1);
    StubServers.INVENTORY.delay(800);
    var pending = CompletableFuture.supplyAsync(() -> merge(UUID.randomUUID().toString()));
    Thread.sleep(300);
    // While merge waits on inventory, nobody holds the guest cart row: NOWAIT would fail with 55P03 otherwise.
    assertThat(jdbc.queryForList("select id from carts where id = cast(? as uuid) for update nowait", String.class, guestCart)).hasSize(1);
    assertThat(pending.get().statusCode()).withFailMessage(pending.get().body()).isEqualTo(200);
  }
}
