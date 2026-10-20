package vn.fashion.order.deps;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.fashion.platform.security.ServiceTokenIssuer;

/** ADR-19 caller identity "order-service"; without a key pair every dependency call is unavailable, startup is not blocked. */
@Component
public class ServiceTokens {
  private final ServiceTokenIssuer issuer;

  public ServiceTokens(@Value("${fashion.order.service-private-key:}") String privateKey,
                       @Value("${fashion.order.service-public-key:}") String publicKey) {
    ServiceTokenIssuer built = null;
    if (!privateKey.isBlank() && !publicKey.isBlank()) {
      try {
        var ec = KeyFactory.getInstance("EC");
        built = new ServiceTokenIssuer("order-service",
            (ECPublicKey) ec.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.strip()))),
            (ECPrivateKey) ec.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.strip()))),
            Clock.systemUTC());
      } catch (Exception e) { throw new IllegalStateException("invalid order service key pair", e); }
    }
    this.issuer = built;
  }

  public String bearer(String audience) {
    if (issuer == null) throw new DependencyUnavailable(audience);
    return "Bearer " + issuer.issue(audience);
  }
}
