package vn.fashion.cart;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.platform.idempotency.IdempotencyStore;

@Configuration
public class CartBeans {
  @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
  @Bean IdempotencyStore idempotencyStore(JdbcClient jdbc) { return new IdempotencyStore(jdbc); }
}
