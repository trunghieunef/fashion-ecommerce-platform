package vn.fashion.catalog.admin;

import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import vn.fashion.catalog.web.AdminAuth;
import vn.fashion.catalog.web.Api;

@RestController
@RequestMapping("/admin/api/v1/catalog")
public class VariantAdminController {
  private final VariantAdminService service;
  private final AdminAuth auth;
  private final Tracer tracer;
  public VariantAdminController(VariantAdminService service, AdminAuth auth, Tracer tracer) { this.service = service; this.auth = auth; this.tracer = tracer; }
  @PostMapping("/products/{id}/variants") public ResponseEntity<Api.Response<JsonNode>> create(@RequestHeader(value="Authorization", required=false) String authorization,
      @RequestHeader(value="Idempotency-Key", required=false) String key, @PathVariable UUID id, @RequestBody VariantAdminService.Input input) {
    UUID actor = auth.requireCatalogWriter(authorization); var meta = Api.metadata(tracer);
    var result = service.create(actor, id, key, service.normalize(input), meta.traceId(), UUID.fromString(meta.requestId()));
    return Api.ok(HttpStatus.valueOf(result.status()), result.data(), meta);
  }
  @PutMapping("/variants/{id}") public ResponseEntity<Api.Response<Variant>> update(@RequestHeader(value="Authorization", required=false) String authorization,
      @PathVariable UUID id, @RequestBody VariantAdminService.Update input) {
    UUID actor = auth.requireCatalogWriter(authorization); var meta = Api.metadata(tracer);
    return Api.ok(HttpStatus.OK, service.update(actor, id, input, UUID.fromString(meta.requestId())), meta);
  }
}
