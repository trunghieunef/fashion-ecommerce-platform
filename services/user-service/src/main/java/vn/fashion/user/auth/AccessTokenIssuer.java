package vn.fashion.user.auth;

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
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Member access token (03 section 2.1, USR-05): ES256 JWT, 900 s, iss user-service, aud fashion-api,
 * sub = user id, auth_version for sensitive re-checks. The public key reaches verifiers through
 * configuration with its kid (decided 2026-10-05, same model as ADR-19).
 */
@Component
public class AccessTokenIssuer {
  public static final Duration TTL = Duration.ofSeconds(900);
  static final String ISSUER = "user-service";
  static final String AUDIENCE = "fashion-api";

  private final ECDSASigner signer;
  private final String keyId;
  private final Clock clock = Clock.systemUTC();

  public AccessTokenIssuer(@Value("${fashion.user.jwt.private-key}") String privateKeyBase64,
                           @Value("${fashion.user.jwt.key-id}") String keyId) {
    try {
      var spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.strip()));
      this.signer = new ECDSASigner((ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(spec));
    } catch (Exception e) {
      throw new IllegalStateException("USER_JWT_PRIVATE_KEY must be a Base64 PKCS#8 EC P-256 key", e);
    }
    this.keyId = keyId;
  }

  public String issue(UUID userId, long authVersion) {
    Instant now = clock.instant();
    var claims = new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject(userId.toString())
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(TTL)))
        .jwtID(UUID.randomUUID().toString())
        .claim("auth_version", authVersion)
        .build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("cannot sign access token", e);
    }
    return jwt.serialize();
  }
}
