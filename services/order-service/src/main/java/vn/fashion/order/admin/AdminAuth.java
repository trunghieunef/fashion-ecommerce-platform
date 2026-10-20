package vn.fashion.order.admin;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.fashion.order.web.Api;
import vn.fashion.platform.security.AccessTokenVerifier;

/** OPS/FINANCE Bearer check (13): the service verifies the token and permission itself, not only the Gateway. */
@Component
public class AdminAuth {
  public record AdminActor(UUID userId, Set<String> permissions) { }
  private final AccessTokenVerifier verifier;

  public AdminAuth(@Value("${fashion.order.jwt.public-keys}") String keys, Clock clock) {
    verifier = new AccessTokenVerifier(AccessTokenVerifier.parseKeys(keys), clock);
  }

  public AdminActor require(String authorization, String permission) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw unauthorized();
    var actor = verifier.verify(authorization.substring(7).strip()).orElseThrow(AdminAuth::unauthorized);
    if (!actor.permissions().contains(permission)) throw forbidden();
    return new AdminActor(actor.userId(), actor.permissions());
  }
  public static Api.Problem forbidden() { return new Api.Problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "PERMISSION_REQUIRED", List.of()); }
  private static Api.Problem unauthorized() {
    return new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_ACCESS_TOKEN", List.of());
  }
}
