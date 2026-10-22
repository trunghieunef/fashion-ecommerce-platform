package vn.fashion.order.orders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * NOT-01: NOTIFY_ORDER_* on notification.events for COD orders with a contact email, appended in the transaction of the status
 * move (OrderTransitions.move). Carries everything the email renders; the phone is masked and the free-text cancel reason
 * (history) is never read - the category comes from the orders.cancel_reason code only.
 */
@Component
public class OrderNotifications {
  private static final Map<String, String> KINDS = Map.of(
      "PENDING>CONFIRMED", "confirmed", "CONFIRMED>PACKING", "preparing", "*>DELIVERED", "delivered",
      "*>CANCELLED", "cancelled", "*>RETURNED", "returned");
  private final JdbcClient jdbc;
  private final OrderEvents events;

  public OrderNotifications(JdbcClient jdbc, OrderEvents events) { this.jdbc = jdbc; this.events = events; }

  /** Called after the order row is already in status to; writes at most one event, replays dedupe by key at the consumer. */
  public void onTransition(UUID orderId, String from, String to) {
    String kind = KINDS.getOrDefault(from + ">" + to, KINDS.get("*>" + to));
    if (kind == null) return;
    var o = jdbc.sql("""
        select order_no, user_id, contact_email, locale, created_at, subtotal, shipping_fee, total_amount, version, cancel_reason,
          shipping_address ->> 'recipient_name' as name, coalesce(shipping_address ->> 'phone', contact_phone) as phone,
          shipping_address ->> 'address_line' as address
        from orders where id = :id and payment_method = 'COD' and contact_email is not null""")
        .param("id", orderId).query().listOfRows();
    if (o.isEmpty()) return;
    var row = o.get(0);
    var data = new LinkedHashMap<String, Object>();
    data.put("order_no", row.get("order_no"));
    data.put("placed_at", ((java.sql.Timestamp) row.get("created_at")).toInstant().toString());
    data.put("items", jdbc.sql("""
        select name, size, color, quantity, line_total, image_url from order_items where order_id = :id order by id""")
        .param("id", orderId).query().listOfRows());
    data.put("subtotal", row.get("subtotal"));
    data.put("shipping_fee", row.get("shipping_fee"));
    data.put("total", row.get("total_amount"));
    data.put("payment_method", "COD");
    if (kind.equals("confirmed")) {
      data.put("ship_to", Map.of("name", row.get("name"), "phone_masked", mask((String) row.get("phone")), "address", row.get("address")));
    }
    if (kind.equals("cancelled")) data.put("cancel_category", category((String) row.get("cancel_reason")));
    var payload = new LinkedHashMap<String, Object>();
    payload.put("dedupe_key", "order:" + orderId + ":" + kind);
    payload.put("user_id", row.get("user_id") == null ? null : row.get("user_id").toString());
    payload.put("recipient", Map.of("email", row.get("contact_email")));
    payload.put("channel", "EMAIL");
    payload.put("locale", row.get("locale"));
    payload.put("template_key", "ORDER_" + kind.toUpperCase());
    payload.put("data", data);
    events.notify("NOTIFY_ORDER_" + kind.toUpperCase(), orderId, (Long) row.get("version"), payload, "saga:" + orderId);
  }

  /** First 3 and last 3 digits, 0901234123 -> 090****123. */
  static String mask(String phone) { return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3); }

  static String category(String cancelReason) {
    if (cancelReason == null) return "OTHER";
    return switch (cancelReason) {
      case "CUSTOMER_CANCELLED" -> "CUSTOMER";
      case "OPS_CANCELLED" -> "SHOP";
      case "EXPIRED", "ORDER_EXPIRED", "RESERVATION_EXPIRED" -> "EXPIRED";
      case "OUT_OF_STOCK" -> "OUT_OF_STOCK";
      default -> "OTHER";
    };
  }
}
