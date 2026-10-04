package vn.fashion.platform.security;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs short-lived service tokens (ADR-19): ES256 with this service's own key,
 * iss = sub = kid = service name, aud = the single target service. Never log the token.
 */
public class ServiceTokenIssuer {
  public static final Duration TTL = Duration.ofSeconds(60);

  private final String serviceName;
  private final JwtEncoder encoder;
  private final Clock clock;

  public ServiceTokenIssuer(String serviceName, ECPublicKey publicKey, ECPrivateKey privateKey, Clock clock) {
    this.serviceName = serviceName;
    this.clock = clock;
    var key = new ECKey.Builder(Curve.P_256, publicKey).privateKey(privateKey).keyID(serviceName).build();
    this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
  }

  public String issue(String audience) {
    Instant now = clock.instant();
    var claims = JwtClaimsSet.builder()
        .issuer(serviceName)
        .subject(serviceName)
        .audience(List.of(audience))
        .issuedAt(now)
        .expiresAt(now.plus(TTL))
        .id(UUID.randomUUID().toString())
        .build();
    var header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(serviceName).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
