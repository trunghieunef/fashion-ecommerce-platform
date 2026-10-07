package vn.fashion.catalog.admin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.media.UploadService;
import vn.fashion.catalog.media.UploadService.RenditionView;
import vn.fashion.catalog.web.Api;

/** Full-replacement attach of approved assets to a product or a collection lookbook (CAT-03 spec section 2). */
@Service
public class MediaAttachService {
  static final int MAX_PRODUCT_IMAGES = 20, MAX_COLLECTION_IMAGES = 50;
  public record ProductImage(UUID assetId, RenditionView image, RenditionView thumb, String url, String thumbUrl,
      String altVi, String altEn, String variantColor, int sortOrder) { }
  public record ProductImages(UUID id, long version, List<ProductImage> images) { }
  public record CollectionImage(UUID assetId, RenditionView image, RenditionView thumb, String url, String thumbUrl,
      String captionVi, String captionEn, int sortOrder) { }
  public record CollectionImages(UUID id, long version, List<CollectionImage> images, UUID coverAssetId) { }

  public static class ProductImageItem {
    public String assetId, altVi, altEn, variantColor;
    public JsonNode sortOrder;
    @JsonIgnore public final List<String> unknownFields = new ArrayList<>();
    @JsonAnySetter public void unknown(String key, JsonNode value) { unknownFields.add(key); }
  }
  public static class CollectionImageItem {
    public String assetId, captionVi, captionEn;
    public JsonNode sortOrder;
    @JsonIgnore public final List<String> unknownFields = new ArrayList<>();
    @JsonAnySetter public void unknown(String key, JsonNode value) { unknownFields.add(key); }
  }
  public static class ProductImagesRequest {
    public JsonNode expectedVersion;
    public List<ProductImageItem> images;
    @JsonIgnore public final List<String> unknownFields = new ArrayList<>();
    @JsonAnySetter public void unknown(String key, JsonNode value) { unknownFields.add(key); }
  }
  public static class CollectionImagesRequest {
    public JsonNode expectedVersion;
    public List<CollectionImageItem> images;
    private JsonNode cover;
    private boolean coverPresent;
    /** cover_asset_id is required but nullable, so presence is tracked separately from the value. */
    @JsonProperty("cover_asset_id") void setCover(JsonNode value) { cover = value; coverPresent = true; }
    @JsonIgnore public final List<String> unknownFields = new ArrayList<>();
    @JsonAnySetter public void unknown(String key, JsonNode value) { unknownFields.add(key); }
  }

  /** Validated body; alt/caption/color are meaningful per target kind only. */
  record Item(UUID assetId, String altVi, String altEn, String color, String captionVi, String captionEn, int sort) { }
  record Input(long expectedVersion, List<Item> items, UUID cover) { }
  private record Row(UUID assetId, RenditionView image, RenditionView thumb, String altVi, String altEn, String variantColor,
      String captionVi, String captionEn, int sortOrder) { }
  private record Snapshot(long version, List<Row> rows, UUID cover) { }
  private record AssetRow(boolean available, UUID actor, String state, String targetType, UUID target, boolean retained) { }
  private record Target(String table, String images, String idCol, String type, String fields, String notFound, String action, String audited) { }

  private static final Target PRODUCT = new Target("products", "product_images", "product_id", "PRODUCT",
      "'alt_vi',i.alt_vi,'alt_en',i.alt_en,'variant_color',i.variant_color", "PRODUCT_NOT_FOUND", "catalog.product.images.update", "product");
  private static final Target COLLECTION = new Target("collections", "lookbook_images", "collection_id", "COLLECTION",
      "'caption_vi',i.caption_vi,'caption_en',i.caption_en", "COLLECTION_NOT_FOUND", "catalog.collection.images.update", "collection");
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final AdminCommands commands;
  private final AuditLog audit;
  public MediaAttachService(JdbcClient jdbc, ObjectMapper json, AdminCommands commands, AuditLog audit) {
    this.jdbc = jdbc; this.json = json; this.commands = commands; this.audit = audit;
  }

  // ---- request validation (no DB) ----
  Input normalize(ProductImagesRequest r) {
    var errors = new ArrayList<Api.FieldError>(); r.unknownFields.forEach(k -> errors.add(new Api.FieldError(k, "unknown field")));
    Long version = integer(r.expectedVersion, "expected_version", Long.MAX_VALUE, errors);
    var items = new ArrayList<Item>(); var seen = new HashSet<UUID>();
    if (r.images == null || r.images.size() > MAX_PRODUCT_IMAGES) errors.add(new Api.FieldError("images", "required, maximum " + MAX_PRODUCT_IMAGES + " images"));
    else for (int i = 0; i < r.images.size(); i++) {
      String f = "images[" + i + "]"; var row = r.images.get(i);
      if (row == null) { errors.add(new Api.FieldError(f, "must be an item object")); continue; }
      row.unknownFields.forEach(k -> errors.add(new Api.FieldError(f + "." + k, "unknown field")));
      UUID asset = asset(row.assetId, f, seen, errors);
      String vi = bounded(row.altVi, f + ".alt_vi", 1, 255, errors), en = bounded(row.altEn, f + ".alt_en", 1, 255, errors);
      Long sort = integer(row.sortOrder, f + ".sort_order", Integer.MAX_VALUE, errors);
      if (row.variantColor != null && row.variantColor.isBlank()) errors.add(new Api.FieldError(f + ".variant_color", "must match a variant color or be null"));
      items.add(new Item(asset, vi, en, row.variantColor, null, null, sort == null ? 0 : sort.intValue()));
    }
    TaxonomyService.valid(errors);
    return new Input(version, List.copyOf(items), null);
  }
  Input normalize(CollectionImagesRequest r) {
    var errors = new ArrayList<Api.FieldError>(); r.unknownFields.forEach(k -> errors.add(new Api.FieldError(k, "unknown field")));
    Long version = integer(r.expectedVersion, "expected_version", Long.MAX_VALUE, errors);
    var items = new ArrayList<Item>(); var seen = new HashSet<UUID>();
    if (r.images == null || r.images.size() > MAX_COLLECTION_IMAGES) errors.add(new Api.FieldError("images", "required, maximum " + MAX_COLLECTION_IMAGES + " images"));
    else for (int i = 0; i < r.images.size(); i++) {
      String f = "images[" + i + "]"; var row = r.images.get(i);
      if (row == null) { errors.add(new Api.FieldError(f, "must be an item object")); continue; }
      row.unknownFields.forEach(k -> errors.add(new Api.FieldError(f + "." + k, "unknown field")));
      UUID asset = asset(row.assetId, f, seen, errors);
      String vi = row.captionVi == null ? "" : bounded(row.captionVi, f + ".caption_vi", 0, 500, errors);
      String en = row.captionEn == null ? "" : bounded(row.captionEn, f + ".caption_en", 0, 500, errors);
      Long sort = integer(row.sortOrder, f + ".sort_order", Integer.MAX_VALUE, errors);
      items.add(new Item(asset, null, null, null, vi, en, sort == null ? 0 : sort.intValue()));
    }
    UUID cover = null;
    if (!r.coverPresent) errors.add(new Api.FieldError("cover_asset_id", "is required, null allowed"));
    else if (r.cover != null && !r.cover.isNull()) {
      cover = uuid(r.cover.isString() ? r.cover.asString() : null);
      if (cover == null || !seen.contains(cover)) errors.add(new Api.FieldError("cover_asset_id", "must be an asset in images"));
    }
    TaxonomyService.valid(errors);
    return new Input(version, List.copyOf(items), cover);
  }
  private static UUID uuid(String value) {
    try {
      if (value == null) return null;
      UUID id = UUID.fromString(value);
      return id.toString().equalsIgnoreCase(value) ? id : null;
    } catch (IllegalArgumentException e) { return null; }
  }
  private static UUID asset(String value, String field, Set<UUID> seen, List<Api.FieldError> errors) {
    UUID id = uuid(value);
    if (id == null) errors.add(new Api.FieldError(field + ".asset_id", "must be a UUID"));
    else if (!seen.add(id)) errors.add(new Api.FieldError(field + ".asset_id", "duplicate asset"));
    return id;
  }
  private static String bounded(String raw, String field, int min, int max, List<Api.FieldError> errors) {
    String value = raw == null ? null : raw.strip();
    if (value == null || value.codePointCount(0, value.length()) < min || value.codePointCount(0, value.length()) > max)
      errors.add(new Api.FieldError(field, "must be " + min + ".." + max + " characters"));
    return value;
  }
  private static Long integer(JsonNode value, String field, long max, List<Api.FieldError> errors) {
    if (value == null || value.isNull() || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0 || value.asLong() > max) {
      errors.add(new Api.FieldError(field, "required nonnegative integer, maximum " + max)); return null;
    }
    return value.asLong();
  }

  // ---- reads ----
  public ProductImages getProduct(UUID id) {
    var s = snapshot(PRODUCT, id);
    return new ProductImages(id, s.version(), s.rows().stream().map(r -> new ProductImage(r.assetId(), r.image(), r.thumb(),
        UploadService.imageUrl(r.assetId(), "image"), UploadService.imageUrl(r.assetId(), "thumb"),
        r.altVi(), r.altEn(), r.variantColor(), r.sortOrder())).toList());
  }
  public CollectionImages getCollection(UUID id) {
    var s = snapshot(COLLECTION, id);
    return new CollectionImages(id, s.version(), s.rows().stream().map(r -> new CollectionImage(r.assetId(), r.image(), r.thumb(),
        UploadService.imageUrl(r.assetId(), "image"), UploadService.imageUrl(r.assetId(), "thumb"),
        r.captionVi(), r.captionEn(), r.sortOrder())).toList(), s.cover());
  }
  private Snapshot snapshot(Target t, UUID id) {
    String cover = t == COLLECTION ? "x.cover_asset_id" : "null::uuid";
    return jdbc.sql("""
        select x.version, %s as cover, coalesce((select json_agg(json_build_object('asset_id',i.asset_id,
          'image',json_build_object('content_type',a.image_content_type,'size_bytes',a.image_size_bytes,'width',a.image_width,'height',a.image_height,'sha256',a.image_sha256),
          'thumb',json_build_object('content_type',a.thumb_content_type,'size_bytes',a.thumb_size_bytes,'width',a.thumb_width,'height',a.thumb_height,'sha256',a.thumb_sha256),
          %s,'sort_order',i.sort_order) order by i.sort_order,i.asset_id)
          from %s i join media_assets a on a.id=i.asset_id where i.%s=x.id),'[]'::json) as images
        from %s x where x.id=:id
        """.formatted(cover, t.fields(), t.images(), t.idCol(), t.table())).param("id", id).query((rs, n) -> {
          List<Row> rows = json.readValue(rs.getString("images"), json.getTypeFactory().constructCollectionType(List.class, Row.class));
          return new Snapshot(rs.getLong("version"), rows, rs.getObject("cover", UUID.class));
        }).optional().orElseThrow(() -> TaxonomyService.notFound(t.notFound()));
  }

  // ---- writes ----
  public ProductImages putProduct(UUID actor, UUID id, ProductImagesRequest request, UUID requestId) {
    return put(actor, id, normalize(request), requestId, PRODUCT, () -> getProduct(id));
  }
  public CollectionImages putCollection(UUID actor, UUID id, CollectionImagesRequest request, UUID requestId) {
    return put(actor, id, normalize(request), requestId, COLLECTION, () -> getCollection(id));
  }
  private <T> T put(UUID actor, UUID id, Input in, UUID requestId, Target t, java.util.function.Supplier<T> view) {
    return commands.update(() -> {
      var head = jdbc.sql("select version, " + (t == PRODUCT ? "status" : "''") + " from " + t.table() + " where id=:id for update")
          .param("id", id).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional()
          .orElseThrow(() -> TaxonomyService.notFound(t.notFound()));
      AdminCommands.requireVersion(in.expectedVersion(), Long.parseLong(head[0]));
      Set<UUID> current = new HashSet<>(jdbc.sql("select asset_id from " + t.images() + " where " + t.idCol() + "=:id").param("id", id).query(UUID.class).list());
      UUID coverBefore = t == PRODUCT ? null : jdbc.sql("select cover_asset_id from collections where id=:id").param("id", id).query(UUID.class).optional().orElse(null);
      validateAssets(actor, id, in, current, t);
      if (t == PRODUCT) validateProduct(id, in, head[1]);
      jdbc.sql("delete from " + t.images() + " where " + t.idCol() + "=:id").param("id", id).update();
      for (var item : in.items()) {
        if (t == PRODUCT) jdbc.sql("insert into product_images(product_id,asset_id,alt_vi,alt_en,variant_color,sort_order) values (:id,:asset,:vi,:en,:color,:sort)")
            .param("id", id).param("asset", item.assetId()).param("vi", item.altVi()).param("en", item.altEn()).param("color", item.color()).param("sort", item.sort()).update();
        else jdbc.sql("insert into lookbook_images(collection_id,asset_id,caption_vi,caption_en,sort_order) values (:id,:asset,:cvi,:cen,:sort)")
            .param("id", id).param("asset", item.assetId()).param("cvi", item.captionVi()).param("cen", item.captionEn()).param("sort", item.sort()).update();
      }
      var wanted = new HashSet<UUID>(); in.items().forEach(i -> wanted.add(i.assetId()));
      var added = new ArrayList<UUID>(wanted); added.removeAll(current);
      var removed = new ArrayList<UUID>(current); removed.removeAll(wanted);
      added.sort(null); removed.sort(null);
      if (!removed.isEmpty()) jdbc.sql("update media_assets set detached_at=now() where id in (:ids)").param("ids", removed).update();
      if (!added.isEmpty()) jdbc.sql("update media_assets set detached_at=null where id in (:ids)").param("ids", added).update();
      if (t == PRODUCT) jdbc.sql("update products set version=version+1, updated_at=now() where id=:id").param("id", id).update();
      else jdbc.sql("update collections set cover_asset_id=:cover, version=version+1, updated_at=now() where id=:id")
          .param("id", id).param("cover", in.cover()).update();
      var diff = new LinkedHashMap<String, Object>();
      diff.put("added", added); diff.put("removed", removed); diff.put("cover_before", coverBefore); diff.put("cover_after", in.cover());
      audit.record(actor, t.action(), t.audited(), id, null, null, diff, requestId);
      return view.get();
    });
  }
  private void validateAssets(UUID actor, UUID id, Input in, Set<UUID> current, Target t) {
    var errors = new ArrayList<Api.FieldError>();
    if (!in.items().isEmpty()) {
      var rows = new HashMap<UUID, AssetRow>();
      jdbc.sql("select a.id, a.availability='AVAILABLE', u.actor_id, u.state, u.target_type, coalesce(u.product_id,u.collection_id), "
          + "coalesce(a.detached_at,a.approved_at) + interval '7 days' > now() from media_assets a join media_uploads u on u.id=a.id "
          + "where a.id in (:ids) order by a.id for update of a")
          .param("ids", in.items().stream().map(Item::assetId).sorted().toList())
          .query((rs, n) -> { rows.put(rs.getObject(1, UUID.class), new AssetRow(rs.getBoolean(2), rs.getObject(3, UUID.class), rs.getString(4),
              rs.getString(5), rs.getObject(6, UUID.class), rs.getBoolean(7))); return 0; }).list();
      for (int i = 0; i < in.items().size(); i++) {
        UUID asset = in.items().get(i).assetId(); var row = rows.get(asset);
        boolean ok = row != null && row.available();
        if (ok && !current.contains(asset))
          ok = "APPROVED".equals(row.state()) && actor.equals(row.actor()) && t.type().equals(row.targetType()) && id.equals(row.target()) && row.retained();
        if (!ok) errors.add(new Api.FieldError("images[" + i + "].asset_id", "asset cannot be attached"));
      }
    }
    TaxonomyService.valid(errors);
  }
  private void validateProduct(UUID id, Input in, String status) {
    var errors = new ArrayList<Api.FieldError>();
    var colors = new HashSet<>(jdbc.sql("select color from product_variants where product_id=:id").param("id", id).query(String.class).list());
    for (int i = 0; i < in.items().size(); i++) {
      String color = in.items().get(i).color();
      if (color != null && !colors.contains(color)) errors.add(new Api.FieldError("images[" + i + "].variant_color", "must match a variant color of this product"));
    }
    TaxonomyService.valid(errors);
    if ("ACTIVE".equals(status) && in.items().isEmpty())
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ACTIVE_PRODUCT_REQUIRES_IMAGE", List.of(new Api.FieldError("images", "an ACTIVE product needs at least one image")));
  }
}
