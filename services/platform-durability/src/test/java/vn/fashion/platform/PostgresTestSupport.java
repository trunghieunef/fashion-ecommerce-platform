package vn.fashion.platform;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One PostgreSQL 17.11 per JVM with the durability DDL; expiry uses the DB clock, never sleeps. */
public abstract class PostgresTestSupport {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
  protected static final DataSource DATA_SOURCE;

  static {
    POSTGRES.start();
    DATA_SOURCE = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    new ResourceDatabasePopulator(
        new ClassPathResource("db/platform/durability.sql"),
        new ClassPathResource("test-tables.sql")).execute(DATA_SOURCE);
  }

  protected final JdbcClient jdbc = JdbcClient.create(DATA_SOURCE);
  protected final TransactionTemplate tx =
      new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));

  @BeforeEach
  void truncate() {
    jdbc.sql("truncate outbox_events, processed_events, idempotency_requests, background_tasks,"
        + " test_effects, test_work").update();
  }

  protected int effects() {
    return jdbc.sql("select count(*) from test_effects").query(Integer.class).single();
  }
}
