package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

/** payment.events consumer (group order-service): inbox row and effect in one transaction, ACK after commit. */
class PaymentEventsListenerIntegrationTest extends OrderTestSupport {
  @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2")
      .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  @DynamicPropertySource static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.kafka.listener.auto-startup", () -> "true");
  }

  @BeforeEach void topics() throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      for (String topic : List.of("payment.events", "payment.events.DLT")) {
        try { admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(10, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) { if (!(e.getCause() instanceof TopicExistsException)) throw e; }
      }
    }
  }

  @Test void aCompletedPaymentIsRecordedFromKafka() throws Exception {
    UUID id = placeOrder();
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", id.toString()); payload.put("order_no", "ORD1"); payload.put("payment_id", UUID.randomUUID().toString());
    payload.put("method", "COD"); payload.put("amount", 439000); payload.put("currency", "VND"); payload.put("status", "SUCCESS");
    payload.put("paid_at", "2026-10-20T05:00:00Z"); payload.put("provider_txn_id", null); payload.put("reason", null);
    var envelope = new LinkedHashMap<String, Object>();
    envelope.put("event_id", UUID.randomUUID().toString()); envelope.put("event_type", "PAYMENT_COMPLETED"); envelope.put("version", 1);
    envelope.put("occurred_at", java.time.Instant.now().toString()); envelope.put("aggregate_id", payload.get("payment_id"));
    envelope.put("aggregate_version", 2); envelope.put("aggregate_sequence", 3); envelope.put("correlation_id", "kafka-test");
    envelope.put("payload", payload);
    try (var producer = new KafkaProducer<String, byte[]>(Map.of("bootstrap.servers", kafka.getBootstrapServers()),
        new StringSerializer(), new ByteArraySerializer())) {
      producer.send(new ProducerRecord<>("payment.events", "ORD1", mapper.writeValueAsBytes(envelope))).get();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    String state = "NONE";
    while (!state.equals("PAID") && System.nanoTime() < deadline) {
      Thread.sleep(300);
      state = (String) saga(id).get("payment_state");
    }
    assertThat(state).isEqualTo("PAID");
    assertThat(count("select count(*) from processed_events where consumer_name = 'order-service'")).isEqualTo(1);
  }
}
