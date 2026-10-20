package vn.fashion.order.deps;

import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.fashion.order.saga.ReservationHash;
import vn.fashion.order.saga.SagaOrder;

/** PAY-01 internal payment: create and close send the same order snapshot (payment hashes it, PAY-08 tombstone). */
@Component
public class PaymentClient {
  private final JsonHttp http;
  private final String url;

  public PaymentClient(JsonHttp http, @Value("${fashion.order.payment-url}") String url) { this.http = http; this.url = url; }

  public Outcome create(SagaOrder o) {
    return Outcome.of("payment", http, "payment-service", url + "/internal/api/v1/payments", snapshot(o),
        "Idempotency-Key", "payment:" + o.id());
  }

  public Outcome close(SagaOrder o, String reason) {
    var body = snapshot(o);
    body.put("reason", reason);
    return Outcome.of("payment", http, "payment-service", url + "/internal/api/v1/payments/by-order/" + o.id() + "/close", body,
        "Idempotency-Key", "close:" + o.id());
  }

  private static LinkedHashMap<String, Object> snapshot(SagaOrder o) {
    var body = new LinkedHashMap<String, Object>();
    body.put("order_id", o.id().toString());
    body.put("order_no", o.orderNo());
    body.put("user_id", o.userId() == null ? null : o.userId().toString());
    body.put("method", o.method());
    body.put("amount", o.total());
    body.put("currency", "VND");
    body.put("expires_at", ReservationHash.time(o.expiresAt()));
    return body;
  }
}
