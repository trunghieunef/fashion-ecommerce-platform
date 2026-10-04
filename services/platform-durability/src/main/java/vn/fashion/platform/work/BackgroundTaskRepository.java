package vn.fashion.platform.work;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable async work (05 section 15 background_tasks): enqueued with the business change, claimed
 * with a lease, finished by token compare-and-set. Exhausted retries become MANUAL and keep the
 * obligation; the work's target still dedupes by business key because a lost lease can re-run it.
 */
public class BackgroundTaskRepository {
  public record ClaimedTask(UUID id, String kind, String businessKey, String payloadJson, int attempts,
                            UUID leaseToken) {
  }

  private final JdbcClient jdbc;

  public BackgroundTaskRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Enqueues inside the business transaction; false when (kind, businessKey) already exists. */
  public boolean enqueue(String kind, String businessKey, String payloadJson) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("task enqueue must join the business transaction");
    }
    return jdbc.sql("""
            insert into background_tasks(kind, business_key, payload)
            values (:kind, :key, cast(:payload as jsonb))
            on conflict (kind, business_key) do nothing
            """)
        .param("kind", kind).param("key", businessKey).param("payload", payloadJson)
        .update() == 1;
  }

  /** Leases due PENDING tasks and RUNNING tasks whose lease expired (database clock). */
  public List<ClaimedTask> claimDue(String kind, int limit, Duration lease) {
    return jdbc.sql("""
            with due as (
              select id from background_tasks
              where kind = :kind and next_attempt_at <= now()
                and (status = 'PENDING' or (status = 'RUNNING' and lease_until <= now()))
              order by next_attempt_at, id
              limit :limit
              for update skip locked
            )
            update background_tasks t
            set status = 'RUNNING', lease_token = gen_random_uuid(),
                lease_until = now() + make_interval(secs => :leaseSeconds),
                attempts = t.attempts + 1, updated_at = now()
            from due
            where t.id = due.id
            returning t.id, t.kind, t.business_key, t.payload::text as payload, t.attempts, t.lease_token
            """)
        .param("kind", kind).param("limit", limit).param("leaseSeconds", lease.toMillis() / 1000.0)
        .query((rs, row) -> new ClaimedTask(
            rs.getObject("id", UUID.class),
            rs.getString("kind"),
            rs.getString("business_key"),
            rs.getString("payload"),
            rs.getInt("attempts"),
            rs.getObject("lease_token", UUID.class)))
        .list();
  }

  /** Marks DONE only while the caller still holds a valid lease. */
  public boolean complete(UUID id, UUID leaseToken) {
    return jdbc.sql("""
            update background_tasks
            set status = 'DONE', lease_token = null, lease_until = null, last_error = null,
                updated_at = now()
            where id = :id and status = 'RUNNING' and lease_token = :token and lease_until > now()
            """)
        .param("id", id).param("token", leaseToken)
        .update() == 1;
  }

  /**
   * Schedules a retry after {@code retryAfter} (caller applies backoff with jitter), or MANUAL once
   * {@code maxAttempts} is reached. False when the lease is no longer held.
   */
  public boolean fail(UUID id, UUID leaseToken, String error, Duration retryAfter, int maxAttempts) {
    return jdbc.sql("""
            update background_tasks
            set status = case when attempts >= :maxAttempts then 'MANUAL' else 'PENDING' end,
                next_attempt_at = now() + make_interval(secs => :retrySeconds),
                last_error = left(:error, 1000), lease_token = null, lease_until = null,
                updated_at = now()
            where id = :id and status = 'RUNNING' and lease_token = :token and lease_until > now()
            """)
        .param("maxAttempts", maxAttempts).param("retrySeconds", retryAfter.toMillis() / 1000.0)
        .param("error", error).param("id", id).param("token", leaseToken)
        .update() == 1;
  }
}
