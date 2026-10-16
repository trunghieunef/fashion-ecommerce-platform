package vn.fashion.platform.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.outbox.OutboxRepository;

public class OutboxRelay {
  private final OutboxRepository outbox;
  private final KafkaTemplate<String, String> kafka;
  private final Duration sendTimeout;
  private final ObjectMapper json = new ObjectMapper();
  private Counter failures;
  public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Duration sendTimeout) {
    this.outbox = outbox; this.kafka = kafka; this.sendTimeout = sendTimeout;
  }
  void metrics(MeterRegistry registry) { failures = registry.counter("outbox_relay_failures_total"); }

  @Scheduled(fixedDelayString = "${fashion.messaging.relay.interval:500ms}")
  public int relayOnce() {
    return outbox.relay(100, Duration.ofSeconds(30), message -> {
      try {
        var envelope = EventEnvelope.parse(message.envelopeJson().getBytes(UTF_8), json);
        var record = new ProducerRecord<>(message.topic(), message.partitionKey(), message.envelopeJson());
        record.headers().add("event_id", envelope.eventId().toString().getBytes(UTF_8));
        record.headers().add("event_type", envelope.eventType().getBytes(UTF_8));
        record.headers().add("correlation_id", envelope.correlationId().getBytes(UTF_8));
        if (envelope.correlationId().matches("[0-9a-f]{32}")) {
          String span = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
          record.headers().add("traceparent", ("00-" + envelope.correlationId() + "-" + span + "-01").getBytes(UTF_8));
        }
        kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        if (failures != null) failures.increment();
        throw new IllegalStateException("Kafka publish interrupted");
      } catch (Exception e) {
        if (failures != null) failures.increment();
        throw new IllegalStateException("Kafka publish failed");
      }
    });
  }
}
