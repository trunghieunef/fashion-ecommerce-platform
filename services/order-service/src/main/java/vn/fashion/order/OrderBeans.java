package vn.fashion.order;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.platform.outbox.OutboxRepository;

@Configuration
public class OrderBeans {
  @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
  @Bean OutboxRepository outboxRepository(JdbcClient jdbc, io.micrometer.tracing.Tracer tracer) {
    return new OutboxRepository(jdbc, vn.fashion.platform.messaging.TraceParents.current(tracer));
  }
  /** Quote expiry and order deadlines; tests replace it to move time. */
  @Bean Clock clock() { return Clock.systemUTC(); }
}
