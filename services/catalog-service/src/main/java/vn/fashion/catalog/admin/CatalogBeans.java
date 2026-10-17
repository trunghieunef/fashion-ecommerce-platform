package vn.fashion.catalog.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.platform.idempotency.IdempotencyStore;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.outbox.OutboxRepository;

@Configuration
public class CatalogBeans {
  @Bean IdempotencyStore idempotencyStore(JdbcClient jdbc) { return new IdempotencyStore(jdbc); }
  @Bean OutboxRepository outboxRepository(JdbcClient jdbc, io.micrometer.tracing.Tracer tracer) {
    return new OutboxRepository(jdbc, vn.fashion.platform.messaging.TraceParents.current(tracer));
  }
  @Bean InboxGuard inboxGuard(JdbcClient jdbc, TransactionTemplate tx) { return new InboxGuard(jdbc, tx); }
}
