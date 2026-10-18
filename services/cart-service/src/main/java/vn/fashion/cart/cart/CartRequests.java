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
    int quantity = quantity(body.path("quantity"), "quantity", errors);
    long version = version(body.path("expected_version"));
    if (!errors.isEmpty()) throw invalid(errors);
    return new Add(variant, quantity, version);
  }
  public static Put put(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    int quantity = quantity(body.path("quantity"), "quantity", errors);
    long version = version(body.path("expected_version"));
    if (!errors.isEmpty()) throw invalid(errors);
    return new Put(quantity, version);
  }
  /** CART-02 merge: both expected versions are required. */
  public record Merge(UUID guestCartId, long guestExpectedVersion, long memberExpectedVersion) { }
  public static Merge merge(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    UUID guest = uuid(body.path("guest_cart_id"), "guest_cart_id", errors);
    long gv = requiredVersion(body.path("guest_expected_version"), "guest_expected_version", errors);
    long mv = requiredVersion(body.path("member_expected_version"), "member_expected_version", errors);
    if (!errors.isEmpty()) throw invalid(errors);
    return new Merge(guest, gv, mv);
  }
  private static long requiredVersion(JsonNode node, String field, List<Api.FieldError> errors) {
    if (node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0) return node.longValue();
    errors.add(new Api.FieldError(field, "is required, a non-negative integer"));
    return -1;
  }
  /** CART-03 checkout cleanup: 1..100 distinct items of the order's cart snapshot. */
  public record Cleanup(UUID orderId, List<CleanupItem> items) { }
  public record CleanupItem(UUID itemId, long itemVersion, int quantity) { }
  public static Cleanup cleanup(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    UUID order = uuid(body.path("order_id"), "order_id", errors);
    var items = new ArrayList<CleanupItem>();
    JsonNode list = body.path("items");
    if (!list.isArray() || list.isEmpty() || list.size() > 100) errors.add(new Api.FieldError("items", "must have 1..100 items"));
    else for (JsonNode item : list) {
      UUID id = uuid(item.path("item_id"), "items.item_id", errors);
      JsonNode v = item.path("item_version");
      boolean versionOk = v.isIntegralNumber() && v.canConvertToLong() && v.longValue() >= 0;
      if (!versionOk) errors.add(new Api.FieldError("items.item_version", "must be a non-negative integer"));
      int quantity = quantity(item.path("quantity"), "items.quantity", errors);
      if (id != null && items.stream().anyMatch(i -> id.equals(i.itemId()))) errors.add(new Api.FieldError("items.item_id", "must be distinct"));
      items.add(new CleanupItem(id, versionOk ? v.longValue() : -1, quantity));
    }
    if (!errors.isEmpty()) throw invalid(errors);
    return new Cleanup(order, List.copyOf(items));
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
  private static int quantity(JsonNode node, String field, List<Api.FieldError> errors) {
    if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1 || node.intValue() > 99) {
      errors.add(new Api.FieldError(field, "must be an integer 1..99"));
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
