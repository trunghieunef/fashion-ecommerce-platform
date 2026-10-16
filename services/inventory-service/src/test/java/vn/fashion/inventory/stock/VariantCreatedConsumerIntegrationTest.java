package vn.fashion.inventory.stock;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.fashion.inventory.InventoryTestSupport;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VariantCreatedConsumerIntegrationTest extends InventoryTestSupport {
  @Autowired @Qualifier("kafkaTemplate") KafkaTemplate<String,String> producer;
  @Autowired KafkaListenerEndpointRegistry listeners;
  @DynamicPropertySource static void messaging(DynamicPropertyRegistry r) {
    r.add("fashion.messaging.relay.enabled",()->"true");
    r.add("spring.kafka.listener.auto-startup",()->"false");
  }
  @BeforeEach void topics() throws Exception {
    try(var a=admin()) {
      for(String topic:List.of("catalog.events","catalog.events.DLT","inventory.events")) {
        try { a.createTopics(List.of(new NewTopic(topic,3,(short)1))).all().get(10,TimeUnit.SECONDS); }
        catch(java.util.concurrent.ExecutionException e) {
          if(!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) throw e;
        }
      }
    }
  }
  @AfterEach void stop() { listeners.stop(); }

  @Test @Order(1) void startsFromEarliestForNewGroup() throws Exception {
    publish(event(UUID.randomUUID(),UUID.randomUUID(),"EARLIEST"));
    start(); awaitSku("EARLIEST"); awaitCommitted();
  }
  @Test void registersSkuWithZeroStockAndEmitsSnapshot() throws Exception {
    start(); publish(event(UUID.randomUUID(),UUID.randomUUID(),"ZERO")); awaitSku("ZERO");
    var row=jdbc.queryForMap("select * from stock_items where sku='ZERO'");
    assertThat(row).containsEntry("on_hand",0).containsEntry("reserved",0).containsEntry("version",0L);
    var payload=mapper.readTree(jdbc.queryForObject("select payload::text from outbox_events where aggregate_id='ZERO'",String.class));
    assertThat(payload.path("available").asInt()).isZero();
    assertThat(payload.path("stock_version").asLong()).isZero(); awaitCommitted();
  }
  @Test void duplicateEventIdIsIgnored() throws Exception {
    String body=event(UUID.randomUUID(),UUID.randomUUID(),"DEDUPE");
    start(); publish(body); publish(body); awaitSku("DEDUPE"); awaitCommitted();
    assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id='DEDUPE'",Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from processed_events",Integer.class)).isEqualTo(1);
  }
  @Test void newEventSameVariantDoesNotResetStock() throws Exception {
    UUID variant=UUID.randomUUID(); start(); publish(event(UUID.randomUUID(),variant,"KEEP")); awaitSku("KEEP"); awaitCommitted();
    jdbc.update("update stock_items set on_hand=7 where sku='KEEP'");
    publish(event(UUID.randomUUID(),variant,"KEEP")); awaitCommitted();
    assertThat(jdbc.queryForObject("select on_hand from stock_items where sku='KEEP'",Integer.class)).isEqualTo(7);
    assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id='KEEP'",Integer.class)).isEqualTo(1);
  }
  @Test void sameVariantDifferentSkuGoesToDlt() throws Exception {
    UUID variant=UUID.randomUUID(); start(); publish(event(UUID.randomUUID(),variant,"ORIGINAL")); awaitSku("ORIGINAL"); awaitCommitted();
    String bad=event(UUID.randomUUID(),variant,"WRONG"); publish(bad);
    assertThat(poll("catalog.events.DLT",mapper.readTree(bad).path("event_id").asText())).isEqualTo(bad);
    awaitCommitted(); assertThat(jdbc.queryForObject("select count(*) from stock_items where sku='WRONG'",Integer.class)).isZero();
  }
  @Test void invalidPayloadGoesToDltWithoutBlocking() throws Exception {
    var bad=mapper.readTree(event(UUID.randomUUID(),UUID.randomUUID(),"BAD")).deepCopy();
    ((tools.jackson.databind.node.ObjectNode)bad.path("payload")).remove("sku");
    String body=mapper.writeValueAsString(bad); start(); publish(body); publish(event(UUID.randomUUID(),UUID.randomUUID(),"GOOD"));
    awaitSku("GOOD"); assertThat(poll("catalog.events.DLT",bad.path("event_id").asText())).isEqualTo(body); awaitCommitted();
  }
  @Test void catalogChangedIsIgnored() throws Exception {
    String body=event(UUID.randomUUID(),UUID.randomUUID(),"IGNORED").replace("VARIANT_CREATED","CATALOG_CHANGED");
    start(); publish(body); awaitCommitted();
    assertThat(jdbc.queryForObject("select count(*) from stock_items",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from processed_events",Integer.class)).isZero();
  }
  @Test void unsupportedHandlerVersionGoesToDltWithoutBlocking() throws Exception {
    var bad=(tools.jackson.databind.node.ObjectNode)mapper.readTree(event(UUID.randomUUID(),UUID.randomUUID(),"VERSION-BAD"));
    bad.put("version",2);String body=mapper.writeValueAsString(bad);
    start();publish(body);publish(event(UUID.randomUUID(),UUID.randomUUID(),"VERSION-GOOD"));
    awaitSku("VERSION-GOOD");assertThat(poll("catalog.events.DLT",bad.path("event_id").asText())).isEqualTo(body);awaitCommitted();
    assertThat(jdbc.queryForObject("select count(*) from stock_items where sku='VERSION-BAD'",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id=?::uuid",Integer.class,bad.path("event_id").asText())).isZero();
  }
  @Test void inventoryRelayPublishesSnapshot() throws Exception {
    start(); publish(event(UUID.randomUUID(),UUID.randomUUID(),"RELAY")); awaitSku("RELAY");
    String body=poll("inventory.events","RELAY"); var n=mapper.readTree(body);
    assertThat(n.path("event_type").asText()).isEqualTo("INVENTORY_UPDATED");
    assertThat(n.path("payload").path("sku").asText()).isEqualTo("RELAY"); awaitCommitted();
  }
  void start() { listeners.start(); }
  String event(UUID id,UUID variant,String sku) {
    UUID product=UUID.nameUUIDFromBytes(variant.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return mapper.writeValueAsString(Map.of("event_id",id,"event_type","VARIANT_CREATED","version",1,
        "occurred_at",Instant.now().toString(),"aggregate_id",product,"aggregate_version",0,
        "aggregate_sequence",1,"correlation_id","4bf92f3577b34da6a3ce929d0e0e4736",
        "payload",Map.of("product_id",product,"variant_id",variant,"sku",sku,"version",0)));
  }
  void publish(String body) throws Exception { producer.send(new ProducerRecord<>("catalog.events",0,"product",body)).get(10,TimeUnit.SECONDS); }
  AdminClient admin() { return AdminClient.create(Map.of("bootstrap.servers",kafka.getBootstrapServers())); }
  void awaitSku(String sku) throws Exception { await(()->jdbc.queryForObject("select count(*) from stock_items where sku=?",Integer.class,sku)==1); }
  void awaitCommitted() throws Exception {
    try(var a=admin()) {
      var ends=a.listOffsets(Map.of(new org.apache.kafka.common.TopicPartition("catalog.events",0),OffsetSpec.latest())).all().get();
      long wanted=ends.values().iterator().next().offset();
      await(()-> { try { var offset=a.listConsumerGroupOffsets("inventory-service").partitionsToOffsetAndMetadata().get().get(new org.apache.kafka.common.TopicPartition("catalog.events",0));
        return offset!=null && offset.offset()>=wanted; } catch(Exception e) { return false; } });
    }
  }
  void await(BooleanSupplier condition) throws Exception {
    long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
    while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(20);
    assertThat(condition.getAsBoolean()).isTrue();
  }
  String poll(String topic,String identity) {
    try(var c=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",kafka.getBootstrapServers(),"group.id",UUID.randomUUID().toString(),
        "auto.offset.reset","earliest","enable.auto.commit",false,"key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class))) {
      c.subscribe(List.of(topic)); long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
      while(System.nanoTime()<deadline) for(var record:c.poll(Duration.ofMillis(100))) {
        var n=mapper.readTree(record.value());
        if(identity.equals(n.path("event_id").asText()) || identity.equals(n.path("payload").path("sku").asText())) {
          if(topic.equals("inventory.events")) assertThat(record.key()).isEqualTo(identity);
          return record.value();
        }
      }
      throw new AssertionError("Expected record not received: "+topic);
    }
  }
}
