package vn.fashion.shipping.shipments;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

/** SHIPMENT_CREATED through the outbox, routed by contracts/events/registry.json (03 section 5.2/5.3). */
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
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", view.orderId().toString());
    payload.put("order_no", view.orderNo());
    payload.put("shipment_id", view.shipmentId().toString());
    payload.put("carrier", view.carrier());
    payload.put("tracking_code", view.trackingCode());
    payload.put("status", view.status());
    payload.put("handed_over_at", null);
    payload.put("provider_occurred_at", null);
    payload.put("shipment_version", view.version());
    var route = routes.required("SHIPMENT_CREATED");
    outbox.append(new OutboxEvent(UUID.randomUUID(), "shipment", (String) payload.get(route.required("aggregate_id").asString()),
        view.version(), "SHIPMENT_CREATED", 1, route.required("topic").asString(),
        (String) payload.get(route.required("partition_key").asString()), correlationId, json.writeValueAsString(payload)));
  }
}
