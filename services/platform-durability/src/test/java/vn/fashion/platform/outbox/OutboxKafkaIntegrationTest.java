package vn.fashion.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import vn.fashion.platform.PostgresTestSupport;
import vn.fashion.platform.inbox.InboxGuard;

/** PostgreSQL + Kafka 4.1.2 (KRaft) proof of 06 section 1.3: crash after publish, dedupe, order. */
class OutboxKafkaIntegrationTest extends PostgresTestSupport {
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static KafkaProducer<String, String> producer;

  private final OutboxRepository outbox = new OutboxRepository(jdbc);
  private final InboxGuard inbox = new InboxGuard(jdbc, tx);

  @BeforeAll
  static void startKafka() {
    KAFKA.start();
    producer = new KafkaProducer<>(Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ProducerConfig.ACKS_CONFIG, "all",
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
  }

  @AfterAll
  static void stopKafka() {
    producer.close();
    KAFKA.stop();
  }

  @Test
  void crashAfterPublishRedeliversSameEventAndConsumerAppliesItOnce() {
    String topic = "order.events.crash";
    var event = event(topic, "order-a", 0);
    tx.executeWithoutResult(s -> outbox.append(event));

    // Relay publishes, broker ACKs, then the process dies before markSent.
    outbox.claim(10, LEASE).forEach(publisher());
    jdbc.sql("update outbox_events set lease_until = now() - interval '1 second'").update();
    assertThat(outbox.relay(10, LEASE, publisher())).isEqualTo(1);

    var records = consume(topic, 2);
    assertThat(records).extracting(ConsumerRecord::key).containsOnly("FS-SYNTH-0001");
    assertThat(records.get(0).value()).isEqualTo(records.get(1).value());
    for (var record : records) {
      UUID eventId = UUID.fromString(field(record.value(), "event_id"));
      inbox.applyOnce("test-consumer", eventId, () -> jdbc.sql(
              "insert into test_effects(event_id, note) values (:id, 'applied')")
          .param("id", eventId).update());
    }
    assertThat(effects()).isEqualTo(1);
    assertThat(jdbc.sql("select status from outbox_events").query(String.class).single()).isEqualTo("SENT");
  }

  @Test
  void eventsAtTheSameVersionArriveInSequenceOrder() {
    String topic = "order.events.order";
    tx.executeWithoutResult(s -> {
      outbox.append(event(topic, "order-a", 2));
      outbox.append(event(topic, "order-a", 2));
      outbox.append(event(topic, "order-a", 2));
    });

    int rounds = 0;
    while (outbox.relay(10, LEASE, publisher()) > 0) {
      rounds++;
    }

    assertThat(rounds).as("one head per aggregate per claim").isEqualTo(3);
    assertThat(consume(topic, 3)).extracting(r -> field(r.value(), "aggregate_sequence"))
        .containsExactly("1", "2", "3");
  }

  private static Consumer<OutboxMessage> publisher() {
    return message -> {
      try {
        producer.send(new ProducerRecord<>(message.topic(), message.partitionKey(), message.envelopeJson()))
            .get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new IllegalStateException("publish failed", e);
      }
    };
  }

  private static List<ConsumerRecord<String, String>> consume(String topic, int expected) {
    try (var consumer = new KafkaConsumer<String, String>(Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
      consumer.subscribe(List.of(topic));
      var records = new ArrayList<ConsumerRecord<String, String>>();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (records.size() < expected && System.nanoTime() < deadline) {
        consumer.poll(Duration.ofMillis(500)).forEach(records::add);
      }
      return records;
    }
  }

  private String field(String envelope, String name) {
    return jdbc.sql("select cast(:env as jsonb) ->> :name").param("env", envelope).param("name", name)
        .query(String.class).single();
  }

  private static OutboxEvent event(String topic, String aggregateId, long version) {
    return new OutboxEvent(UUID.randomUUID(), "order", aggregateId, version, "ORDER_CREATED", 1,
        topic, "FS-SYNTH-0001", "synthetic-correlation", "{\"order_no\":\"FS-SYNTH-0001\"}");
  }
}
