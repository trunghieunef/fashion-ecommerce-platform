package vn.fashion.platform.inbox;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumer-side dedupe (05 processed_events, 06 section 1.3): the inbox row and the local effect
 * commit in one transaction owned by this call; acknowledge the Kafka offset only after it returns.
 * Calling it inside a caller transaction is rejected, otherwise it would return before commit.
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
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("applyOnce must run outside a transaction");
    }
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
