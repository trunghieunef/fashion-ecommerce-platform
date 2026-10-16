package vn.fashion.platform.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

abstract class MessagingTestSupport {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2")
      .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  static final DriverManagerDataSource SOURCE;
  static {
    POSTGRES.start(); KAFKA.start();
    SOURCE = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    new ResourceDatabasePopulator(new ClassPathResource("db/platform/durability.sql")).execute(SOURCE);
    JdbcClient.create(SOURCE).sql("create table effects(event_id uuid primary key)").update();
  }
  final JdbcClient jdbc = JdbcClient.create(SOURCE);
  final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(SOURCE));
  final OutboxRepository outbox = new OutboxRepository(jdbc);
  final InboxGuard inbox = new InboxGuard(jdbc, tx);
  final ObjectMapper json = new ObjectMapper();
  AnnotationConfigApplicationContext context;
  SimpleMeterRegistry meters;
  KafkaTemplate<String, String> kafka;
  String topic;
  String group;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() throws Exception {
    jdbc.sql("truncate outbox_events, processed_events, effects").update();
    topic = "test-" + UUID.randomUUID(); group = "test-" + UUID.randomUUID();
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1), new NewTopic(topic + ".DLT", 3, (short) 1)))
          .all().get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    context = new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
        "spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers(), "fashion.messaging.relay.enabled", "false",
        "test.topic", topic, "test.group", group)));
    context.registerBean(JdbcClient.class, () -> jdbc);
    context.registerBean(OutboxRepository.class, () -> outbox);
    context.registerBean(InboxGuard.class, () -> inbox);
    context.registerBean(ObjectMapper.class, () -> json);
    context.registerBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);
    context.register(MessagingAutoConfiguration.class);
    registerListener();
    context.refresh();
    meters = context.getBean(SimpleMeterRegistry.class);
    context.getBeansOfType(MeterBinder.class).values().forEach(b -> b.bindTo(meters));
    kafka = (KafkaTemplate<String, String>) context.getBean("kafkaTemplate");
  }

  void registerListener() { }

  @AfterEach void close() { if (context != null) context.close(); }

  OutboxRelay relay() {
    var relay = new OutboxRelay(outbox, kafka, Duration.ofSeconds(5));
    relay.metrics(meters);
    return relay;
  }

  OutboxEvent append(String aggregate) {
    var event = new OutboxEvent(UUID.randomUUID(), "product", aggregate, 0, "VARIANT_CREATED", 1,
        topic, aggregate, "4bf92f3577b34da6a3ce929d0e0e4736", "{\"sku\":\"SKU-1\"}");
    tx.executeWithoutResult(s -> outbox.append(event));
    return event;
  }

  static String envelope(UUID id, String type) {
    return """
        {"event_id":"%s","event_type":"%s","version":1,"occurred_at":"2026-10-15T00:00:00Z",
         "aggregate_id":"SKU-1","aggregate_version":0,"aggregate_sequence":1,
         "correlation_id":"4bf92f3577b34da6a3ce929d0e0e4736","payload":{"sku":"SKU-1"}}
        """.formatted(id, type);
  }

  List<ConsumerRecord<String, byte[]>> consume(String name, int count) {
    try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest", ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
      consumer.subscribe(List.of(name));
      var records = new ArrayList<ConsumerRecord<String, byte[]>>();
      long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while (records.size() < count && System.nanoTime() < until) {
        consumer.poll(Duration.ofMillis(100)).forEach(records::add);
      }
      assertThat(records).hasSize(count);
      return records;
    }
  }

  static void await(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(25);
    assertThat(condition.getAsBoolean()).as("condition before bounded deadline").isTrue();
  }

  int effects() { return jdbc.sql("select count(*) from effects").query(Integer.class).single(); }
  long committed(int partition) {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
          .get(3, java.util.concurrent.TimeUnit.SECONDS).get(new org.apache.kafka.common.TopicPartition(topic, partition));
      return offset == null ? 0 : offset.offset();
    } catch (Exception e) { return 0; }
  }
}
