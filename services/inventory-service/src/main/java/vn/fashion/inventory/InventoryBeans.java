package vn.fashion.inventory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.fashion.platform.idempotency.IdempotencyStore;
import vn.fashion.platform.outbox.OutboxRepository;

@Configuration
public class InventoryBeans {
  @Bean org.springframework.transaction.support.TransactionTemplate transactionTemplate(
      org.springframework.transaction.PlatformTransactionManager manager) {
    return new org.springframework.transaction.support.TransactionTemplate(manager);
  }
  @Bean vn.fashion.platform.inbox.InboxGuard inboxGuard(JdbcClient jdbc,
      org.springframework.transaction.support.TransactionTemplate tx) {
    return new vn.fashion.platform.inbox.InboxGuard(jdbc, tx);
  }
  @Bean IdempotencyStore idempotencyStore(JdbcClient jdbc) { return new IdempotencyStore(jdbc); }
  @Bean OutboxRepository outboxRepository(JdbcClient jdbc, io.micrometer.tracing.Tracer tracer) {
    return new OutboxRepository(jdbc, vn.fashion.platform.messaging.TraceParents.current(tracer));
  }
}
