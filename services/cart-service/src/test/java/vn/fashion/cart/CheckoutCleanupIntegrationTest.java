package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.fashion.cart.cart.CartIdentity;

/** CART-03 spec section 4 / T18: cleanup removes only items still matching the order snapshot; retry never deducts twice. */
class CheckoutCleanupIntegrationTest extends CartTestSupport {
  String cartId, cookie, actor;
  long version;

  @BeforeEach void setup() {
    StubServers.CATALOG.reset(); StubServers.INVENTORY.reset();
    StubServers.CATALOG.reply(s -> {
      var ids = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(s.body()).results()
          .map(m -> StubServers.quoteItem(m.group(), true, "CL-" + m.group().substring(0, 8), 1000, 1)).toList();
      return new StubServers.Reply(200, StubServers.ok("[" + String.join(",", ids) + "]"));
    });
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.stock("x", 50)));
    var r = send("POST", "/api/v1/carts", null);
    cartId = data(r, 201).path("cart_id").asString();
    String token = CartIdentityIntegrationTest.token(r);
    cookie = "cart_token=" + token;
    actor = "guest:" + CartIdentity.hash(token);
    version = 0;
  }

  JsonNode add(String variant, int quantity) {
    var cart = data(send("POST", "/api/v1/carts/" + cartId + "/items", "{\"variant_id\":\"" + variant + "\",\"quantity\":" + quantity
        + ",\"expected_version\":" + version + "}", "Cookie", cookie, "Idempotency-Key", UUID.randomUUID().toString()), 200);
    version = cart.path("version").asLong();
    return cart;
  }
  JsonNode snapshot() {
    return data(send("GET", "/internal/api/v1/carts/" + cartId + "/snapshot?expected_version=" + version, null,
        "Authorization", serviceToken("order-service"), "Cart-Actor", actor), 200).path("items");
  }
  static String item(JsonNode snapshotItem) {
    return "{\"item_id\":\"" + snapshotItem.path("item_id").asString() + "\",\"item_version\":" + snapshotItem.path("item_version").asLong()
        + ",\"quantity\":" + snapshotItem.path("quantity").asInt() + "}";
  }
  static String body(String orderId, List<String> items) {
    return "{\"order_id\":\"" + orderId + "\",\"items\":[" + String.join(",", items) + "]}";
  }
  static List<String> items(JsonNode snapshot) {
    var all = new ArrayList<String>();
    snapshot.forEach(i -> all.add(item(i)));
    return all;
  }
  HttpResponse<String> cleanup(String orderId, String key, String body, String cartActor) {
    var headers = new ArrayList<>(List.of("Authorization", serviceToken("order-service"), "Cart-Actor", cartActor));
    if (key != null) { headers.add("Idempotency-Key"); headers.add(key); }
    return send("POST", "/internal/api/v1/carts/" + cartId + "/checkout-cleanup", body, headers.toArray(String[]::new));
  }
  HttpResponse<String> cleanup(String orderId, String body) { return cleanup(orderId, orderId, body, actor); }
  List<String> currentItems() {
    return jdbc.queryForList("select id::text from cart_items where cart_id = cast(? as uuid) order by created_at, id", String.class, cartId);
  }
  long cartVersion() { return jdbc.queryForObject("select version from carts where id = cast(? as uuid)", Long.class, cartId); }

  @Test void t18KeepsChangedAndNewItems() {
    add(UUID.randomUUID().toString(), 3);
    add(UUID.randomUUID().toString(), 1);
    add(UUID.randomUUID().toString(), 2);
    var snap = snapshot();
    String a = snap.get(0).path("item_id").asString(), b = snap.get(1).path("item_id").asString(), c = snap.get(2).path("item_id").asString();
    // Customer keeps editing on another device while the order is being created.
    version = data(send("PUT", "/api/v1/carts/" + cartId + "/items/" + a, "{\"quantity\":1,\"expected_version\":" + version + "}", "Cookie", cookie), 200)
        .path("version").asLong();
    version = data(send("DELETE", "/api/v1/carts/" + cartId + "/items/" + b + "?expected_version=" + version, null, "Cookie", cookie), 200)
        .path("version").asLong();
    String d = add(UUID.randomUUID().toString(), 1).path("items").get(2).path("item_id").asString();
    String order = UUID.randomUUID().toString();

    var result = data(cleanup(order, body(order, items(snap))), 200);
    assertThat(result.path("cart_id").asString()).isEqualTo(cartId);
    assertThat(result.path("order_id").asString()).isEqualTo(order);
    assertThat(result.path("cart_version").asLong()).isEqualTo(version + 1).isEqualTo(cartVersion());
    assertThat(result.path("removed").get(0).asString()).isEqualTo(c);
    assertThat(result.path("removed")).hasSize(1);
    assertThat(result.path("retained")).hasSize(1);
    assertThat(result.path("retained").get(0).path("item_id").asString()).isEqualTo(a);
    assertThat(result.path("retained").get(0).path("reason").asString()).isEqualTo("ITEM_CHANGED");
    assertThat(result.path("retained").get(0).path("current_quantity").asInt()).isEqualTo(1);
    assertThat(result.path("missing")).hasSize(1);
    assertThat(result.path("missing").get(0).asString()).isEqualTo(b);
    assertThat(currentItems()).containsExactly(a, d);
    assertThat(jdbc.queryForObject("select status from carts where id = cast(? as uuid)", String.class, cartId)).isEqualTo("ACTIVE");
  }

  @Test void editedBackToSameQuantityIsRetained() {
    // Review I2: qty 3 → 1 → 3 after the snapshot changes item_version only; the item must survive.
    add(UUID.randomUUID().toString(), 3);
    var snap = snapshot();
    String a = snap.get(0).path("item_id").asString();
    for (int q : new int[] {1, 3})
      version = data(send("PUT", "/api/v1/carts/" + cartId + "/items/" + a, "{\"quantity\":" + q + ",\"expected_version\":" + version + "}",
          "Cookie", cookie), 200).path("version").asLong();
    String order = UUID.randomUUID().toString();
    var result = data(cleanup(order, body(order, items(snap))), 200);
    assertThat(result.path("removed")).isEmpty();
    assertThat(result.path("retained").get(0).path("reason").asString()).isEqualTo("ITEM_CHANGED");
    assertThat(result.path("retained").get(0).path("current_quantity").asInt()).isEqualTo(3);
    assertThat(currentItems()).containsExactly(a);
  }

  @Test void quantityMismatchAtSameVersionIsRetained() {
    add(UUID.randomUUID().toString(), 2);
    var snap = snapshot().get(0);
    String order = UUID.randomUUID().toString();
    String wrongQty = "{\"item_id\":\"" + snap.path("item_id").asString() + "\",\"item_version\":" + snap.path("item_version").asLong() + ",\"quantity\":1}";
    var result = data(cleanup(order, body(order, List.of(wrongQty))), 200);
    assertThat(result.path("retained").get(0).path("current_quantity").asInt()).isEqualTo(2);
    assertThat(currentItems()).hasSize(1);
  }

  @Test void retrySameOrderDoesNotDeductTwice() {
    String variant = UUID.randomUUID().toString();
    add(variant, 2);
    var snap = snapshot();
    String order = UUID.randomUUID().toString();
    var first = cleanup(order, body(order, items(snap)));
    data(first, 200);
    version = cartVersion();
    String again = add(variant, 2).path("items").get(0).path("item_id").asString();
    long before = cartVersion();
    var retry = cleanup(order, body(order, items(snap)));
    assertThat(json(retry).path("data")).isEqualTo(json(first).path("data"));
    assertThat(currentItems()).containsExactly(again);
    assertThat(cartVersion()).isEqualTo(before);
  }

  @Test void sameOrderDifferentBodyIs409() {
    add(UUID.randomUUID().toString(), 1);
    add(UUID.randomUUID().toString(), 1);
    var snap = items(snapshot());
    String order = UUID.randomUUID().toString();
    data(cleanup(order, body(order, snap.subList(0, 1))), 200);
    assertThat(problem(cleanup(order, body(order, snap)), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(currentItems()).hasSize(1);
  }

  @Test void keyMustEqualOrderId() {
    add(UUID.randomUUID().toString(), 1);
    var snap = items(snapshot());
    String order = UUID.randomUUID().toString();
    assertThat(problem(cleanup(order, null, body(order, snap), actor), 400)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(problem(cleanup(order, UUID.randomUUID().toString(), body(order, snap), actor), 400)).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
    assertThat(currentItems()).hasSize(1);
  }

  @Test void validation() {
    add(UUID.randomUUID().toString(), 1);
    String one = items(snapshot()).get(0);
    String order = UUID.randomUUID().toString();
    var many = new ArrayList<String>();
    for (int i = 0; i < 101; i++) many.add("{\"item_id\":\"" + UUID.randomUUID() + "\",\"item_version\":0,\"quantity\":1}");
    String id = UUID.randomUUID().toString();
    for (String bad : List.of(body(order, List.of()), body(order, many), body(order, List.of(one, one)),
        body(order, List.of("{\"item_id\":\"" + id + "\",\"item_version\":0,\"quantity\":0}")),
        body(order, List.of("{\"item_id\":\"" + id + "\",\"item_version\":0,\"quantity\":100}")),
        body(order, List.of("{\"item_id\":\"" + id + "\",\"item_version\":-1,\"quantity\":1}")),
        body(order, List.of("{\"item_id\":\"" + id.toUpperCase() + "\",\"item_version\":0,\"quantity\":1}")),
        // PR #26 review: a bad or missing item_id before a valid one must stay 400, not NPE → 500.
        body(order, List.of("{\"item_id\":\"bad\",\"item_version\":0,\"quantity\":1}", one)),
        body(order, List.of("{\"item_version\":0,\"quantity\":1}", one)),
        "{\"items\":[" + one + "]}")) {
      var response = cleanup(order, bad);
      assertThat(response.statusCode()).withFailMessage(bad + " -> " + response.body()).isEqualTo(400);
      assertThat(json(response).path("code").asString()).isEqualTo("VALIDATION_ERROR");
    }
    assertThat(problem(cleanup(order, "[1]"), 400)).isEqualTo("MALFORMED_JSON");
    assertThat(currentItems()).hasSize(1);
  }

  @Test void nothingRemovedKeepsVersion() {
    add(UUID.randomUUID().toString(), 2);
    var snap = snapshot();
    String a = snap.get(0).path("item_id").asString();
    version = data(send("PUT", "/api/v1/carts/" + cartId + "/items/" + a, "{\"quantity\":1,\"expected_version\":" + version + "}", "Cookie", cookie), 200)
        .path("version").asLong();
    String order = UUID.randomUUID().toString();
    var result = data(cleanup(order, body(order, items(snap))), 200);
    assertThat(result.path("removed")).isEmpty();
    assertThat(result.path("cart_version").asLong()).isEqualTo(version).isEqualTo(cartVersion());
  }

  @Test void parallelSameOrderRemovesOnce() throws Exception {
    add(UUID.randomUUID().toString(), 1);
    add(UUID.randomUUID().toString(), 1);
    var snap = items(snapshot());
    String order = UUID.randomUUID().toString();
    long before = cartVersion();
    var barrier = new CyclicBarrier(2);
    var calls = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < 2; i++) calls.add(CompletableFuture.supplyAsync(() -> {
      try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); }
      return cleanup(order, body(order, snap));
    }));
    var one = calls.get(0).get();
    var two = calls.get(1).get();
    assertThat(json(one).path("data")).isEqualTo(data(two, 200));
    assertThat(currentItems()).isEmpty();
    assertThat(cartVersion()).isEqualTo(before + 1);
  }

  @Test void notOwnedRollsBackKey() {
    add(UUID.randomUUID().toString(), 1);
    var snap = items(snapshot());
    String order = UUID.randomUUID().toString();
    assertThat(problem(cleanup(order, order, body(order, snap), "guest:" + CartIdentity.hash("someone-else")), 404)).isEqualTo("CART_NOT_FOUND");
    assertThat(problem(cleanup(order, order, body(order, snap), "user:" + UUID.randomUUID()), 404)).isEqualTo("CART_NOT_FOUND");
    data(cleanup(order, body(order, snap)), 200);
    assertThat(currentItems()).isEmpty();
  }

  @Test void authBeforeBody() {
    String order = UUID.randomUUID().toString();
    var noToken = send("POST", "/internal/api/v1/carts/" + cartId + "/checkout-cleanup", "{bad", "Cart-Actor", actor, "Idempotency-Key", order);
    assertThat(problem(noToken, 401)).isEqualTo("INVALID_SERVICE_TOKEN");
    var wrongCaller = send("POST", "/internal/api/v1/carts/" + cartId + "/checkout-cleanup", "{bad",
        "Authorization", serviceToken("cart-service"), "Cart-Actor", actor, "Idempotency-Key", order);
    assertThat(problem(wrongCaller, 403)).isEqualTo("CALLER_NOT_ALLOWED");
    assertThat(problem(cleanup(order, order, "{bad", "user:x"), 400)).isEqualTo("INVALID_ACTOR");
  }
}
