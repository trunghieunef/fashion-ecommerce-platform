package vn.fashion.catalog.stock;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import vn.fashion.catalog.ProjectionEvents;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

class InventoryEventsListenerIntegrationTest extends CatalogAdminTestSupport {
  @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2")
      .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  @DynamicPropertySource static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.kafka.listener.auto-startup", () -> "true");
  }
  @Autowired StockObservations stock;

  @BeforeEach void topics() throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      for (String topic : List.of("inventory.events", "inventory.events.DLT", "order.events", "order.events.DLT")) {
        try { admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(10, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) { if (!(e.getCause() instanceof TopicExistsException)) throw e; }
      }
    }
  }

  @Test void listenerConsumesUpdatesAndAcksOtherTypes() throws Exception {
    try (var producer = new KafkaProducer<String, byte[]>(Map.of("bootstrap.servers", kafka.getBootstrapServers()),
        new StringSerializer(), new ByteArraySerializer())) {
      producer.send(new ProducerRecord<>("inventory.events", "ORD-1", ProjectionEvents.bytes(mapper, UUID.randomUUID(),
          "INVENTORY_RESERVED", UUID.randomUUID().toString(), Map.of("order_no", "ORD-1")))).get();
      producer.send(new ProducerRecord<>("inventory.events", "KAFKA-A", ProjectionEvents.bytes(mapper, UUID.randomUUID(),
          "INVENTORY_UPDATED", "KAFKA-A", ProjectionEvents.stock("KAFKA-A", 6, 1, Instant.parse("2026-10-17T03:00:00Z"))))).get();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (stock.forSkus(List.of("KAFKA-A")).isEmpty() && System.nanoTime() < deadline) Thread.sleep(200);
    assertThat(stock.forSkus(List.of("KAFKA-A")).get("KAFKA-A").available()).isEqualTo(6);
    assertThat(jdbc.queryForObject("select count(*) from variant_stock_observations", Integer.class)).isEqualTo(1);
  }
}
