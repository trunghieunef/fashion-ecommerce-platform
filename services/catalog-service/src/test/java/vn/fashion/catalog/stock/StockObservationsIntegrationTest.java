package vn.fashion.catalog.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.catalog.ProjectionEvents;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;
import vn.fashion.catalog.product.PublicCatalogFixtures;
import vn.fashion.platform.messaging.PermanentEventException;

class StockObservationsIntegrationTest extends CatalogAdminTestSupport {
  @Autowired StockObservations stock;
  private PublicCatalogFixtures fixtures;
  private final Instant t1 = Instant.parse("2026-10-17T03:00:00.123Z");

  @BeforeEach void setup() { fixtures = new PublicCatalogFixtures(jdbc); }

  private Boolean apply(String sku, int available, long version, Instant at) {
    return stock.apply(ProjectionEvents.envelope(mapper, "INVENTORY_UPDATED", sku, ProjectionEvents.stock(sku, available, version, at)));
  }

  @Test void newerVersionUpdatesAndOlderIsIgnored() {
    assertThat(apply("SKU-A", 5, 2, t1)).isTrue();
    assertThat(apply("SKU-A", 9, 1, t1.plusSeconds(5))).isFalse();
    assertThat(apply("SKU-A", 7, 2, t1.plusSeconds(9))).isFalse();
    var seen = stock.forSkus(List.of("SKU-A")).get("SKU-A");
    assertThat(seen.available()).isEqualTo(5);
    assertThat(seen.observedAt()).isEqualTo(t1);
    assertThat(apply("SKU-A", 4, 3, t1.plusSeconds(10))).isTrue();
    assertThat(stock.forSkus(List.of("SKU-A")).get("SKU-A").available()).isEqualTo(4);
  }

  @Test void invalidPayloadIsPermanent() {
    var missing = ProjectionEvents.stock("SKU-A", 1, 1, t1); missing.remove("stock_version");
    var negative = ProjectionEvents.stock("SKU-A", -1, 1, t1);
    var zeroVersion = ProjectionEvents.stock("SKU-A", 1, 0, t1);
    var longSku = ProjectionEvents.stock("S".repeat(65), 1, 1, t1); // Review F7: would retry forever as a DB error
    for (var payload : List.of(missing, negative, zeroVersion, longSku)) {
      assertThatThrownBy(() -> stock.apply(ProjectionEvents.envelope(mapper, "INVENTORY_UPDATED", "SKU-A", payload)))
          .isInstanceOf(PermanentEventException.class);
    }
    assertThat(jdbc.queryForObject("select count(*) from variant_stock_observations", Integer.class)).isZero();
  }

  @Test void unknownSkuIsStored() {
    assertThat(apply("NO-VARIANT", 2, 1, t1)).isTrue();
    assertThat(stock.forSkus(List.of("NO-VARIANT"))).containsKey("NO-VARIANT");
  }

  @Test void detailShowsPerVariantStockAndOldestObservedAt() {
    UUID category = fixtures.category(null, "shirts", "ACTIVE");
    UUID product = fixtures.product("stock", "Áo", category, null, "ACTIVE", 100000, Instant.now(), 0);
    UUID a = fixtures.variant(product, "STOCK-A", "M", "Blue", null, "ACTIVE");
    UUID b = fixtures.variant(product, "STOCK-B", "L", "Blue", null, "ACTIVE");
    UUID c = fixtures.variant(product, "STOCK-C", "S", "Blue", null, "ACTIVE");
    var none = data(send("GET", "/api/v1/catalog/products/" + product, null, null, null), 200);
    assertThat(none.path("available").isNull()).isTrue();
    assertThat(none.path("observed_at").isNull()).isTrue();

    apply("STOCK-A", 2, 1, t1);
    apply("STOCK-B", 3, 1, t1.plusSeconds(60));
    var detail = data(send("GET", "/api/v1/catalog/products/" + product, null, null, null), 200);
    for (var variant : detail.path("variants")) {
      String id = variant.path("id").asText();
      if (id.equals(a.toString())) {
        assertThat(variant.path("available").asInt()).isEqualTo(2);
        assertThat(variant.path("observed_at").asText()).isEqualTo("2026-10-17T03:00:00.123Z");
      } else if (id.equals(b.toString())) {
        assertThat(variant.path("available").asInt()).isEqualTo(3);
      } else {
        assertThat(id).isEqualTo(c.toString());
        assertThat(variant.has("available")).isTrue();
        assertThat(variant.path("available").isNull()).isTrue();
        assertThat(variant.path("observed_at").isNull()).isTrue();
      }
    }
    assertThat(detail.path("available").asInt()).isEqualTo(5);
    assertThat(Instant.parse(detail.path("observed_at").asText())).isEqualTo(t1.truncatedTo(ChronoUnit.MILLIS));
  }
}
