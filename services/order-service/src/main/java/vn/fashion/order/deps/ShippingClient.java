package vn.fashion.order.deps;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.fashion.order.saga.SagaOrder;
import vn.fashion.order.web.Api;

/** SHP-01 stored fee quote (internal); the order snapshots it. */
@Component
public class ShippingClient {
  public record Line(UUID variantId, int quantity) { }
  public record Quote(UUID quoteId, String carrier, int weightGrams, long fee, long codFee, Instant expiresAt) { }
  private final JsonHttp http;
  private final String url;

  public ShippingClient(JsonHttp http, @Value("${fashion.order.shipping-url}") String url) { this.http = http; this.url = url; }

  /** A new key per call: every quote is a new stored resource. Shipping 400/422 reach the customer unchanged. */
  public Quote quote(String provinceCode, boolean cod, List<Line> items) {
    var body = Map.of("province_code", provinceCode, "cod", cod, "items",
        items.stream().map(l -> Map.of("variant_id", l.variantId().toString(), "quantity", l.quantity())).toList());
    var reply = http.call("shipping", "shipping-service", url + "/internal/api/v1/shipping/quotes", body,
        "Idempotency-Key", UUID.randomUUID().toString());
    if (reply.status() == 422 || reply.status() == 400)
      throw new Api.Problem(HttpStatus.valueOf(reply.status()), "VALIDATION_ERROR", reply.message(), List.of());
    if (reply.status() != 200) throw new DependencyUnavailable("shipping");
    try {
      var d = reply.data();
      if (!d.path("fee").isIntegralNumber() || d.path("fee").longValue() < 0 || !d.path("cod_fee").isIntegralNumber()
          || d.path("cod_fee").longValue() < 0) throw new DependencyUnavailable("shipping");
      return new Quote(UUID.fromString(d.path("quote_id").asString()), d.path("carrier").asString(), d.path("weight_grams").asInt(),
          d.path("fee").longValue(), d.path("cod_fee").longValue(), Instant.parse(d.path("expires_at").asString()));
    } catch (DependencyUnavailable e) { throw e; }
    catch (RuntimeException e) { throw new DependencyUnavailable("shipping"); }
  }

  /** SHP-01 SELF shipment for a packed order (key shipment:{id}); cod_amount is what the courier collects. */
  public Outcome create(SagaOrder o) {
    var body = new LinkedHashMap<String, Object>();
    body.put("order_id", o.id().toString());
    body.put("order_no", o.orderNo());
    body.put("quote_id", o.quoteId().toString());
    body.put("cod_amount", o.method().equals("COD") ? o.total() : 0L);
    body.put("to_address", o.toAddress());
    return Outcome.of("shipping", http, "shipping-service", url + "/internal/api/v1/shipping/shipments", body,
        "Idempotency-Key", "shipment:" + o.id());
  }
}
