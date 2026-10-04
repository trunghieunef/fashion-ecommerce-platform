package vn.fashion.platform.work;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lease with token compare-and-set for a service's own work table (06 section 1.2), e.g.
 * order_sagas. The table needs id uuid, lease_token uuid and lease_until timestamptz. A worker whose
 * lease expired cannot write its result; target services still guard their own business keys.
 */
// ponytail: claim by id only; due-work scan (next_retry_at, status) stays in the owning service's query.
public class LeaseRepository {
  private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final String table;

  public LeaseRepository(JdbcClient jdbc, TransactionTemplate tx, String table) {
    if (!IDENTIFIER.matcher(table).matches()) {
      throw new IllegalArgumentException("table must be a plain identifier: " + table);
    }
    this.jdbc = jdbc;
    this.tx = tx;
    this.table = table;
  }

  /** Returns a new lease token when the row is unleased or its lease expired (database clock). */
  public Optional<UUID> claim(UUID id, Duration lease) {
    return jdbc.sql("update " + table + """
             set lease_token = gen_random_uuid(),
                lease_until = now() + make_interval(secs => :leaseSeconds)
            where id = :id and (lease_until is null or lease_until <= now())
            returning lease_token
            """)
        .param("id", id)
        .param("leaseSeconds", lease.toMillis() / 1000.0)
        .query(UUID.class)
        .optional();
  }

  /**
   * Releases a still-valid lease and runs {@code writeResult} in the same transaction; false, with
   * no write, when the lease expired or was reclaimed. A failing result write keeps the lease.
   */
  public boolean complete(UUID id, UUID leaseToken, Runnable writeResult) {
    return Boolean.TRUE.equals(tx.execute(status -> {
      int released = jdbc.sql("update " + table + """
               set lease_token = null, lease_until = null
              where id = :id and lease_token = :token and lease_until > now()
              """)
          .param("id", id).param("token", leaseToken)
          .update();
      if (released == 0) {
        return false;
      }
      writeResult.run();
      return true;
    }));
  }
}
