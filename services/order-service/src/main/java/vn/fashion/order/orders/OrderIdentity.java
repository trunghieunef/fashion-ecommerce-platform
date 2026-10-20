package vn.fashion.order.orders;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.fashion.order.web.Api;
import vn.fashion.platform.security.AccessTokenVerifier;

/**
 * Spec section 4. A Bearer decides alone (invalid is 401, never a fallback); without one, a cart checkout token
 * (ES256, iss cart-service, aud order-service, sub guest:<sha256>, cart_id) makes a guest. Tokens are never logged.
 */
@Component
public class OrderIdentity {
  private static final Pattern GUEST = Pattern.compile("guest:([0-9a-f]{64})");
  private final AccessTokenVerifier members;
  private final Map<String, ECPublicKey> checkoutKeys;
  private final Clock clock;

  public OrderIdentity(@Value("${fashion.order.jwt.public-keys}") String memberKeys,
                       @Value("${fashion.order.checkout-public-keys:}") String checkoutKeys, Clock clock) {
    this.members = new AccessTokenVerifier(AccessTokenVerifier.parseKeys(memberKeys), clock);
    this.checkoutKeys = checkoutKeys.isBlank() ? Map.of() : AccessTokenVerifier.parseKeys(checkoutKeys);
    this.clock = clock;
  }

  /** The member or guest of this request, else empty (no credential at all). */
  public Optional<OrderActor> resolve(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null) {
      if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw unauthorized();
      var actor = members.verify(authorization.substring(7).strip()).orElseThrow(OrderIdentity::unauthorized);
      return Optional.of(OrderActor.member(actor.userId()));
    }
    String checkout = request.getHeader("Checkout-Token");
    return checkout == null ? Optional.empty() : Optional.of(guest(checkout));
  }
  public OrderActor require(HttpServletRequest request) { return resolve(request).orElseThrow(OrderIdentity::unauthorized); }

  private OrderActor guest(String token) {
    try {
      var jwt = SignedJWT.parse(token.strip());
      var key = checkoutKeys.get(jwt.getHeader().getKeyID());
      if (key == null || !JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new ECDSAVerifier(key)))
        throw invalidCheckout();
      var claims = jwt.getJWTClaimsSet();
      var matcher = GUEST.matcher(String.valueOf(claims.getSubject()));
      Date exp = claims.getExpirationTime();
      if (!"cart-service".equals(claims.getIssuer()) || !List.of("order-service").equals(claims.getAudience())
          || exp == null || !exp.toInstant().isAfter(clock.instant()) || !matcher.matches())
        throw invalidCheckout();
      return OrderActor.guest(matcher.group(1), UUID.fromString(claims.getStringClaim("cart_id")));
    } catch (Api.Problem e) { throw e; }
    catch (Exception e) { throw invalidCheckout(); }
  }

  public static Api.Problem unauthorized() {
    return new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_ACCESS_TOKEN", List.of());
  }
  static Api.Problem invalidCheckout() {
    return new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_CHECKOUT_TOKEN", List.of());
  }
}
