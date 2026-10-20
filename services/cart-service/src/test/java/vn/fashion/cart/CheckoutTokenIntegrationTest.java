package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.fashion.cart.cart.CartIdentity;

/** TASK:ORD-01 spec section 3: guest checkout token signed by cart, verified by order. */
class CheckoutTokenIntegrationTest extends CartTestSupport {
  static final String COOKIE = "A".repeat(43), OTHER = "B".repeat(43);

  @Test void aGuestGetsAShortLivedTokenBoundToItsCart() throws Exception {
    UUID cart = seedCart(null, CartIdentity.hash(COOKIE), "2 days");
    Instant before = jdbc.queryForObject("select expires_at from carts where id = ?", java.sql.Timestamp.class, cart).toInstant();
    var response = send("POST", "/api/v1/carts/" + cart + "/checkout-token", null, "Cookie", "cart_token=" + COOKIE);
    var data = data(response, 200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    var jwt = SignedJWT.parse(data.path("checkout_token").asString());
    assertThat(jwt.verify(new ECDSAVerifier(KEY.toECPublicKey()))).isTrue();
    assertThat(jwt.getHeader().getKeyID()).isEqualTo("cart-checkout-test");
    var claims = jwt.getJWTClaimsSet();
    assertThat(claims.getIssuer()).isEqualTo("cart-service");
    assertThat(claims.getAudience()).isEqualTo(List.of("order-service"));
    assertThat(claims.getSubject()).isEqualTo("guest:" + CartIdentity.hash(COOKIE));
    assertThat(claims.getStringClaim("cart_id")).isEqualTo(cart.toString());
    assertThat(claims.getJWTID()).isNotBlank();
    assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(900_000);
    assertThat(Instant.parse(data.path("expires_at").asString())).isEqualTo(claims.getExpirationTime().toInstant());
    // Issuing a token is a read: the sliding guest expiry does not move.
    assertThat(jdbc.queryForObject("select expires_at from carts where id = ?", java.sql.Timestamp.class, cart).toInstant())
        .isEqualTo(before);
  }

  @Test void membersStrangersAndUnknownCartsGetNoToken() {
    UUID cart = seedCart(null, CartIdentity.hash(COOKIE), "2 days");
    UUID memberCart = seedCart(MEMBER, null, "30 days");
    assertThat(problem(send("POST", "/api/v1/carts/" + memberCart + "/checkout-token", null,
        "Authorization", "Bearer " + memberToken()), 400)).isEqualTo("CHECKOUT_TOKEN_GUEST_ONLY");
    assertThat(problem(send("POST", "/api/v1/carts/" + cart + "/checkout-token", null,
        "Cookie", "cart_token=" + OTHER), 404)).isEqualTo("CART_NOT_FOUND");
    assertThat(problem(send("POST", "/api/v1/carts/" + UUID.randomUUID() + "/checkout-token", null,
        "Cookie", "cart_token=" + COOKIE), 404)).isEqualTo("CART_NOT_FOUND");
    assertThat(send("POST", "/api/v1/carts/" + cart + "/checkout-token", null).statusCode()).isEqualTo(401);
  }
}
