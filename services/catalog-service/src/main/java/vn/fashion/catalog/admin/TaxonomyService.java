package vn.fashion.catalog.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.fashion.catalog.web.Api;

@Service
public class TaxonomyService {
  public record Category(UUID id, UUID parentId, String nameVi, String nameEn, String slug, int sortOrder, String status, long version) { }
  public record Brand(UUID id, String name, String logoUrl, String status, long version) { }
  public record CategoryInput(UUID parentId, String nameVi, String nameEn, String slug, Integer sortOrder, String status, Long expectedVersion) { }
  public record BrandInput(String name, String logoUrl, String status, Long expectedVersion) { }
  private final JdbcClient jdbc;
  private final AuditLog audit;
  private final AdminCommands commands;
  public TaxonomyService(JdbcClient jdbc, AuditLog audit, AdminCommands commands) { this.jdbc = jdbc; this.audit = audit; this.commands = commands; }
  static String strip(String value) { return value == null ? null : value.strip(); }
  static void text(List<Api.FieldError> errors, String field, String value, int max) {
    if (value == null || value.isBlank() || value.length() > max) errors.add(new Api.FieldError(field, "must be 1.." + max + " characters"));
  }
  static void slug(List<Api.FieldError> errors, String value) {
    if (value == null || value.length() > 160 || !value.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) errors.add(new Api.FieldError("slug", "must be a lowercase slug, maximum 160 characters"));
  }
  static void valid(List<Api.FieldError> errors) {
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
  }
  static void status(List<Api.FieldError> errors, String status) {
    if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) errors.add(new Api.FieldError("status", "must be ACTIVE or INACTIVE"));
  }
  CategoryInput normalize(CategoryInput request, boolean create) {
    var result = new CategoryInput(request.parentId(), strip(request.nameVi()), strip(request.nameEn()), strip(request.slug()),
        request.sortOrder() == null ? 0 : request.sortOrder(), create ? "ACTIVE" : request.status(), create ? null : request.expectedVersion());
    var errors = new ArrayList<Api.FieldError>();
    text(errors, "name_vi", result.nameVi(), 255); text(errors, "name_en", result.nameEn(), 255);
    slug(errors, result.slug()); status(errors, result.status()); valid(errors); return result;
  }
  BrandInput normalize(BrandInput request, boolean create) {
    var result = new BrandInput(strip(request.name()), strip(request.logoUrl()), create ? "ACTIVE" : request.status(), create ? null : request.expectedVersion());
    var errors = new ArrayList<Api.FieldError>(); text(errors, "name", result.name(), 255); status(errors, result.status());
    if (result.logoUrl() != null && (result.logoUrl().length() > 500 || !(result.logoUrl().startsWith("https://") || result.logoUrl().startsWith("http://"))))
      errors.add(new Api.FieldError("logo_url", "must be an http/https URL, maximum 500 characters"));
    valid(errors); return result;
  }
  public List<Category> categories() { return jdbc.sql("select * from categories order by parent_id nulls first,sort_order,slug").query(Category.class).list(); }
  public List<Brand> brands() { return jdbc.sql("select * from brands order by name,id").query(Brand.class).list(); }
  private void parent(UUID id, UUID parentId) {
    if (parentId == null) return;
    var errors = new ArrayList<Api.FieldError>();
    if (parentId.equals(id)) errors.add(new Api.FieldError("parent_id", "cannot be itself"));
    else {
      var parent = jdbc.sql("select * from categories where id=:id for update").param("id", parentId).query(Category.class).optional();
      if (parent.isEmpty() || parent.get().parentId() != null) errors.add(new Api.FieldError("parent_id", "must be an existing root category"));
      if (id != null && jdbc.sql("select exists(select 1 from categories where parent_id=:id)").param("id", id).query(Boolean.class).single())
        errors.add(new Api.FieldError("parent_id", "category with children must stay a root"));
    }
    valid(errors);
  }
  Category createCategory(UUID actor, CategoryInput r, UUID requestId) {
    parent(null, r.parentId()); UUID id = UUID.randomUUID();
    var result = jdbc.sql("""
        insert into categories(id,parent_id,name_vi,name_en,slug,sort_order) values (:id,:parent,:vi,:en,:slug,:sort) returning *
        """).param("id", id).param("parent", r.parentId()).param("vi", r.nameVi()).param("en", r.nameEn())
        .param("slug", r.slug()).param("sort", r.sortOrder()).query(Category.class).single();
    audit.record(actor, "catalog.category.create", "category", id, null, null, result, requestId); return result;
  }
  Category updateCategory(UUID actor, UUID id, CategoryInput r, UUID requestId) {
    return commands.update(() -> {
      var before = jdbc.sql("select * from categories where id=:id for update").param("id", id).query(Category.class).optional()
          .orElseThrow(() -> notFound("CATEGORY_NOT_FOUND"));
      AdminCommands.requireVersion(r.expectedVersion(), before.version()); parent(id, r.parentId());
      var result = jdbc.sql("""
          update categories set parent_id=:parent,name_vi=:vi,name_en=:en,slug=:slug,sort_order=:sort,status=:status,version=version+1,updated_at=now() where id=:id returning *
          """).param("id", id).param("parent", r.parentId()).param("vi", r.nameVi()).param("en", r.nameEn())
          .param("slug", r.slug()).param("sort", r.sortOrder()).param("status", r.status()).query(Category.class).single();
      audit.record(actor, "catalog.category.update", "category", id, null, before, result, requestId); return result;
    });
  }
  Brand createBrand(UUID actor, BrandInput r, UUID requestId) {
    UUID id = UUID.randomUUID();
    var result = jdbc.sql("insert into brands(id,name,logo_url) values (:id,:name,:logo) returning *")
        .param("id", id).param("name", r.name()).param("logo", r.logoUrl()).query(Brand.class).single();
    audit.record(actor, "catalog.brand.create", "brand", id, null, null, result, requestId); return result;
  }
  Brand updateBrand(UUID actor, UUID id, BrandInput r, UUID requestId) {
    return commands.update(() -> {
      var before = jdbc.sql("select * from brands where id=:id for update").param("id", id).query(Brand.class).optional().orElseThrow(() -> notFound("BRAND_NOT_FOUND"));
      AdminCommands.requireVersion(r.expectedVersion(), before.version());
      var result = jdbc.sql("update brands set name=:name,logo_url=:logo,status=:status,version=version+1,updated_at=now() where id=:id returning *")
          .param("id", id).param("name", r.name()).param("logo", r.logoUrl()).param("status", r.status()).query(Brand.class).single();
      audit.record(actor, "catalog.brand.update", "brand", id, null, before, result, requestId); return result;
    });
  }
  static Api.Problem notFound(String message) { return new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of()); }
}
