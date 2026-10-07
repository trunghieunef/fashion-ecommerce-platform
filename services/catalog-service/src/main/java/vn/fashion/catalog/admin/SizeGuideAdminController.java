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
@RequestMapping("/admin/api/v1/catalog/size-guides")
public class SizeGuideAdminController {
  private final SizeGuideAdminService service;
  private final AdminAuth auth;
  private final Tracer tracer;
  public SizeGuideAdminController(SizeGuideAdminService service, AdminAuth auth, Tracer tracer) {
    this.service = service; this.auth = auth; this.tracer = tracer;
  }
  @GetMapping("/{category_id}/{locale}") public ResponseEntity<Api.Response<SizeGuideAdminService.Guide>> load(
      @RequestHeader(value="Authorization", required=false) String authorization,
      @PathVariable("category_id") UUID categoryId, @PathVariable String locale) {
    auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.load(categoryId, locale), tracer);
  }
  @PutMapping("/{category_id}/{locale}") public ResponseEntity<Api.Response<JsonNode>> put(
      @RequestHeader(value="Authorization", required=false) String authorization,
      @RequestHeader(value="Idempotency-Key", required=false) String key,
      @PathVariable("category_id") UUID categoryId, @PathVariable String locale, @RequestBody SizeGuideAdminService.Request input) {
    UUID actor = auth.requireCatalogWriter(authorization); var request = service.normalize(categoryId, locale, input); AdminCommands.requireKey(key);
    var meta = Api.metadata(tracer); var replay = service.put(actor, key, request, UUID.fromString(meta.requestId()));
    return Api.ok(HttpStatus.valueOf(replay.status()), replay.data(), meta);
  }
}
