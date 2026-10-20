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

/** shipping.events consumer (group order-service): inbox row and effect in one transaction, ACK after commit. */
class ShippingEventsListenerIntegrationTest extends OrderTestSupport {
  @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2")
      .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  @DynamicPropertySource static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.kafka.listener.auto-startup", () -> "true");
  }

  @BeforeEach void topics() throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      for (String topic : List.of("payment.events", "payment.events.DLT", "shipping.events", "shipping.events.DLT")) {
        try { admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(10, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) { if (!(e.getCause() instanceof TopicExistsException)) throw e; }
      }
    }
  }

  @Test void aDeliveredShipmentCompletesTheOrderFromKafka() throws Exception {
    UUID id = placeOrder();
    jdbc.update("update orders set status = 'PACKING' where id = ?", id);
    String shipment = UUID.randomUUID().toString();
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", id.toString()); payload.put("order_no", "ORD1"); payload.put("shipment_id", shipment);
    payload.put("carrier", "SELF"); payload.put("tracking_code", null); payload.put("status", "DELIVERED");
    payload.put("handed_over_at", "2026-10-21T03:00:00Z"); payload.put("provider_occurred_at", "2026-10-21T08:00:00Z");
    payload.put("shipment_version", 4);
    var envelope = new LinkedHashMap<String, Object>();
    envelope.put("event_id", UUID.randomUUID().toString()); envelope.put("event_type", "SHIPMENT_STATUS_UPDATED"); envelope.put("version", 1);
    envelope.put("occurred_at", java.time.Instant.now().toString()); envelope.put("aggregate_id", shipment);
    envelope.put("aggregate_version", 4); envelope.put("aggregate_sequence", 4); envelope.put("correlation_id", "kafka-test");
    envelope.put("payload", payload);
    try (var producer = new KafkaProducer<String, byte[]>(Map.of("bootstrap.servers", kafka.getBootstrapServers()),
        new StringSerializer(), new ByteArraySerializer())) {
      producer.send(new ProducerRecord<>("shipping.events", "ORD1", mapper.writeValueAsBytes(envelope))).get();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    String status = "PACKING";
    while (!status.equals("DELIVERED") && System.nanoTime() < deadline) {
      Thread.sleep(300);
      status = status(id);
    }
    assertThat(status).isEqualTo("DELIVERED");
    assertThat(count("select count(*) from processed_events where consumer_name = 'order-service'")).isEqualTo(1);
    assertThat(count("select count(*) from outbox_events where event_type = 'ORDER_COMPLETED'")).isEqualTo(1);
  }
}
