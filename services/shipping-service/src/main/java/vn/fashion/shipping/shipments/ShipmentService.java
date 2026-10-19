package vn.fashion.shipping.shipments;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.idempotency.IdempotencyStore;
import vn.fashion.shipping.fee.FeeService;
import vn.fashion.shipping.rules.RuleConfig;
import vn.fashion.shipping.web.Api;

/**
 * SHP-01 spec section 5: one SELF shipment per order, created from exactly one quote, in one transaction (no HTTP).
 * SELF confirms at once, so PENDING -> CREATED happens before commit; hand-over and delivery are SHP-02.
 */
@Service
public class ShipmentService {
  public record Create(UUID orderId, String orderNo, UUID quoteId, Map<String, String> toAddress, long codAmount) {
    String canonical() { return orderId + "|" + orderNo + "|" + quoteId + "|" + codAmount + "|" + new java.util.TreeMap<>(toAddress); }
  }
  public record View(UUID shipmentId, UUID orderId, String orderNo, String status, String carrier, String trackingCode, long fee,
                     long codAmount, Instant handedOverAt, long version) { }
  private record Quote(UUID id, String provinceCode, boolean cod, long fee, int weightGrams) { }
  static final String ACTOR = "service:order-service";
  private static final Pattern PHONE = Pattern.compile("[0-9+ ]{6,32}");
  private static final String VIEW = """
      select id as shipment_id, order_id, order_no, status, carrier, tracking_code, fee, cod_amount, handed_over_at, version
      from shipments""";
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final IdempotencyStore keys;
  private final ShipmentEvents events;
  private final ObjectMapper json;
  private final String fromAddress;

  public ShipmentService(JdbcClient jdbc, TransactionTemplate tx, IdempotencyStore keys, ShipmentEvents events, ObjectMapper json,
                         @Value("${fashion.shipping.from-address}") String fromAddress) {
    this.jdbc = jdbc; this.tx = tx; this.keys = keys; this.events = events; this.json = json; this.fromAddress = fromAddress;
  }

  /** Returns a fresh View or the stored response; the same order with another body is 409 SHIPMENT_CONFLICT. */
  public Object create(String key, Create c, String correlationId) {
    FeeService.requireKey(key);
    String hash = FeeService.sha256(c.canonical());
    return tx.execute(status -> {
      var outcome = keys.begin(ACTOR, "shipping.shipment.create", key, hash);
      if (outcome.status() == IdempotencyStore.Status.CONFLICT) throw conflict("IDEMPOTENCY_KEY_REUSED");
      if (outcome.status() == IdempotencyStore.Status.COMPLETED) return (Object) json.readTree(outcome.responseBody());
      // Serialize every create that uses this quote (same order retried, or another order reusing it). Quotes are
      // insert-only for the runtime role, so a transaction-scoped advisory lock replaces SELECT ... FOR UPDATE.
      jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:q, 0))").param("q", c.quoteId().toString()).query((rs, n) -> 1).list();
      var quote = jdbc.sql("select id, province_code, cod, fee, weight_grams from shipping_quotes where id = :id")
          .param("id", c.quoteId()).query(Quote.class).optional()
          .orElseThrow(() -> new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "QUOTE_NOT_FOUND", List.of()));
      var existing = byOrder(c.orderId());
      if (existing.isPresent()) {
        String storedHash = jdbc.sql("select request_hash from shipments where order_id = :o").param("o", c.orderId()).query(String.class).single();
        if (!storedHash.equals(hash)) throw conflict("SHIPMENT_CONFLICT");
        keys.finish(ACTOR, "shipping.shipment.create", key, existing.get().shipmentId(), 200, json.writeValueAsString(existing.get()));
        return existing.get();
      }
      if (!quote.provinceCode().equals(c.toAddress().get("province_code")) || quote.cod() != (c.codAmount() > 0))
        throw conflict("QUOTE_MISMATCH");
      if (jdbc.sql("select count(*) from shipments where quote_id = :q").param("q", c.quoteId()).query(Long.class).single() > 0)
        throw conflict("QUOTE_ALREADY_USED");
      UUID id = UUID.randomUUID();
      int inserted = jdbc.sql("""
          insert into shipments(id, order_id, order_no, quote_id, carrier, provider_request_id, fee, weight_grams, from_address, to_address,
            cod_amount, request_hash)
          values (:id, :o, :no, :q, 'SELF', :req, :fee, :w, cast(:from as jsonb), cast(:to as jsonb), :cod, :h)
          on conflict do nothing""")
          .param("id", id).param("o", c.orderId()).param("no", c.orderNo()).param("q", c.quoteId()).param("req", "SELF-" + c.orderId())
          .param("fee", quote.fee()).param("w", quote.weightGrams()).param("from", fromAddress).param("to", json.writeValueAsString(c.toAddress()))
          .param("cod", c.codAmount()).param("h", hash).update();
      if (inserted == 0) throw conflict("SHIPMENT_CONFLICT"); // order_no taken by another order
      history(id, "create", null, "PENDING");
      jdbc.sql("update shipments set status = 'CREATED', tracking_code = :t, version = version + 1, updated_at = now() where id = :id")
          .param("t", "SELF-" + c.orderNo()).param("id", id).update();
      history(id, "self-created", "PENDING", "CREATED");
      var view = byOrder(c.orderId()).orElseThrow();
      events.created(view, correlationId);
      keys.finish(ACTOR, "shipping.shipment.create", key, id, 200, json.writeValueAsString(view));
      return view;
    });
  }

  public Optional<View> byOrder(UUID orderId) {
    return jdbc.sql(VIEW + " where order_id = :o").param("o", orderId).query(View.class).optional();
  }

  private void history(UUID shipment, String key, String from, String to) {
    jdbc.sql("""
        insert into shipment_status_history(id, shipment_id, provider_event_key, from_status, to_status, applied)
        values (:id, :s, :k, :f, :t, true)""")
        .param("id", UUID.randomUUID()).param("s", shipment).param("k", key).param("f", from).param("t", to).update();
  }

  /** order_id, order_no 1..32, quote_id, cod_amount >= 0, to_address with recipient/phone/province/ward/address line. */
  public static Create parse(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    UUID order = uuid(body.path("order_id"), "order_id", errors);
    UUID quote = uuid(body.path("quote_id"), "quote_id", errors);
    String orderNo = text(body.path("order_no"), "order_no", 32, true, errors);
    JsonNode cod = body.path("cod_amount");
    long codAmount = cod.isIntegralNumber() && cod.canConvertToLong() && cod.longValue() >= 0 ? cod.longValue() : -1;
    if (codAmount < 0) errors.add(new Api.FieldError("cod_amount", "must be an integer VND >= 0"));
    JsonNode a = body.path("to_address");
    var address = new LinkedHashMap<String, String>();
    if (!a.isObject()) errors.add(new Api.FieldError("to_address", "must be an object"));
    else {
      for (String name : a.propertyNames())
        if (!List.of("recipient_name", "phone", "province_code", "district_code", "ward_code", "address_line").contains(name))
          errors.add(new Api.FieldError("to_address." + name, "is not allowed"));
      put(address, "recipient_name", text(a.path("recipient_name"), "to_address.recipient_name", 200, true, errors));
      String phone = text(a.path("phone"), "to_address.phone", 32, true, errors);
      if (phone != null && !PHONE.matcher(phone).matches()) errors.add(new Api.FieldError("to_address.phone", "must be 6..32 digits, + or space"));
      put(address, "phone", phone);
      for (String code : List.of("province_code", "district_code", "ward_code")) {
        String value = text(a.path(code), "to_address." + code, 32, !code.equals("district_code"), errors);
        if (value != null && !RuleConfig.PROVINCE.matcher(value).matches())
          errors.add(new Api.FieldError("to_address." + code, "must be 1..32 of A-Z a-z 0-9 _ -"));
        put(address, code, value);
      }
      put(address, "address_line", text(a.path("address_line"), "to_address.address_line", 500, true, errors));
    }
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    return new Create(order, orderNo, quote, address, codAmount);
  }

  private static void put(Map<String, String> map, String key, String value) { if (value != null) map.put(key, value); }
  private static String text(JsonNode node, String field, int max, boolean required, List<Api.FieldError> errors) {
    if (node.isMissingNode() || node.isNull()) { if (required) errors.add(new Api.FieldError(field, "is required")); return null; }
    String value = node.isString() ? node.asString().strip() : "";
    if (value.isEmpty() || value.length() > max) { errors.add(new Api.FieldError(field, "must be 1.." + max + " characters")); return null; }
    return value;
  }
  private static UUID uuid(JsonNode node, String field, List<Api.FieldError> errors) {
    try {
      if (node.isString()) { UUID id = UUID.fromString(node.asString()); if (id.toString().equals(node.asString())) return id; }
    } catch (IllegalArgumentException ignored) { }
    errors.add(new Api.FieldError(field, "must be a lowercase UUID"));
    return null;
  }
  private static Api.Problem conflict(String message) { return new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", message, List.of()); }
}
