package vn.fashion.order.deps;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.fashion.order.saga.ReservationHash;
import vn.fashion.order.saga.SagaOrder;

/** INV-02 internal reservation: reserve once per order (key reserve:{id}), release with the original snapshot and hash. */
@Component
public class InventoryClient {
  private final JsonHttp http;
  private final String url;

  public InventoryClient(JsonHttp http, @Value("${fashion.order.inventory-url}") String url) { this.http = http; this.url = url; }

  public Outcome reserve(SagaOrder o) {
    var body = snapshot(o);
    body.put("order_id", o.id().toString());
    body.put("request_hash", hash(o));
    return Outcome.of("inventory", http, "inventory-service", url + "/internal/api/v1/inventory/reserve", body,
        "Idempotency-Key", "reserve:" + o.id());
  }

  /** ORD-03: deducts the held stock once (key commit:{id}); a repeat answers 200 COMMITTED, the 409 message is the code. */
  public Outcome commit(SagaOrder o) {
    return Outcome.of("inventory", http, "inventory-service", url + "/internal/api/v1/inventory/commit",
        Map.of("order_id", o.id().toString()), "Idempotency-Key", "commit:" + o.id());
  }

  /**
   * RELEASED/EXPIRED (also a tombstone before any reserve) is OK; COMMITTED is OK too with data.restore_required true: stock
   * was taken, so the caller must restore it (ORD-04 spec section 4), never treat it as released.
   */
  public Outcome release(SagaOrder o, String reason) {
    var body = Map.of("order_id", o.id().toString(), "original_reservation", snapshot(o), "original_request_hash", hash(o),
        "reason", reason);
    return Outcome.of("inventory", http, "inventory-service", url + "/internal/api/v1/inventory/release", body,
        "Idempotency-Key", "release:" + o.id());
  }

  /** INV-03 /returns for the full ordered quantity; Idempotency-Key = operation_key, a replay must send the same body. */
  public Outcome restore(SagaOrder o, String operationKey, String reason, String evidenceRef) {
    var body = new LinkedHashMap<String, Object>();
    body.put("order_id", o.id().toString());
    body.put("operation_key", operationKey);
    body.put("reason", reason);
    body.put("evidence_ref", evidenceRef);
    body.put("items", snapshot(o).get("items"));
    return Outcome.of("inventory", http, "inventory-service", url + "/internal/api/v1/inventory/returns", body,
        "Idempotency-Key", operationKey);
  }

  static String hash(SagaOrder o) { return ReservationHash.of(o.id(), o.orderNo(), o.expiresAt(), skus(o)); }
  private static Map<String, Integer> skus(SagaOrder o) {
    var skus = new LinkedHashMap<String, Integer>();
    o.items().forEach(i -> skus.merge(i.sku(), i.quantity(), Integer::sum));
    return skus;
  }
  private static LinkedHashMap<String, Object> snapshot(SagaOrder o) {
    var body = new LinkedHashMap<String, Object>();
    body.put("order_no", o.orderNo());
    body.put("items", ReservationHash.canonical(skus(o)).entrySet().stream()
        .map(e -> Map.of("sku", e.getKey(), "quantity", e.getValue())).toList());
    body.put("expires_at", ReservationHash.time(o.expiresAt()));
    return body;
  }
}
