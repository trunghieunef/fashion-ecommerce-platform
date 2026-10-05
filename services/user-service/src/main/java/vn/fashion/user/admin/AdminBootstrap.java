package vn.fashion.user.admin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * First SUPER_ADMIN (05 section 6: no default admin password). At startup, when
 * USER_BOOTSTRAP_ADMIN_EMAIL is set and no SUPER_ADMIN exists yet, grants the role to that already
 * registered ACTIVE account and audits it with actor SYSTEM. Afterwards the variable has no effect;
 * an account registered later needs a restart.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final AdminService admin;
  private final String email;

  public AdminBootstrap(JdbcClient jdbc, TransactionTemplate tx, AdminService admin,
                        @Value("${fashion.user.bootstrap-admin-email:}") String email) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.admin = admin;
    this.email = email;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!email.isBlank()) {
      // The address is contact data: do not log it.
      log.info(grant(email) ? "Admin bootstrap granted SUPER_ADMIN"
          : "Admin bootstrap skipped: a SUPER_ADMIN exists or the account is not registered and active");
    }
  }

  public boolean grant(String rawEmail) {
    String normalized = rawEmail.strip().toLowerCase(Locale.ROOT);
    return Boolean.TRUE.equals(tx.execute(status -> {
      // Replicas starting together: one bootstrap at a time.
      jdbc.sql("select pg_advisory_xact_lock(hashtextextended('user.admin-bootstrap', 0))")
          .query((rs, row) -> 1).list();
      boolean exists = jdbc.sql("""
              select exists(select 1 from user_roles ur join roles r on r.id = ur.role_id
                            where r.code = 'SUPER_ADMIN')
              """).query(Boolean.class).single();
      if (exists) {
        return false;
      }
      var userId = jdbc.sql("select id from users where email = :email and status = 'ACTIVE' for update")
          .param("email", normalized).query(UUID.class).optional();
      if (userId.isEmpty()) {
        return false;
      }
      List<String> before = admin.rolesOf(userId.get());
      jdbc.sql("insert into user_roles(user_id, role_id) select :id, id from roles where code = 'SUPER_ADMIN'")
          .param("id", userId.get()).update();
      admin.bumpAuthVersion(userId.get());
      admin.audit(null, "user.bootstrap_super_admin", userId.get(), "USER_BOOTSTRAP_ADMIN_EMAIL",
          Map.of("roles", before), Map.of("roles", admin.rolesOf(userId.get())), UUID.randomUUID());
      return true;
    }));
  }
}
