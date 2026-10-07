package vn.fashion.catalog.media;

import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.fashion.catalog.web.AdminAuth;
import vn.fashion.catalog.web.Api;

@RestController
@RequestMapping("/admin/api/v1/catalog/images/uploads")
public class UploadController {
  private final UploadService service;
  private final AdminAuth auth;
  private final Tracer tracer;
  public UploadController(UploadService service, AdminAuth auth, Tracer tracer) {
    this.service = service; this.auth = auth; this.tracer = tracer;
  }
  @PostMapping public ResponseEntity<?> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody UploadService.CreateRequest input) {
    UUID actor = auth.requireCatalogWriter(authorization);
    return service.create(actor, key, input);
  }
  @GetMapping("/{uploadId}") public ResponseEntity<Api.Response<UploadService.StatusView>> status(
      @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID uploadId) {
    UUID actor = auth.requireCatalogWriter(authorization);
    return Api.ok(HttpStatus.OK, service.status(actor, uploadId), tracer);
  }
  @PostMapping("/{uploadId}/complete") public ResponseEntity<?> complete(
      @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID uploadId) {
    return service.complete(auth.requireCatalogWriter(authorization), uploadId);
  }
}
