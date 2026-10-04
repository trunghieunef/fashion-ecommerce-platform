package vn.fashion.platform.inbox;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumer-side dedupe (05 processed_events, 06 section 1.3): the inbox row and the local effect
 * commit in one transaction; acknowledge the Kafka offset only after this returns.
 */
public class InboxGuard {
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public InboxGuard(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  /** Runs {@code effect} once per (consumer, event); empty when this consumer already applied it. */
  public <T> Optional<T> applyOnce(String consumer, UUID eventId, Supplier<T> effect) {
    return tx.execute(status -> {
      int inserted = jdbc.sql("""
              insert into processed_events(consumer_name, event_id) values (:consumer, :eventId)
              on conflict do nothing
              """)
          .param("consumer", consumer)
          .param("eventId", eventId)
          .update();
      return inserted == 0 ? Optional.<T>empty() : Optional.ofNullable(effect.get());
    });
  }
}
