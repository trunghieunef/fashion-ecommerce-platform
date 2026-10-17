package vn.fashion.catalog.stock;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.InboxListenerSupport;

/** inventory.events: stock snapshots only; reservation lifecycle events are acknowledged without effect. */
@Component
public class InventoryEventsListener {
  private final InboxListenerSupport support;
  private final StockObservations stock;
  public InventoryEventsListener(InboxGuard inbox, ObjectMapper json, StockObservations stock) {
    this.support = new InboxListenerSupport(inbox, json, "catalog-service"); this.stock = stock;
  }
  @KafkaListener(topics = "inventory.events", groupId = "catalog-service", autoStartup = "${spring.kafka.listener.auto-startup:true}")
  public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    support.handle(record, ack, Map.of("INVENTORY_UPDATED", stock::apply, "INVENTORY_RESTOCKED", stock::apply));
  }
}
