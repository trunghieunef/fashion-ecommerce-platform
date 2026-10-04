package vn.fashion.platform.idempotency;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * API idempotency (03 section 1.3, 05 idempotency_requests). Call {@link #begin} and {@link #finish}
 * in the same local transaction as the business write; a concurrent duplicate waits on the
 * primary key and then sees the committed outcome. The caller maps CONFLICT to 409 and
 * IN_PROGRESS to 202 with the status URL. actor_key comes from verified auth/session only.
 */
public class IdempotencyStore {
  public enum Status { STARTED, IN_PROGRESS, COMPLETED, CONFLICT }

  public record Outcome(Status status, UUID resourceId, Integer responseCode, String responseBody) {
  }

  private final JdbcClient jdbc;

  public IdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Outcome begin(String actorKey, String operation, String key, String requestHash) {
    int inserted = jdbc.sql("""
            insert into idempotency_requests(actor_key, operation, key, request_hash)
            values (:actor, :operation, :key, :hash)
            on conflict do nothing
            """)
        .param("actor", actorKey).param("operation", operation).param("key", key)
        .param("hash", requestHash)
        .update();
    if (inserted == 1) {
      return new Outcome(Status.STARTED, null, null, null);
    }
    return jdbc.sql("""
            select request_hash, status, resource_id, response_code, response_body::text as body
            from idempotency_requests
            where actor_key = :actor and operation = :operation and key = :key
            """)
        .param("actor", actorKey).param("operation", operation).param("key", key)
        .query((rs, row) -> {
          if (!rs.getString("request_hash").equals(requestHash)) {
            return new Outcome(Status.CONFLICT, null, null, null);
          }
          if ("PROCESSING".equals(rs.getString("status"))) {
            return new Outcome(Status.IN_PROGRESS, rs.getObject("resource_id", UUID.class), null, null);
          }
          return new Outcome(Status.COMPLETED, rs.getObject("resource_id", UUID.class),
              rs.getInt("response_code"), rs.getString("body"));
        })
        .single();
  }

  /** Stores the final response once; false when it was already completed. Never store secrets. */
  public boolean finish(String actorKey, String operation, String key, UUID resourceId,
                        int responseCode, String responseJson) {
    return jdbc.sql("""
            update idempotency_requests
            set status = 'COMPLETED', resource_id = :resourceId, response_code = :code,
                response_body = cast(:body as jsonb), updated_at = now()
            where actor_key = :actor and operation = :operation and key = :key
              and status = 'PROCESSING'
            """)
        .param("resourceId", resourceId).param("code", responseCode).param("body", responseJson)
        .param("actor", actorKey).param("operation", operation).param("key", key)
        .update() == 1;
  }
}
