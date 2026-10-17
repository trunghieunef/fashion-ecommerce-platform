package vn.fashion.catalog.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.catalog.ProjectionEvents;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;
import vn.fashion.catalog.product.PublicCatalogFixtures;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

class SoldQuantitiesIntegrationTest extends CatalogAdminTestSupport {
  @Autowired SoldQuantities sold;
  @Autowired InboxGuard inbox;
  private PublicCatalogFixtures fixtures;
  private UUID p1, p2, a, b, c;

  @BeforeEach void setup() {
    fixtures = new PublicCatalogFixtures(jdbc);
    UUID category = fixtures.category(null, "shirts", "ACTIVE");
    p1 = fixtures.product("p1", "Áo 1", category, null, "ACTIVE", 100000, Instant.parse("2026-10-01T00:00:00Z"), 0);
    p2 = fixtures.product("p2", "Áo 2", category, null, "ACTIVE", 100000, Instant.parse("2026-10-02T00:00:00Z"), 0);
    a = fixtures.variant(p1, "SOLD-A", "M", "Blue", null, "ACTIVE");
    b = fixtures.variant(p1, "SOLD-B", "L", "Blue", null, "ACTIVE");
    c = fixtures.variant(p2, "SOLD-C", "M", "Red", null, "ACTIVE");
  }

  static Map<String, Object> item(UUID variant, int quantity) { return Map.of("variant_id", variant.toString(), "sku", "X", "quantity", quantity); }
  EventEnvelope completed(UUID order, Object userId, List<Map<String, Object>> items) {
    var p = new LinkedHashMap<String, Object>();
    p.put("order_id", order.toString()); p.put("order_no", "FS-1"); p.put("user_id", userId); p.put("order_version", 5);
    p.put("items", items); p.put("delivered_at", "2026-10-17T02:59:00Z");
    return ProjectionEvents.envelope(mapper, "ORDER_COMPLETED", order.toString(), p);
  }
  long soldOf(UUID product) { return jdbc.queryForObject("select sold_quantity from products where id=?", Long.class, product); }

  @Test void completedOrderAddsQuantityPerProduct() {
    var before = jdbc.queryForMap("select version,updated_at from products where id=?", p1);
    assertThat(sold.apply(completed(UUID.randomUUID(), UUID.randomUUID().toString(), List.of(item(a, 2), item(b, 1), item(c, 4))))).isTrue();
    assertThat(soldOf(p1)).isEqualTo(3);
    assertThat(soldOf(p2)).isEqualTo(4);
    assertThat(jdbc.queryForMap("select version,updated_at from products where id=?", p1)).isEqualTo(before);
  }

  @Test void replaySameOrderDoesNotAddTwice() {
    UUID order = UUID.randomUUID();
    var env = completed(order, null, List.of(item(a, 2), item(c, 4)));
    inbox.applyOnce("catalog-service", env.eventId(), () -> sold.apply(env));
    inbox.applyOnce("catalog-service", env.eventId(), () -> sold.apply(env));
    var redelivered = completed(order, null, List.of(item(a, 2), item(c, 4)));
    assertThat(redelivered.eventId()).isNotEqualTo(env.eventId());
    assertThat(inbox.applyOnce("catalog-service", redelivered.eventId(), () -> sold.apply(redelivered))).contains(false);
    assertThat(soldOf(p1)).isEqualTo(2);
    assertThat(soldOf(p2)).isEqualTo(4);
  }

  @Test void duplicateVariantLinesSum() {
    sold.apply(completed(UUID.randomUUID(), null, List.of(item(a, 2), item(a, 3))));
    assertThat(soldOf(p1)).isEqualTo(5);
  }

  @Test void unknownVariantIsPermanentAndAddsNothing() {
    UUID order = UUID.randomUUID();
    assertThatThrownBy(() -> inbox.applyOnce("catalog-service", UUID.randomUUID(),
        () -> sold.apply(completed(order, null, List.of(item(a, 1), item(UUID.randomUUID(), 1))))))
        .isInstanceOf(PermanentEventException.class);
    assertThat(soldOf(p1)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from product_sales_applied", Integer.class)).isZero();
  }

  @Test void invalidPayloadIsPermanent() {
    for (var items : List.<List<Map<String, Object>>>of(List.of(), List.of(item(a, 0)), List.of(item(a, 100)))) {
      assertThatThrownBy(() -> sold.apply(completed(UUID.randomUUID(), null, items))).isInstanceOf(PermanentEventException.class);
    }
  }

  @Test void guestOrderCounts() {
    sold.apply(completed(UUID.randomUUID(), null, List.of(item(c, 1))));
    assertThat(soldOf(p2)).isEqualTo(1);
  }

  @Test void bestSellingOrdersBySoldQuantity() {
    sold.apply(completed(UUID.randomUUID(), null, List.of(item(a, 1), item(c, 4))));
    var page = data(send("GET", "/api/v1/catalog/products?sort=BEST_SELLING", null, null, null), 200);
    assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(p2.toString());
    assertThat(page.path("items").get(1).path("id").asText()).isEqualTo(p1.toString());
  }
}
