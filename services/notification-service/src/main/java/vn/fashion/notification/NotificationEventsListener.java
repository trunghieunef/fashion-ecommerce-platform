package vn.fashion.notification;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.InboxListenerSupport;

/** notification.events: NOTIFY_ORDER_* become email rows; other types are acknowledged. ACK after the inbox commit. */
@Component
public class NotificationEventsListener {
  private final InboxListenerSupport support;
  private final NotificationIntake intake;
  public NotificationEventsListener(InboxGuard inbox, ObjectMapper json, NotificationIntake intake) {
    support = new InboxListenerSupport(inbox, json, "notification-service"); this.intake = intake;
  }
  @KafkaListener(topics = "notification.events", groupId = "notification-service", autoStartup = "${spring.kafka.listener.auto-startup:true}")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    support.handle(record, ack, Map.of("NOTIFY_ORDER_CONFIRMED", intake::accept, "NOTIFY_ORDER_PREPARING", intake::accept,
        "NOTIFY_ORDER_DELIVERED", intake::accept, "NOTIFY_ORDER_CANCELLED", intake::accept, "NOTIFY_ORDER_RETURNED", intake::accept));
  }
}
