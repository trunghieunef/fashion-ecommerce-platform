package vn.fashion.user.auth;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

/**
 * Registration, login with lockout and refresh-token families (06 section 2.1). Secrets are only
 * stored as hashes: BCrypt for passwords, SHA-256 for refresh tokens.
 */
@Service
public class AuthService {
  public record UserView(UUID id, String email, String fullName, String locale) {
  }

  public record Session(UserView user, String accessToken, String refreshToken) {
  }

  public static final class EmailTakenException extends RuntimeException {
  }

  public static final class InvalidCredentialsException extends RuntimeException {
  }

  public static final class InvalidRefreshTokenException extends RuntimeException {
  }

  static final Duration REFRESH_TTL = Duration.ofDays(30);
  private static final int MAX_FAILED_LOGINS = 5;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final AccessTokenIssuer accessTokens;
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final Tracer tracer;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  // Compared against when the account is unknown or locked so timing does not reveal it.
  private final String dummyHash = passwords.encode("timing-equalizer-" + UUID.randomUUID());

  public AuthService(JdbcClient jdbc, TransactionTemplate tx, AccessTokenIssuer accessTokens,
                     ObjectMapper json, Tracer tracer) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.accessTokens = accessTokens;
    this.outbox = new OutboxRepository(jdbc);
    this.json = json;
    this.tracer = tracer;
  }

  public Session register(String email, String password, String fullName, String locale) {
    String hash = passwords.encode(password);
    UUID userId = UUID.randomUUID();
    try {
      return tx.execute(status -> {
        jdbc.sql("""
                insert into users(id, email, password_hash, full_name, locale)
                values (:id, :email, :hash, :fullName, :locale)
                """)
            .param("id", userId).param("email", email).param("hash", hash)
            .param("fullName", fullName).param("locale", locale)
            .update();
        outbox.append(new OutboxEvent(UUID.randomUUID(), "user", userId.toString(), 0, "USER_CREATED", 1,
            "user.events", userId.toString(), correlationId(),
            json.writeValueAsString(java.util.Map.of("user_id", userId.toString(), "locale", locale))));
        String refresh = newRefreshToken(userId, UUID.randomUUID());
        return new Session(new UserView(userId, email, fullName, locale), accessTokens.issue(userId, 0), refresh);
      });
    } catch (DuplicateKeyException e) {
      throw new EmailTakenException();
    }
  }

  public Session login(String email, String password) {
    record Account(UUID id, String hash, String fullName, String locale, long authVersion, boolean locked) {
    }
    Optional<Account> account = jdbc.sql("""
            select id, password_hash, full_name, locale, auth_version,
                   coalesce(locked_until > now(), false) as locked
            from users where email = :email and status = 'ACTIVE'
            """)
        .param("email", email)
        .query((rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("password_hash"),
            rs.getString("full_name"), rs.getString("locale"), rs.getLong("auth_version"), rs.getBoolean("locked")))
        .optional();
    if (account.isEmpty() || account.get().locked() || account.get().hash() == null) {
      passwords.matches(password, dummyHash);
      throw new InvalidCredentialsException();
    }
    Account found = account.get();
    if (!passwords.matches(password, found.hash())) {
      recordFailedLogin(found.id());
      throw new InvalidCredentialsException();
    }
    return tx.execute(status -> {
      jdbc.sql("update users set failed_login_attempts = 0, locked_until = null, updated_at = now() where id = :id")
          .param("id", found.id()).update();
      String refresh = newRefreshToken(found.id(), UUID.randomUUID());
      return new Session(new UserView(found.id(), email, found.fullName(), found.locale()),
          accessTokens.issue(found.id(), found.authVersion()), refresh);
    });
  }

  /**
   * Rotate on use. A token that was already rotated or revoked is a replay: the whole family is
   * revoked and that revocation commits before the caller gets 401. Every write to a family
   * (rotation, replay revocation, logout) first takes the family lock, so a revocation never
   * misses a token that a concurrent rotation is inserting.
   */
  public Session refresh(String rawToken) {
    enum Outcome { ROTATED, REPLAY, INVALID }
    record Result(Outcome outcome, Session session) {
    }
    Result result = tx.execute(status -> {
      Optional<UUID> family = familyOf(rawToken);
      if (family.isEmpty()) {
        return new Result(Outcome.INVALID, null);
      }
      lockFamily(family.get());
      record Token(UUID id, UUID userId, UUID familyId, boolean revoked, boolean expired) {
      }
      Optional<Token> token = jdbc.sql("""
              select id, user_id, family_id, revoked_at is not null as revoked, expires_at <= now() as expired
              from refresh_tokens where token_hash = :hash for update
              """)
          .param("hash", sha256(rawToken))
          .query((rs, row) -> new Token(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
              rs.getObject("family_id", UUID.class), rs.getBoolean("revoked"), rs.getBoolean("expired")))
          .optional();
      if (token.isEmpty()) {
        return new Result(Outcome.INVALID, null);
      }
      Token current = token.get();
      if (current.revoked()) {
        revokeFamily(current.familyId());
        return new Result(Outcome.REPLAY, null);
      }
      if (current.expired()) {
        return new Result(Outcome.INVALID, null);
      }
      record Owner(long authVersion) {
      }
      Optional<Owner> owner = jdbc.sql("""
              select auth_version from users
              where id = :id and status = 'ACTIVE' and coalesce(locked_until <= now(), true)
              """)
          .param("id", current.userId()).query((rs, row) -> new Owner(rs.getLong(1))).optional();
      if (owner.isEmpty()) {
        revokeFamily(current.familyId());
        return new Result(Outcome.INVALID, null);
      }
      String next = newRefreshToken(current.userId(), current.familyId());
      jdbc.sql("""
              update refresh_tokens set revoked_at = now(),
                replaced_by = (select id from refresh_tokens where token_hash = :next)
              where id = :id
              """)
          .param("next", sha256(next)).param("id", current.id()).update();
      return new Result(Outcome.ROTATED,
          new Session(null, accessTokens.issue(current.userId(), owner.get().authVersion()), next));
    });
    if (result.outcome() != Outcome.ROTATED) {
      throw new InvalidRefreshTokenException();
    }
    return result.session();
  }

  /** Revokes the token's whole family; unknown tokens are ignored so logout is idempotent. */
  public void logout(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return;
    }
    tx.executeWithoutResult(status -> familyOf(rawToken).ifPresent(family -> {
      lockFamily(family);
      revokeFamily(family);
    }));
  }

  private Optional<UUID> familyOf(String rawToken) {
    return jdbc.sql("select family_id from refresh_tokens where token_hash = :hash")
        .param("hash", sha256(rawToken)).query(UUID.class).optional();
  }

  /**
   * Transaction-scoped lock per family. Statements after it see rows committed by the previous
   * holder (READ COMMITTED), which row locks on one token alone do not guarantee.
   */
  private void lockFamily(UUID familyId) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(cast(:family as text), 0))")
        .param("family", familyId).query((rs, row) -> 1).list();
  }

  private void recordFailedLogin(UUID userId) {
    // After an expired lock the count restarts at 1; the fifth failure locks for 15 minutes.
    jdbc.sql("""
            update users set
              failed_login_attempts = case when locked_until <= now() then 1 else failed_login_attempts + 1 end,
              locked_until = case
                when (case when locked_until <= now() then 1 else failed_login_attempts + 1 end) >= :max
                  then now() + interval '15 minutes'
                when locked_until <= now() then null
                else locked_until end,
              updated_at = now()
            where id = :id
            """)
        .param("max", MAX_FAILED_LOGINS).param("id", userId).update();
  }

  private void revokeFamily(UUID familyId) {
    jdbc.sql("update refresh_tokens set revoked_at = now() where family_id = :family and revoked_at is null")
        .param("family", familyId).update();
  }

  private String newRefreshToken(UUID userId, UUID familyId) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.sql("""
            insert into refresh_tokens(id, user_id, family_id, token_hash, expires_at)
            values (:id, :userId, :familyId, :hash, now() + make_interval(days => :days))
            """)
        .param("id", UUID.randomUUID()).param("userId", userId).param("familyId", familyId)
        .param("hash", sha256(raw)).param("days", (int) REFRESH_TTL.toDays())
        .update();
    return raw;
  }

  private String correlationId() {
    Span span = tracer.currentSpan();
    return span != null ? span.context().traceId() : UUID.randomUUID().toString().replace("-", "");
  }

  static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
