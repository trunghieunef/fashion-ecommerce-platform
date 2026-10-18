package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec section 5: knowing a cart_id never grants access; every miss looks the same. */
class CartOwnershipIntegrationTest extends CartTestSupport {
  static final String CARTS = "/api/v1/carts";

  record Guest(String id, String cookie) { }
  Guest guest() {
    var r = send("POST", CARTS, null);
    return new Guest(data(r, 201).path("cart_id").asString(), "cart_token=" + CartIdentityIntegrationTest.token(r));
  }
  String memberCart(UUID user) {
    return data(send("POST", CARTS, null, "Authorization", "Bearer " + memberToken(user)), 201).path("cart_id").asString();
  }
  void assertNotFound(HttpResponse<String> r) {
    assertThat(problem(r, 404)).isEqualTo("CART_NOT_FOUND");
    assertThat(json(r).path("code").asString()).isEqualTo("NOT_FOUND");
    assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
  }

  @Test void guessedCartIdIs404() {
    var g = guest();
    assertNotFound(send("GET", CARTS + "/" + UUID.randomUUID(), null, "Cookie", g.cookie()));
  }
  @Test void otherGuestCookieIs404() {
    var a = guest(); var b = guest();
    assertNotFound(send("GET", CARTS + "/" + a.id(), null, "Cookie", b.cookie()));
    assertNotFound(send("GET", CARTS + "/" + a.id(), null));
  }
  @Test void guestCookieCannotReadMemberCart() {
    String member = memberCart(UUID.randomUUID());
    assertNotFound(send("GET", CARTS + "/" + member, null, "Cookie", guest().cookie()));
  }
  @Test void memberCannotReadOtherMemberCart() {
    String a = memberCart(UUID.randomUUID());
    assertNotFound(send("GET", CARTS + "/" + a, null, "Authorization", "Bearer " + memberToken(UUID.randomUUID())));
  }
  @Test void memberCannotReadGuestCart() {
    var g = guest();
    assertNotFound(send("GET", CARTS + "/" + g.id(), null, "Authorization", "Bearer " + memberToken(UUID.randomUUID())));
  }
  @Test void malformedCartIdIs404() {
    var g = guest();
    assertNotFound(send("GET", CARTS + "/not-a-uuid", null, "Cookie", g.cookie()));
    assertNotFound(send("GET", CARTS + "/" + g.id().toUpperCase(), null, "Cookie", g.cookie()));
  }
  @Test void ownerReadsOwnCart() {
    var g = guest();
    var cart = data(send("GET", CARTS + "/" + g.id(), null, "Cookie", g.cookie()), 200);
    assertThat(cart.path("cart_id").asString()).isEqualTo(g.id());
    assertThat(cart.path("items")).isEmpty();
  }
}
