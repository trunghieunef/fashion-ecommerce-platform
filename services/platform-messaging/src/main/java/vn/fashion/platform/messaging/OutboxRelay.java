package vn.fashion.platform.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.log.LogAccessor;
import vn.fashion.platform.outbox.OutboxMessage;
import vn.fashion.platform.outbox.OutboxRepository;

public class OutboxRelay {
  private static final LogAccessor LOG = new LogAccessor(OutboxRelay.class);
  private final OutboxRepository outbox;
  private final KafkaTemplate<String, String> kafka;
  private final Duration sendTimeout;
  private final Tracer tracer;
  private final Propagator propagator;
  private final ObjectMapper json = new ObjectMapper();
  private Counter failures;
  public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Duration sendTimeout) {
    this(outbox, kafka, sendTimeout, null, null);
  }
  /** tracer/propagator null: legacy traceparent from correlation_id only (TASK:PLT-05). */
  public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Duration sendTimeout,
                     Tracer tracer, Propagator propagator) {
    this.outbox = outbox; this.kafka = kafka; this.sendTimeout = sendTimeout;
    this.tracer = tracer; this.propagator = propagator;
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
        Span span = publishSpan(message, envelope, record);
        try { kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS); }
        finally { if (span != null) span.end(); }
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

  /** PRODUCER span parented by the stored traceparent; its context replaces the legacy header. Never fails the publish. */
  private Span publishSpan(OutboxMessage message, EventEnvelope envelope, ProducerRecord<String, String> record) {
    if (tracer == null || propagator == null || message.traceParent() == null) return null;
    try {
      Span span = propagator.extract(Map.of("traceparent", message.traceParent()), Map::get)
          .name("outbox publish " + envelope.eventType()).kind(Span.Kind.PRODUCER)
          .tag("messaging.destination.name", message.topic()).tag("event_type", envelope.eventType())
          .tag("event_id", envelope.eventId().toString()).start();
      record.headers().remove("traceparent");
      propagator.inject(span.context(), record.headers(), (headers, key, value) -> {
        headers.remove(key); headers.add(key, value.getBytes(UTF_8));
      });
      return span;
    } catch (RuntimeException e) {
      LOG.debug(() -> "Publish span skipped; event_id=" + envelope.eventId());
      return null;
    }
  }
}
