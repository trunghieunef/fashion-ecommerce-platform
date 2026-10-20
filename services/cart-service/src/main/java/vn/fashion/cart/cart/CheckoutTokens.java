package vn.fashion.cart.cart;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TASK:ORD-01 (08 CART-03, option C): short-lived ES256 token proving a guest owns a cart, for order-service. The cookie
 * never leaves cart; the token carries only its hash, the cart id and the expiry. Never log the token.
 */
@Component
public class CheckoutTokens {
  public record Issued(String token, Instant expiresAt) { }

  private final ECPrivateKey key;
  private final String kid;
  private final Duration ttl;
  private final Clock clock = Clock.systemUTC();

  public CheckoutTokens(@Value("${fashion.cart.checkout.private-key:}") String privateKey,
                        @Value("${fashion.cart.checkout.kid:cart-checkout-1}") String kid,
                        @Value("${fashion.cart.checkout.ttl:15m}") Duration ttl) {
    this.key = privateKey.isBlank() ? null : parse(privateKey);
    this.kid = kid; this.ttl = ttl;
  }

  public Issued issue(UUID cartId, String guestHash) {
    if (key == null) throw new IllegalStateException("fashion.cart.checkout.private-key is not configured");
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS), exp = now.plus(ttl);
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(),
        new JWTClaimsSet.Builder().issuer("cart-service").audience("order-service").subject("guest:" + guestHash)
            .claim("cart_id", cartId.toString()).jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now)).expirationTime(Date.from(exp)).build());
    try { jwt.sign(new ECDSASigner(key)); }
    catch (JOSEException e) { throw new IllegalStateException(e); }
    return new Issued(jwt.serialize(), exp);
  }

  private static ECPrivateKey parse(String base64) {
    try {
      return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
    } catch (Exception e) { throw new IllegalStateException("Invalid fashion.cart.checkout.private-key", e); }
  }
}
