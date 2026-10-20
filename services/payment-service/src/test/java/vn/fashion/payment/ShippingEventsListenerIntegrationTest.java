package vn.fashion.payment;

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

class ShippingEventsListenerIntegrationTest extends PaymentTestSupport {
  @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2")
      .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  @DynamicPropertySource static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.kafka.listener.auto-startup", () -> "true");
  }

  @BeforeEach void topics() throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      for (String topic : List.of("shipping.events", "shipping.events.DLT")) {
        try { admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(10, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) { if (!(e.getCause() instanceof TopicExistsException)) throw e; }
      }
    }
  }

  byte[] event(String type, String order, long gross) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", order); payload.put("order_no", "K" + order.substring(0, 8)); payload.put("payment_id", null);
    payload.put("shipment_id", UUID.randomUUID().toString()); payload.put("event_ref", UUID.randomUUID().toString());
    payload.put("gross_amount", gross); payload.put("fee_amount", 0); payload.put("net_amount", gross); payload.put("currency", "VND");
    payload.put("evidence_ref", "BIEN-NHAN-K"); payload.put("occurred_at", "2026-10-20T05:00:00Z");
    var envelope = new LinkedHashMap<String, Object>();
    envelope.put("event_id", UUID.randomUUID().toString()); envelope.put("event_type", type); envelope.put("version", 1);
    envelope.put("occurred_at", "2026-10-20T05:00:01Z"); envelope.put("aggregate_id", payload.get("shipment_id"));
    envelope.put("aggregate_version", 5); envelope.put("aggregate_sequence", 5); envelope.put("correlation_id", "kafka-test");
    envelope.put("payload", payload);
    return mapper.writeValueAsBytes(envelope);
  }

  @Test void evidenceBeforeThePaymentIsRetriedUntilThePaymentExists() throws Exception {
    String order = UUID.randomUUID().toString();
    try (var producer = new KafkaProducer<String, byte[]>(Map.of("bootstrap.servers", kafka.getBootstrapServers()),
        new StringSerializer(), new ByteArraySerializer())) {
      producer.send(new ProducerRecord<>("shipping.events", "K1", event("SHIPMENT_STATUS_UPDATED", order, 0))).get();
      producer.send(new ProducerRecord<>("shipping.events", "K1", event("COD_COLLECTED", order, 45000))).get();
    }
    Thread.sleep(3000); // the listener is failing and retrying meanwhile: nothing may be written
    assertThat(count("select count(*) from payment_transactions where source = 'COD'")).isZero();
    String id = cod(order, 45000);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    String status = "COD_PENDING";
    while (!status.equals("COD_COLLECTED") && System.nanoTime() < deadline) {
      Thread.sleep(300);
      status = jdbc.queryForObject("select status from payments where id = cast(? as uuid)", String.class, id);
    }
    assertThat(status).isEqualTo("COD_COLLECTED");
    assertThat(count("select count(*) from processed_events")).isEqualTo(1); // SHIPMENT_STATUS_UPDATED acked without inbox row
  }
}
