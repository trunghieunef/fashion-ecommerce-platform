package vn.fashion.inventory.stock;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

@Component
public class StockEvents {
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final JsonNode routes;
  public StockEvents(OutboxRepository outbox,ObjectMapper json) {
    this.outbox=outbox;this.json=json;
    try(var resource=StockEvents.class.getResourceAsStream("/contracts/events/registry.json")) {
      if(resource==null) throw new IllegalStateException("Event registry missing");
      routes=json.readTree(resource);
    } catch(IOException e) { throw new IllegalStateException("Cannot read event registry",e); }
  }
  public void append(StockRow after,boolean restocked,String correlationId) {
    var payload=json.valueToTree(Map.of("sku",after.sku(),"on_hand",after.onHand(),"reserved",after.reserved(),
        "available",after.available(),"stock_version",after.version(),"observed_at",after.updatedAt().toString()));
    append("INVENTORY_UPDATED",after,payload,correlationId);
    if(restocked) append("INVENTORY_RESTOCKED",after,payload,correlationId);
  }
  private void append(String type,StockRow row,JsonNode payload,String correlationId) {
    var route=routes.required(type);
    outbox.append(new OutboxEvent(UUID.randomUUID(),"stock",payload.required(route.required("aggregate_id").asText()).asText(),
        row.version(),type,1,route.required("topic").asText(),payload.required(route.required("partition_key").asText()).asText(),
        correlationId,json.writeValueAsString(payload)));
  }
}
