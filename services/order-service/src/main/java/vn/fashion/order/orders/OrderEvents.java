package vn.fashion.order.orders;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

/**
 * order.events (and NOTIFY_ORDER_* for NOT-01) through the outbox, routed by contracts/events/registry.json. No address or
 * contact (PII) in order.events; NOTIFY_* carry the recipient email, name, masked phone and address line the email needs
 * (notification does not read the order DB; 05 section 11.2, 08 NOT-01).
 */
@Component
public class OrderEvents {
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final JsonNode routes;

  public OrderEvents(OutboxRepository outbox, ObjectMapper json) {
    this.outbox = outbox; this.json = json;
    try (var resource = OrderEvents.class.getResourceAsStream("/contracts/events/registry.json")) {
      if (resource == null) throw new IllegalStateException("Event registry missing");
      this.routes = json.readTree(resource);
    } catch (IOException e) { throw new IllegalStateException("Cannot read event registry", e); }
  }

  /** ORDER_CREATED (OrderState): appended in the create transaction. */
  public void created(OrderView view, UUID userId, String correlationId) { lifecycle("ORDER_CREATED", view, userId, null, correlationId); }

  /** ORDER_CREATED/ORDER_CONFIRMED (OrderState) or ORDER_CANCELLED (OrderCancelled, with reason), in the transition's transaction. */
  public void lifecycle(String type, OrderView view, UUID userId, String reason, String correlationId) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", view.orderId().toString());
    payload.put("order_no", view.orderNo());
    payload.put("user_id", userId == null ? null : userId.toString());
    payload.put("status", view.status());
    payload.put("order_version", view.version());
    payload.put("items", items(view));
    payload.put("total_amount", view.totals().totalAmount());
    payload.put("currency", view.totals().currency());
    payload.put("method", view.method());
    if (reason != null) payload.put("reason", reason);
    append(type, view, payload, correlationId);
  }

  /** ORDER_COMPLETED (OrderCompleted, ORD-03 spec section 6), in the transaction of the SHIPPING -> DELIVERED move. */
  public void completed(OrderView view, UUID userId, Instant deliveredAt, String correlationId) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", view.orderId().toString());
    payload.put("order_no", view.orderNo());
    payload.put("user_id", userId == null ? null : userId.toString());
    payload.put("order_version", view.version());
    payload.put("items", items(view));
    payload.put("delivered_at", deliveredAt.toString());
    append("ORDER_COMPLETED", view, payload, correlationId);
  }

  private static java.util.List<Map<String, Object>> items(OrderView view) {
    return view.items().stream()
        .map(i -> Map.<String, Object>of("variant_id", i.variantId().toString(), "sku", i.sku(), "quantity", i.quantity())).toList();
  }

  /**
   * NOTIFY_ORDER_* (notification-events schema NotifyOrder). The registry names order_id as aggregate_id, but the body has no
   * order_id (additionalProperties false), so the id is passed in; the partition key (dedupe_key) is read from the payload.
   */
  public void notify(String type, UUID orderId, long version, Map<String, Object> payload, String correlationId) {
    var route = routes.required(type);
    outbox.append(new OutboxEvent(UUID.randomUUID(), "order", orderId.toString(), version, type, 1, route.required("topic").asString(),
        (String) payload.get(route.required("partition_key").asString()), correlationId, json.writeValueAsString(payload)));
  }

  private void append(String type, OrderView view, Map<String, Object> payload, String correlationId) {
    var route = routes.required(type);
    outbox.append(new OutboxEvent(UUID.randomUUID(), "order", (String) payload.get(route.required("aggregate_id").asString()),
        view.version(), type, 1, route.required("topic").asString(),
        (String) payload.get(route.required("partition_key").asString()), correlationId, json.writeValueAsString(payload)));
  }
}
