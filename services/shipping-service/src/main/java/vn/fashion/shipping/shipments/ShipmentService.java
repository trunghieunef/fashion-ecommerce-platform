package vn.fashion.shipping.shipments;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
import vn.fashion.shipping.rules.RuleConfig;
import vn.fashion.shipping.web.Api;

/**
 * SHP-01 spec section 5: one SELF shipment per order, created from exactly one quote, in one transaction (no HTTP).
 * SELF confirms at once, so PENDING -> CREATED happens before commit; hand-over and delivery are SelfEventService (SHP-02).
 */
@Service
public class ShipmentService {
  public record Create(UUID orderId, String orderNo, UUID quoteId, Map<String, String> toAddress, long codAmount) { }
  public record View(UUID shipmentId, UUID orderId, String orderNo, String status, String carrier, String trackingCode, long fee,
                     long codAmount, Instant handedOverAt, long version) { }
  private record Quote(UUID id, String provinceCode, boolean cod, long fee, int weightGrams) { }
  private record Current(UUID id, String status, String requestHash) { }
  /** SELF can cancel at once until hand-over (SHP-02 spec section 3.2). */
  private static final Set<String> CANCELLABLE = Set.of("PENDING", "CREATED", "PICKING");
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
    String hash = hash(c);
    return tx.execute(status -> {
      var replay = Api.replay(keys.begin(ACTOR, "shipping.shipment.create", key, hash), json);
      if (replay != null) return replay;
      lockQuote(c.quoteId());
      // Review 5: an order that already has a shipment answers first (same body -> it, other body -> 409).
      var existing = byOrder(c.orderId());
      if (existing.isPresent()) {
        // SHP-02: a cancelled shipment or a cancel-before-create tombstone refuses every late create, same body or not.
        if (existing.get().status().equals("CANCELLED")) throw conflict("SHIPMENT_CANCELLED");
        if (!requestHash(c.orderId()).equals(hash)) throw conflict("SHIPMENT_CONFLICT");
        keys.finish(ACTOR, "shipping.shipment.create", key, existing.get().shipmentId(), 200, json.writeValueAsString(existing.get()));
        return existing.get();
      }
      var quote = usableQuote(c);
      UUID id = UUID.randomUUID();
      if (!insert(id, c, quote, hash, "PENDING")) throw lateConflict(c.orderId());
      history(id, "create", null, "PENDING");
      jdbc.sql("update shipments set status = 'CREATED', tracking_code = :t, version = version + 1, updated_at = now() where id = :id")
          .param("t", "SELF-" + c.orderNo()).param("id", id).update();
      history(id, "self-created", "PENDING", "CREATED");
      var view = byId(id);
      events.created(view, correlationId);
      keys.finish(ACTOR, "shipping.shipment.create", key, id, 200, json.writeValueAsString(view));
      return view;
    });
  }

  /**
   * SHP-02 spec section 3.2: the body is the original create snapshot. Before hand-over SELF cancels at once; without a
   * shipment a CANCELLED tombstone blocks a late create; after hand-over 409, so the order never restores stock.
   */
  public Object cancel(String key, UUID pathOrderId, Create c, String reason, String correlationId) {
    if (!c.orderId().equals(pathOrderId))
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS",
          List.of(new Api.FieldError("order_id", "must equal the order_id of the path")));
    String hash = hash(c);
    String requestHash = Api.sha256(hash + "\n" + reason);
    return tx.execute(status -> {
      var replay = Api.replay(keys.begin(ACTOR, "shipping.shipment.cancel", key, requestHash), json);
      if (replay != null) return replay;
      lockQuote(c.quoteId()); // same lock as create: a create and a cancel of one quote never interleave
      var current = jdbc.sql("select id, status, request_hash from shipments where order_id = :o for update")
          .param("o", c.orderId()).query(Current.class).optional();
      View view;
      if (current.isEmpty()) {
        var quote = usableQuote(c);
        UUID id = UUID.randomUUID();
        if (!insert(id, c, quote, hash, "CANCELLED")) throw lateConflict(c.orderId());
        history(id, "cancel-before-create", null, "CANCELLED", null, reason, "{}");
        view = byId(id);
        events.statusUpdated(view, null, correlationId);
      } else if (current.get().status().equals("CANCELLED")) {
        view = byId(current.get().id());
      } else {
        if (!current.get().requestHash().equals(hash)) throw conflict("SHIPMENT_CONFLICT");
        if (!CANCELLABLE.contains(current.get().status()))
          throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "SHIPMENT_HANDED_OVER",
              List.of(new Api.FieldError("status", "current=" + current.get().status())));
        jdbc.sql("update shipments set status = 'CANCELLED', version = version + 1, updated_at = now() where id = :id")
            .param("id", current.get().id()).update();
        history(current.get().id(), "cancel", current.get().status(), "CANCELLED", null, reason, "{}");
        view = byId(current.get().id());
        events.statusUpdated(view, null, correlationId);
      }
      keys.finish(ACTOR, "shipping.shipment.cancel", key, view.shipmentId(), 200, json.writeValueAsString(view));
      return view;
    });
  }

  public Optional<View> byOrder(UUID orderId) {
    return jdbc.sql(VIEW + " where order_id = :o").param("o", orderId).query(View.class).optional();
  }
  View byId(UUID id) {
    return jdbc.sql(VIEW + " where id = :id").param("id", id).query(View.class).single();
  }

  /** OPS detail (permission shipping.read): addresses, COD amounts and history with the sanitized evidence. */
  public Map<String, Object> adminDetail(UUID shipmentId) {
    var row = jdbc.sql("""
        select id, order_id, order_no, quote_id, status, carrier, tracking_code, fee, weight_grams, cod_amount, cod_collected_amount,
          cod_remitted_amount, handed_over_at, assigned_to, version, created_at, from_address::text as from_address, to_address::text as to_address
        from shipments where id = :id""").param("id", shipmentId).query().listOfRows().stream().findFirst()
        .orElseThrow(ShipmentController::notFound);
    var detail = new LinkedHashMap<String, Object>();
    row.forEach((k, v) -> detail.put(k.equals("id") ? "shipment_id" : k,
        k.endsWith("_address") ? json.readTree((String) v) : v instanceof Timestamp t ? t.toInstant() : v));
    detail.put("history", jdbc.sql("""
        select from_status, to_status, provider_event_key, applied, reason, provider_occurred_at, sanitized_payload::text as payload,
          created_at from shipment_status_history where shipment_id = :id order by created_at, id""").param("id", shipmentId)
        .query((rs, n) -> {
          var h = new LinkedHashMap<String, Object>();
          h.put("from_status", rs.getString("from_status"));
          h.put("to_status", rs.getString("to_status"));
          h.put("provider_event_key", rs.getString("provider_event_key"));
          h.put("applied", rs.getBoolean("applied"));
          h.put("reason", rs.getString("reason"));
          var at = rs.getTimestamp("provider_occurred_at");
          h.put("provider_occurred_at", at == null ? null : at.toInstant());
          h.put("evidence", json.readTree(rs.getString("payload")));
          h.put("created_at", rs.getTimestamp("created_at").toInstant());
          return h;
        }).list());
    return detail;
  }

  /** Review 4: canonical JSON (sorted keys), so moving text between fields is another body, never a retry. */
  private String hash(Create c) {
    var canonical = new TreeMap<String, Object>();
    canonical.put("order_id", c.orderId().toString());
    canonical.put("order_no", c.orderNo());
    canonical.put("quote_id", c.quoteId().toString());
    canonical.put("cod_amount", c.codAmount());
    canonical.put("to_address", new TreeMap<>(c.toAddress()));
    return Api.sha256(json.writeValueAsString(canonical));
  }
  /** Quotes are insert-only for the runtime role, so a transaction-scoped advisory lock replaces SELECT ... FOR UPDATE. */
  private void lockQuote(UUID quoteId) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:q, 0))").param("q", quoteId.toString()).query((rs, n) -> 1).list();
  }
  private String requestHash(UUID orderId) {
    return jdbc.sql("select request_hash from shipments where order_id = :o").param("o", orderId).query(String.class).single();
  }
  /** The quote exists, matches the order's province and COD, and no other shipment (or tombstone) used it. */
  private Quote usableQuote(Create c) {
    var quote = jdbc.sql("select id, province_code, cod, fee, weight_grams from shipping_quotes where id = :id")
        .param("id", c.quoteId()).query(Quote.class).optional()
        .orElseThrow(() -> new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "QUOTE_NOT_FOUND", List.of()));
    if (!quote.provinceCode().equals(c.toAddress().get("province_code")) || quote.cod() != (c.codAmount() > 0))
      throw conflict("QUOTE_MISMATCH");
    if (jdbc.sql("select count(*) from shipments where quote_id = :q").param("q", c.quoteId()).query(Long.class).single() > 0)
      throw conflict("QUOTE_ALREADY_USED");
    return quote;
  }
  private boolean insert(UUID id, Create c, Quote quote, String hash, String status) {
    return jdbc.sql("""
        insert into shipments(id, order_id, order_no, quote_id, carrier, provider_request_id, status, fee, weight_grams, from_address,
          to_address, cod_amount, request_hash)
        values (:id, :o, :no, :q, 'SELF', :req, :status, :fee, :w, cast(:from as jsonb), cast(:to as jsonb), :cod, :h)
        on conflict do nothing""")
        .param("id", id).param("o", c.orderId()).param("no", c.orderNo()).param("q", c.quoteId()).param("req", "SELF-" + c.orderId())
        .param("status", status).param("fee", quote.fee()).param("w", quote.weightGrams()).param("from", fromAddress)
        .param("to", json.writeValueAsString(c.toAddress())).param("cod", c.codAmount()).param("h", hash).update() == 1;
  }
  /** Insert lost: the order got a shipment under another quote meanwhile, or order_no belongs to another order. */
  private Api.Problem lateConflict(UUID orderId) {
    return byOrder(orderId).filter(v -> v.status().equals("CANCELLED")).isPresent() ? conflict("SHIPMENT_CANCELLED") : conflict("SHIPMENT_CONFLICT");
  }

  private void history(UUID shipment, String key, String from, String to) {
    history(shipment, key, from, to, null, null, "{}");
  }
  /** clock_timestamp(), not now(): rows of one transaction must keep their order for (created_at, id) reads. */
  void history(UUID shipment, String key, String from, String to, Instant occurredAt, String reason, String payload) {
    jdbc.sql("""
        insert into shipment_status_history(id, shipment_id, provider_event_key, from_status, to_status, provider_occurred_at,
          sanitized_payload, applied, reason, created_at)
        values (:id, :s, :k, :f, :t, :at, cast(:p as jsonb), true, :r, clock_timestamp())""")
        .param("id", UUID.randomUUID()).param("s", shipment).param("k", key).param("f", from).param("t", to)
        .param("at", occurredAt == null ? null : Timestamp.from(occurredAt)).param("p", payload).param("r", reason).update();
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
    UUID id = node.isString() ? Api.uuid(node.asString()) : null;
    if (id != null) return id;
    errors.add(new Api.FieldError(field, "must be a lowercase UUID"));
    return null;
  }
  private static Api.Problem conflict(String message) { return new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", message, List.of()); }
}
