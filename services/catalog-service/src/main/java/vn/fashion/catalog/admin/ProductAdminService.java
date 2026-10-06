package vn.fashion.catalog.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import vn.fashion.catalog.web.Api;

@Service
public class ProductAdminService {
  public record Product(UUID id, UUID categoryId, UUID brandId, String nameVi, String nameEn, String slug,
      String descriptionVi, String descriptionEn, long basePrice, List<String> tags, String status,
      Instant publishedAt, long version, List<Variant> variants) { }
  public record ProductPage(List<Product> items, int page, int size, long total) { }
  public record Input(UUID categoryId, UUID brandId, String nameVi, String nameEn, String slug,
      String descriptionVi, String descriptionEn, Long basePrice, List<String> tags, Long expectedVersion) { }
  private final JdbcClient jdbc;
  private final DataSource source;
  private final AdminCommands commands;
  private final AuditLog audit;
  public ProductAdminService(JdbcClient jdbc, DataSource source, AdminCommands commands, AuditLog audit) {
    this.jdbc = jdbc; this.source = source; this.commands = commands; this.audit = audit;
  }
  Input normalize(Input input, boolean create) {
    var tags = input.tags() == null ? List.<String>of() : input.tags().stream().map(TaxonomyService::strip).toList();
    var r = new Input(input.categoryId(), input.brandId(), TaxonomyService.strip(input.nameVi()), TaxonomyService.strip(input.nameEn()),
        TaxonomyService.strip(input.slug()), HtmlSanitizer.clean(input.descriptionVi()), HtmlSanitizer.clean(input.descriptionEn()),
        input.basePrice(), tags, create ? null : input.expectedVersion());
    var errors = new ArrayList<Api.FieldError>();
    TaxonomyService.text(errors, "name_vi", r.nameVi(), 255); TaxonomyService.text(errors, "name_en", r.nameEn(), 255); TaxonomyService.slug(errors, r.slug());
    if (r.basePrice() == null || r.basePrice() < 0) errors.add(new Api.FieldError("base_price", "must be a nonnegative integer VND"));
    if (tags.size() > 20 || tags.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 50)) errors.add(new Api.FieldError("tags", "maximum 20 tags of 1..50 characters"));
    if (r.descriptionVi().length() > 20000) errors.add(new Api.FieldError("description_vi", "maximum 20000 characters after sanitizing"));
    if (r.descriptionEn().length() > 20000) errors.add(new Api.FieldError("description_en", "maximum 20000 characters after sanitizing"));
    if (r.categoryId() == null || !jdbc.sql("select exists(select 1 from categories where id=:id)").param("id", r.categoryId()).query(Boolean.class).single())
      errors.add(new Api.FieldError("category_id", "must refer to an existing category"));
    if (r.brandId() != null && !jdbc.sql("select exists(select 1 from brands where id=:id)").param("id", r.brandId()).query(Boolean.class).single())
      errors.add(new Api.FieldError("brand_id", "must refer to an existing brand"));
    TaxonomyService.valid(errors); return r;
  }
  public ProductPage list(int page, int size, String status) {
    var errors = new ArrayList<Api.FieldError>();
    if (page < 1) errors.add(new Api.FieldError("page", "must be at least 1"));
    if (size < 1 || size > 100) errors.add(new Api.FieldError("size", "must be 1..100"));
    if (status != null && !List.of("DRAFT", "ACTIVE", "INACTIVE").contains(status)) errors.add(new Api.FieldError("status", "invalid product status"));
    TaxonomyService.valid(errors);
    String where = status == null ? "" : " where status=:status";
    var query = jdbc.sql("select * from products" + where + " order by created_at desc,id desc limit :size offset :offset").param("size", size).param("offset", (long)(page - 1) * size);
    var count = jdbc.sql("select count(*) from products" + where);
    if (status != null) { query.param("status", status); count.param("status", status); }
    return new ProductPage(query.query(this::map).list(), page, size, count.query(Long.class).single());
  }
  private Product map(ResultSet rs, int row) throws SQLException {
    var timestamp = rs.getTimestamp("published_at");
    return new Product(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class),
        rs.getString("name_vi"), rs.getString("name_en"), rs.getString("slug"), rs.getString("description_vi"), rs.getString("description_en"),
        rs.getLong("base_price"), Arrays.asList((String[])rs.getArray("tags").getArray()), rs.getString("status"),
        timestamp == null ? null : timestamp.toInstant(), rs.getLong("version"), List.of());
  }
  private Product find(UUID id, boolean lock) {
    var p = jdbc.sql("select * from products where id=:id" + (lock ? " for update" : "")).param("id", id).query(this::map).optional()
        .orElseThrow(() -> TaxonomyService.notFound("PRODUCT_NOT_FOUND"));
    var variants = jdbc.sql("select * from product_variants where product_id=:id order by created_at,id").param("id", id).query(Variant.class).list();
    return new Product(p.id(), p.categoryId(), p.brandId(), p.nameVi(), p.nameEn(), p.slug(), p.descriptionVi(), p.descriptionEn(),
        p.basePrice(), p.tags(), p.status(), p.publishedAt(), p.version(), variants);
  }
  public Product load(UUID id) { return find(id, false); }
  public Product lockForUpdate(UUID id) { return find(id, true); }
  private Product write(UUID id, Input r, boolean create) {
    var connection = DataSourceUtils.getConnection(source);
    try {
      var tags = connection.createArrayOf("text", r.tags().toArray(String[]::new));
      try {
        String sql = create ? """
            insert into products(id,category_id,brand_id,name_vi,name_en,slug,description_vi,description_en,base_price,tags,status)
            values (:id,:category,:brand,:vi,:en,:slug,:descriptionVi,:descriptionEn,:price,:tags,'DRAFT')
            """ : """
            update products set category_id=:category,brand_id=:brand,name_vi=:vi,name_en=:en,slug=:slug,
            description_vi=:descriptionVi,description_en=:descriptionEn,base_price=:price,tags=:tags,version=version+1,updated_at=now() where id=:id
            """;
        jdbc.sql(sql).param("id", id).param("category", r.categoryId()).param("brand", r.brandId()).param("vi", r.nameVi()).param("en", r.nameEn())
            .param("slug", r.slug()).param("descriptionVi", r.descriptionVi()).param("descriptionEn", r.descriptionEn()).param("price", r.basePrice())
            .param("tags", tags, Types.ARRAY).update();
        return load(id);
      } finally { tags.free(); }
    } catch (SQLException e) { throw new IllegalStateException("cannot bind catalog tags", e); }
    finally { DataSourceUtils.releaseConnection(connection, source); }
  }
  Product create(UUID actor, Input r) {
    var p = write(UUID.randomUUID(), r, true);
    audit.record(actor, "catalog.product.create", "product", p.id(), null, null, p); return p;
  }
  Product update(UUID actor, UUID id, Input r) {
    return commands.update(() -> {
      var before = lockForUpdate(id); AdminCommands.requireVersion(r.expectedVersion(), before.version());
      var p = write(id, r, false); audit.record(actor, "catalog.product.update", "product", id, null, before, p); return p;
    });
  }
}
