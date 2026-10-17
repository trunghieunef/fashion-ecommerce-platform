package vn.fashion.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;
import vn.fashion.catalog.stock.StockObservations;

/** Review F2: a 5xx after the handler started must not carry the success Cache-Control. */
class PublicServerErrorHeadersIntegrationTest extends CatalogAdminTestSupport {
  @MockitoSpyBean PublicCatalogRepository catalog;
  @MockitoSpyBean StockObservations stock;

  @Test void databaseFailureOnCategoriesAndBrandsIsNoStore() {
    doThrow(new DataAccessResourceFailureException("down")).when(catalog).categories();
    doThrow(new DataAccessResourceFailureException("down")).when(catalog).brands();
    for (String path : new String[] {"/categories", "/brands"}) {
      var response = send("GET", "/api/v1/catalog" + path, null, null, null);
      assertThat(response.statusCode()).as(path).isEqualTo(503);
      assertThat(response.headers().firstValue("Cache-Control")).as(path).contains("no-store");
    }
  }

  @Test void stockFailureOnDetailIsNoStore() {
    var fixtures = new PublicCatalogFixtures(jdbc);
    UUID category = fixtures.category(null, "shirts", "ACTIVE");
    UUID product = fixtures.product("err", "Áo", category, null, "ACTIVE", 100000, Instant.now(), 0);
    fixtures.variant(product, "ERR-M", "M", "Blue", null, "ACTIVE");
    doThrow(new DataAccessResourceFailureException("down")).when(stock).forSkus(anyList());
    var response = send("GET", "/api/v1/catalog/products/" + product, null, null, null);
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
  }
}
