package vn.fashion.catalog.admin;

import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import vn.fashion.catalog.web.AdminAuth;
import vn.fashion.catalog.web.Api;

@RestController
@RequestMapping("/admin/api/v1/catalog")
public class TaxonomyController {
  private final TaxonomyService service;
  private final AdminAuth auth;
  private final AdminCommands commands;
  private final Tracer tracer;
  public TaxonomyController(TaxonomyService service, AdminAuth auth, AdminCommands commands, Tracer tracer) {
    this.service = service; this.auth = auth; this.commands = commands; this.tracer = tracer;
  }
  @GetMapping("/categories") public ResponseEntity<Api.Response<List<TaxonomyService.Category>>> categories(@RequestHeader(value="Authorization", required=false) String authorization) {
    auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.categories(), tracer);
  }
  @GetMapping("/brands") public ResponseEntity<Api.Response<List<TaxonomyService.Brand>>> brands(@RequestHeader(value="Authorization", required=false) String authorization) {
    auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.brands(), tracer);
  }
  @PostMapping("/categories") public ResponseEntity<Api.Response<JsonNode>> createCategory(@RequestHeader(value="Authorization", required=false) String authorization,
      @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody TaxonomyService.CategoryInput input) {
    UUID actor = auth.requireCatalogWriter(authorization); var request = service.normalize(input, true);
    var replay = commands.create(actor, "catalog.category.create", key, request, () -> {
      var result = service.createCategory(actor, request); return new AdminCommands.Result(result.id(), 201, result);
    });
    return Api.ok(HttpStatus.valueOf(replay.status()), replay.data(), tracer);
  }
  @PutMapping("/categories/{id}") public ResponseEntity<Api.Response<TaxonomyService.Category>> updateCategory(@RequestHeader(value="Authorization", required=false) String authorization,
      @PathVariable UUID id, @RequestBody TaxonomyService.CategoryInput request) {
    UUID actor = auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.updateCategory(actor, id, service.normalize(request, false)), tracer);
  }
  @PostMapping("/brands") public ResponseEntity<Api.Response<JsonNode>> createBrand(@RequestHeader(value="Authorization", required=false) String authorization,
      @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody TaxonomyService.BrandInput input) {
    UUID actor = auth.requireCatalogWriter(authorization); var request = service.normalize(input, true);
    var replay = commands.create(actor, "catalog.brand.create", key, request, () -> {
      var result = service.createBrand(actor, request); return new AdminCommands.Result(result.id(), 201, result);
    });
    return Api.ok(HttpStatus.valueOf(replay.status()), replay.data(), tracer);
  }
  @PutMapping("/brands/{id}") public ResponseEntity<Api.Response<TaxonomyService.Brand>> updateBrand(@RequestHeader(value="Authorization", required=false) String authorization,
      @PathVariable UUID id, @RequestBody TaxonomyService.BrandInput request) {
    UUID actor = auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.updateBrand(actor, id, service.normalize(request, false)), tracer);
  }
}
