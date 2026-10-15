package vn.fashion.platform.messaging;

import java.util.Map;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.inbox.InboxGuard;

public class InboxListenerSupport {
  private final InboxGuard inbox;
  private final ObjectMapper json;
  private final String consumerName;
  public InboxListenerSupport(InboxGuard inbox, ObjectMapper json, String consumerName) {
    this.inbox = inbox; this.json = json; this.consumerName = consumerName;
  }
  public void handle(ConsumerRecord<String, byte[]> record, Acknowledgment ack,
                     Map<String, Function<EventEnvelope, Object>> handlers) {
    var envelope = EventEnvelope.parse(record.value(), json);
    var handler = handlers.get(envelope.eventType());
    if (handler == null) { ack.acknowledge(); return; }
    inbox.applyOnce(consumerName, envelope.eventId(), () -> handler.apply(envelope));
    ack.acknowledge();
  }
}
