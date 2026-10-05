package vn.fashion.user.auth;

import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.platform.security.AccessTokenVerifier;
import vn.fashion.platform.security.ServiceTokenVerifier;
import vn.fashion.user.web.Api;

/**
 * Secret handoff to notification (03 section 4): a service token (ADR-19) from an allowlisted
 * caller, and this operation only accepts notification-service. The response is never cached; the
 * Gateway does not route /internal.
 */
@RestController
public class NotificationSecretController {
  static final String SERVICE = "user-service";
  static final String CALLER = "notification-service";

  public record SecretData(UUID challengeId, String purpose, String secret, Instant expiresAt) {
  }

  private final ServiceTokenVerifier callers;
  private final PasswordService passwords;
  private final Tracer tracer;

  public NotificationSecretController(@Value("${fashion.user.internal-callers}") String internalCallers,
                                      PasswordService passwords, Tracer tracer) {
    // Same kid:base64-X.509 list format as ADR-21 keys; the kid is the caller's service name.
    this.callers = new ServiceTokenVerifier(SERVICE,
        internalCallers.isBlank() ? Map.of() : AccessTokenVerifier.parseKeys(internalCallers), Clock.systemUTC());
    this.passwords = passwords;
    this.tracer = tracer;
  }

  @GetMapping("/internal/api/v1/users/notification-secrets/{challengeId}")
  public ResponseEntity<Api.Response<SecretData>> secret(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable UUID challengeId) {
    String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
        ? authorization.substring(7).strip() : null;
    String caller = callers.verify(token).orElseThrow(() ->
        new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_SERVICE_TOKEN", List.of()));
    if (!CALLER.equals(caller)) {
      throw new Api.Problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "CALLER_NOT_ALLOWED", List.of());
    }
    var secret = passwords.secret(challengeId).orElseThrow(() ->
        new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "SECRET_NOT_FOUND", List.of()));
    var response = Api.ok(HttpStatus.OK,
        new SecretData(secret.challengeId(), secret.purpose(), secret.value(), secret.expiresAt()), tracer);
    return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
        .cacheControl(CacheControl.noStore()).body(response.getBody());
  }
}
