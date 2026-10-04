package vn.fashion.platform.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies service tokens at the receiving service (ADR-19), deny by default: only ES256 signed
 * by the key allowlisted for the claimed caller, aud contains this service, sub = iss, and a
 * lifetime of at most {@link ServiceTokenIssuer#TTL}. Returns the caller name, which the endpoint
 * still checks against its own caller allowlist (03 section 4). Never logs the token.
 */
public class ServiceTokenVerifier {
  private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

  private final Map<String, JwtDecoder> decoders;

  public ServiceTokenVerifier(String serviceName, Map<String, ECPublicKey> allowedCallers, Clock clock) {
    this.decoders = allowedCallers.entrySet().stream().collect(Collectors.toUnmodifiableMap(
        Map.Entry::getKey, caller -> decoder(serviceName, caller.getKey(), caller.getValue(), clock)));
  }

  public Optional<String> verify(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      String caller = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
      JwtDecoder decoder = caller == null ? null : decoders.get(caller);
      if (decoder == null) {
        return Optional.empty();
      }
      decoder.decode(token);
      return Optional.of(caller);
    } catch (ParseException | JwtException e) {
      return Optional.empty();
    }
  }

  private static JwtDecoder decoder(String serviceName, String caller, ECPublicKey key, Clock clock) {
    var processor = new DefaultJWTProcessor<SecurityContext>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
        new ImmutableJWKSet<>(new JWKSet(new ECKey.Builder(Curve.P_256, key).keyID(caller).build()))));
    // Claims are checked by the Spring validators below with the injected clock.
    processor.setJWTClaimsSetVerifier((claims, context) -> { });
    var decoder = new NimbusJwtDecoder(processor);
    var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
    timestamps.setClock(clock);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
        timestamps,
        new JwtIssuerValidator(caller),
        new JwtClaimValidator<String>(JwtClaimNames.SUB, caller::equals),
        new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(serviceName)),
        ServiceTokenVerifier::shortLived)));
    return decoder;
  }

  private static OAuth2TokenValidatorResult shortLived(Jwt jwt) {
    Instant issuedAt = jwt.getIssuedAt();
    Instant expiresAt = jwt.getExpiresAt();
    boolean ok = issuedAt != null && expiresAt != null
        && !expiresAt.isAfter(issuedAt.plus(ServiceTokenIssuer.TTL).plus(CLOCK_SKEW));
    return ok
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "service token lifetime", null));
  }
}
