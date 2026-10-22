package vn.fashion.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.fashion.platform.outbox.OutboxRepository;

@Configuration
public class UserBeans {
  /** The outbox relay (enabled by default since NOT-01) needs this bean; AuthService/PasswordService write through their own instances. */
  @Bean OutboxRepository outboxRepository(JdbcClient jdbc, io.micrometer.tracing.Tracer tracer) {
    return new OutboxRepository(jdbc, vn.fashion.platform.messaging.TraceParents.current(tracer));
  }
}
