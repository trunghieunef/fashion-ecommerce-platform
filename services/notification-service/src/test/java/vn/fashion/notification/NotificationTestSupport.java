package vn.fashion.notification;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL with the migration/runtime roles; listener and relay are off, tests call the listener directly. */
@Testcontainers
@SpringBootTest(properties = {"fashion.messaging.relay.enabled=false", "spring.kafka.listener.auto-startup=false",
    "fashion.notification.worker.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class NotificationTestSupport {
  @Container protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("notification").withUsername("postgres").withPassword("postgres").withInitScript("notification-test-init.sql");
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ObjectMapper mapper;
  @Autowired protected NotificationEventsListener listener;

  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "notification_runtime");
    registry.add("spring.datasource.password", () -> "notification_runtime_test");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "notification_migration");
    registry.add("spring.flyway.password", () -> "notification_migration_test");
    registry.add("fashion.notification.storefront-base-url", () -> "https://shop.example.invalid");
    registry.add("fashion.notification.public-asset-base-url", () -> "https://cdn.example.invalid");
  }

  @BeforeEach protected void reset() {
    asOwner("delete from notifications; delete from processed_events; update notification_templates set active = true");
  }
  protected void asOwner(String sql) {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); var s = c.createStatement()) { s.execute(sql); }
    catch (java.sql.SQLException e) { throw new AssertionError(e); }
  }
  protected int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

  /** An envelope as order-service writes it; payload is the NotifyOrder JSON. */
  protected String envelope(UUID eventId, String type, String payload) {
    return "{\"event_id\":\"" + eventId + "\",\"event_type\":\"" + type + "\",\"version\":1,\"occurred_at\":\"" + Instant.now()
        + "\",\"aggregate_id\":\"" + UUID.randomUUID() + "\",\"aggregate_version\":1,\"aggregate_sequence\":1,"
        + "\"correlation_id\":\"c\",\"payload\":" + payload + "}";
  }
  protected void deliver(String envelope) {
    listener.receive(new ConsumerRecord<>("notification.events", 0, 0, "k", envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8)), () -> { });
  }
}
