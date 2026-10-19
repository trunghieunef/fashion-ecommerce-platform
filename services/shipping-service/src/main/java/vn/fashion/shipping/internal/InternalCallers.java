package vn.fashion.shipping.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.fashion.platform.security.AccessTokenVerifier;
import vn.fashion.platform.security.ServiceTokenVerifier;
import vn.fashion.shipping.web.Api;

/** ADR-19 token with audience shipping-service, caller order-service only; checked before headers and body. */
@Component
public class InternalCallers {
  private final ServiceTokenVerifier verifier;

  public InternalCallers(@Value("${fashion.shipping.internal-callers:}") String keys) {
    verifier = new ServiceTokenVerifier("shipping-service", keys.isBlank() ? Map.of() : AccessTokenVerifier.parseKeys(keys), Clock.systemUTC());
  }

  public void require(String authorization) {
    String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7).strip() : null;
    String caller = verifier.verify(token)
        .orElseThrow(() -> new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_SERVICE_TOKEN", List.of()));
    if (!"order-service".equals(caller)) throw new Api.Problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "CALLER_NOT_ALLOWED", List.of());
  }
}
