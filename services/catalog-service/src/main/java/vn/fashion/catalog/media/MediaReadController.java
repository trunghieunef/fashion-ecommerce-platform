package vn.fashion.catalog.media;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import vn.fashion.catalog.web.AdminAuth;
import vn.fashion.catalog.web.Api;

/** Serves approved image bytes. Visibility is decided by the DB only; 304 never touches S3 (spec section 6). */
@RestController
public class MediaReadController {
  private static final String VISIBLE = """
      a.availability = 'AVAILABLE' and (
        exists (select 1 from product_images pi join products p on p.id = pi.product_id
                where pi.asset_id = a.id and p.status = 'ACTIVE')
        or exists (select 1 from lookbook_images li join collections c on c.id = li.collection_id
                where li.asset_id = a.id and c.status = 'ACTIVE'
                  and (c.start_at is null or c.start_at <= now()) and (c.end_at is null or now() < c.end_at)
                  and exists (select 1 from collection_items ci join products p on p.id = ci.product_id
                              where ci.collection_id = c.id and p.status = 'ACTIVE')))""";
  /** Attached assets are visible to every OPS; detached or not yet attached ones only to their uploader. */
  private static final String ADMIN_VISIBLE = """
      a.availability = 'AVAILABLE' and (
        u.actor_id = :actor
        or exists (select 1 from product_images pi where pi.asset_id = a.id)
        or exists (select 1 from lookbook_images li where li.asset_id = a.id))""";
  private static final String COLUMNS = "a.image_key, a.thumb_key, a.image_content_type, a.thumb_content_type, "
      + "a.image_size_bytes, a.thumb_size_bytes, a.image_sha256, a.thumb_sha256";

  /** Runs before argument binding, so every error response (incl. bad UUID) is no-store; success overrides it. */
  @ControllerAdvice(assignableTypes = MediaReadController.class)
  static class NoStoreOnErrors {
    @ModelAttribute void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
  }

  private record Blob(String key, String contentType, long size, String sha256) { }

  private final JdbcClient jdbc;
  private final MediaStorage storage;
  private final AdminAuth auth;
  private final Tracer tracer;

  public MediaReadController(JdbcClient jdbc, MediaStorage storage, AdminAuth auth, Tracer tracer) {
    this.jdbc = jdbc; this.storage = storage; this.auth = auth; this.tracer = tracer;
  }

  @GetMapping("/api/v1/catalog/images/{assetId}")
  public ResponseEntity<?> publicRead(@PathVariable UUID assetId, @RequestParam(required = false) String kind,
      HttpServletRequest request, HttpServletResponse response) {
    boolean thumb = thumb(kind);
    var blob = find("select " + COLUMNS + " from media_assets a where a.id = :id and " + VISIBLE, assetId, null, thumb);
    return serve(blob, "public, max-age=300", true, request, response);
  }

  @GetMapping("/admin/api/v1/catalog/images/{assetId}")
  public ResponseEntity<?> adminRead(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable UUID assetId, @RequestParam(required = false) String kind,
      HttpServletRequest request, HttpServletResponse response) {
    UUID actor = auth.requireCatalogWriter(authorization);
    boolean thumb = thumb(kind);
    var blob = find("select " + COLUMNS + " from media_assets a join media_uploads u on u.id = a.id where a.id = :id and "
        + ADMIN_VISIBLE, assetId, actor, thumb);
    return serve(blob, "private, no-store", false, request, response);
  }

  private static boolean thumb(String kind) {
    if (kind == null || kind.equals("image")) return false;
    if (kind.equals("thumb")) return true;
    throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS",
        List.of(new Api.FieldError("kind", "must be image or thumb")));
  }

  private Blob find(String sql, UUID id, UUID actor, boolean thumb) {
    var spec = jdbc.sql(sql).param("id", id);
    if (actor != null) spec = spec.param("actor", actor);
    return spec.query((rs, n) -> thumb
        ? new Blob(rs.getString("thumb_key"), rs.getString("thumb_content_type"), rs.getLong("thumb_size_bytes"), rs.getString("thumb_sha256"))
        : new Blob(rs.getString("image_key"), rs.getString("image_content_type"), rs.getLong("image_size_bytes"), rs.getString("image_sha256")))
        .optional()
        .orElseThrow(() -> new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "IMAGE_NOT_FOUND", List.of()));
  }

  private ResponseEntity<?> serve(Blob blob, String cacheControl, boolean conditional, HttpServletRequest request,
      HttpServletResponse response) {
    String etag = "\"" + blob.sha256() + "\"";
    String correlation = Api.metadata(tracer).traceId();
    boolean wildcard = conditional && "*".equals(String.valueOf(request.getHeader("If-None-Match")).strip()); // Spring does not match "*"
    if (wildcard) { response.setStatus(HttpStatus.NOT_MODIFIED.value()); response.setHeader("ETag", etag); }
    if (wildcard || conditional && new ServletWebRequest(request, response).checkNotModified(etag)) { // 304 written, S3 untouched
      response.setHeader("Cache-Control", cacheControl);
      response.setHeader("X-Correlation-Id", correlation);
      return null;
    }
    var stream = storage.open(blob.key()).orElseThrow(MediaStorage.Unavailable::new); // before committing the 200
    response.setHeader("Cache-Control", cacheControl);
    var ok = ResponseEntity.ok().contentType(MediaType.parseMediaType(blob.contentType())).contentLength(blob.size());
    if (conditional) ok.eTag(etag); // an ETag would make Spring answer 304 itself; admin preview has no validator
    return ok.header("X-Correlation-Id", correlation)
        .header("X-Content-Type-Options", "nosniff").header("Content-Disposition", "inline")
        .body(new InputStreamResource(stream)); // converter copies then closes the S3 stream
  }
}
