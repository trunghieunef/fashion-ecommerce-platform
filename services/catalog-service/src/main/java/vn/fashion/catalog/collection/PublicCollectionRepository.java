package vn.fashion.catalog.collection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import vn.fashion.catalog.product.ProductCursor;
import vn.fashion.catalog.product.ProductSummary;
import vn.fashion.catalog.product.PublicProductSql;

@Repository
public class PublicCollectionRepository {
  private static final String COLUMNS = """
      co.id, co.slug, co.name_vi, co.name_en, co.start_at, co.end_at,
      (select ma.id from media_assets ma where ma.id=co.cover_asset_id
         and ma.availability='AVAILABLE') cover_asset_id
      """;
  private static final String VISIBLE = """
      from collections co
      where co.status='ACTIVE' and (co.start_at is null or co.start_at <= now())
        and (co.end_at is null or co.end_at > now())
        and exists (select 1
      """ + PublicProductSql.FROM_PUBLIC + """
          and exists (select 1 from collection_items visible_item
            where visible_item.collection_id=co.id and visible_item.product_id=p.id))
      """;
  private final JdbcClient jdbc;

  public PublicCollectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public CollectionPage list(ProductCursor after, int limit) {
    String sql = "select " + COLUMNS + ", co.created_at " + VISIBLE;
    var params = new HashMap<String, Object>();
    if (after != null) {
      sql += " and (co.created_at, co.id) < (:created, :id)";
      params.put("created", Timestamp.from(Instant.parse(after.value())));
      params.put("id", after.id());
    }
    params.put("limit", limit + 1);
    var rows = jdbc.sql(sql + " order by co.created_at desc, co.id desc limit :limit").params(params)
        .query((rs, row) -> new CollectionRow(summary(rs), rs.getTimestamp("created_at").toInstant())).list();
    boolean hasMore = rows.size() > limit;
    var page = hasMore ? rows.subList(0, limit) : rows;
    String next = null;
    if (hasMore) {
      CollectionRow last = page.getLast();
      next = new ProductCursor("COLLECTIONS", last.createdAt().toString(), last.collection().id(), "").encode();
    }
    return new CollectionPage(page.stream().map(CollectionRow::collection).toList(), next);
  }

  @Transactional(readOnly = true)
  public Optional<CollectionDetail> detail(String slug, ProductCursor after, int limit) {
    var collection = jdbc.sql("select " + COLUMNS + VISIBLE + " and co.slug=:slug").param("slug", slug)
        .query((rs, row) -> summary(rs)).optional();
    if (collection.isEmpty()) return Optional.empty();
    UUID id = collection.get().id();
    var lookbook = jdbc.sql("""
        select li.asset_id, li.caption_vi, li.caption_en, li.sort_order
        from lookbook_images li join media_assets ma on ma.id=li.asset_id
        where li.collection_id=:collection and ma.availability='AVAILABLE'
        order by li.sort_order, li.asset_id
        """).param("collection", id).query((rs, row) -> new LookbookView(rs.getObject("asset_id", UUID.class),
            rs.getString("caption_vi"), rs.getString("caption_en"), rs.getInt("sort_order"))).list();
    String sql = "select card.*, ci.sort_order from collection_items ci cross join lateral (select "
        + PublicProductSql.CARD_COLUMNS + " " + PublicProductSql.FROM_PUBLIC
        + " and p.id=ci.product_id) card where ci.collection_id=:collection";
    var params = new HashMap<String, Object>();
    params.put("collection", id);
    params.put("limit", limit + 1);
    if (after != null) {
      sql += " and (ci.sort_order, ci.product_id) > (:sort, :id)";
      params.put("sort", Integer.parseInt(after.value()));
      params.put("id", after.id());
    }
    var rows = jdbc.sql(sql + " order by ci.sort_order, ci.product_id limit :limit").params(params)
        .query((rs, row) -> new ProductRow(PublicProductSql.card(rs), rs.getInt("sort_order"))).list();
    boolean hasMore = rows.size() > limit;
    var page = hasMore ? rows.subList(0, limit) : rows;
    String next = null;
    if (hasMore) {
      ProductRow last = page.getLast();
      next = new ProductCursor("COLLECTION:" + slug, Integer.toString(last.sortOrder()), last.card().id(), "").encode();
    }
    return Optional.of(new CollectionDetail(collection.get(), lookbook, page.stream().map(ProductRow::card).toList(), next));
  }

  private static CollectionSummary summary(ResultSet rs) throws SQLException {
    Timestamp start = rs.getTimestamp("start_at"), end = rs.getTimestamp("end_at");
    return new CollectionSummary(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name_vi"),
        rs.getString("name_en"), rs.getObject("cover_asset_id", UUID.class),
        start == null ? null : start.toInstant(), end == null ? null : end.toInstant());
  }

  private record CollectionRow(CollectionSummary collection, Instant createdAt) {}
  private record ProductRow(ProductSummary card, int sortOrder) {}
  public record CollectionSummary(UUID id, String slug, String nameVi, String nameEn, UUID coverAssetId,
                                  Instant startAt, Instant endAt) {}
  public record CollectionPage(List<CollectionSummary> items, String nextCursor) {}
  public record CollectionDetail(CollectionSummary collection, List<LookbookView> lookbook,
                                 List<ProductSummary> products, String nextCursor) {}
  public record LookbookView(UUID assetId, String captionVi, String captionEn, int sortOrder) {}
}
