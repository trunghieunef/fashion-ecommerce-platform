package vn.fashion.inventory.reservation;

import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.inventory.stock.StockEvents;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

@Component
public class ReservationEvents {
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final JsonNode routes;
  public ReservationEvents(OutboxRepository outbox,ObjectMapper json) {
    this.outbox=outbox;this.json=json;this.routes=StockEvents.loadRoutes(json);
  }
  public void append(String type,ReservationRow row,List<ReservationSnapshot.Item> items,String correlationId) {
    var payload=new LinkedHashMap<String,Object>();
    payload.put("order_id",row.orderId().toString());payload.put("order_no",row.orderNo());
    payload.put("reservation_id",row.id().toString());payload.put("status",row.status());
    payload.put("expires_at",ReservationSnapshot.time(row.expiresAt()));
    payload.put("items",items.stream().map(i->Map.of("sku",i.sku(),"quantity",i.quantity())).toList());
    payload.put("reason",row.releaseReason());payload.put("version",row.version());
    var route=routes.required(type);
    outbox.append(new OutboxEvent(UUID.randomUUID(),"reservation",row.id().toString(),row.version(),type,1,
        route.required("topic").asText(),row.orderNo(),correlationId,json.writeValueAsString(payload)));
  }
}
