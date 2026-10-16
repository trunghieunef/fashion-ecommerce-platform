package vn.fashion.platform.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record EventEnvelope(UUID eventId, String eventType, int version, Instant occurredAt,
    String aggregateId, long aggregateVersion, long aggregateSequence, String correlationId, JsonNode payload) {
  public static EventEnvelope parse(byte[] json, ObjectMapper m) {
    try {
      JsonNode n = m.readTree(json);
      if (n == null || !n.isObject() || n.size() != 9 || !n.path("payload").isObject()) throw invalid();
      String id = text(n, "event_id");
      UUID event = UUID.fromString(id);
      if (!event.toString().equalsIgnoreCase(id)) throw invalid();
      long version = number(n, "version", 1);
      if (version > Integer.MAX_VALUE) throw invalid();
      String occurred = text(n, "occurred_at");
      if (!occurred.endsWith("Z")) throw invalid();
      return new EventEnvelope(event, text(n, "event_type"), (int) version, Instant.parse(occurred),
          text(n, "aggregate_id"), number(n, "aggregate_version", 0), number(n, "aggregate_sequence", 1),
          text(n, "correlation_id"), n.get("payload"));
    } catch (RuntimeException e) {
      // Parser/UUID exceptions can quote raw input; keep those values out of retry/DLT logs.
      throw invalid();
    }
  }
  private static String text(JsonNode n, String key) {
    var value = n.get(key);
    if (value == null || !value.isString() || value.asText().isEmpty()) throw invalid();
    return value.asText();
  }
  private static long number(JsonNode n, String key, long minimum) {
    var value = n.get(key);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) throw invalid();
    return value.longValue();
  }
  private static PermanentEventException invalid() { return new PermanentEventException("Invalid event envelope"); }
}
