package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.fashion.cart.cart.CartIdentity;

/** CART-03 spec sections 2-3: order-service reads a verified actor's cart at an expected version; read only. */
class CartSnapshotIntegrationTest extends CartTestSupport {
  static final String GUEST = CartIdentity.hash("guest-token-snapshot");
  UUID cart, first, second;

  @BeforeEach void setup() {
    StubServers.CATALOG.reset(); StubServers.INVENTORY.reset();
    cart = seedCart(null, GUEST, "10 days");
    first = seedItem(cart, "SNAP-A", 2, 1);
    second = seedItem(cart, "SNAP-B", 1, 0);
  }

  String path(UUID id, String version) {
    return "/internal/api/v1/carts/" + id + "/snapshot" + (version == null ? "" : "?expected_version=" + version);
  }

  @Test void snapshotRequiresOrderServiceToken() {
    assertThat(problem(send("GET", path(cart, "3"), null, "Cart-Actor", "guest:" + GUEST), 401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("GET", path(cart, "3"), null, "Authorization", "Bearer nope", "Cart-Actor", "guest:" + GUEST), 401))
        .isEqualTo("INVALID_SERVICE_TOKEN");
    String otherAudience;
    try { otherAudience = new vn.fashion.platform.security.ServiceTokenIssuer("order-service", KEY.toECPublicKey(), KEY.toECPrivateKey(),
        java.time.Clock.systemUTC()).issue("inventory-service"); }
    catch (Exception e) { throw new AssertionError(e); }
    assertThat(problem(send("GET", path(cart, "3"), null, "Authorization", "Bearer " + otherAudience, "Cart-Actor", "guest:" + GUEST), 401))
        .isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("GET", path(cart, "3"), null, "Authorization", serviceToken("cart-service"), "Cart-Actor", "guest:" + GUEST), 403))
        .isEqualTo("CALLER_NOT_ALLOWED");
  }

  @Test void snapshotRejectsBadActor() {
    String token = serviceToken("order-service");
    for (String actor : new String[] {"user:" + UUID.randomUUID().toString().toUpperCase(), "guest:xyz", "guest:" + GUEST.toUpperCase(),
        "admin:" + UUID.randomUUID(), ""}) {
      assertThat(problem(send("GET", path(cart, "3"), null, "Authorization", token, "Cart-Actor", actor), 400))
          .withFailMessage(actor).isEqualTo("INVALID_ACTOR");
    }
    assertThat(problem(send("GET", path(cart, "3"), null, "Authorization", token), 400)).isEqualTo("INVALID_ACTOR");
  }

  @Test void snapshotIsOwnerOnly() {
    String token = serviceToken("order-service");
    UUID member = UUID.randomUUID();
    UUID memberCart = seedCart(member, null, null);
    UUID expired = seedCart(null, CartIdentity.hash("expired"), "-1 minute");
    assertThat(problem(send("GET", path(cart, "3"), null, "Authorization", token, "Cart-Actor", "guest:" + CartIdentity.hash("other")), 404))
        .isEqualTo("CART_NOT_FOUND");
    assertThat(problem(send("GET", path(cart, "3"), null, "Authorization", token, "Cart-Actor", "user:" + member), 404)).isEqualTo("CART_NOT_FOUND");
    assertThat(problem(send("GET", path(expired, "3"), null, "Authorization", token, "Cart-Actor", "guest:" + CartIdentity.hash("expired")), 404))
        .isEqualTo("CART_NOT_FOUND");
    assertThat(problem(send("GET", "/internal/api/v1/carts/not-a-uuid/snapshot?expected_version=3", null, "Authorization", token,
        "Cart-Actor", "guest:" + GUEST), 404)).isEqualTo("CART_NOT_FOUND");
    assertThat(data(send("GET", path(memberCart, "3"), null, "Authorization", token, "Cart-Actor", "user:" + member), 200)
        .path("owner").asString()).isEqualTo("MEMBER");
  }

  @Test void snapshotChecksVersion() {
    String token = serviceToken("order-service");
    assertThat(problem(send("GET", path(cart, null), null, "Authorization", token, "Cart-Actor", "guest:" + GUEST), 400)).isEqualTo("VERSION_REQUIRED");
    var conflict = send("GET", path(cart, "2"), null, "Authorization", token, "Cart-Actor", "guest:" + GUEST);
    assertThat(problem(conflict, 409)).isEqualTo("VERSION_CONFLICT");
    assertThat(json(conflict).path("errors").get(0).path("message").asString()).isEqualTo("current=3");
  }

  @Test void snapshotReturnsItemsWithoutWrites() {
    var before = jdbc.queryForMap("select updated_at, expires_at, version from carts where id = ?", cart);
    var response = send("GET", path(cart, "3"), null, "Authorization", serviceToken("order-service"), "Cart-Actor", "guest:" + GUEST);
    var snapshot = data(response, 200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(snapshot.path("cart_id").asString()).isEqualTo(cart.toString());
    assertThat(snapshot.path("version").asLong()).isEqualTo(3);
    assertThat(snapshot.path("owner").asString()).isEqualTo("GUEST");
    var items = snapshot.path("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).path("item_id").asString()).isEqualTo(first.toString());
    assertThat(items.get(0).path("sku").asString()).isEqualTo("SNAP-A");
    assertThat(items.get(0).path("quantity").asInt()).isEqualTo(2);
    assertThat(items.get(0).path("item_version").asLong()).isEqualTo(1);
    assertThat(items.get(0).path("price_snapshot").asLong()).isEqualTo(100000);
    assertThat(items.get(0).path("catalog_version").asLong()).isEqualTo(7);
    assertThat(items.get(0).path("variant_id").asString()).matches("[0-9a-f-]{36}");
    assertThat(items.get(1).path("item_id").asString()).isEqualTo(second.toString());
    assertThat(jdbc.queryForMap("select updated_at, expires_at, version from carts where id = ?", cart)).isEqualTo(before);
    assertThat(StubServers.CATALOG.seen).isEmpty();
    assertThat(StubServers.INVENTORY.seen).isEmpty();
  }
}
