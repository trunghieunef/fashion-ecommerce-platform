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
@RequestMapping("/admin/api/v1/catalog/collections")
public class CollectionAdminController {
  private final CollectionAdminService service;
  private final AdminAuth auth;
  private final Tracer tracer;
  private final AdminCommands commands;
  public CollectionAdminController(CollectionAdminService service, AdminAuth auth, Tracer tracer, AdminCommands commands) {
    this.service = service; this.auth = auth; this.tracer = tracer; this.commands = commands;
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
  @PostMapping public ResponseEntity<Api.Response<JsonNode>> create(
      @RequestHeader(value="Authorization", required=false) String authorization,
      @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody CollectionAdminService.CreateRequest input) {
    UUID actor = auth.requireCatalogWriter(authorization); var request = service.normalize(input); var meta = Api.metadata(tracer);
    var replay = commands.create(actor, "catalog.collection.create", key, request, () -> {
      var result = service.create(actor, request, UUID.fromString(meta.requestId())); return new AdminCommands.Result(result.id(), 201, result);
    });
    return Api.ok(HttpStatus.valueOf(replay.status()), replay.data(), meta);
  }
  @PutMapping("/{id}") public ResponseEntity<Api.Response<CollectionAdminService.CollectionData>> update(
      @RequestHeader(value="Authorization", required=false) String authorization, @PathVariable UUID id,
      @RequestBody CollectionAdminService.UpdateRequest input) {
    UUID actor = auth.requireCatalogWriter(authorization); var request = service.normalize(input); var meta = Api.metadata(tracer);
    return Api.ok(HttpStatus.OK, service.update(actor, id, request, UUID.fromString(meta.requestId())), meta);
  }
}
