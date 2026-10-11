package vn.fashion.catalog.product;

import org.junit.jupiter.api.Test;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class PublicSearchSchemaIntegrationTest extends CatalogAdminTestSupport {
  @Test void runtimeCanUseUnaccentFunction() {
    assertThat(jdbc.queryForObject("select f_unaccent(lower('Áo Sơ Mi Đỏ'))", String.class)).isEqualTo("ao so mi do");
  }

  @Test void functionIsImmutableForFutureIndexes() {
    assertThat(jdbc.queryForObject("select provolatile from pg_proc where proname='f_unaccent'", String.class)).isEqualTo("i");
  }
}
