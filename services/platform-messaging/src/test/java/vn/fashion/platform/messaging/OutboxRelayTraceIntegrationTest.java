package vn.fashion.platform.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

/** TASK:PLT-05: the publish span continues the trace stored with the outbox row. */
class OutboxRelayTraceIntegrationTest extends MessagingTestSupport {
  static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  static final String PARENT = "b7ad6b7169203331";
  static final String STORED = "00-" + TRACE_ID + "-" + PARENT + "-01";

  static final class Exported implements SpanExporter {
    final List<SpanData> spans = new CopyOnWriteArrayList<>();
    @Override public CompletableResultCode export(Collection<SpanData> batch) { spans.addAll(batch); return CompletableResultCode.ofSuccess(); }
    @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
    @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
  }

  final Exported exported = new Exported();
  final SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build();
  final io.opentelemetry.api.trace.Tracer otel = provider.get("test");
  final OtelTracer tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> { });
  final OtelPropagator propagator = new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);

  @AfterEach void closeProvider() { provider.close(); }

  OutboxRelay tracedRelay() {
    var relay = new OutboxRelay(outbox, kafka, Duration.ofSeconds(5), tracer, propagator);
    relay.metrics(meters);
    return relay;
  }

  OutboxEvent appendTraced(String traceParent) {
    var traced = new OutboxRepository(jdbc, () -> traceParent);
    var event = new OutboxEvent(UUID.randomUUID(), "product", "product-t", 0, "VARIANT_CREATED", 1,
        topic, "product-t", TRACE_ID, "{\"sku\":\"SKU-1\"}");
    tx.executeWithoutResult(s -> traced.append(event));
    return event;
  }

  static String header(org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> r) {
    return new String(r.headers().lastHeader("traceparent").value(), UTF_8);
  }

  @Test void publishSpanIsChildOfStoredTraceParent() {
    var event = appendTraced(STORED);
    assertThat(tracedRelay().relayOnce()).isEqualTo(1);
    var record = consume(topic, 1).getFirst();
    assertThat(record.headers().headers("traceparent")).hasSize(1);
    String sent = header(record);
    assertThat(sent).startsWith("00-" + TRACE_ID + "-").doesNotContain(PARENT);
    assertThat(exported.spans).hasSize(1);
    var span = exported.spans.getFirst();
    assertThat(span.getName()).isEqualTo("outbox publish VARIANT_CREATED");
    assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
    assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
    assertThat(span.getParentSpanId()).isEqualTo(PARENT);
    assertThat(sent).isEqualTo("00-" + TRACE_ID + "-" + span.getSpanId() + "-01");
    var attributes = span.getAttributes().asMap().toString();
    assertThat(attributes).contains("messaging.destination.name", topic, event.eventId().toString())
        .doesNotContain("SKU-1").doesNotContain("product-t");
  }

  @Test void rowWithoutTraceParentKeepsLegacyHeader() {
    appendTraced(null);
    assertThat(tracedRelay().relayOnce()).isEqualTo(1);
    assertThat(header(consume(topic, 1).getFirst())).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01");
    assertThat(exported.spans).isEmpty();
  }

  @Test void republishKeepsTraceId() {
    appendTraced(STORED);
    tracedRelay().relayOnce();
    jdbc.sql("update outbox_events set status='PENDING', published_at=null").update();
    tracedRelay().relayOnce();
    var records = consume(topic, 2);
    assertThat(records).extracting(OutboxRelayTraceIntegrationTest::header)
        .allMatch(h -> h.startsWith("00-" + TRACE_ID + "-")).doesNotHaveDuplicates();
  }

  @Test void failedPublishMarksSpanAsError() {
    // Review M1: a publish that never reached the broker must not show as a successful span.
    appendTraced(STORED);
    var props = new java.util.HashMap<String, Object>(java.util.Map.of(
        org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
        org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class,
        org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class,
        org.apache.kafka.clients.producer.ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
        org.apache.kafka.clients.producer.ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500,
        org.apache.kafka.clients.producer.ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000));
    var factory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(props);
    try {
      var bad = new org.springframework.kafka.core.KafkaTemplate<>(factory); bad.setProducerListener(null);
      var failed = new OutboxRelay(outbox, bad, Duration.ofSeconds(1), tracer, propagator); failed.metrics(meters);
      assertThat(failed.relayOnce()).isZero();
    } finally { factory.destroy(); }
    assertThat(exported.spans).hasSize(1);
    assertThat(exported.spans.getFirst().getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
  }

  @Test void currentTraceParentFollowsTheActiveSpan() {
    var supplier = TraceParents.current(tracer);
    assertThat(supplier.get()).isNull();
    var span = tracer.nextSpan().name("request").start();
    try (var scope = tracer.withSpan(span)) {
      assertThat(supplier.get()).isEqualTo("00-" + span.context().traceId() + "-" + span.context().spanId() + "-01");
    } finally { span.end(); }
  }
}
