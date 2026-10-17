package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class ReservationEventRelayIntegrationTest extends ReservationTestSupport {
  @DynamicPropertySource static void relay(DynamicPropertyRegistry registry) {
    registry.add("fashion.messaging.relay.enabled",()->"true");
  }
  @Test void reservedEventReachesKafkaKeyedByOrderNo() throws Exception {
    try(var admin=AdminClient.create(Map.of("bootstrap.servers",kafka.getBootstrapServers()))) {
      try { admin.createTopics(List.of(new NewTopic("inventory.events",3,(short)1))).all().get(10,TimeUnit.SECONDS); }
      catch(java.util.concurrent.ExecutionException e) {
        if(!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) throw e;
      }
    }
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);data(reserve(s),201);
    var props=Map.<String,Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG,"inv02-"+UUID.randomUUID(),ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
    try(var consumer=new KafkaConsumer<String,String>(props)) {
      consumer.subscribe(List.of("inventory.events"));
      long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
      while(System.nanoTime()<deadline) {
        for(var record:consumer.poll(Duration.ofMillis(500))) {
          var event=mapper.readTree(record.value());
          if("INVENTORY_RESERVED".equals(event.path("event_type").asString())
              && s.orderId().toString().equals(event.path("payload").path("order_id").asString())) {
            assertThat(record.key()).isEqualTo(s.orderNo());
            assertThat(event.path("payload").path("order_id").asString()).isEqualTo(s.orderId().toString());
            assertThat(event.path("aggregate_version").asLong()).isEqualTo(1);
            return;
          }
        }
      }
      fail("INVENTORY_RESERVED not relayed within 20s");
    }
  }
}
