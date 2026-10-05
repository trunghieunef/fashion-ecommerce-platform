package vn.fashion.user.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.idempotency.IdempotencyStore;
import vn.fashion.user.auth.AuthService;
import vn.fashion.user.web.Api;

/**
 * SUPER_ADMIN user management (03 section 3, 06 section 2.1 RBAC). Each change locks the target's
 * row, bumps auth_version so the user's current tokens stop working, and appends an audit row in the
 * same transaction. An admin cannot change or lock their own account.
 */
@Service
public class AdminService {
  public record AdminUser(UUID id, String email, String fullName, String status, List<String> roles,
                          long version, OffsetDateTime createdAt) {
  }

  public record UserPage(List<AdminUser> items, int page, int size, long total) {
  }

  private static final String USER_SELECT = """
      select u.id, u.email, u.full_name, u.status, u.auth_version, u.created_at,
             coalesce(array_agg(r.code order by r.code) filter (where r.code is not null), '{}') as roles
      from users u left join user_roles ur on ur.user_id = u.id left join roles r on r.id = ur.role_id
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final IdempotencyStore idempotency;

  public AdminService(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.idempotency = new IdempotencyStore(jdbc);
  }

  public Set<String> roleCodes() {
    return Set.copyOf(jdbc.sql("select code from roles").query(String.class).list());
  }

  public UserPage list(int page, int size) {
    List<AdminUser> items = jdbc.sql(USER_SELECT + " group by u.id order by u.created_at, u.id limit :size offset :offset")
        .param("size", size).param("offset", (long) (page - 1) * size)
        .query((rs, row) -> user(rs)).list();
    long total = jdbc.sql("select count(*) from users").query(Long.class).single();
    return new UserPage(items, page, size, total);
  }

  public AdminUser changeRoles(UUID actor, UUID target, Set<String> roles, String reason, long expectedVersion,
                               UUID requestId) {
    return tx.execute(status -> {
      AdminUser before = lockTarget(actor, target);
      if (Set.copyOf(before.roles()).equals(roles)) {
        return before; // a retry of a change that already applied
      }
      requireVersion(before, expectedVersion);
      jdbc.sql("delete from user_roles where user_id = :id").param("id", target).update();
      if (!roles.isEmpty()) {
        jdbc.sql("insert into user_roles(user_id, role_id) select :id, id from roles where code in (:codes)")
            .param("id", target).param("codes", List.copyOf(roles)).update();
      }
      bumpAuthVersion(target);
      audit(actor, "user.roles_changed", target, reason, Map.of("roles", before.roles()),
          Map.of("roles", new TreeSet<>(roles)), requestId);
      return find(target);
    });
  }

  /** Idempotent by key: a retry with the same body returns the stored response. */
  public JsonNode lock(UUID actor, UUID target, String reason, long expectedVersion, String key, UUID requestId) {
    String operation = "user.lock";
    String hash = AuthService.sha256(json.writeValueAsString(List.of(target, expectedVersion, reason)));
    return tx.execute(status -> {
      var outcome = idempotency.begin(actor.toString(), operation, key, hash);
      if (outcome.status() == IdempotencyStore.Status.CONFLICT) {
        throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "IDEMPOTENCY_KEY_REUSED", List.of());
      }
      if (outcome.status() == IdempotencyStore.Status.COMPLETED) {
        return json.readTree(outcome.responseBody());
      }
      AdminUser before = lockTarget(actor, target);
      requireVersion(before, expectedVersion);
      jdbc.sql("update users set status = 'LOCKED' where id = :id").param("id", target).update();
      bumpAuthVersion(target);
      jdbc.sql("update refresh_tokens set revoked_at = now() where user_id = :id and revoked_at is null")
          .param("id", target).update();
      audit(actor, "user.locked", target, reason, Map.of("status", before.status()), Map.of("status", "LOCKED"),
          requestId);
      JsonNode response = json.valueToTree(find(target));
      idempotency.finish(actor.toString(), operation, key, target, 200, response.toString());
      return response;
    });
  }

  List<String> rolesOf(UUID userId) {
    return jdbc.sql("""
            select r.code from user_roles ur join roles r on r.id = ur.role_id
            where ur.user_id = :id order by r.code
            """)
        .param("id", userId).query(String.class).list();
  }

  void bumpAuthVersion(UUID userId) {
    jdbc.sql("update users set auth_version = auth_version + 1, updated_at = now() where id = :id")
        .param("id", userId).update();
  }

  /** actor null is SYSTEM. Only role/status facts are recorded, never contact data or secrets. */
  void audit(UUID actor, String action, UUID userId, String reason, Object before, Object after, UUID requestId) {
    jdbc.sql("""
            insert into audit_logs(id, actor_id, action, resource_type, resource_id, reason,
                                   before_data, after_data, request_id)
            values (:id, :actor, :action, 'user', :resource, :reason,
                    cast(:before as jsonb), cast(:after as jsonb), :requestId)
            """)
        .param("id", UUID.randomUUID()).param("actor", actor).param("action", action)
        .param("resource", userId.toString()).param("reason", reason)
        .param("before", json.writeValueAsString(before)).param("after", json.writeValueAsString(after))
        .param("requestId", requestId)
        .update();
  }

  /** Same lock order as refresh (account row before tokens), so the two cannot deadlock. */
  private AdminUser lockTarget(UUID actor, UUID target) {
    if (actor.equals(target)) {
      throw new Api.Problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "SELF_MODIFICATION_FORBIDDEN", List.of());
    }
    jdbc.sql("select id from users where id = :id for update").param("id", target).query(UUID.class).optional()
        .orElseThrow(() -> new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "USER_NOT_FOUND", List.of()));
    return find(target);
  }

  private static void requireVersion(AdminUser user, long expectedVersion) {
    if (user.version() != expectedVersion) {
      throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "STALE_VERSION", List.of());
    }
  }

  private AdminUser find(UUID userId) {
    return jdbc.sql(USER_SELECT + " where u.id = :id group by u.id").param("id", userId)
        .query((rs, row) -> user(rs)).single();
  }

  private static AdminUser user(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new AdminUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"),
        rs.getString("status"), List.of((String[]) rs.getArray("roles").getArray()), rs.getLong("auth_version"),
        rs.getObject("created_at", OffsetDateTime.class));
  }
}
