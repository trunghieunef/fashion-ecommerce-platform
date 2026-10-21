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
  private static final Set<String> PII = Set.of("contact_phone", "contact_email", "shipping_address", "note", "reason");
  /** The order_no shape of OrderService (ORD + yyyyMM + 6 digits, any case); any other q is a phone. */
  private static final Pattern ORDER_NO = Pattern.compile("ORD\\d{12}", Pattern.CASE_INSENSITIVE);
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public OrderAdminQueries(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

  /** A blank q is no filter (PR #34 review M3). */
  public static String query(String q) { return q == null || q.isBlank() ? null : q.strip(); }
  public static boolean isPhoneQuery(String q) { return query(q) != null && !ORDER_NO.matcher(query(q)).matches(); }
  /** Phone as digits with 84 written as 0, so +84 912 345 678 finds 0912345678 (PR #34 review M2). */
  static String phone(String q) {
    String digits = q.replaceAll("[^0-9]", "");
    return digits.startsWith("84") && digits.length() == 11 ? "0" + digits.substring(2) : digits;
  }

  public Map<String, Object> page(String status, String q, String cursor, int limit, boolean pii) {
    if (status != null && !vn.fashion.order.orders.OrderService.STATUSES.contains(status)) throw invalid("status");
    Instant afterAt = null;
    UUID afterId = null;
    if (cursor != null) {
      try {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
        afterAt = Instant.parse(parts[0]);
        afterId = UUID.fromString(parts[1]);
      } catch (RuntimeException e) { throw invalid("cursor"); }
    }
    q = query(q);
    boolean byNo = q != null && !isPhoneQuery(q);
    if (q != null) q = byNo ? q.toUpperCase(java.util.Locale.ROOT) : phone(q);
    var rows = jdbc.sql("""
        select id, order_no, status, version, payment_method as method, total_amount, created_at, expires_at, confirmed_at, delivered_at,
          contact_phone, contact_email
        from orders where (cast(:status as text) is null or status = :status)
          and (cast(:q as text) is null or (:byNo and order_no = :q) or (not :byNo and :q <> '' and :q = (
            case when regexp_replace(contact_phone, '[^0-9]', '', 'g') ~ '^84[0-9]{9}$'
              then '0' || substr(regexp_replace(contact_phone, '[^0-9]', '', 'g'), 3)
              else regexp_replace(contact_phone, '[^0-9]', '', 'g') end)))
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
        select id as order_item_id, variant_id, sku, name, size, color, quantity, unit_price, line_total from order_items
        where order_id = :id order by sku, id""").param("id", id).query().listOfRows().stream().map(r -> row(r, true)).toList());
    order.put("history", jdbc.sql("""
        select from_status, to_status, actor_type, actor_id, request_id, created_at, reason from order_status_history
        where order_id = :id order by created_at, id""").param("id", id).query().listOfRows().stream().map(r -> row(r, pii)).toList());
    order.put("saga", jdbc.sql("""
        select intent, step, status, inventory_state, payment_state, payment_id, shipment_id, retry_count, next_retry_at, last_error
        from order_sagas where order_id = :id""").param("id", id).query().listOfRows().stream().findFirst().map(r -> row(r, true)).orElse(null));
    order.put("returns", returns(id, pii));
    return order;
  }

  /** ORD-04 return slips of the order, oldest first, each with its counted items (the slip reason is PII like history). */
  public List<Map<String, Object>> returns(UUID orderId, boolean pii) {
    return jdbc.sql("""
        select id as return_id, status, reason, evidence_ref, actor_id, created_at, updated_at from order_returns
        where order_id = :id order by created_at, id""").param("id", orderId).query().listOfRows().stream().map(r -> {
          var slip = row(r, pii);
          // Free text typed by OPS (evidence, condition) may name the customer: hidden like reason (PR #35 review M6).
          if (!pii) slip.put("evidence_ref", null);
          var items = jdbc.sql("""
              select order_item_id, received_quantity, restock_quantity, condition from order_return_items
              where return_id = :id order by order_item_id""").param("id", r.get("return_id")).query().listOfRows();
          if (!pii) items = items.stream().map(i -> { var m = new LinkedHashMap<>(i); m.put("condition", null); return (Map<String, Object>) m; }).toList();
          slip.put("items", items);
          return slip;
        }).toList();
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
