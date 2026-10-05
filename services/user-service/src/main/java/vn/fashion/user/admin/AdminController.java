package vn.fashion.user.admin;

import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.fashion.user.web.Api;
import vn.fashion.user.web.MemberAuth;

/** Admin users, roles and lock (03 section 3); every operation needs permission user.manage. */
@RestController
@RequestMapping("/admin/api/v1/users")
public class AdminController {
  static final String PERMISSION = "user.manage";

  private final AdminService admin;
  private final MemberAuth auth;
  private final Tracer tracer;

  public AdminController(AdminService admin, MemberAuth auth, Tracer tracer) {
    this.admin = admin;
    this.auth = auth;
    this.tracer = tracer;
  }

  public record RolesChange(List<String> roles, String reason, Long expectedVersion) {
  }

  public record LockRequest(String reason, Long expectedVersion) {
  }

  @GetMapping
  public ResponseEntity<Api.Response<AdminService.UserPage>> list(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    auth.requirePermission(authorization, PERMISSION);
    var errors = new ArrayList<Api.FieldError>();
    if (page < 1) {
      errors.add(new Api.FieldError("page", "must be at least 1"));
    }
    if (size < 1 || size > 100) {
      errors.add(new Api.FieldError("size", "must be 1..100"));
    }
    requireValid(errors, "INVALID_PAGE");
    return Api.ok(HttpStatus.OK, admin.list(page, size), tracer);
  }

  @PutMapping("/{id}/roles")
  public ResponseEntity<Api.Response<AdminService.AdminUser>> changeRoles(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable UUID id, @RequestBody RolesChange request) {
    UUID actor = auth.requirePermission(authorization, PERMISSION);
    var errors = new ArrayList<Api.FieldError>();
    Set<String> known = admin.roleCodes();
    if (request.roles() == null || request.roles().stream().anyMatch(r -> r == null || !known.contains(r))) {
      errors.add(new Api.FieldError("roles", "must be a list of " + new java.util.TreeSet<>(known)));
    }
    validateAudited(request.reason(), request.expectedVersion(), errors);
    requireValid(errors, "INVALID_ROLE_CHANGE");
    var metadata = Api.metadata(tracer);
    return Api.ok(HttpStatus.OK, admin.changeRoles(actor, id, Set.copyOf(request.roles()), request.reason().strip(),
        request.expectedVersion(), UUID.fromString(metadata.requestId())), metadata);
  }

  @PostMapping("/{id}/lock")
  public ResponseEntity<Api.Response<JsonNode>> lock(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @PathVariable UUID id, @RequestBody LockRequest request) {
    UUID actor = auth.requirePermission(authorization, PERMISSION);
    var errors = new ArrayList<Api.FieldError>();
    if (key == null || key.isBlank() || key.length() > 128) {
      errors.add(new Api.FieldError("Idempotency-Key", "header of 1..128 characters is required"));
    }
    validateAudited(request.reason(), request.expectedVersion(), errors);
    requireValid(errors, "INVALID_LOCK");
    var metadata = Api.metadata(tracer);
    return Api.ok(HttpStatus.OK, admin.lock(actor, id, request.reason().strip(), request.expectedVersion(), key,
        UUID.fromString(metadata.requestId())), metadata);
  }

  private static void validateAudited(String reason, Long expectedVersion, List<Api.FieldError> errors) {
    if (reason == null || reason.isBlank() || reason.length() > 500) {
      errors.add(new Api.FieldError("reason", "must be 1..500 characters"));
    }
    if (expectedVersion == null) {
      errors.add(new Api.FieldError("expected_version", "is required"));
    }
  }

  private static void requireValid(List<Api.FieldError> errors, String message) {
    if (!errors.isEmpty()) {
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, errors);
    }
  }
}
