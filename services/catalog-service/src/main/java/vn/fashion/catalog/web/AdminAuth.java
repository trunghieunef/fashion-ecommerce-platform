package vn.fashion.catalog.web;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.fashion.platform.security.AccessTokenVerifier;

@Component
public class AdminAuth {
  private final AccessTokenVerifier verifier;
  public AdminAuth(@Value("${fashion.catalog.jwt.public-keys}") String keys) {
    verifier = new AccessTokenVerifier(AccessTokenVerifier.parseKeys(keys), Clock.systemUTC());
  }
  public UUID requireCatalogWriter(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw unauthorized();
    var actor = verifier.verify(authorization.substring(7).strip()).orElseThrow(AdminAuth::unauthorized);
    if (!actor.permissions().contains("catalog.write"))
      throw new Api.Problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "PERMISSION_REQUIRED", List.of());
    return actor.userId();
  }
  private static Api.Problem unauthorized() {
    return new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_ACCESS_TOKEN", List.of());
  }
}
