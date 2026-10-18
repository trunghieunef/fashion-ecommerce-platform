package vn.fashion.cart.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.fashion.cart.cart.CartActor;
import vn.fashion.cart.web.Api;
import vn.fashion.platform.security.AccessTokenVerifier;
import vn.fashion.platform.security.ServiceTokenVerifier;

/** CART-03 spec section 2: ADR-19 token, order-service only, then the actor order-service verified. */
@Component
public class InternalCallers {
  private static final Pattern MEMBER = Pattern.compile("user:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
  private static final Pattern GUEST = Pattern.compile("guest:([0-9a-f]{64})");
  private final ServiceTokenVerifier verifier;

  public InternalCallers(@Value("${fashion.cart.internal-callers:}") String keys) {
    verifier = new ServiceTokenVerifier("cart-service", keys.isBlank() ? Map.of() : AccessTokenVerifier.parseKeys(keys), Clock.systemUTC());
  }

  public void require(String authorization) {
    String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7).strip() : null;
    String caller = verifier.verify(token)
        .orElseThrow(() -> new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_SERVICE_TOKEN", List.of()));
    if (!"order-service".equals(caller)) throw new Api.Problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "CALLER_NOT_ALLOWED", List.of());
  }

  /** {@code Cart-Actor}: {@code user:<lowercase uuid>} or {@code guest:<sha-256 hex of cart_token>}. */
  public static CartActor actor(String header) {
    if (header != null) {
      var member = MEMBER.matcher(header);
      if (member.matches()) return CartActor.member(UUID.fromString(member.group(1)));
      var guest = GUEST.matcher(header);
      if (guest.matches()) return CartActor.guest(guest.group(1));
    }
    throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_ACTOR", List.of(new Api.FieldError("Cart-Actor", "must be user:<uuid> or guest:<sha256>")));
  }
}
