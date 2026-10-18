package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Spec sections 5-6: versioned, idempotent item mutations; dependencies called before the cart lock. */
class CartItemsIntegrationTest extends CartTestSupport {
  static final Pattern UUIDS = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  final Map<String, Boolean> active = new ConcurrentHashMap<>();
  final Map<String, Boolean> known = new ConcurrentHashMap<>();
  volatile int available = 5;
  String cartId, cookie;

  static String sku(String variant) { return "SKU-" + variant.substring(0, 8).toUpperCase(); }

  @BeforeEach void setup() {
    StubServers.CATALOG.reset(); StubServers.INVENTORY.reset();
    StubServers.CATALOG.reply(seen -> {
      var items = new ArrayList<String>();
      var m = UUIDS.matcher(seen.body());
      while (m.find()) {
        String id = m.group();
        items.add(known.getOrDefault(id, true) ? StubServers.quoteItem(id, active.getOrDefault(id, true), sku(id), 100000, 3) : StubServers.missing(id));
      }
      return new StubServers.Reply(200, StubServers.ok("[" + String.join(",", items) + "]"));
    });
    StubServers.INVENTORY.reply(seen -> new StubServers.Reply(200, StubServers.stock(seen.path().substring(seen.path().lastIndexOf('/') + 1), available)));
    var r = send("POST", "/api/v1/carts", null);
    cartId = data(r, 201).path("cart_id").asString();
    cookie = "cart_token=" + CartIdentityIntegrationTest.token(r);
  }

  HttpResponse<String> add(String variant, int quantity, long version, String key) {
    String body = "{\"variant_id\":\"" + variant + "\",\"quantity\":" + quantity + ",\"expected_version\":" + version + "}";
    return key == null ? send("POST", "/api/v1/carts/" + cartId + "/items", body, "Cookie", cookie)
        : send("POST", "/api/v1/carts/" + cartId + "/items", body, "Cookie", cookie, "Idempotency-Key", key);
  }
  HttpResponse<String> put(String item, int quantity, long version) {
    return send("PUT", "/api/v1/carts/" + cartId + "/items/" + item, "{\"quantity\":" + quantity + ",\"expected_version\":" + version + "}", "Cookie", cookie);
  }
  JsonNode cart() { return data(send("GET", "/api/v1/carts/" + cartId, null, "Cookie", cookie), 200); }
  static String v() { return UUID.randomUUID().toString(); }
  String ikey() { return UUID.randomUUID().toString(); }

  @Test void addStoresSnapshotAndBumpsVersion() {
    String variant = v();
    var cart = data(add(variant, 2, 0, ikey()), 200);
    assertThat(cart.path("version").asLong()).isEqualTo(1);
    var item = cart.path("items").get(0);
    assertThat(item.path("variant_id").asString()).isEqualTo(variant);
    assertThat(item.path("sku").asString()).isEqualTo(sku(variant));
    assertThat(item.path("quantity").asInt()).isEqualTo(2);
    assertThat(item.path("price_snapshot").asLong()).isEqualTo(100000);
    assertThat(jdbc.queryForObject("select catalog_version from cart_items", Long.class)).isEqualTo(3);
  }

  @Test void addSameVariantSums() {
    String variant = v();
    data(add(variant, 2, 0, ikey()), 200);
    var cart = data(add(variant, 3, 1, ikey()), 200);
    assertThat(cart.path("items")).hasSize(1);
    assertThat(cart.path("items").get(0).path("quantity").asInt()).isEqualTo(5);
  }

  @Test void sumOver99IsQuantityLimit() {
    available = 1000; String variant = v();
    data(add(variant, 60, 0, ikey()), 200);
    assertThat(problem(add(variant, 40, 1, ikey()), 400)).isEqualTo("QUANTITY_LIMIT");
    assertThat(problem(add(v(), 0, 1, ikey()), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(add(v(), 100, 1, ikey()), 400)).isEqualTo("INVALID_FIELDS");
  }

  @Test void hundredFirstItemIsLimit() {
    for (int i = 0; i < 100; i++) data(add(v(), 1, i, ikey()), 200);
    assertThat(problem(add(v(), 1, 100, ikey()), 400)).isEqualTo("CART_ITEM_LIMIT");
  }

  @Test void retrySameKeyDoesNotAddAgain() {
    String variant = v(), k = ikey();
    var first = data(add(variant, 2, 0, k), 200);
    var again = data(add(variant, 2, 0, k), 200);
    assertThat(again).isEqualTo(first);
    assertThat(cart().path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    assertThat(cart().path("version").asLong()).isEqualTo(1);
  }

  @Test void sameKeyDifferentBodyIs409() {
    String k = ikey();
    data(add(v(), 2, 0, k), 200);
    assertThat(problem(add(v(), 1, 1, k), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test void missingKeyIs400() {
    assertThat(problem(add(v(), 1, 0, null), 400)).isEqualTo("INVALID_KEY");
  }

  @Test void concurrentSameKeyAddsOnce() throws Exception {
    String variant = v(), k = ikey();
    var codes = new ConcurrentLinkedQueue<Integer>();
    try (var pool = Executors.newFixedThreadPool(4)) {
      for (int i = 0; i < 4; i++) pool.submit(() -> codes.add(add(variant, 2, 0, k).statusCode()));
      pool.shutdown(); assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(codes).containsOnly(200);
    var cart = cart();
    assertThat(cart.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    assertThat(cart.path("version").asLong()).isEqualTo(1);
  }

  @Test void staleVersionIs409WithCurrent() {
    data(add(v(), 1, 0, ikey()), 200);
    var r = add(v(), 1, 0, ikey());
    assertThat(problem(r, 409)).isEqualTo("VERSION_CONFLICT");
    assertThat(json(r).path("errors").get(0).path("field").asString()).isEqualTo("expected_version");
    assertThat(json(r).path("errors").get(0).path("message").asString()).isEqualTo("current=1");
  }

  @Test void missingVersionIs400() {
    var r = send("POST", "/api/v1/carts/" + cartId + "/items", "{\"variant_id\":\"" + v() + "\",\"quantity\":1}",
        "Cookie", cookie, "Idempotency-Key", ikey());
    assertThat(problem(r, 400)).isEqualTo("VERSION_REQUIRED");
  }

  @Test void parallelSameVersionOneWins() throws Exception {
    String item = data(add(v(), 1, 0, ikey()), 200).path("items").get(0).path("item_id").asString();
    var a = CompletableFuture.supplyAsync(() -> put(item, 2, 1).statusCode());
    var b = CompletableFuture.supplyAsync(() -> put(item, 3, 1).statusCode());
    assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
    assertThat(cart().path("version").asLong()).isEqualTo(2);
  }

  @Test void inactiveVariantIs409() {
    String variant = v(); active.put(variant, false);
    assertThat(problem(add(variant, 1, 0, ikey()), 409)).isEqualTo("VARIANT_NOT_AVAILABLE");
  }

  @Test void unknownVariantIs409() {
    String variant = v(); known.put(variant, false);
    assertThat(problem(add(variant, 1, 0, ikey()), 409)).isEqualTo("VARIANT_NOT_AVAILABLE");
  }

  @Test void outOfStockIs409WithAvailable() {
    var r = add(v(), 6, 0, ikey());
    assertThat(problem(r, 409)).isEqualTo("OUT_OF_STOCK");
    assertThat(json(r).path("errors").get(0).path("field").asString()).isEqualTo("quantity");
    assertThat(json(r).path("errors").get(0).path("message").asString()).isEqualTo("available=5");
  }

  @Test void catalogDownIs503AndWritesNothing() {
    StubServers.CATALOG.reply(s -> new StubServers.Reply(503, "{}"));
    assertThat(problem(add(v(), 1, 0, ikey()), 503)).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(cart().path("version").asLong()).isZero();
  }

  @Test void inventoryDownIs503AndWritesNothing() {
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(500, "{}"));
    assertThat(problem(add(v(), 1, 0, ikey()), 503)).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(jdbc.queryForObject("select count(*) from cart_items", Integer.class)).isZero();
  }

  @Test void decreaseAllowedWhenVariantInactive() {
    String variant = v();
    String item = data(add(variant, 3, 0, ikey()), 200).path("items").get(0).path("item_id").asString();
    active.put(variant, false);
    assertThat(data(put(item, 1, 1), 200).path("items").get(0).path("quantity").asInt()).isEqualTo(1);
    assertThat(problem(put(item, 4, 2), 409)).isEqualTo("VARIANT_NOT_AVAILABLE");
  }

  @Test void removeAndClear() {
    String item = data(add(v(), 1, 0, ikey()), 200).path("items").get(0).path("item_id").asString();
    data(add(v(), 1, 1, ikey()), 200);
    var removed = data(send("DELETE", "/api/v1/carts/" + cartId + "/items/" + item + "?expected_version=2", null, "Cookie", cookie), 200);
    assertThat(removed.path("items")).hasSize(1);
    assertThat(removed.path("version").asLong()).isEqualTo(3);
    var cleared = data(send("DELETE", "/api/v1/carts/" + cartId + "?expected_version=3", null, "Cookie", cookie), 200);
    assertThat(cleared.path("items")).isEmpty();
    assertThat(cleared.path("version").asLong()).isEqualTo(4);
    assertThat(jdbc.queryForObject("select status from carts where id=?", String.class, UUID.fromString(cartId))).isEqualTo("ACTIVE");
    var other = send("POST", "/api/v1/carts", null);
    String otherCookie = "cart_token=" + CartIdentityIntegrationTest.token(other);
    String otherCart = data(other, 201).path("cart_id").asString();
    assertThat(problem(send("DELETE", "/api/v1/carts/" + otherCart + "/items/" + item + "?expected_version=0", null, "Cookie", otherCookie), 404))
        .isEqualTo("CART_ITEM_NOT_FOUND");
  }

  @Test void noRowLockDuringDependencyCall() throws Exception {
    // Review I3: the concurrent request must need the cart row lock; a plain read never waits on it.
    StubServers.CATALOG.delay(800);
    var slow = CompletableFuture.supplyAsync(() -> add(v(), 1, 0, ikey()));
    Thread.sleep(200);
    long start = System.nanoTime();
    var cleared = send("DELETE", "/api/v1/carts/" + cartId + "?expected_version=0", null, "Cookie", cookie);
    assertThat(cleared.statusCode()).isEqualTo(200);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(300));
    assertThat(problem(slow.get(5, TimeUnit.SECONDS), 409)).isEqualTo("VERSION_CONFLICT");
  }

  @Test void dependencyBadShapeOnWriteIs503() {
    // Review I4: values the database would reject, or answers missing the asked variant, are dependency failures.
    for (String reply : new String[] {
        StubServers.ok("[" + StubServers.quoteItem("%s", true, "SKU-NEG", -1, 3) + "]"),
        StubServers.ok("[" + StubServers.quoteItem("%s", true, "S".repeat(65), 100, 3) + "]"),
        StubServers.ok("[{\"variant_id\":\"%s\",\"found\":true,\"sku\":\"SKU-X\",\"effective_price\":1,\"product_version\":1}]"),
        StubServers.ok("[]")}) {
      String variant = v();
      StubServers.CATALOG.reply(s -> new StubServers.Reply(200, reply.replace("%s", variant)));
      assertThat(problem(add(variant, 1, 0, ikey()), 503)).as(reply).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }
    StubServers.CATALOG.reply(s -> new StubServers.Reply(200, StubServers.ok("[" + StubServers.quoteItem(UUIDS.matcher(s.body()).results().findFirst().orElseThrow().group(), true, "SKU-OK", 1, 1) + "]")));
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"available\":99999999999,\"observed_at\":\"2026-10-18T03:00:00Z\"}")));
    assertThat(problem(add(v(), 1, 0, ikey()), 503)).isEqualTo("DEPENDENCY_UNAVAILABLE");
    // Review I5: only a SKU_NOT_FOUND body means "not registered yet"; any other 404 is a dependency failure.
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(404, "<html>no route</html>"));
    assertThat(problem(add(v(), 1, 0, ikey()), 503)).isEqualTo("DEPENDENCY_UNAVAILABLE");
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(404, "{\"code\":\"NOT_FOUND\",\"message\":\"SKU_NOT_FOUND\"}"));
    assertThat(problem(add(v(), 1, 0, ikey()), 409)).isEqualTo("OUT_OF_STOCK");
    assertThat(cart().path("version").asLong()).isZero();
  }
}
