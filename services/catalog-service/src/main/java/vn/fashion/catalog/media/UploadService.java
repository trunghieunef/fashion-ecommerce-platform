package vn.fashion.catalog.media;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.admin.AdminCommands;
import vn.fashion.catalog.admin.AuditLog;
import vn.fashion.catalog.web.Api;

@Service
public class UploadService {
  static final int PUT_TTL_SECONDS = 300;
  static final long MAX_BYTES = 5_242_880L;
  public static class CreateRequest {
    public String filename, contentType, targetType, targetId;
    public JsonNode sizeBytes;
    @JsonIgnore public final List<String> unknownFields = new ArrayList<>();
    @JsonAnySetter public void unknown(String key, JsonNode value) { unknownFields.add(key); }
  }
  /** Normalized request; this is what the idempotency hash covers. */
  record Input(String filename, String contentType, String targetType, UUID targetId, long sizeBytes) { }
  /** URL-free descriptor stored as the idempotency response body. */
  record Descriptor(UUID uploadId, Instant putExpiresAt, Instant completeDeadline, String contentType, long sizeBytes) { }
  public record Intent(UUID uploadId, Instant putExpiresAt, Instant completeDeadline, URI putUrl, Map<String, Object> putHeaders) { }
  public record RenditionView(String contentType, long sizeBytes, int width, int height, String sha256) { }
  public record AssetView(UUID assetId, RenditionView image, RenditionView thumb, String url, String thumbUrl) { }
  public record StatusView(UUID uploadId, String targetType, UUID targetId, String state, Instant putExpiresAt,
      Instant completeDeadline, Instant leaseUntil, String reasonCode, AssetView asset, String assetAvailability) { }

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final AdminCommands commands;
  private final AuditLog audit;
  private final MediaStorage storage;
  private final Tracer tracer;
  public UploadService(JdbcClient jdbc, ObjectMapper json, AdminCommands commands, AuditLog audit, MediaStorage storage, Tracer tracer) {
    this.jdbc = jdbc; this.json = json; this.commands = commands; this.audit = audit; this.storage = storage; this.tracer = tracer;
  }

  Input normalize(CreateRequest r) {
    var errors = new ArrayList<Api.FieldError>();
    r.unknownFields.forEach(k -> errors.add(new Api.FieldError(k, "unknown field")));
    String name = r.filename == null ? null : r.filename.strip();
    if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 255)
      errors.add(new Api.FieldError("filename", "must be 1..255 characters"));
    if (!"image/jpeg".equals(r.contentType) && !"image/png".equals(r.contentType))
      errors.add(new Api.FieldError("content_type", "must be image/jpeg or image/png"));
    if (!"PRODUCT".equals(r.targetType) && !"COLLECTION".equals(r.targetType))
      errors.add(new Api.FieldError("target_type", "must be PRODUCT or COLLECTION"));
    UUID target = null;
    try {
      if (r.targetId == null) throw new IllegalArgumentException();
      target = UUID.fromString(r.targetId);
      if (!target.toString().equalsIgnoreCase(r.targetId)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) { errors.add(new Api.FieldError("target_id", "must be a UUID")); }
    long size = 0;
    var n = r.sizeBytes;
    if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.asLong() < 1 || n.asLong() > MAX_BYTES)
      errors.add(new Api.FieldError("size_bytes", "required integer 1.." + MAX_BYTES));
    else size = n.asLong();
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    return new Input(name, r.contentType, r.targetType, target, size);
  }

  public ResponseEntity<?> create(UUID actor, String key, CreateRequest request) {
    var in = normalize(request);
    boolean product = "PRODUCT".equals(in.targetType());
    boolean exists = jdbc.sql("select exists(select 1 from " + (product ? "products" : "collections") + " where id=:id)")
        .param("id", in.targetId()).query(Boolean.class).single();
    if (!exists) throw new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", product ? "PRODUCT_NOT_FOUND" : "COLLECTION_NOT_FOUND", List.of());
    var meta = Api.metadata(tracer);
    var replay = commands.create(actor, "catalog.media.upload.create", key, in, () -> {
      UUID id = UUID.randomUUID();
      var times = jdbc.sql("""
          insert into media_uploads(id,actor_id,target_type,product_id,collection_id,filename,content_type,size_bytes,quarantine_key,put_expires_at,complete_deadline)
          values (:id,:actor,:type,:product,:collection,:filename,:ct,:size,:qkey, now() + make_interval(secs => :ttl), now() + make_interval(secs => :ttl) + interval '24 hours')
          returning put_expires_at, complete_deadline
          """).param("id", id).param("actor", actor).param("type", in.targetType())
          .param("product", product ? in.targetId() : null).param("collection", product ? null : in.targetId())
          .param("filename", in.filename()).param("ct", in.contentType()).param("size", (int) in.sizeBytes())
          .param("qkey", "quarantine/" + id + "/raw").param("ttl", (double) PUT_TTL_SECONDS)
          .query((rs, i) -> new Instant[] {rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()}).single();
      audit.record(actor, "catalog.media.upload.create", "media_upload", id, null, null,
          Map.of("target_type", in.targetType(), "target_id", in.targetId(), "content_type", in.contentType(), "size_bytes", in.sizeBytes()),
          UUID.fromString(meta.requestId()));
      return new AdminCommands.Result(id, 201, new Descriptor(id, times[0], times[1], in.contentType(), in.sizeBytes()));
    });
    // Signer runs after commit and never extends past put_expires_at.
    var d = json.treeToValue(replay.data(), Descriptor.class);
    // The row is authoritative for the deadline; the cached descriptor only identifies the upload.
    Instant expires = jdbc.sql("select put_expires_at from media_uploads where id=:id").param("id", d.uploadId())
        .query((rs, i) -> rs.getTimestamp(1).toInstant()).optional().orElse(Instant.EPOCH);
    var ttl = Duration.between(Instant.now(), expires);
    if (ttl.compareTo(Duration.ofSeconds(1)) < 0)
      throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "UPLOAD_URL_EXPIRED", List.of());
    URI url = storage.presignPut("quarantine/" + d.uploadId() + "/raw", d.contentType(), d.sizeBytes(), ttl);
    return Api.ok(HttpStatus.valueOf(replay.status()), new Intent(d.uploadId(), expires, expires.plus(Duration.ofHours(24)), url,
        Map.of("Content-Type", d.contentType(), "Content-Length", d.sizeBytes())), meta);
  }

  public StatusView status(UUID actor, UUID uploadId) {
    var view = jdbc.sql("""
        select u.id, u.target_type, coalesce(u.product_id, u.collection_id) target_id, u.state, u.put_expires_at,
               u.complete_deadline, u.lease_until, u.reason_code, a.availability
        from media_uploads u left join media_assets a on a.id = u.id
        where u.id=:id and u.actor_id=:actor
        """).param("id", uploadId).param("actor", actor).query((rs, i) -> {
      var lease = rs.getTimestamp("lease_until");
      return new StatusView(rs.getObject("id", UUID.class), rs.getString("target_type"), rs.getObject("target_id", UUID.class),
          rs.getString("state"), rs.getTimestamp("put_expires_at").toInstant(), rs.getTimestamp("complete_deadline").toInstant(),
          lease == null ? null : lease.toInstant(), rs.getString("reason_code"), null, rs.getString("availability"));
    }).optional().orElseThrow(() -> new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "UPLOAD_NOT_FOUND", List.of()));
    if (!"APPROVED".equals(view.state())) return view;
    return new StatusView(view.uploadId(), view.targetType(), view.targetId(), view.state(), view.putExpiresAt(),
        view.completeDeadline(), view.leaseUntil(), view.reasonCode(), toAsset(uploadId), view.assetAvailability());
  }

  /** Shared with complete (Task 5). Null when no asset row exists. */
  AssetView toAsset(UUID id) {
    return jdbc.sql("""
        select image_content_type, image_size_bytes, image_width, image_height, image_sha256,
               thumb_content_type, thumb_size_bytes, thumb_width, thumb_height, thumb_sha256
        from media_assets where id=:id
        """).param("id", id).query((rs, i) -> new AssetView(id,
        new RenditionView(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getInt(4), rs.getString(5).strip()),
        new RenditionView(rs.getString(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getString(10).strip()),
        "/api/v1/catalog/images/" + id + "?kind=image", "/api/v1/catalog/images/" + id + "?kind=thumb")).optional().orElse(null);
  }
}
