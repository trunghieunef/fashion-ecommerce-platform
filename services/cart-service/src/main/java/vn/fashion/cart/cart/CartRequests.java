package vn.fashion.cart.cart;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import vn.fashion.cart.web.Api;

/** Strict request parsing (spec section 5): quantity 1..99, expected_version required, canonical UUIDs. */
public final class CartRequests {
  public record Add(UUID variantId, int quantity, long expectedVersion) { }
  public record Put(int quantity, long expectedVersion) { }
  private CartRequests() { }

  public static Add add(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    UUID variant = uuid(body.path("variant_id"), "variant_id", errors);
    int quantity = quantity(body.path("quantity"), errors);
    long version = version(body.path("expected_version"));
    if (!errors.isEmpty()) throw invalid(errors);
    return new Add(variant, quantity, version);
  }
  public static Put put(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    int quantity = quantity(body.path("quantity"), errors);
    long version = version(body.path("expected_version"));
    if (!errors.isEmpty()) throw invalid(errors);
    return new Put(quantity, version);
  }
  public static long version(String raw) {
    if (raw == null || raw.isBlank()) throw versionRequired();
    try { long v = Long.parseLong(raw); if (v >= 0) return v; } catch (NumberFormatException ignored) { }
    throw invalid(List.of(new Api.FieldError("expected_version", "must be a non-negative integer")));
  }
  public static void requireKey(String key) {
    if (key == null || key.isBlank() || key.length() > 128)
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_KEY",
          List.of(new Api.FieldError("Idempotency-Key", "must be 1..128 characters")));
  }

  private static long version(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) throw versionRequired();
    if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0)
      throw invalid(List.of(new Api.FieldError("expected_version", "must be a non-negative integer")));
    return node.longValue();
  }
  private static int quantity(JsonNode node, List<Api.FieldError> errors) {
    if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1 || node.intValue() > 99) {
      errors.add(new Api.FieldError("quantity", "must be an integer 1..99"));
      return 0;
    }
    return node.intValue();
  }
  private static UUID uuid(JsonNode node, String field, List<Api.FieldError> errors) {
    try {
      if (node.isString()) {
        UUID id = UUID.fromString(node.asString());
        if (id.toString().equals(node.asString())) return id;
      }
    } catch (IllegalArgumentException ignored) { }
    errors.add(new Api.FieldError(field, "must be a lowercase UUID"));
    return null;
  }
  private static Api.Problem versionRequired() {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "VERSION_REQUIRED",
        List.of(new Api.FieldError("expected_version", "is required")));
  }
  static Api.Problem invalid(List<Api.FieldError> errors) {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
  }
}
