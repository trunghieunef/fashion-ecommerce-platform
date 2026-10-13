package vn.fashion.catalog.product;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductBrowseRepository {
  private final JdbcClient jdbc;

  public ProductBrowseRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public ProductPage browse(BrowseFilters f, ProductCursor after, int limit) {
    String key = switch (f.sort()) {
      case NEWEST -> "coalesce(p.published_at,p.created_at)";
      case BEST_SELLING -> "p.sold_quantity";
      case PRICE_ASC, PRICE_DESC -> "pr.min_price";
    };
    boolean ascending = f.sort() == BrowseFilters.Sort.PRICE_ASC;
    StringBuilder sql = new StringBuilder("select " + PublicProductSql.CARD_COLUMNS + ", " + key
        + " sort_value " + PublicProductSql.FROM_PUBLIC);
    var params = new HashMap<String, Object>();
    if (f.q() != null) {
      sql.append("""
          and exists (select 1 from (select '%' ||
            replace(replace(replace(f_unaccent(lower(:q)), chr(92), chr(92)||chr(92)),
              '%', chr(92)||'%'), '_', chr(92)||'_') || '%' pattern) search
            where f_unaccent(lower(p.name_vi)) ilike search.pattern escape '\\'
              or f_unaccent(lower(p.name_en)) ilike search.pattern escape '\\'
              or f_unaccent(lower(p.slug)) ilike search.pattern escape '\\')
          """);
      params.put("q", f.q());
    }
    if (f.categoryId() != null) {
      sql.append(" and (p.category_id=:cat or c.parent_id=:cat)");
      params.put("cat", f.categoryId());
    }
    if (f.brandId() != null) {
      sql.append(" and p.brand_id=:brand");
      params.put("brand", f.brandId());
    }
    if (f.tag() != null) {
      sql.append(" and p.tags @> array[:tag]::text[]");
      params.put("tag", f.tag());
    }
    if (f.size() != null || f.color() != null) {
      sql.append(" and exists (select 1 from product_variants v2 where v2.product_id=p.id and v2.status='ACTIVE'");
      if (f.size() != null) {
        sql.append(" and lower(v2.size)=lower(:size)");
        params.put("size", f.size());
      }
      if (f.color() != null) {
        sql.append(" and lower(v2.color)=lower(:color)");
        params.put("color", f.color());
      }
      sql.append(")");
    }
    if (f.minPrice() != null) {
      sql.append(" and pr.min_price>=:min");
      params.put("min", f.minPrice());
    }
    if (f.maxPrice() != null) {
      sql.append(" and pr.min_price<=:max");
      params.put("max", f.maxPrice());
    }
    if (after != null) {
      sql.append(" and (" + key + ", p.id) " + (ascending ? ">" : "<") + " (:v, :id)");
      params.put("v", f.sort() == BrowseFilters.Sort.NEWEST
          ? Timestamp.from(Instant.parse(after.value())) : Long.parseLong(after.value()));
      params.put("id", after.id());
    }
    String direction = ascending ? " asc" : " desc";
    sql.append(" order by " + key + direction + ", p.id" + direction + " limit :limit");
    params.put("limit", limit + 1);
    var rows = jdbc.sql(sql.toString()).params(params).query((rs, rowNum) -> new Row(
        PublicProductSql.card(rs), f.sort() == BrowseFilters.Sort.NEWEST
            ? rs.getTimestamp("sort_value").toInstant().toString() : Long.toString(rs.getLong("sort_value")))).list();
    boolean hasMore = rows.size() > limit;
    var page = hasMore ? rows.subList(0, limit) : rows;
    String next = null;
    if (hasMore) {
      Row last = page.getLast();
      next = new ProductCursor(f.sort().name(), last.value(), last.card().id(), f.hash()).encode();
    }
    return new ProductPage(page.stream().map(Row::card).toList(), next);
  }

  private record Row(ProductSummary card, String value) {}
}
