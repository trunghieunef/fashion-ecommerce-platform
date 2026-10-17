package vn.fashion.catalog.sales;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

/** CAT-02b spec section 5: ORDER_COMPLETED adds sold_quantity once per order_id (inbox dedupes event_id). */
@Component
public class SoldQuantities {
  private final JdbcClient jdbc;
  public SoldQuantities(JdbcClient jdbc) { this.jdbc = jdbc; }

  /** false when this order was already applied; unknown variant is permanent and adds nothing. */
  @Transactional
  public Boolean apply(EventEnvelope env) {
    JsonNode p = env.payload();
    if (env.version() != 1) throw invalid();
    UUID order = uuid(p.path("order_id"));
    JsonNode items = p.path("items");
    if (!items.isArray() || items.isEmpty() || items.size() > 100) throw invalid();
    var quantities = new HashMap<UUID, Integer>();
    for (JsonNode item : items) {
      var q = item.path("quantity");
      if (!q.isIntegralNumber() || !q.canConvertToInt() || q.intValue() < 1 || q.intValue() > 99) throw invalid();
      quantities.merge(uuid(item.path("variant_id")), q.intValue(), Integer::sum);
    }
    var products = new HashMap<UUID, UUID>();
    jdbc.sql("select id, product_id from product_variants where id = any(:ids)")
        .param("ids", quantities.keySet().toArray(UUID[]::new))
        .query((rs, row) -> products.put(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class))).list();
    if (products.size() != quantities.size()) throw new PermanentEventException("ORDER_COMPLETED references an unknown variant");
    if (jdbc.sql("insert into product_sales_applied(order_id) values (:o) on conflict do nothing").param("o", order).update() == 0)
      return false;
    var perProduct = new HashMap<UUID, Long>();
    quantities.forEach((variant, q) -> perProduct.merge(products.get(variant), (long) q, Long::sum));
    for (Map.Entry<UUID, Long> e : perProduct.entrySet()) {
      jdbc.sql("update products set sold_quantity = sold_quantity + :q where id = :id")
          .param("q", e.getValue()).param("id", e.getKey()).update();
    }
    return true;
  }

  private static UUID uuid(JsonNode node) {
    try {
      if (!node.isString()) throw invalid();
      UUID id = UUID.fromString(node.asText());
      if (!id.toString().equalsIgnoreCase(node.asText())) throw invalid();
      return id;
    } catch (IllegalArgumentException e) { throw invalid(); }
  }
  private static PermanentEventException invalid() { return new PermanentEventException("Invalid ORDER_COMPLETED payload"); }
}
