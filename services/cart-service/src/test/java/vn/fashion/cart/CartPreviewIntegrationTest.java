package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Spec section 6: labeled reference price/stock on read; dependency trouble never loses the cart. */
class CartPreviewIntegrationTest extends CartTestSupport {
  volatile long price = 100000;
  volatile int available = 5;
  final String variant = UUID.randomUUID().toString();
  String cartId, cookie;

  @BeforeEach void setup() {
    StubServers.CATALOG.reset(); StubServers.INVENTORY.reset();
    StubServers.CATALOG.reply(s -> new StubServers.Reply(200, StubServers.ok("[" + StubServers.quoteItem(variant, true, "PREVIEW-M", price, 3) + "]")));
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.stock("PREVIEW-M", available)));
    var r = send("POST", "/api/v1/carts", null);
    cartId = data(r, 201).path("cart_id").asString();
    cookie = "cart_token=" + CartIdentityIntegrationTest.token(r);
  }
  void addItem() {
    data(send("POST", "/api/v1/carts/" + cartId + "/items", "{\"variant_id\":\"" + variant + "\",\"quantity\":2,\"expected_version\":0}",
        "Cookie", cookie, "Idempotency-Key", UUID.randomUUID().toString()), 200);
  }
  JsonNode read() { return data(send("GET", "/api/v1/carts/" + cartId, null, "Cookie", cookie), 200); }

  @Test void previewShowsPriceChangeAndStock() {
    addItem();
    price = 90000; available = 2;
    var cart = read();
    assertThat(cart.path("preview_status").asString()).isEqualTo("OK");
    var item = cart.path("items").get(0);
    assertThat(item.path("price_snapshot").asLong()).isEqualTo(100000);
    var preview = item.path("preview");
    assertThat(preview.path("effective_price").asLong()).isEqualTo(90000);
    assertThat(preview.path("price_changed").asBoolean()).isTrue();
    assertThat(preview.path("active").asBoolean()).isTrue();
    assertThat(preview.path("available").asInt()).isEqualTo(2);
    assertThat(preview.path("observed_at").asString()).isEqualTo("2026-10-18T03:00:00Z");
  }

  @Test void dependencyDownStillReturnsCart() {
    addItem();
    StubServers.CATALOG.reply(s -> new StubServers.Reply(503, "{}"));
    var cart = read();
    assertThat(cart.path("preview_status").asString()).isEqualTo("UNAVAILABLE");
    assertThat(cart.path("items")).hasSize(1);
    assertThat(cart.path("items").get(0).path("preview").isNull()).isTrue();
    assertThat(cart.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
  }

  @Test void malformedDependencyResponseIsUnavailable() {
    addItem();
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, "{\"data\":{\"available\":\"many\"}}"));
    assertThat(read().path("preview_status").asString()).isEqualTo("UNAVAILABLE");
  }

  @Test void largeCartPreviewHasOneDeadline() {
    // Review I2: 100 slow-but-valid stock reads must not serialize on a small pool (was ~7.5 s at 600 ms each).
    StubServers.CATALOG.reply(s -> {
      var ids = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(s.body()).results()
          .map(m -> StubServers.quoteItem(m.group(), true, "BIG-" + m.group().substring(0, 8), 1000, 1)).toList();
      return new StubServers.Reply(200, StubServers.ok("[" + String.join(",", ids) + "]"));
    });
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.stock("x", 50)));
    for (int i = 0; i < 100; i++) {
      data(send("POST", "/api/v1/carts/" + cartId + "/items", "{\"variant_id\":\"" + UUID.randomUUID() + "\",\"quantity\":1,\"expected_version\":" + i + "}",
          "Cookie", cookie, "Idempotency-Key", UUID.randomUUID().toString()), 200);
    }
    // 600 ms each: parallel ≈ 0.6-1 s; the old 8-thread pool queued 100 calls ≈ 7.5 s. (900 ms + connect exceeds the 1 s client timeout.)
    StubServers.INVENTORY.delay(600);
    long start = System.nanoTime();
    var cart = read();
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofMillis(2500));
    assertThat(cart.path("items")).hasSize(100);
    assertThat(cart.path("preview_status").asString()).isEqualTo("OK");
  }

  @Test void emptyCartSkipsDependencies() {
    var cart = read();
    assertThat(cart.path("preview_status").asString()).isEqualTo("EMPTY");
    assertThat(StubServers.CATALOG.seen).isEmpty();
    assertThat(StubServers.INVENTORY.seen).isEmpty();
  }

  @Test void readDoesNotWrite() {
    addItem();
    price = 1; available = 0;
    read(); read();
    assertThat(jdbc.queryForObject("select version from carts where id=?", Long.class, UUID.fromString(cartId))).isEqualTo(1);
    assertThat(jdbc.queryForObject("select price_snapshot from cart_items", Long.class)).isEqualTo(100000);
  }

  @Test void mineAndOpenAlsoCarryPreview() {
    String bearer = "Bearer " + memberToken(UUID.randomUUID());
    var opened = data(send("POST", "/api/v1/carts", null, "Authorization", bearer), 201);
    assertThat(opened.path("preview_status").asString()).isEqualTo("EMPTY");
    assertThat(data(send("GET", "/api/v1/carts/mine", null, "Authorization", bearer), 200).path("preview_status").asString()).isEqualTo("EMPTY");
  }
}
