package vn.fashion.order.quotes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import vn.fashion.order.web.Api;

/** Spec section 5 body, strict: unknown or missing fields are 400 INVALID_FIELDS. */
public record QuoteRequest(UUID cartId, long cartVersion, String method, Quote.Address address, Quote.Contact contact, String note) {
  static final Set<String> METHODS = Set.of("COD", "VNPAY", "MOMO");
  private static final Pattern PHONE = Pattern.compile("\\+?[0-9]{9,15}");
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

  public static QuoteRequest parse(JsonNode body, boolean guest) {
    var errors = new ArrayList<Api.FieldError>();
    unknown(body, Set.of("cart_id", "cart_version", "method", "shipping_address", "contact", "note"), "", errors);
    UUID cart = Api.uuid(body.path("cart_id").asString(null));
    if (cart == null) errors.add(new Api.FieldError("cart_id", "must be a lowercase UUID"));
    JsonNode v = body.path("cart_version");
    if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0) errors.add(new Api.FieldError("cart_version", "must be an integer >= 0"));
    String method = body.path("method").asString(null);
    if (method == null || !METHODS.contains(method)) errors.add(new Api.FieldError("method", "must be COD, VNPAY or MOMO"));
    JsonNode a = body.path("shipping_address");
    Quote.Address address = null;
    if (!a.isObject()) errors.add(new Api.FieldError("shipping_address", "is required"));
    else {
      unknown(a, Set.of("recipient_name", "phone", "province_code", "ward_code", "address_line"), "shipping_address.", errors);
      address = new Quote.Address(text(a, "recipient_name", 100, "shipping_address.", errors), phone(a, "shipping_address.", errors),
          text(a, "province_code", 10, "shipping_address.", errors), text(a, "ward_code", 10, "shipping_address.", errors),
          text(a, "address_line", 255, "shipping_address.", errors));
    }
    JsonNode c = body.path("contact");
    Quote.Contact contact = null;
    if (!c.isObject()) errors.add(new Api.FieldError("contact", "is required"));
    else {
      unknown(c, Set.of("phone", "email"), "contact.", errors);
      String email = c.path("email").isNull() || c.path("email").isMissingNode() ? null : c.path("email").asString(null);
      if (email == null && guest) errors.add(new Api.FieldError("contact.email", "is required for a guest"));
      if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches()))
        errors.add(new Api.FieldError("contact.email", "must be an email address"));
      contact = new Quote.Contact(phone(c, "contact.", errors), email);
    }
    String note = null;
    JsonNode n = body.path("note");
    if (!n.isMissingNode() && !n.isNull()) {
      if (!n.isString() || n.asString().length() > 500) errors.add(new Api.FieldError("note", "must be text of at most 500"));
      else note = n.asString().isBlank() ? null : n.asString().strip();
    }
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    return new QuoteRequest(cart, v.longValue(), method, address, contact, note);
  }

  static void unknown(JsonNode node, Set<String> allowed, String prefix, List<Api.FieldError> errors) {
    for (String name : node.propertyNames()) if (!allowed.contains(name)) errors.add(new Api.FieldError(prefix + name, "is not allowed"));
  }
  private static String text(JsonNode node, String field, int max, String prefix, List<Api.FieldError> errors) {
    JsonNode v = node.path(field);
    if (!v.isString() || v.asString().isBlank() || v.asString().strip().length() > max) {
      errors.add(new Api.FieldError(prefix + field, "must be text of 1.." + max));
      return null;
    }
    return v.asString().strip();
  }
  private static String phone(JsonNode node, String prefix, List<Api.FieldError> errors) {
    String p = node.path("phone").asString(null);
    if (p == null || !PHONE.matcher(p).matches()) { errors.add(new Api.FieldError(prefix + "phone", "must be 9-15 digits")); return null; }
    return p;
  }
}
