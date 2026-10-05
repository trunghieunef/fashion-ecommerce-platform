package vn.fashion.user.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;
import vn.fashion.user.web.Api;

/**
 * Change, forgot and reset password (03 section 2.1, 06 section 2.1, PRD USR-03/05). A reset token
 * is random, valid 30 minutes and once; the database keeps its SHA-256, notification gets it
 * through {@link SecretVault}. Changing or resetting bumps auth_version and revokes every refresh
 * token. The account row is locked before tokens, the same order as login, refresh and admin.
 */
@Service
public class PasswordService {
  static final String RESET = "RESET_PASSWORD";
  static final Duration RESET_TTL = Duration.ofMinutes(30);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final AuthService auth;
  private final SecretVault vault;
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

  public PasswordService(JdbcClient jdbc, TransactionTemplate tx, AuthService auth, SecretVault vault,
                         ObjectMapper json) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.auth = auth;
    this.vault = vault;
    this.outbox = new OutboxRepository(jdbc);
    this.json = json;
  }

  public void change(UUID userId, String currentPassword, String newPassword) {
    String newHash = passwords.encode(newPassword);
    boolean changed = Boolean.TRUE.equals(tx.execute(status -> {
      String hash = jdbc.sql("select password_hash from users where id = :id and status = 'ACTIVE' for no key update")
          .param("id", userId).query(String.class).optional().orElse(null);
      if (hash == null || !passwords.matches(currentPassword, hash)) {
        return false;
      }
      replacePassword(userId, newHash);
      return true;
    }));
    if (!changed) {
      auth.recordFailedLogin(userId);
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_PASSWORD_CHANGE",
          List.of(new Api.FieldError("current_password", "is incorrect")));
    }
  }

  /** Always succeeds for the caller; only an ACTIVE account gets a token, older ones are voided. */
  public void forgot(String email) {
    record Account(UUID id, String locale, long authVersion) {
    }
    Optional<Account> account = jdbc.sql("select id, locale, auth_version from users where email = :email and status = 'ACTIVE'")
        .param("email", email)
        .query((rs, row) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3))).optional();
    if (account.isEmpty()) {
      return;
    }
    UUID userId = account.get().id();
    UUID challengeId = UUID.randomUUID();
    String token = randomToken();
    Instant expiresAt = Instant.now().plus(RESET_TTL).truncatedTo(ChronoUnit.SECONDS);
    List<UUID> superseded = tx.execute(status -> {
      jdbc.sql("select id from users where id = :id for no key update").param("id", userId).query(UUID.class).single();
      List<UUID> previous = jdbc.sql("""
              update user_action_tokens set used_at = now()
              where user_id = :id and purpose = 'RESET_PASSWORD' and used_at is null returning id
              """)
          .param("id", userId).query(UUID.class).list();
      jdbc.sql("""
              insert into user_action_tokens(id, user_id, purpose, target_email, token_hash, expires_at)
              values (:id, :userId, 'RESET_PASSWORD', :email, :hash, :expiresAt)
              """)
          .param("id", challengeId).param("userId", userId).param("email", email)
          .param("hash", AuthService.sha256(token)).param("expiresAt", java.sql.Timestamp.from(expiresAt))
          .update();
      var payload = new LinkedHashMap<String, Object>();
      payload.put("dedupe_key", RESET + ":" + challengeId);
      payload.put("user_id", userId.toString());
      payload.put("recipient", Map.of("email", email));
      payload.put("channel", "EMAIL");
      payload.put("locale", account.get().locale());
      payload.put("template_key", RESET);
      payload.put("challenge_id", challengeId.toString());
      payload.put("expires_at", expiresAt.toString());
      outbox.append(new OutboxEvent(UUID.randomUUID(), "user", userId.toString(), account.get().authVersion(),
          "NOTIFY_RESET_PASSWORD", 1, "notification.events", RESET + ":" + challengeId, auth.correlationId(),
          json.writeValueAsString(payload)));
      // Inside the transaction: if Redis is down nothing is queued and the caller sees 503.
      vault.put(new SecretVault.Secret(challengeId, RESET, token, expiresAt));
      return previous;
    });
    superseded.forEach(vault::delete);
  }

  public void reset(String token, String newPassword) {
    record Pending(UUID id, UUID userId) {
    }
    Optional<Pending> pending = jdbc.sql("select id, user_id from user_action_tokens where token_hash = :hash and purpose = 'RESET_PASSWORD'")
        .param("hash", AuthService.sha256(token))
        .query((rs, row) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional();
    if (pending.isEmpty()) {
      throw invalidToken();
    }
    String newHash = passwords.encode(newPassword);
    boolean consumed = Boolean.TRUE.equals(tx.execute(status -> {
      Optional<String> email = jdbc.sql("select email from users where id = :id and status = 'ACTIVE' for no key update")
          .param("id", pending.get().userId()).query(String.class).optional();
      int used = email.isEmpty() ? 0 : jdbc.sql("""
              update user_action_tokens set used_at = now()
              where id = :id and used_at is null and expires_at > now() and target_email = :email
              """)
          .param("id", pending.get().id()).param("email", email.get()).update();
      if (used == 0) {
        return false;
      }
      replacePassword(pending.get().userId(), newHash);
      // Proving control of the mailbox also clears the failed-login lock (PRD USR-03).
      jdbc.sql("update users set failed_login_attempts = 0, locked_until = null where id = :id")
          .param("id", pending.get().userId()).update();
      return true;
    }));
    vault.delete(pending.get().id());
    if (!consumed) {
      throw invalidToken();
    }
  }

  /** The secret while its token is still unused and unexpired (03 section 4). */
  public Optional<SecretVault.Secret> secret(UUID challengeId) {
    boolean live = jdbc.sql("select count(*) from user_action_tokens where id = :id and used_at is null and expires_at > now()")
        .param("id", challengeId).query(Integer.class).single() == 1;
    return live ? vault.get(challengeId) : Optional.empty();
  }

  private void replacePassword(UUID userId, String newHash) {
    jdbc.sql("update users set password_hash = :hash, auth_version = auth_version + 1, updated_at = now() where id = :id")
        .param("hash", newHash).param("id", userId).update();
    jdbc.sql("update refresh_tokens set revoked_at = now() where user_id = :id and revoked_at is null")
        .param("id", userId).update();
  }

  private static Api.Problem invalidToken() {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_RESET_TOKEN",
        List.of(new Api.FieldError("token", "is invalid, used or expired")));
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
