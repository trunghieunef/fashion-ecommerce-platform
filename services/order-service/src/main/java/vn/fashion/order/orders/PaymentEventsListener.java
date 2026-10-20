package vn.fashion.order.orders;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.InboxListenerSupport;

/** payment.events for order-service: inbox + effect in one transaction, ACK after commit (platform-messaging retry/DLT). */
@Component
public class PaymentEventsListener {
  private final InboxListenerSupport support;
  private final PaymentEvents events;
  public PaymentEventsListener(InboxGuard inbox, ObjectMapper json, PaymentEvents events) {
    support = new InboxListenerSupport(inbox, json, "order-service"); this.events = events;
  }
  @KafkaListener(topics = "payment.events", groupId = "order-service", autoStartup = "${spring.kafka.listener.auto-startup:true}")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    support.handle(record, ack, Map.of("PAYMENT_COMPLETED", events::apply, "PAYMENT_FAILED", events::apply,
        "PAYMENT_CREATED", events::apply));
  }
}
