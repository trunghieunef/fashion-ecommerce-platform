package vn.fashion.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.platform.inbox.InboxGuard;

@Configuration
@EnableScheduling
public class NotificationBeans {
  @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
  @Bean InboxGuard inboxGuard(JdbcClient jdbc, TransactionTemplate tx) { return new InboxGuard(jdbc, tx); }
}
