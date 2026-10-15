package vn.fashion.platform.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EventEnvelopeTest {
  final ObjectMapper json = new ObjectMapper();
  @Test void parsesOutboxEnvelope() {
    var id = UUID.randomUUID();
    var env = EventEnvelope.parse(envelope(id), json);
    assertThat(env.eventId()).isEqualTo(id);
    assertThat(env.eventType()).isEqualTo("VARIANT_CREATED");
    assertThat(env.payload().path("sku").asText()).isEqualTo("SKU-1");
  }
  @Test void rejectsMissingEventId() {
    assertThatThrownBy(() -> EventEnvelope.parse("{\"event_type\":\"X\"}".getBytes(UTF_8), json))
        .isInstanceOf(PermanentEventException.class);
  }
  @Test void rejectsNonJson() {
    assertThatThrownBy(() -> EventEnvelope.parse("not json".getBytes(UTF_8), json))
        .isInstanceOf(PermanentEventException.class);
  }
  @Test void rejectsMissingRequiredEnvelopeIdentifiers() {
    for(String field : new String[]{"event_id","event_type","version"}) {
      var node=(tools.jackson.databind.node.ObjectNode)json.readTree(envelope(UUID.randomUUID()));
      node.remove(field);
      assertThatThrownBy(() -> EventEnvelope.parse(json.writeValueAsBytes(node),json))
          .isInstanceOf(PermanentEventException.class);
    }
  }
  byte[] envelope(UUID id) {
    return ("""
        {"event_id":"%s","event_type":"VARIANT_CREATED","version":1,"occurred_at":"2026-10-15T00:00:00Z",
         "aggregate_id":"SKU-1","aggregate_version":0,"aggregate_sequence":1,
         "correlation_id":"4bf92f3577b34da6a3ce929d0e0e4736","payload":{"sku":"SKU-1"}}
        """).formatted(id).getBytes(UTF_8);
  }
}
