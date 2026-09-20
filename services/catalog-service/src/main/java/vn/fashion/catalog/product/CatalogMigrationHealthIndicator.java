package vn.fashion.catalog.product;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component("catalogMigration")
public class CatalogMigrationHealthIndicator implements HealthIndicator {
  private final JdbcClient jdbc;

  public CatalogMigrationHealthIndicator(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Health health() {
    try {
      boolean migrated = jdbc.sql("""
              select to_regclass('public.products') is not null
                 and to_regclass('public.flyway_schema_history') is not null
                 and exists (
                   select 1 from flyway_schema_history
                   where script = 'V001__catalog_baseline.sql' and success
                 )
              """)
          .query(Boolean.class)
          .single();
      return migrated
          ? Health.up().withDetail("baseline", "V001").build()
          : Health.down().withDetail("baseline", "V001 is not applied").build();
    } catch (DataAccessException exception) {
      return Health.down(exception).build();
    }
  }
}
