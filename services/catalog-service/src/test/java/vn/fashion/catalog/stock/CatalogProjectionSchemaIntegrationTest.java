package vn.fashion.catalog.stock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

class CatalogProjectionSchemaIntegrationTest extends CatalogAdminTestSupport {
  @Test void stockVersionMustBePositive() {
    assertThatThrownBy(() -> jdbc.update("insert into variant_stock_observations(sku,available,stock_version,observed_at) values ('A',1,0,now())"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void availableCannotBeNegative() {
    assertThatThrownBy(() -> jdbc.update("insert into variant_stock_observations(sku,available,stock_version,observed_at) values ('A',-1,1,now())"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void salesAppliedOrderIdIsUnique() {
    UUID order = UUID.randomUUID();
    jdbc.update("insert into product_sales_applied(order_id) values (?)", order);
    assertThatThrownBy(() -> jdbc.update("insert into product_sales_applied(order_id) values (?)", order))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
