package vn.fashion.order.admin;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.order.web.Api;

/** OPS order reads (spec section 5). pii = the caller holds order.fulfill: contact, address, note and history reasons. */
@Component
public class OrderAdminQueries {
  static final Set<String> STATUSES = Set.of("PENDING", "WAITING_PAYMENT", "CONFIRMED", "PAID", "PACKING", "SHIPPING",
      "DELIVERED", "CANCELLING", "CANCELLED", "REFUNDING", "REFUNDED", "RETURNING", "RETURNED");
  private static final Set<String> PII = Set.of("contact_phone", "contact_email", "shipping_address", "note", "reason");
  /** The order_no shape of OrderService (ORD + yyyyMM + 6 digits); any other q is a phone. */
  private static final Pattern ORDER_NO = Pattern.compile("ORD\\d{12}");
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public OrderAdminQueries(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

  public static boolean isPhoneQuery(String q) { return q != null && !ORDER_NO.matcher(q).matches(); }

  public Map<String, Object> page(String status, String q, String cursor, int limit, boolean pii) {
    if (status != null && !STATUSES.contains(status)) throw invalid("status");
    Instant afterAt = null;
    UUID afterId = null;
    if (cursor != null) {
      try {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
        afterAt = Instant.parse(parts[0]);
        afterId = UUID.fromString(parts[1]);
      } catch (RuntimeException e) { throw invalid("cursor"); }
    }
    boolean byNo = q != null && !isPhoneQuery(q);
    var rows = jdbc.sql("""
        select id, order_no, status, version, payment_method as method, total_amount, created_at, expires_at, confirmed_at, delivered_at,
          contact_phone, contact_email
        from orders where (cast(:status as text) is null or status = :status)
          and (cast(:q as text) is null or (:byNo and order_no = :q) or (not :byNo and contact_phone = :q))
          and (cast(:at as timestamptz) is null or (created_at, id) < (cast(:at as timestamptz), cast(:id as uuid)))
        order by created_at desc, id desc limit :n""")
        .param("status", status).param("q", q).param("byNo", byNo)
        .param("at", afterAt == null ? null : Timestamp.from(afterAt)).param("id", afterId).param("n", limit + 1)
        .query().listOfRows().stream().map(r -> row(r, pii)).toList();
    String next = null;
    if (rows.size() > limit) {
      var last = rows.get(limit - 1);
      next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("created_at") + "|" + last.get("id")).getBytes(StandardCharsets.UTF_8));
      rows = rows.subList(0, limit);
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("items", rows.stream().map(r -> { var m = new LinkedHashMap<>(r); m.remove("id"); return m; }).toList());
    out.put("next_cursor", next);
    return out;
  }

  public Map<String, Object> detail(String orderNo, boolean pii) {
    var order = jdbc.sql("""
        select id, order_no, user_id, status, version, payment_method as method, currency, subtotal, discount_amount, shipping_fee,
          shipping_discount, cod_fee, total_amount, created_at, expires_at, confirmed_at, shipped_at, delivered_at, cancel_reason,
          contact_phone, contact_email, shipping_address::text as shipping_address, note
        from orders where order_no = :no""").param("no", orderNo).query().listOfRows().stream().findFirst()
        .map(r -> row(r, pii))
        .orElseThrow(() -> new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "ORDER_NOT_FOUND", List.of()));
    UUID id = (UUID) order.remove("id");
    order.put("items", jdbc.sql("""
        select variant_id, sku, name, size, color, quantity, unit_price, line_total from order_items
        where order_id = :id order by sku, id""").param("id", id).query().listOfRows().stream().map(r -> row(r, true)).toList());
    order.put("history", jdbc.sql("""
        select from_status, to_status, actor_type, actor_id, request_id, created_at, reason from order_status_history
        where order_id = :id order by created_at, id""").param("id", id).query().listOfRows().stream().map(r -> row(r, pii)).toList());
    order.put("saga", jdbc.sql("""
        select intent, step, status, inventory_state, payment_state, payment_id, shipment_id, retry_count, next_retry_at, last_error
        from order_sagas where order_id = :id""").param("id", id).query().listOfRows().stream().findFirst().map(r -> row(r, true)).orElse(null));
    return order;
  }

  /** Ordered row: timestamps as Instant, shipping_address text as JSON; PII columns dropped (a history reason becomes null). */
  private Map<String, Object> row(Map<String, Object> source, boolean pii) {
    var out = new LinkedHashMap<String, Object>();
    source.forEach((k, v) -> {
      if (PII.contains(k) && !pii) { if (k.equals("reason")) out.put(k, null); return; }
      out.put(k, v instanceof Timestamp t ? t.toInstant() : k.equals("shipping_address") && v != null ? json.readTree((String) v) : v);
    });
    return out;
  }

  private static Api.Problem invalid(String field) {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_PARAMETER", List.of(new Api.FieldError(field, "has an invalid value")));
  }
}
