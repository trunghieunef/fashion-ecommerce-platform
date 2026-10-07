package vn.fashion.catalog.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.web.Api;

@Service
public class CollectionAdminService {
  public record Item(UUID productId, int sortOrder) { }
  public record Summary(UUID id, String nameVi, String nameEn, String slug, String coverUrl,
      Instant startAt, Instant endAt, String status, long version) { }
  public record CollectionData(UUID id, String nameVi, String nameEn, String slug, String coverUrl,
      Instant startAt, Instant endAt, String status, long version, List<Item> items) { }
  public record Page(List<Summary> items, int page, int size, long total) { }
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  public CollectionAdminService(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
  public Page list(int page, int size, String status) {
    var errors = new ArrayList<Api.FieldError>();
    if (page < 1) errors.add(new Api.FieldError("page", "must be at least 1"));
    if (size < 1 || size > 100) errors.add(new Api.FieldError("size", "must be 1..100"));
    if (status != null && !List.of("DRAFT", "ACTIVE", "INACTIVE").contains(status)) errors.add(new Api.FieldError("status", "invalid collection status"));
    TaxonomyService.valid(errors);
    String where = status == null ? "" : " where status=:status";
    var query = jdbc.sql("select * from collections" + where + " order by created_at desc,id desc limit :size offset :offset")
        .param("size", size).param("offset", (long)(page - 1) * size);
    var count = jdbc.sql("select count(*) from collections" + where);
    if (status != null) { query.param("status", status); count.param("status", status); }
    return new Page(query.query(this::summary).list(), page, size, count.query(Long.class).single());
  }
  private Summary summary(ResultSet rs, int row) throws SQLException {
    var start = rs.getTimestamp("start_at"); var end = rs.getTimestamp("end_at");
    return new Summary(rs.getObject("id", UUID.class), rs.getString("name_vi"), rs.getString("name_en"), rs.getString("slug"), null,
        start == null ? null : start.toInstant(), end == null ? null : end.toInstant(), rs.getString("status"), rs.getLong("version"));
  }
  public CollectionData load(UUID id) {
    return jdbc.sql("""
        select c.*,coalesce((select json_agg(json_build_object('product_id',i.product_id,'sort_order',i.sort_order)
          order by i.sort_order,i.product_id) from collection_items i where i.collection_id=c.id),'[]'::json) as items
        from collections c where c.id=:id
        """).param("id", id).query((rs, row) -> {
          var c = summary(rs, row);
          List<Item> items = json.readValue(rs.getString("items"), json.getTypeFactory().constructCollectionType(List.class, Item.class));
          return new CollectionData(c.id(), c.nameVi(), c.nameEn(), c.slug(), c.coverUrl(), c.startAt(), c.endAt(), c.status(), c.version(), items);
        }).optional().orElseThrow(() -> TaxonomyService.notFound("COLLECTION_NOT_FOUND"));
  }
}
