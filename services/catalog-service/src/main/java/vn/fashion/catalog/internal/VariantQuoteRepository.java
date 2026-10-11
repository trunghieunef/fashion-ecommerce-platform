package vn.fashion.catalog.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.fashion.catalog.product.ProductSummary;
import vn.fashion.catalog.product.PublicProductSql;

@Repository
public class VariantQuoteRepository {
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record QuoteItem(UUID variantId, boolean found, Boolean active, String sku, UUID productId,
      String nameVi, String nameEn, String size, String color, Long basePrice, Long priceOverride,
      Long effectivePrice, Integer weightGrams, Long productVersion, ProductSummary.CardImage image) { }
  private final JdbcClient jdbc;

  public VariantQuoteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public List<QuoteItem> quote(List<UUID> ids) {
    var found = new HashMap<UUID, QuoteItem>();
    jdbc.sql("""
        select v.id variant_id, v.status='ACTIVE' and (
        """ + PublicProductSql.ACTIVE_PRODUCT + ") active, " + """
        v.sku, v.product_id, p.name_vi, p.name_en, v.size, v.color, p.base_price, v.price_override,
        coalesce(v.price_override,p.base_price) effective_price, v.weight_grams, p.version product_version,
        """ + PublicProductSql.IMAGE_COLUMNS + """
         from product_variants v join products p on p.id=v.product_id
        join categories c on c.id=p.category_id left join brands b on b.id=p.brand_id
        where v.id = any(:ids)
        """).param("ids", ids.toArray(UUID[]::new), Types.ARRAY).query((rs, row) -> {
          UUID asset = rs.getObject("asset_id", UUID.class);
          return new QuoteItem(rs.getObject("variant_id", UUID.class), true, rs.getBoolean("active"),
              rs.getString("sku"), rs.getObject("product_id", UUID.class), rs.getString("name_vi"),
              rs.getString("name_en"), rs.getString("size"), rs.getString("color"), rs.getLong("base_price"),
              rs.getObject("price_override", Long.class), rs.getLong("effective_price"), rs.getInt("weight_grams"),
              rs.getLong("product_version"), asset == null ? null
                  : new ProductSummary.CardImage(asset, rs.getString("alt_vi"), rs.getString("alt_en")));
        }).list().forEach(item -> found.put(item.variantId(), item));
    return ids.stream().map(id -> found.getOrDefault(id,
        new QuoteItem(id, false, null, null, null, null, null, null, null, null, null, null, null, null, null))).toList();
  }
}
