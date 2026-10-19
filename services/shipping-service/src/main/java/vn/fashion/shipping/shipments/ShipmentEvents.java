package vn.fashion.shipping.shipments;

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

/** shipping.events through the outbox, routed by contracts/events/registry.json (03 section 5.2/5.3). */
@Component
public class ShipmentEvents {
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final JsonNode routes;

  public ShipmentEvents(OutboxRepository outbox, ObjectMapper json) {
    this.outbox = outbox; this.json = json;
    try (var resource = ShipmentEvents.class.getResourceAsStream("/contracts/events/registry.json")) {
      if (resource == null) throw new IllegalStateException("Event registry missing");
      this.routes = json.readTree(resource);
    } catch (IOException e) { throw new IllegalStateException("Cannot read event registry", e); }
  }

  public void created(ShipmentService.View view, String correlationId) {
    status("SHIPMENT_CREATED", view, null, correlationId);
  }

  /** SHP-02: every status change (SELF event, cancel, tombstone); handed_over_at only once hand-over is confirmed. */
  public void statusUpdated(ShipmentService.View view, Instant occurredAt, String correlationId) {
    status("SHIPMENT_STATUS_UPDATED", view, occurredAt, correlationId);
  }

  /** COD_COLLECTED / COD_REMITTED: SELF has no carrier fee, so gross = net; payment_id is unknown to shipping. */
  public void cod(String type, ShipmentService.View view, UUID eventRef, long amount, String evidenceRef, Instant occurredAt,
                  String correlationId) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", view.orderId().toString());
    payload.put("order_no", view.orderNo());
    payload.put("payment_id", null);
    payload.put("shipment_id", view.shipmentId().toString());
    payload.put("event_ref", eventRef.toString());
    payload.put("gross_amount", amount);
    payload.put("fee_amount", 0);
    payload.put("net_amount", amount);
    payload.put("currency", "VND");
    payload.put("evidence_ref", evidenceRef);
    payload.put("occurred_at", occurredAt.toString());
    append(type, view.version(), payload, correlationId);
  }

  private void status(String type, ShipmentService.View view, Instant occurredAt, String correlationId) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", view.orderId().toString());
    payload.put("order_no", view.orderNo());
    payload.put("shipment_id", view.shipmentId().toString());
    payload.put("carrier", view.carrier());
    payload.put("tracking_code", view.trackingCode());
    payload.put("status", view.status());
    payload.put("handed_over_at", view.handedOverAt() == null ? null : view.handedOverAt().toString());
    payload.put("provider_occurred_at", occurredAt == null ? null : occurredAt.toString());
    payload.put("shipment_version", view.version());
    append(type, view.version(), payload, correlationId);
  }

  private void append(String type, long version, Map<String, Object> payload, String correlationId) {
    var route = routes.required(type);
    outbox.append(new OutboxEvent(UUID.randomUUID(), "shipment", (String) payload.get(route.required("aggregate_id").asString()),
        version, type, 1, route.required("topic").asString(),
        (String) payload.get(route.required("partition_key").asString()), correlationId, json.writeValueAsString(payload)));
  }
}
