package vn.fashion.platform.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies member/admin access tokens issued by user-service (ADR-21), deny by default: ES256
 * signed by a configured key selected by kid (two kids during rotation), iss user-service, aud
 * fashion-api, unexpired, iat not in the future, lifetime at most 15 minutes, UUID subject and an
 * auth_version claim. Returns the actor; callers still check ownership and permissions.
 */
public class AccessTokenVerifier {
  public record Actor(UUID userId, long authVersion) {
  }

  public static final String ISSUER = "user-service";
  public static final String AUDIENCE = "fashion-api";
  private static final Duration MAX_LIFETIME = Duration.ofMinutes(15);
  private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

  private final NimbusJwtDecoder decoder;

  public AccessTokenVerifier(Map<String, ECPublicKey> keysByKid, Clock clock) {
    List<JWK> keys = keysByKid.entrySet().stream()
        .<JWK>map(e -> new ECKey.Builder(Curve.P_256, e.getValue()).keyID(e.getKey()).build())
        .toList();
    var processor = new DefaultJWTProcessor<SecurityContext>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
        new ImmutableJWKSet<>(new JWKSet(keys))));
    // Claims are checked by the Spring validators below with the injected clock.
    processor.setJWTClaimsSetVerifier((claims, context) -> { });
    this.decoder = new NimbusJwtDecoder(processor);
    var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
    timestamps.setClock(clock);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
        timestamps,
        new JwtIssuerValidator(ISSUER),
        new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(AUDIENCE)),
        jwt -> lifetime(jwt, clock))));
  }

  /** Parses {@code kid:base64-X.509,kid2:base64-X.509} (USER_JWT_PUBLIC_KEYS). */
  public static Map<String, ECPublicKey> parseKeys(String config) {
    var keys = new LinkedHashMap<String, ECPublicKey>();
    for (String entry : config.split(",")) {
      String[] parts = entry.strip().split(":", 2);
      if (parts.length != 2 || parts[0].isBlank()) {
        throw new IllegalArgumentException("expected kid:base64-public-key entries");
      }
      try {
        var spec = new X509EncodedKeySpec(Base64.getDecoder().decode(parts[1].strip()));
        keys.put(parts[0].strip(), (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(spec));
      } catch (Exception e) {
        throw new IllegalArgumentException("invalid public key for kid " + parts[0].strip(), e);
      }
    }
    return Map.copyOf(keys);
  }

  public Optional<Actor> verify(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      SignedJWT.parse(token); // JWS only: JWE and unsecured tokens are rejected here.
      Jwt jwt = decoder.decode(token);
      Object version = jwt.getClaims().get("auth_version");
      if (jwt.getSubject() == null || !(version instanceof Number number)) {
        return Optional.empty();
      }
      return Optional.of(new Actor(UUID.fromString(jwt.getSubject()), number.longValue()));
    } catch (ParseException | JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static OAuth2TokenValidatorResult lifetime(Jwt jwt, Clock clock) {
    Instant issuedAt = jwt.getIssuedAt();
    Instant expiresAt = jwt.getExpiresAt();
    boolean ok = issuedAt != null && expiresAt != null
        && !issuedAt.isAfter(clock.instant().plus(CLOCK_SKEW))
        && expiresAt.isAfter(issuedAt)
        && !expiresAt.isAfter(issuedAt.plus(MAX_LIFETIME).plus(CLOCK_SKEW));
    return ok
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "access token lifetime", null));
  }
}
