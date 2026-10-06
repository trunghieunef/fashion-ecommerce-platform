package vn.fashion.catalog.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.fashion.platform.idempotency.IdempotencyStore;
import vn.fashion.platform.outbox.OutboxRepository;

@Configuration
public class CatalogBeans {
  @Bean IdempotencyStore idempotencyStore(JdbcClient jdbc) { return new IdempotencyStore(jdbc); }
  @Bean OutboxRepository outboxRepository(JdbcClient jdbc) { return new OutboxRepository(jdbc); }
}
