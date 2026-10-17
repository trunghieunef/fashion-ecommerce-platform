package vn.fashion.catalog.stock;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vn.fashion.catalog.product.PublicCatalogRepository.ProductDetail;
import vn.fashion.catalog.product.PublicCatalogRepository.VariantView;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

/** CAT-02b spec section 4: display-only stock per SKU; a stale stock_version never overwrites. */
@Component
public class StockObservations {
  public record Observation(int available, Instant observedAt) { }
  private final JdbcClient jdbc;
  public StockObservations(JdbcClient jdbc) { this.jdbc = jdbc; }

  /** INVENTORY_UPDATED/RESTOCKED payload (stock-events StockSnapshot); true when the observation was stored. */
  public Boolean apply(EventEnvelope env) {
    JsonNode p = env.payload();
    // sku 1..64 (common.schema.json); a longer value would fail in the DB and be retried forever.
    if (env.version() != 1 || !p.path("sku").isString() || p.path("sku").asText().isEmpty()
        || p.path("sku").asText().length() > 64) throw invalid();
    long available = count(p, "available", 0), version = count(p, "stock_version", 1);
    if (available > Integer.MAX_VALUE) throw invalid();
    Instant observed;
    try {
      if (!p.path("observed_at").isString()) throw invalid();
      observed = Instant.parse(p.path("observed_at").asText());
    } catch (java.time.format.DateTimeParseException e) { throw invalid(); }
    return jdbc.sql("""
        insert into variant_stock_observations(sku,available,stock_version,observed_at)
        values (:sku,:available,:version,:observed)
        on conflict (sku) do update set available=excluded.available, stock_version=excluded.stock_version,
          observed_at=excluded.observed_at, updated_at=now()
        where variant_stock_observations.stock_version < excluded.stock_version
        """).param("sku", p.path("sku").asText()).param("available", (int) available).param("version", version)
        .param("observed", OffsetDateTime.ofInstant(observed, ZoneOffset.UTC)).update() == 1;
  }

  public Map<String, Observation> forSkus(List<String> skus) {
    var result = new HashMap<String, Observation>();
    if (skus.isEmpty()) return result;
    jdbc.sql("select sku,available,observed_at from variant_stock_observations where sku = any(:skus)")
        .param("skus", skus.toArray(String[]::new))
        .query((rs, row) -> result.put(rs.getString("sku"), new Observation(rs.getInt("available"),
            rs.getObject("observed_at", OffsetDateTime.class).toInstant()))).list();
    return result;
  }

  /** Fills variant stock and the product totals: sum of observed variants, oldest observed_at; null when none observed. */
  public ProductDetail attach(ProductDetail d) {
    var seen = forSkus(d.variants().stream().map(VariantView::sku).toList());
    var variants = new ArrayList<VariantView>();
    Integer total = null; Instant oldest = null;
    for (var v : d.variants()) {
      var o = seen.get(v.sku());
      variants.add(new VariantView(v.id(), v.sku(), v.size(), v.color(), v.effectivePrice(),
          o == null ? null : o.available(), o == null ? null : o.observedAt()));
      if (o == null) continue;
      total = total == null ? o.available() : Math.addExact(total, o.available());
      if (oldest == null || o.observedAt().isBefore(oldest)) oldest = o.observedAt();
    }
    return new ProductDetail(d.id(), d.slug(), d.nameVi(), d.nameEn(), d.descriptionVi(), d.descriptionEn(), d.tags(),
        d.category(), d.brand(), d.minPrice(), d.maxPrice(), variants, d.images(), d.sizeGuide(), total, oldest);
  }

  private static long count(JsonNode p, String field, long minimum) {
    var v = p.path(field);
    if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < minimum) throw invalid();
    return v.longValue();
  }
  private static PermanentEventException invalid() { return new PermanentEventException("Invalid stock snapshot"); }
}
