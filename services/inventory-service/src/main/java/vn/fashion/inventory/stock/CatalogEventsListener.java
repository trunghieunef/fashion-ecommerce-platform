package vn.fashion.inventory.stock;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.InboxListenerSupport;

@Component
public class CatalogEventsListener {
  private final InboxListenerSupport support;
  private final SkuRegistration registration;
  public CatalogEventsListener(InboxGuard inbox,ObjectMapper json,SkuRegistration registration) {
    support=new InboxListenerSupport(inbox,json,"inventory-service");this.registration=registration;
  }
  @KafkaListener(topics="catalog.events",groupId="inventory-service",autoStartup="${spring.kafka.listener.auto-startup:true}")
  public void receive(ConsumerRecord<String,byte[]> record,Acknowledgment ack) {
    support.handle(record,ack,Map.of("VARIANT_CREATED",registration::register));
  }
}
