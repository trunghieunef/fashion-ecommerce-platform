package vn.fashion.payment.payments;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.InboxListenerSupport;

/** shipping.events: only COD evidence matters to payment; other types are acknowledged. ACK after the inbox commit. */
@Component
public class ShippingEventsListener {
  private final InboxListenerSupport support;
  private final CodEvidence evidence;
  public ShippingEventsListener(InboxGuard inbox, ObjectMapper json, CodEvidence evidence) {
    support = new InboxListenerSupport(inbox, json, "payment-service"); this.evidence = evidence;
  }
  @KafkaListener(topics = "shipping.events", groupId = "payment-service", autoStartup = "${spring.kafka.listener.auto-startup:true}")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    support.handle(record, ack, Map.of("COD_COLLECTED", evidence::apply, "COD_REMITTED", evidence::apply));
  }
}
