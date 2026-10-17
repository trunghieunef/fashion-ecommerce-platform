package vn.fashion.catalog.product;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PublicCatalogRepository {
  public record Ref(UUID id, String name) { }
  public record VariantView(UUID id, String sku, String size, String color, long effectivePrice, Integer available,
      Instant observedAt) { }
  public record ImageView(UUID assetId, String altVi, String altEn, String variantColor, int sortOrder) { }
  public record SizeGuideView(UUID categoryId, String locale, String guidelineHtml, JsonNode table) { }
  public record ProductDetail(UUID id, String slug, String nameVi, String nameEn, String descriptionVi,
      String descriptionEn, List<String> tags, Ref category, Ref brand, long minPrice, long maxPrice,
      List<VariantView> variants, List<ImageView> images, SizeGuideView sizeGuide, Integer available,
      Instant observedAt) { }
  public record CategoryNode(UUID id, String slug, String nameVi, String nameEn, List<CategoryNode> children) { }
  public record BrandView(UUID id, String name) { }
  private record CategoryRow(UUID parentId, CategoryNode node) { }
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public PublicCatalogRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<ProductDetail> product(UUID id, String locale) {
    return jdbc.sql("""
        select p.id, p.slug, p.name_vi, p.name_en, p.description_vi, p.description_en, p.tags,
               p.category_id, c.name_vi category_vi, c.name_en category_en, p.brand_id, b.name brand_name,
               p.base_price, pr.min_price, pr.max_price
        """ + PublicProductSql.FROM_PUBLIC + " and p.id=:id").param("id", id).query((rs, row) -> {
          UUID category = rs.getObject("category_id", UUID.class);
          UUID brand = rs.getObject("brand_id", UUID.class);
          return new ProductDetail(id, rs.getString("slug"), rs.getString("name_vi"), rs.getString("name_en"),
              rs.getString("description_vi"), rs.getString("description_en"),
              Arrays.asList((String[]) rs.getArray("tags").getArray()),
              new Ref(category, rs.getString("en".equals(locale) ? "category_en" : "category_vi")),
              brand == null ? null : new Ref(brand, rs.getString("brand_name")),
              rs.getLong("min_price"), rs.getLong("max_price"), variants(id, rs.getLong("base_price")),
              images(id), sizeGuide(category, locale).orElse(null), null, null);
        }).optional();
  }

  private List<VariantView> variants(UUID product, long basePrice) {
    return jdbc.sql("""
        select id, sku, size, color, coalesce(price_override,:base) effective_price,
               null::int available, null::timestamptz observed_at
        from product_variants where product_id=:product and status='ACTIVE' order by sku,id
        """).param("product", product).param("base", basePrice).query(VariantView.class).list();
  }

  private List<ImageView> images(UUID product) {
    return jdbc.sql("""
        select pi.asset_id, pi.alt_vi, pi.alt_en, pi.variant_color, pi.sort_order
        from product_images pi join media_assets ma on ma.id=pi.asset_id
        where pi.product_id=:product and ma.availability='AVAILABLE' order by pi.sort_order,pi.asset_id
        """).param("product", product).query(ImageView.class).list();
  }

  public List<CategoryNode> categories() {
    var rows = jdbc.sql("""
        select c.id, c.parent_id, c.slug, c.name_vi, c.name_en
        from categories c left join categories parent on parent.id=c.parent_id
        where c.status='ACTIVE'
          and (c.parent_id is null or (parent.status='ACTIVE' and parent.parent_id is null))
        order by c.sort_order,c.slug,c.id
        """).query((rs, row) -> new CategoryRow(rs.getObject("parent_id", UUID.class),
            new CategoryNode(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name_vi"),
                rs.getString("name_en"), new ArrayList<>()))).list();
    var nodes = new HashMap<UUID, CategoryNode>();
    rows.forEach(row -> nodes.put(row.node().id(), row.node()));
    var roots = new ArrayList<CategoryNode>();
    for (var row : rows) {
      if (row.parentId() == null) roots.add(row.node());
      else nodes.get(row.parentId()).children().add(row.node());
    }
    return roots;
  }

  public List<BrandView> brands() {
    return jdbc.sql("select id,name from brands where status='ACTIVE' order by name,id").query(BrandView.class).list();
  }

  public Optional<SizeGuideView> sizeGuide(UUID categoryId, String locale) {
    return jdbc.sql("""
        select sg.category_id, sg.locale, sg.guideline_html, sg.table_json
        from size_guides sg join categories c on c.id=sg.category_id
        where sg.category_id=:category and sg.locale=:locale and c.status='ACTIVE'
        """).param("category", categoryId).param("locale", locale).query((rs, row) -> new SizeGuideView(
            rs.getObject("category_id", UUID.class), rs.getString("locale"), rs.getString("guideline_html"),
            json.readTree(rs.getString("table_json")))).optional();
  }
}
