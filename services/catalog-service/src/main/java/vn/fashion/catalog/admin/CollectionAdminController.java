package vn.fashion.catalog.admin;

import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.fashion.catalog.web.AdminAuth;
import vn.fashion.catalog.web.Api;

@RestController
@RequestMapping("/admin/api/v1/catalog/collections")
public class CollectionAdminController {
  private final CollectionAdminService service;
  private final AdminAuth auth;
  private final Tracer tracer;
  public CollectionAdminController(CollectionAdminService service, AdminAuth auth, Tracer tracer) {
    this.service = service; this.auth = auth; this.tracer = tracer;
  }
  @GetMapping public ResponseEntity<Api.Response<CollectionAdminService.Page>> list(
      @RequestHeader(value="Authorization", required=false) String authorization,
      @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, @RequestParam(required=false) String status) {
    auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.list(page, size, status), tracer);
  }
  @GetMapping("/{id}") public ResponseEntity<Api.Response<CollectionAdminService.CollectionData>> load(
      @RequestHeader(value="Authorization", required=false) String authorization, @PathVariable UUID id) {
    auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.load(id), tracer);
  }
}
