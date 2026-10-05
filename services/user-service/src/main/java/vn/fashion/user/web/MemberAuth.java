package vn.fashion.user.web;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.fashion.platform.security.AccessTokenVerifier;

/**
 * Resolves the member from the Bearer token (ADR-21). As the owner of users, this service also
 * checks the current row: the account must be ACTIVE, not locked, and the token's auth_version
 * must equal the stored one, so a role change, lock or password change applies immediately. Because
 * every role change bumps auth_version, a current token's permissions claim matches the roles.
 */
@Component
public class MemberAuth {
  private final AccessTokenVerifier verifier;
  private final JdbcClient jdbc;

  public MemberAuth(@Value("${fashion.user.jwt.public-keys}") String publicKeys, JdbcClient jdbc) {
    this.verifier = new AccessTokenVerifier(AccessTokenVerifier.parseKeys(publicKeys), Clock.systemUTC());
    this.jdbc = jdbc;
  }

  public UUID requireMember(String authorization) {
    return requireCurrent(authorization).userId();
  }

  /** 401 without a current token, 403 FORBIDDEN when the token lacks the permission code. */
  public UUID requirePermission(String authorization, String permission) {
    var actor = requireCurrent(authorization);
    if (!actor.permissions().contains(permission)) {
      throw new Api.Problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "PERMISSION_REQUIRED", List.of());
    }
    return actor.userId();
  }

  private AccessTokenVerifier.Actor requireCurrent(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw unauthorized();
    }
    var actor = verifier.verify(authorization.substring(7).strip()).orElseThrow(MemberAuth::unauthorized);
    boolean current = jdbc.sql("""
            select count(*) from users
            where id = :id and status = 'ACTIVE' and auth_version = :version
              and coalesce(locked_until <= now(), true)
            """)
        .param("id", actor.userId()).param("version", actor.authVersion())
        .query(Integer.class).single() == 1;
    if (!current) {
      throw unauthorized();
    }
    return actor;
  }

  private static Api.Problem unauthorized() {
    return new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_ACCESS_TOKEN", List.of());
  }
}
