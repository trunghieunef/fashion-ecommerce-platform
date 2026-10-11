package vn.fashion.catalog.product;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class PublicProductSql {
  private PublicProductSql() {}

  public static final String ACTIVE_PRODUCT = "p.status='ACTIVE' and c.status='ACTIVE' "
      + "and (p.brand_id is null or b.status='ACTIVE')";

  // ponytail: query-time aggregation; denormalize min_price/sizes onto products when measured load needs it (spec §1).
  public static final String FROM_PUBLIC = """
      from products p
      join categories c on c.id=p.category_id
      left join brands b on b.id=p.brand_id
      cross join lateral (
        select min(coalesce(v.price_override,p.base_price)) min_price,
               max(coalesce(v.price_override,p.base_price)) max_price
        from product_variants v where v.product_id=p.id and v.status='ACTIVE'
      ) pr
      where
      """ + ACTIVE_PRODUCT + " and pr.min_price is not null\n";

  private static final String FIRST_IMAGE = """
      from product_images pi join media_assets ma on ma.id=pi.asset_id
      where pi.product_id=p.id and ma.availability='AVAILABLE'
      order by pi.sort_order, pi.asset_id limit 1
      """;
  public static final String IMAGE_COLUMNS = "(select pi.asset_id " + FIRST_IMAGE + ") asset_id, "
      + "(select pi.alt_vi " + FIRST_IMAGE + ") alt_vi, "
      + "(select pi.alt_en " + FIRST_IMAGE + ") alt_en";
  public static final String CARD_COLUMNS = "p.id, p.slug, p.name_vi, p.name_en, pr.min_price, pr.max_price, "
      + IMAGE_COLUMNS;

  public static ProductSummary card(ResultSet rs) throws SQLException {
    UUID asset = rs.getObject("asset_id", UUID.class);
    return new ProductSummary(rs.getObject("id", UUID.class), rs.getString("slug"),
        rs.getString("name_vi"), rs.getString("name_en"), rs.getLong("min_price"), rs.getLong("max_price"),
        asset == null ? null : new ProductSummary.CardImage(asset, rs.getString("alt_vi"), rs.getString("alt_en")));
  }
}
