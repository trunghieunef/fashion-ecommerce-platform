package vn.fashion.catalog.sales;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.InboxListenerSupport;

/** order.events: only ORDER_COMPLETED feeds sold_quantity; other order events are acknowledged. */
@Component
public class OrderEventsListener {
  private final InboxListenerSupport support;
  private final SoldQuantities sold;
  public OrderEventsListener(InboxGuard inbox, ObjectMapper json, SoldQuantities sold) {
    this.support = new InboxListenerSupport(inbox, json, "catalog-service"); this.sold = sold;
  }
  @KafkaListener(topics = "order.events", groupId = "catalog-service", autoStartup = "${spring.kafka.listener.auto-startup:true}")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    support.handle(record, ack, Map.of("ORDER_COMPLETED", sold::apply));
  }
}
