package vn.fashion.catalog.admin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
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
  public static class CreateRequest {
    public String nameVi, nameEn, slug, startAt, endAt;
    public List<ItemRequest> items;
    @JsonIgnore public final List<String> unknownFields = new ArrayList<>();
    @JsonAnySetter public void unknown(String key, JsonNode value) { unknownFields.add(key); }
  }
  public static class UpdateRequest extends CreateRequest {
    public String status;
    public JsonNode expectedVersion;
  }
  public static class ItemRequest {
    public String productId;
    public JsonNode sortOrder;
    @JsonIgnore public final List<String> unknownFields = new ArrayList<>();
    @JsonAnySetter public void unknown(String key, JsonNode value) { unknownFields.add(key); }
  }
  public record Input(String nameVi, String nameEn, String slug, Instant startAt, Instant endAt,
      String status, Long expectedVersion, List<Item> items) { }
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final AdminCommands commands;
  private final AuditLog audit;
  public CollectionAdminService(JdbcClient jdbc, ObjectMapper json, AdminCommands commands, AuditLog audit) {
    this.jdbc = jdbc; this.json = json; this.commands = commands; this.audit = audit;
  }
  Input normalize(CreateRequest request) { return normalize(request, null); }
  Input normalize(UpdateRequest request) { return normalize(request, request); }
  private Input normalize(CreateRequest request, UpdateRequest update) {
    var errors = new ArrayList<Api.FieldError>();
    request.unknownFields.forEach(key -> errors.add(new Api.FieldError(key, "unknown field")));
    String vi = TaxonomyService.strip(request.nameVi), en = TaxonomyService.strip(request.nameEn), slug = TaxonomyService.strip(request.slug);
    TaxonomyService.text(errors, "name_vi", vi, 255); TaxonomyService.text(errors, "name_en", en, 255); TaxonomyService.slug(errors, slug);
    Instant start = date(request.startAt, "start_at", errors), end = date(request.endAt, "end_at", errors);
    if (start != null && end != null && !end.isAfter(start)) errors.add(new Api.FieldError("end_at", "must be after start_at"));
    String status = update == null ? "DRAFT" : update.status;
    Long version = update == null ? null : integer(update.expectedVersion, "expected_version", Long.MAX_VALUE, errors);
    if (status == null || !List.of("DRAFT", "ACTIVE", "INACTIVE").contains(status)) errors.add(new Api.FieldError("status", "must be DRAFT, ACTIVE or INACTIVE"));
    var items = new ArrayList<Item>(); var seen = new HashSet<UUID>();
    if (request.items == null || request.items.size() > 1000) errors.add(new Api.FieldError("items", "required, maximum 1000 items"));
    else for (int i = 0; i < request.items.size(); i++) {
      String field = "items[" + i + "]"; var row = request.items.get(i);
      if (row == null) { errors.add(new Api.FieldError(field, "must be an item object")); continue; }
      row.unknownFields.forEach(key -> errors.add(new Api.FieldError(field + "." + key, "unknown field")));
      UUID id = null;
      try {
        if (row.productId == null) throw new IllegalArgumentException();
        id = UUID.fromString(row.productId);
        if (!id.toString().equalsIgnoreCase(row.productId)) throw new IllegalArgumentException();
        if (!seen.add(id)) errors.add(new Api.FieldError(field + ".product_id", "duplicate product"));
      } catch (IllegalArgumentException e) { errors.add(new Api.FieldError(field + ".product_id", "must be a UUID")); }
      Long sort = integer(row.sortOrder, field + ".sort_order", Integer.MAX_VALUE, errors);
      items.add(new Item(id, sort == null ? 0 : sort.intValue()));
    }
    TaxonomyService.valid(errors);
    return new Input(vi, en, slug, start, end, status, version, List.copyOf(items));
  }
  private static Long integer(JsonNode value, String field, long max, List<Api.FieldError> errors) {
    if (value == null || value.isNull() || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0 || value.asLong() > max) {
      errors.add(new Api.FieldError(field, "required nonnegative integer, maximum " + max)); return null;
    }
    return value.asLong();
  }
  private static Instant date(String value, String field, List<Api.FieldError> errors) {
    if (value == null) return null;
    try {
      var utc = OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);
      if (utc.getYear() < 1 || utc.getYear() > 9999) {
        errors.add(new Api.FieldError(field, "UTC year must be 1..9999")); return null;
      }
      return utc.toInstant().truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeException e) {
      errors.add(new Api.FieldError(field, "must be an ISO-8601 timestamp with offset and UTC year 1..9999")); return null;
    }
  }
  private void requireProducts(Input input) {
    if (input.items().isEmpty()) return;
    var existing = new HashSet<>(jdbc.sql("select id from products where id in (:ids)")
        .param("ids", input.items().stream().map(Item::productId).toList()).query(UUID.class).list());
    var errors = new ArrayList<Api.FieldError>();
    for (int i = 0; i < input.items().size(); i++) if (!existing.contains(input.items().get(i).productId()))
      errors.add(new Api.FieldError("items[" + i + "].product_id", "product does not exist"));
    TaxonomyService.valid(errors);
  }
  private CollectionData write(UUID id, Input r, boolean create) {
    String sql = create ? """
        insert into collections(id,name_vi,name_en,slug,start_at,end_at,status) values (:id,:vi,:en,:slug,:start,:end,:status)
        """ : """
        update collections set name_vi=:vi,name_en=:en,slug=:slug,start_at=:start,end_at=:end,status=:status,
          version=version+1,updated_at=now() where id=:id
        """;
    jdbc.sql(sql).param("id", id).param("vi", r.nameVi()).param("en", r.nameEn()).param("slug", r.slug())
        .param("start", r.startAt() == null ? null : r.startAt().atOffset(ZoneOffset.UTC))
        .param("end", r.endAt() == null ? null : r.endAt().atOffset(ZoneOffset.UTC)).param("status", r.status()).update();
    if (!create) jdbc.sql("delete from collection_items where collection_id=:id").param("id", id).update();
    for (var item : r.items()) jdbc.sql("insert into collection_items(collection_id,product_id,sort_order) values (:id,:product,:sort)")
        .param("id", id).param("product", item.productId()).param("sort", item.sortOrder()).update();
    return load(id);
  }
  CollectionData create(UUID actor, Input input, UUID requestId) {
    requireProducts(input); var result = write(UUID.randomUUID(), input, true);
    audit.record(actor, "catalog.collection.create", "collection", result.id(), null, null, result, requestId); return result;
  }
  CollectionData update(UUID actor, UUID id, Input input, UUID requestId) {
    return commands.update(() -> {
      long version = jdbc.sql("select version from collections where id=:id for update").param("id", id).query(Long.class).optional()
          .orElseThrow(() -> TaxonomyService.notFound("COLLECTION_NOT_FOUND"));
      AdminCommands.requireVersion(input.expectedVersion(), version); var before = load(id); requireProducts(input);
      var result = write(id, input, false);
      audit.record(actor, "catalog.collection.update", "collection", id, null, before, result, requestId); return result;
    });
  }
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
    var start = rs.getObject("start_at", OffsetDateTime.class); var end = rs.getObject("end_at", OffsetDateTime.class);
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
