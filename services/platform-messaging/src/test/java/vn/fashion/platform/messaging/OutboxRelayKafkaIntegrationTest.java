package vn.fashion.platform.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxRelayKafkaIntegrationTest extends MessagingTestSupport {
  @Test void marksSentOnlyAfterBrokerAck() {
    var event = append("product-a");
    assertThat(relay().relayOnce()).isEqualTo(1);
    var record = consume(topic, 1).getFirst();
    assertThat(record.key()).isEqualTo("product-a");
    assertThat(EventEnvelope.parse(record.value(), json).eventId()).isEqualTo(event.eventId());
    assertThat(jdbc.sql("select status from outbox_events").query(String.class).single()).isEqualTo("SENT");
    assertThat(new KafkaHealthIndicator(KAFKA.getBootstrapServers()).health().getStatus().getCode()).isEqualTo("UP");
  }

  @Test void keepsPendingWhileBrokerDown() {
    append("product-a");
    var props = new HashMap<String, Object>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
        ProducerConfig.MAX_BLOCK_MS_CONFIG, 500, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000));
    var factory = new DefaultKafkaProducerFactory<String, String>(props);
    try {
      var bad = new KafkaTemplate<>(factory); bad.setProducerListener(null);
      var failed = new OutboxRelay(outbox, bad, Duration.ofSeconds(1)); failed.metrics(meters);
      assertThat(failed.relayOnce()).isZero();
      assertThat(jdbc.sql("select status from outbox_events").query(String.class).single()).isNotEqualTo("SENT");
      assertThat(jdbc.sql("select last_error from outbox_events").query(String.class).single()).isNotBlank();
      assertThat(meters.get("outbox_relay_failures_total").counter().count()).isEqualTo(1);
    } finally { factory.destroy(); }
    jdbc.sql("update outbox_events set lease_until=now()-interval '1 second'").update();
    assertThat(relay().relayOnce()).isEqualTo(1);
    assertThat(consume(topic, 1)).hasSize(1);
    assertThat(jdbc.sql("select status from outbox_events").query(String.class).single()).isEqualTo("SENT");
  }

  @Test void publishesTraceAndEventHeaders() {
    var event = append("product-a"); relay().relayOnce();
    var headers = consume(topic, 1).getFirst().headers();
    assertThat(new String(headers.lastHeader("event_id").value(), UTF_8)).isEqualTo(event.eventId().toString());
    assertThat(new String(headers.lastHeader("event_type").value(), UTF_8)).isEqualTo("VARIANT_CREATED");
    assertThat(new String(headers.lastHeader("correlation_id").value(), UTF_8)).isEqualTo(event.correlationId());
    assertThat(new String(headers.lastHeader("traceparent").value(), UTF_8))
        .matches("00-" + event.correlationId() + "-[0-9a-f]{16}-01");
  }

  @Test void preservesAggregateOrder() {
    append("product-a"); append("product-a"); append("product-a");
    for (int i=0;i<3;i++) assertThat(relay().relayOnce()).isEqualTo(1);
    var records = consume(topic, 3);
    assertThat(records.stream().map(r -> EventEnvelope.parse(r.value(), json).aggregateSequence()).toList())
        .containsExactly(1L, 2L, 3L);
    assertThat(records.get(0).offset()).isLessThan(records.get(1).offset());
    assertThat(records.get(1).offset()).isLessThan(records.get(2).offset());
  }

  @Test void exposesOutboxGauges() {
    append("product-a"); append("product-b");
    jdbc.sql("update outbox_events set created_at=now()-interval '5 seconds'").update();
    assertThat(meters.get("outbox_pending").gauge().value()).isEqualTo(2);
    assertThat(meters.get("outbox_oldest_pending_age_seconds").gauge().value()).isGreaterThan(0);
    relay().relayOnce();
    assertThat(meters.get("outbox_pending").gauge().value()).isZero();
  }
}
