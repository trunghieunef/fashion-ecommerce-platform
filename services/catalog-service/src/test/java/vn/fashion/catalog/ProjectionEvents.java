package vn.fashion.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.messaging.EventEnvelope;

/** Synthetic event envelopes (event-envelope.schema.json) for catalog consumer tests. */
public final class ProjectionEvents {
  private ProjectionEvents() { }

  public static byte[] bytes(ObjectMapper json, UUID eventId, String type, String aggregateId, Object payload) {
    var env = new LinkedHashMap<String, Object>();
    env.put("event_id", eventId.toString()); env.put("event_type", type); env.put("version", 1);
    env.put("occurred_at", "2026-10-17T03:00:00Z"); env.put("aggregate_id", aggregateId);
    env.put("aggregate_version", 1); env.put("aggregate_sequence", 1);
    env.put("correlation_id", "synthetic-test"); env.put("payload", payload);
    return json.writeValueAsBytes(env);
  }

  public static EventEnvelope envelope(ObjectMapper json, String type, String aggregateId, Object payload) {
    return EventEnvelope.parse(bytes(json, UUID.randomUUID(), type, aggregateId, payload), json);
  }

  public static Map<String, Object> stock(String sku, int available, long version, Instant observedAt) {
    var p = new LinkedHashMap<String, Object>();
    p.put("sku", sku); p.put("on_hand", available + 1); p.put("reserved", 1); p.put("available", available);
    p.put("stock_version", version); p.put("observed_at", observedAt.toString());
    return p;
  }
}
