package vn.fashion.platform.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional outbox, at-least-once (04 section 2, 05 section 3, 06 section 1.3). The event id,
 * sequence and envelope stay fixed across re-publish, so consumers dedupe with processed_events.
 */
public class OutboxRepository {
  private final JdbcClient jdbc;

  public OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Appends inside the caller's business transaction and returns the aggregate_sequence. The caller
   * must hold the aggregate's row lock; the unique key rejects a concurrent writer that does not.
   */
  public long append(OutboxEvent event) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("outbox append must join the business transaction");
    }
    return jdbc.sql("""
            insert into outbox_events(id, aggregate_type, aggregate_id, aggregate_version,
                aggregate_sequence, event_type, schema_version, topic, partition_key,
                correlation_id, payload)
            select :id, :type, :aggregateId, :version, coalesce(max(aggregate_sequence), 0) + 1,
                :eventType, :schemaVersion, :topic, :partitionKey, :correlationId,
                cast(:payload as jsonb)
            from outbox_events
            where aggregate_type = :type and aggregate_id = :aggregateId
            returning aggregate_sequence
            """)
        .param("id", event.eventId())
        .param("type", event.aggregateType())
        .param("aggregateId", event.aggregateId())
        .param("version", event.aggregateVersion())
        .param("eventType", event.eventType())
        .param("schemaVersion", event.schemaVersion())
        .param("topic", event.topic())
        .param("partitionKey", event.partitionKey())
        .param("correlationId", event.correlationId())
        .param("payload", event.payloadJson())
        .query(Long.class)
        .single();
  }

  /**
   * Leases up to {@code limit} events, at most the earliest unsent sequence per aggregate, so a
   * leased or failed predecessor blocks its successors. Expiry uses the database clock. Runs as its
   * own committed statement: inside a business transaction it would expose uncommitted intents and
   * hold row locks while publishing, so that is rejected (06 section 1.1).
   */
  public List<OutboxMessage> claim(int limit, Duration lease) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("outbox claim/relay must run outside a transaction");
    }
    return jdbc.sql("""
            with heads as (
              select o.id
              from outbox_events o
              where o.next_attempt_at <= now()
                and (o.status = 'PENDING' or (o.status = 'IN_FLIGHT' and o.lease_until <= now()))
                and not exists (
                  select 1 from outbox_events p
                  where p.aggregate_type = o.aggregate_type and p.aggregate_id = o.aggregate_id
                    and p.aggregate_sequence < o.aggregate_sequence and p.status <> 'SENT')
              order by o.created_at, o.aggregate_sequence
              limit :limit
              for update of o skip locked
            )
            update outbox_events o
            set status = 'IN_FLIGHT', lease_token = gen_random_uuid(),
                lease_until = now() + make_interval(secs => :leaseSeconds), attempts = o.attempts + 1
            from heads
            where o.id = heads.id
            returning o.id, o.topic, o.partition_key, o.aggregate_sequence, o.lease_token,
              jsonb_build_object(
                'event_id', o.id,
                'event_type', o.event_type,
                'version', o.schema_version,
                'occurred_at', to_char(o.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                'aggregate_id', o.aggregate_id,
                'aggregate_version', o.aggregate_version,
                'aggregate_sequence', o.aggregate_sequence,
                'correlation_id', o.correlation_id,
                'payload', o.payload)::text as envelope
            """)
        .param("limit", limit)
        .param("leaseSeconds", lease.toMillis() / 1000.0)
        .query((rs, row) -> new OutboxMessage(
            rs.getObject("id", UUID.class),
            rs.getString("topic"),
            rs.getString("partition_key"),
            rs.getLong("aggregate_sequence"),
            rs.getString("envelope"),
            rs.getObject("lease_token", UUID.class)))
        .list();
  }

  /** Compare-and-set on the lease: false when the lease expired or another relay reclaimed it. */
  public boolean markSent(UUID eventId, UUID leaseToken) {
    return jdbc.sql("""
            update outbox_events
            set status = 'SENT', published_at = now(), lease_token = null, lease_until = null
            where id = :id and status = 'IN_FLIGHT' and lease_token = :token and lease_until > now()
            """)
        .param("id", eventId).param("token", leaseToken)
        .update() == 1;
  }

  /**
   * Claims, publishes synchronously (publisher returns only after broker ACK) and marks sent.
   * A failed publish keeps the lease until expiry, then the same event is retried.
   */
  // ponytail: retry waits for lease expiry, no backoff/next_attempt_at; add when broker outages need it.
  public int relay(int limit, Duration lease, Consumer<OutboxMessage> publisher) {
    int sent = 0;
    for (OutboxMessage message : claim(limit, lease)) {
      try {
        publisher.accept(message);
      } catch (RuntimeException e) {
        jdbc.sql("update outbox_events set last_error = left(:error, 1000) where id = :id and lease_token = :token")
            .param("error", String.valueOf(e.getMessage())).param("id", message.eventId())
            .param("token", message.leaseToken())
            .update();
        continue;
      }
      if (markSent(message.eventId(), message.leaseToken())) {
        sent++;
      }
    }
    return sent;
  }
}
