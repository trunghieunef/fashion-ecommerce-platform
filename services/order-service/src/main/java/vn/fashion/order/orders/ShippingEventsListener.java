package vn.fashion.order.orders;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.InboxListenerSupport;

/**
 * shipping.events for order-service: inbox + effect in one transaction, ACK after commit. Shares the "order-service" inbox
 * consumer name with payment.events: event_id is a UUID, so the two topics never collide.
 */
@Component
public class ShippingEventsListener {
  private final InboxListenerSupport support;
  private final ShippingEvents events;
  public ShippingEventsListener(InboxGuard inbox, ObjectMapper json, ShippingEvents events) {
    support = new InboxListenerSupport(inbox, json, "order-service"); this.events = events;
  }
  @KafkaListener(topics = "shipping.events", groupId = "order-service", autoStartup = "${spring.kafka.listener.auto-startup:true}")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    support.handle(record, ack, Map.of("SHIPMENT_CREATED", events::apply, "SHIPMENT_STATUS_UPDATED", events::apply,
        "COD_COLLECTED", events::apply, "COD_REMITTED", events::apply));
  }
}
