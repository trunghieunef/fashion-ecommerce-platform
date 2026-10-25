package vn.fashion.payment.payments;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

/** payment.events through the outbox, routed by contracts/events/registry.json (03 section 5.2/5.3). */
@Component
public class PaymentEvents {
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final JsonNode routes;

  public PaymentEvents(OutboxRepository outbox, ObjectMapper json) {
    this.outbox = outbox; this.json = json;
    try (var resource = PaymentEvents.class.getResourceAsStream("/contracts/events/registry.json")) {
      if (resource == null) throw new IllegalStateException("Event registry missing");
      this.routes = json.readTree(resource);
    } catch (IOException e) { throw new IllegalStateException("Cannot read event registry", e); }
  }

  public void created(PaymentService.View view, String correlationId) { append("PAYMENT_CREATED", view, null, null, correlationId); }
  /** Close: reason CLOSED (spec section 4). */
  public void failed(PaymentService.View view, String reason, String correlationId) { append("PAYMENT_FAILED", view, reason, null, correlationId); }
  /** COD money confirmed by FINANCE; also on a closed payment, where the order owes a refund. */
  public void completed(PaymentService.View view, String correlationId) { append("PAYMENT_COMPLETED", view, null, null, correlationId); }
  /** TASK:PAY-02: provider money verified (VNPay IPN/QueryDR) carries the provider transaction number. */
  public void completed(PaymentService.View view, String providerTxnId, String correlationId) {
    append("PAYMENT_COMPLETED", view, null, providerTxnId, correlationId);
  }

  private void append(String type, PaymentService.View view, String reason, String providerTxnId, String correlationId) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", view.orderId().toString());
    payload.put("order_no", view.orderNo());
    payload.put("payment_id", view.paymentId().toString());
    payload.put("method", view.method());
    payload.put("amount", view.amount());
    payload.put("currency", view.currency());
    payload.put("status", view.status());
    payload.put("paid_at", view.paidAt() == null ? null : view.paidAt().toString());
    payload.put("provider_txn_id", providerTxnId);
    payload.put("reason", reason);
    var route = routes.required(type);
    outbox.append(new OutboxEvent(UUID.randomUUID(), "payment", (String) payload.get(route.required("aggregate_id").asString()),
        view.version(), type, 1, route.required("topic").asString(),
        (String) payload.get(route.required("partition_key").asString()), correlationId, json.writeValueAsString(payload)));
  }
}
