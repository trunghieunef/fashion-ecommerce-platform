package vn.fashion.catalog.admin;

import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.fashion.catalog.web.AdminAuth;
import vn.fashion.catalog.web.Api;

@RestController
@RequestMapping("/admin/api/v1/catalog")
public class MediaAttachController {
  private final MediaAttachService service;
  private final AdminAuth auth;
  private final Tracer tracer;
  public MediaAttachController(MediaAttachService service, AdminAuth auth, Tracer tracer) {
    this.service = service; this.auth = auth; this.tracer = tracer;
  }
  @GetMapping("/products/{id}/images") public ResponseEntity<Api.Response<MediaAttachService.ProductImages>> product(
      @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id) {
    auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.getProduct(id), tracer);
  }
  @PutMapping("/products/{id}/images") public ResponseEntity<Api.Response<MediaAttachService.ProductImages>> putProduct(
      @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id,
      @RequestBody MediaAttachService.ProductImagesRequest input) {
    UUID actor = auth.requireCatalogWriter(authorization); var meta = Api.metadata(tracer);
    return Api.ok(HttpStatus.OK, service.putProduct(actor, id, input, UUID.fromString(meta.requestId())), meta);
  }
  @GetMapping("/collections/{id}/images") public ResponseEntity<Api.Response<MediaAttachService.CollectionImages>> collection(
      @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id) {
    auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.getCollection(id), tracer);
  }
  @PutMapping("/collections/{id}/images") public ResponseEntity<Api.Response<MediaAttachService.CollectionImages>> putCollection(
      @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id,
      @RequestBody MediaAttachService.CollectionImagesRequest input) {
    UUID actor = auth.requireCatalogWriter(authorization); var meta = Api.metadata(tracer);
    return Api.ok(HttpStatus.OK, service.putCollection(actor, id, input, UUID.fromString(meta.requestId())), meta);
  }
}
