package vn.fashion.catalog.admin;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

class CatalogRelayIntegrationTest extends CatalogAdminTestSupport {
  @org.springframework.beans.factory.annotation.Autowired org.springframework.core.env.Environment environment;
  @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2")
      .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  @DynamicPropertySource static void relayProperties(DynamicPropertyRegistry registry) {
    registry.add("fashion.messaging.relay.enabled", () -> "true");
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }
  @BeforeEach void topics() throws Exception {
    assertThat(environment.getProperty("fashion.messaging.relay.enabled")).isEqualTo("true");
    try (var admin=AdminClient.create(Map.of("bootstrap.servers",kafka.getBootstrapServers()))) {
      try { admin.createTopics(List.of(new NewTopic("catalog.events",3,(short)1)))
          .all().get(10,java.util.concurrent.TimeUnit.SECONDS); }
      catch(java.util.concurrent.ExecutionException e) { if(!(e.getCause() instanceof TopicExistsException)) throw e; }
    }
  }

  @Test void variantCreatedReachesCatalogEvents() throws Exception {
    UUID product=createProduct("relay"); createVariant(product,"relay-1");
    var records=poll(product,1); var record=records.getFirst();
    assertThat(record.key()).isEqualTo(product.toString());
    var env=mapper.readTree(record.value());
    assertThat(env.path("event_type").asText()).isEqualTo("VARIANT_CREATED");
    assertThat(env.path("payload").path("sku").asText()).isEqualTo("RELAY-1");
    assertSent(env.path("event_id").asText());
  }

  @Test void drainsPreExistingPendingEventsInOrder() throws Exception {
    UUID product=UUID.randomUUID();
    jdbc.update("""
        insert into outbox_events(id,aggregate_type,aggregate_id,aggregate_version,aggregate_sequence,
          event_type,schema_version,topic,partition_key,correlation_id,payload,created_at)
        values (?,'product',?,0,1,'VARIANT_CREATED',1,'catalog.events',?,'4bf92f3577b34da6a3ce929d0e0e4736',?::jsonb,now()-interval '1 day'),
               (?,'product',?,0,2,'VARIANT_CREATED',1,'catalog.events',?,'4bf92f3577b34da6a3ce929d0e0e4736',?::jsonb,now()-interval '1 day')
        """,UUID.randomUUID(),product.toString(),product.toString(),payload(product,"OLD-1"),
        UUID.randomUUID(),product.toString(),product.toString(),payload(product,"OLD-2"));
    var records=poll(product,2);
    assertThat(records.stream().map(r->mapper.readTree(r.value()).path("aggregate_sequence").asLong()).toList())
        .containsExactly(1L,2L);
    assertThat(records.get(0).offset()).isLessThan(records.get(1).offset());
    for(var record:records) assertSent(mapper.readTree(record.value()).path("event_id").asText());
  }

  String payload(UUID product,String sku) {
    return mapper.writeValueAsString(Map.of("product_id",product,"variant_id",UUID.randomUUID(),"sku",sku,"version",0));
  }
  void assertSent(String id) throws Exception {
    long until=System.nanoTime()+Duration.ofSeconds(3).toNanos(); String status;
    do {
      status=jdbc.queryForObject("select status from outbox_events where id=?::uuid",String.class,id);
      if("SENT".equals(status)) break;
      Thread.sleep(20);
    } while(System.nanoTime()<until);
    assertThat(status).isEqualTo("SENT");
  }
  List<ConsumerRecord<String,String>> poll(UUID product,int wanted) {
    try(var consumer=new KafkaConsumer<String,String>(Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG,UUID.randomUUID().toString(),
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false,
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class))) {
      consumer.subscribe(List.of("catalog.events")); var records=new ArrayList<ConsumerRecord<String,String>>();
      long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
      while(records.size()<wanted && System.nanoTime()<until) {
        for(var record:consumer.poll(Duration.ofMillis(100))) if(product.toString().equals(record.key())) records.add(record);
      }
      assertThat(records).hasSize(wanted); return records;
    }
  }
}
