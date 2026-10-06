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
@RequestMapping("/admin/api/v1/catalog/products")
public class ProductAdminController {
  private final ProductAdminService service;
  private final AdminAuth auth;
  private final AdminCommands commands;
  private final Tracer tracer;
  public ProductAdminController(ProductAdminService service, AdminAuth auth, AdminCommands commands, Tracer tracer) {
    this.service = service; this.auth = auth; this.commands = commands; this.tracer = tracer;
  }
  @GetMapping public ResponseEntity<Api.Response<ProductAdminService.ProductPage>> list(@RequestHeader(value="Authorization", required=false) String authorization,
      @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, @RequestParam(required=false) String status) {
    auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.list(page, size, status), tracer);
  }
  @GetMapping("/{id}") public ResponseEntity<Api.Response<ProductAdminService.Product>> load(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable UUID id) {
    auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.load(id), tracer);
  }
  @PostMapping public ResponseEntity<Api.Response<JsonNode>> create(@RequestHeader(value="Authorization", required=false) String authorization,
      @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody ProductAdminService.Input input) {
    UUID actor = auth.requireCatalogWriter(authorization); var request = service.normalize(input, true);
    var result = commands.create(actor, "catalog.product.create", key, request, () -> {
      var p = service.create(actor, request); return new AdminCommands.Result(p.id(), 201, p);
    });
    return Api.ok(HttpStatus.valueOf(result.status()), result.data(), tracer);
  }
  @PutMapping("/{id}") public ResponseEntity<Api.Response<ProductAdminService.Product>> update(@RequestHeader(value="Authorization", required=false) String authorization,
      @PathVariable UUID id, @RequestBody ProductAdminService.Input input) {
    UUID actor = auth.requireCatalogWriter(authorization); return Api.ok(HttpStatus.OK, service.update(actor, id, service.normalize(input, false)), tracer);
  }
}
