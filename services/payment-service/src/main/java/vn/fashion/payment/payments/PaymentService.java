package vn.fashion.payment.payments;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.idempotency.IdempotencyStore;
import vn.fashion.payment.web.Api;

/**
 * PAY-01 spec section 5: one payment per order from the order's authenticated snapshot, one business reference
 * (order_no) for its whole life (PAY-07). Every write runs in one transaction under an advisory lock on the order.
 */
@Service
public class PaymentService {
  public record Snapshot(UUID orderId, String orderNo, UUID userId, String method, long amount, String currency, Instant expiresAt) { }
  public record View(UUID paymentId, UUID orderId, String orderNo, String method, long amount, String currency, String status,
                     Instant closedAt, Instant paidAt, long version) { }
  static final String ACTOR = "service:order-service";
  static final long MAX_AMOUNT = 1_000_000_000_000L;
  /** Close before any money: these become FAILED (06 section 17.1); UNKNOWN keeps its state until verified. */
  private static final Set<String> UNCOLLECTED = Set.of("CREATED", "PROCESSING", "COD_PENDING");
  private static final Set<String> SNAPSHOT_FIELDS = Set.of("order_id", "order_no", "user_id", "method", "amount", "currency", "expires_at");
  private static final String VIEW = """
      select id as payment_id, order_id, order_no, method, amount, currency, status, closed_at, paid_at, version from payments""";
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final IdempotencyStore keys;
  private final PaymentEvents events;
  private final ObjectMapper json;

  public PaymentService(JdbcClient jdbc, TransactionTemplate tx, IdempotencyStore keys, PaymentEvents events, ObjectMapper json) {
    this.jdbc = jdbc; this.tx = tx; this.keys = keys; this.events = events; this.json = json;
  }

  /** COD -> COD_PENDING; a closed order (tombstone or closed payment) is 409 PAYMENT_CLOSED, another snapshot 409 PAYMENT_CONFLICT. */
  public Object create(String key, Snapshot s, String correlationId) {
    if (s.amount() <= 0)
      throw new Api.Problem(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_AMOUNT_UNSUPPORTED", "PAYMENT_AMOUNT_UNSUPPORTED", List.of());
    if (!s.method().equals("COD"))
      throw new Api.Problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", "PAYMENT_METHOD_UNAVAILABLE", List.of());
    String hash = hash(s);
    return tx.execute(status -> {
      var replay = Api.replay(keys.begin(ACTOR, "payment.create", key, hash), json);
      if (replay != null) {
        // A retry after a close gets the current fact, not the stored open answer (same rule as shipping SHP-02 I1).
        if (byOrder(s.orderId()).filter(v -> v.closedAt() != null).isPresent()) throw conflict("PAYMENT_CLOSED");
        return replay;
      }
      lockOrder(s.orderId());
      var existing = byOrder(s.orderId());
      if (existing.isPresent()) {
        if (existing.get().closedAt() != null) throw conflict("PAYMENT_CLOSED"); // checked before the hash (spec section 5)
        if (!requestHash(s.orderId()).equals(hash)) throw conflict("PAYMENT_CONFLICT");
        keys.finish(ACTOR, "payment.create", key, existing.get().paymentId(), 200, json.writeValueAsString(existing.get()));
        return existing.get();
      }
      UUID id = UUID.randomUUID();
      insert(id, s, hash, "COD_PENDING", null);
      transaction(id, "CREATE", "create", "COD_PENDING", s.amount(), null, "{}");
      var view = byId(id);
      events.created(view, correlationId);
      keys.finish(ACTOR, "payment.create", key, id, 200, json.writeValueAsString(view));
      return view;
    });
  }

  /**
   * PAY-08 (spec section 4): close stops every new create or resume. Not collected yet -> FAILED; no payment yet -> a FAILED
   * tombstone with the snapshot; money already collected keeps its state (closed_at is independent of the money).
   */
  public Object close(String key, UUID pathOrderId, Snapshot s, String reason, String correlationId) {
    if (!s.orderId().equals(pathOrderId))
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS",
          List.of(new Api.FieldError("order_id", "must equal the order_id of the path")));
    String hash = hash(s);
    return tx.execute(status -> {
      var replay = Api.replay(keys.begin(ACTOR, "payment.close", key, Api.sha256(hash + "\n" + reason)), json);
      if (replay != null) return replay;
      lockOrder(s.orderId());
      var current = byOrder(s.orderId());
      View view;
      if (current.isEmpty()) {
        UUID id = UUID.randomUUID();
        insert(id, s, hash, "FAILED", reason);
        transaction(id, "CLOSE", "close", "FAILED", null, null, "{}");
        view = byId(id);
        events.failed(view, "CLOSED", correlationId);
      } else if (!requestHash(s.orderId()).equals(hash)) {
        throw conflict("PAYMENT_CONFLICT");
      } else if (current.get().closedAt() != null) {
        view = current.get();
      } else {
        String to = UNCOLLECTED.contains(current.get().status()) ? "FAILED" : current.get().status();
        jdbc.sql("""
            update payments set status = :s, closed_at = now(), close_reason = :r, version = version + 1, updated_at = now()
            where id = :id""").param("s", to).param("r", reason).param("id", current.get().paymentId()).update();
        transaction(current.get().paymentId(), "CLOSE", "close", to, null, null, "{}");
        view = byId(current.get().paymentId());
        if (to.equals("FAILED")) events.failed(view, "CLOSED", correlationId);
      }
      keys.finish(ACTOR, "payment.close", key, view.paymentId(), 200, json.writeValueAsString(view));
      return view;
    });
  }

  public Optional<View> byOrder(UUID orderId) {
    return jdbc.sql(VIEW + " where order_id = :o").param("o", orderId).query(View.class).optional();
  }
  View byId(UUID id) {
    return jdbc.sql(VIEW + " where id = :id").param("id", id).query(View.class).single();
  }

  /** Serializes create, close and COD evidence of one order (payments may not exist yet, so no row lock). */
  void lockOrder(UUID orderId) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:o, 0))").param("o", orderId.toString()).query((rs, n) -> 1).list();
  }
  /** Canonical JSON (sorted keys) of the snapshot. */
  String hash(Snapshot s) {
    return Api.sha256(json.writeValueAsString(canonical(s)));
  }
  private static TreeMap<String, Object> canonical(Snapshot s) {
    var canonical = new TreeMap<String, Object>();
    canonical.put("order_id", s.orderId().toString());
    canonical.put("order_no", s.orderNo());
    canonical.put("user_id", s.userId() == null ? null : s.userId().toString());
    canonical.put("method", s.method());
    canonical.put("amount", s.amount());
    canonical.put("currency", s.currency());
    canonical.put("expires_at", s.expiresAt().toString());
    return canonical;
  }
  String requestHash(UUID orderId) {
    return jdbc.sql("select request_hash from payments where order_id = :o").param("o", orderId).query(String.class).single();
  }
  void insert(UUID id, Snapshot s, String hash, String status, String closeReason) {
    jdbc.sql("""
        insert into payments(id, order_id, order_no, user_id, method, amount, currency, status, provider_request_id, request_hash,
          snapshot, expires_at, closed_at, close_reason)
        values (:id, :o, :no, :u, :m, :a, :c, :s, :no, :h, cast(:snap as jsonb), :exp, case when cast(:r as text) is null then null else now() end, :r)""")
        .param("id", id).param("o", s.orderId()).param("no", s.orderNo()).param("u", s.userId()).param("m", s.method())
        .param("a", s.amount()).param("c", s.currency()).param("s", status).param("h", hash)
        .param("snap", json.writeValueAsString(canonical(s))).param("exp", Timestamp.from(s.expiresAt())).param("r", closeReason).update();
  }
  /** One evidence row; a repeated (source, key) is ignored and reported as false. */
  boolean transaction(UUID payment, String source, String key, String status, Long amount, Instant occurredAt, String payload) {
    return jdbc.sql("""
        insert into payment_transactions(id, payment_id, source, provider_event_key, status, amount, occurred_at, sanitized_payload,
          payload_hash)
        values (:id, :p, :src, :k, :st, :a, :at, cast(:payload as jsonb), :ph) on conflict do nothing""")
        .param("id", UUID.randomUUID()).param("p", payment).param("src", source).param("k", key).param("st", status)
        .param("a", amount).param("at", occurredAt == null ? null : Timestamp.from(occurredAt)).param("payload", payload)
        .param("ph", Api.sha256(payload)).update() == 1;
  }

  /** Strict snapshot: unknown fields (other than {@code extra}), missing fields and bad values are 400. */
  public static Snapshot parse(JsonNode body, Set<String> extra) {
    var errors = new ArrayList<Api.FieldError>();
    for (String name : body.propertyNames())
      if (!SNAPSHOT_FIELDS.contains(name) && !extra.contains(name)) errors.add(new Api.FieldError(name, "is not allowed"));
    UUID order = uuid(body.path("order_id"), "order_id", false, errors);
    JsonNode no = body.path("order_no");
    String orderNo = no.isString() ? no.asString() : null;
    if (orderNo == null || orderNo.isEmpty() || orderNo.length() > 32 || !orderNo.strip().equals(orderNo))
      errors.add(new Api.FieldError("order_no", "must be 1..32 characters"));
    UUID user = uuid(body.path("user_id"), "user_id", true, errors);
    JsonNode m = body.path("method");
    String method = m.isString() ? m.asString() : null;
    if (method == null || !Set.of("COD", "VNPAY", "MOMO").contains(method)) errors.add(new Api.FieldError("method", "must be COD, VNPAY or MOMO"));
    JsonNode a = body.path("amount");
    long amount = a.isIntegralNumber() && a.canConvertToLong() && a.longValue() <= MAX_AMOUNT ? a.longValue() : Long.MIN_VALUE;
    if (amount == Long.MIN_VALUE) errors.add(new Api.FieldError("amount", "must be an integer VND <= " + MAX_AMOUNT));
    if (!"VND".equals(body.path("currency").asString(null))) errors.add(new Api.FieldError("currency", "must be VND"));
    Instant expires = null;
    JsonNode e = body.path("expires_at");
    try { if (e.isString()) expires = Instant.parse(e.asString()); } catch (DateTimeParseException ignored) { }
    if (expires == null) errors.add(new Api.FieldError("expires_at", "must be an ISO-8601 UTC timestamp"));
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    return new Snapshot(order, orderNo, user, method, amount, "VND", expires);
  }
  private static UUID uuid(JsonNode node, String field, boolean nullable, List<Api.FieldError> errors) {
    if (nullable && (node.isNull() || node.isMissingNode())) return null;
    UUID id = node.isString() ? Api.uuid(node.asString()) : null;
    if (id == null) errors.add(new Api.FieldError(field, "must be a lowercase UUID"));
    return id;
  }
  static Api.Problem conflict(String message) { return new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", message, List.of()); }
}
