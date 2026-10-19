package vn.fashion.shipping.shipments;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import vn.fashion.shipping.web.Api;

/**
 * SHP-02 spec sections 2-3: OPS records SELF delivery and COD events with evidence. One transaction per event: lock the
 * shipment, check version and edge, update, history (no address), outbox. A wrong or stale event is 409, never applied.
 */
@Service
public class SelfEventService {
  public record Event(String type, long expectedVersion, Instant occurredAt, UUID assignedTo, String evidenceRef, String reason,
                      Long amount) { }
  private record Edge(Set<String> from, String to, Set<String> required, Set<String> allowed) { }
  private record Row(UUID id, String status, long codAmount, long codCollectedAmount, long codRemittedAmount, long version,
                     Instant createdAt) { }
  /** Response and stored replay: state only, never addresses (Review SHP-02 I3). */
  public record Result(UUID shipmentId, UUID orderId, String status, long version, UUID assignedTo, Instant handedOverAt, long codAmount,
                       long codCollectedAmount, long codRemittedAmount) { }
  private static final Set<String> NONE = Set.of();
  /** event_type -> edge; COD events keep DELIVERED. Missing edge or source status -> 409 INVALID_TRANSITION. */
  private static final Map<String, Edge> EDGES = Map.of(
      "ASSIGN", new Edge(Set.of("CREATED", "PICKING"), "PICKING", Set.of("assigned_to"), Set.of("reason")),
      "HANDED_OVER", new Edge(Set.of("PICKING"), "DELIVERING", Set.of("evidence_ref"), Set.of("reason")),
      "DELIVERED", new Edge(Set.of("DELIVERING"), "DELIVERED", Set.of("evidence_ref"), Set.of("reason")),
      "DELIVERY_FAILED", new Edge(Set.of("DELIVERING"), "FAILED", Set.of("reason"), Set.of("evidence_ref")),
      "REDELIVER", new Edge(Set.of("FAILED"), "DELIVERING", NONE, Set.of("reason", "evidence_ref")),
      "RETURN_STARTED", new Edge(Set.of("FAILED"), "RETURNING", Set.of("reason"), Set.of("evidence_ref")),
      "RETURNED", new Edge(Set.of("RETURNING"), "RETURNED", Set.of("evidence_ref"), Set.of("reason")),
      "COD_COLLECTED", new Edge(Set.of("DELIVERED"), "DELIVERED", Set.of("amount", "evidence_ref"), Set.of("reason")),
      "COD_REMITTED", new Edge(Set.of("DELIVERED"), "DELIVERED", Set.of("amount", "evidence_ref"), Set.of("reason")));
  private static final Set<String> FIELDS = Set.of("event_type", "expected_version", "occurred_at", "assigned_to", "evidence_ref",
      "reason", "amount");
  static final String OPERATION = "shipping.self_event";
  /** Allowed OPS clock lead; hand-over is the order's SHIPPING mark, so it stays small for every type. */
  static final java.time.Duration SKEW = java.time.Duration.ofSeconds(60);
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final IdempotencyStore keys;
  private final ShipmentEvents events;
  private final ShipmentService shipments;
  private final ObjectMapper json;

  public SelfEventService(JdbcClient jdbc, TransactionTemplate tx, IdempotencyStore keys, ShipmentEvents events,
                          ShipmentService shipments, ObjectMapper json) {
    this.jdbc = jdbc; this.tx = tx; this.keys = keys; this.events = events; this.shipments = shipments; this.json = json;
  }

  public Object apply(UUID shipmentId, UUID actor, String key, Event e, String correlationId) {
    var canonical = new TreeMap<String, Object>();
    canonical.put("shipment_id", shipmentId.toString());
    canonical.put("event_type", e.type());
    canonical.put("expected_version", e.expectedVersion());
    canonical.put("occurred_at", e.occurredAt() == null ? null : e.occurredAt().toString());
    canonical.put("assigned_to", e.assignedTo() == null ? null : e.assignedTo().toString());
    canonical.put("evidence_ref", e.evidenceRef());
    canonical.put("reason", e.reason());
    canonical.put("amount", e.amount());
    String hash = Api.sha256(json.writeValueAsString(canonical));
    String actorKey = "user:" + actor;
    var outcome = tx.execute(status -> {
      var replay = Api.replay(keys.begin(actorKey, OPERATION, key, hash), json);
      if (replay != null) return replay;
      var row = jdbc.sql("""
          select id, status, cod_amount, cod_collected_amount, cod_remitted_amount, version, created_at
          from shipments where id = :id for update""").param("id", shipmentId).query(Row.class).optional()
          .orElseThrow(ShipmentController::notFound);
      var edge = EDGES.get(e.type());
      Instant occurred = occurredAt(shipmentId, row, e);
      UUID eventId = UUID.randomUUID(); // history row id, and event_ref of the COD events (spec section 5)
      var audit = new LinkedHashMap<String, Object>();
      audit.put("event_type", e.type());
      audit.put("actor_id", actor.toString());
      audit.put("evidence_ref", e.evidenceRef());
      audit.put("assigned_to", e.assignedTo() == null ? null : e.assignedTo().toString());
      audit.put("amount", e.amount());
      if (row.status().equals("CANCELLED")) {
        // PR #30 review I1 (06 section 17.2): physical evidence on a cancelled shipment is never dropped silently. It is kept
        // as history applied=false for OPS reconciliation (checked before the version, which the cancel already moved), the
        // status stays CANCELLED, and the answer is 409; the stored 409 makes a retry answer the same without a second row.
        shipments.history(eventId, shipmentId, "self:" + eventId, "CANCELLED", edge.to(), occurred, e.reason(),
            json.writeValueAsString(audit), false);
        var rejected = new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "CANCELLED_EVIDENCE_RECORDED",
            List.of(new Api.FieldError("status", "current=CANCELLED")));
        keys.finish(actorKey, OPERATION, key, shipmentId, 409, json.writeValueAsString(Api.body(rejected)));
        return (Object) rejected;
      }
      if (row.version() != e.expectedVersion())
        throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "VERSION_CONFLICT",
            List.of(new Api.FieldError("expected_version", "current=" + row.version())));
      if (!edge.from().contains(row.status()))
        throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "INVALID_TRANSITION", List.of(new Api.FieldError("status", "current=" + row.status())));
      long collected = 0, remitted = 0;
      if (e.type().equals("COD_COLLECTED")) {
        if (row.codAmount() == 0 || row.codCollectedAmount() > 0)
          throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "COD_NOT_COLLECTABLE", List.of());
        if (e.amount() != row.codAmount()) throw invalid("amount", "must equal cod_amount=" + row.codAmount());
        collected = e.amount();
      }
      if (e.type().equals("COD_REMITTED")) {
        long outstanding = row.codCollectedAmount() - row.codRemittedAmount();
        if (outstanding == 0) throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "COD_NOT_REMITTABLE", List.of());
        if (e.amount() != outstanding) throw invalid("amount", "must equal the collected, not yet remitted amount=" + outstanding);
        remitted = e.amount();
      }
      jdbc.sql("""
          update shipments set status = :to, assigned_to = coalesce(:assigned, assigned_to),
            handed_over_at = case when :handover then cast(:at as timestamptz) else handed_over_at end,
            cod_collected_amount = cod_collected_amount + :collected, cod_remitted_amount = cod_remitted_amount + :remitted,
            version = version + 1, updated_at = now()
          where id = :id""")
          .param("to", edge.to()).param("assigned", e.assignedTo()).param("handover", e.type().equals("HANDED_OVER"))
          .param("at", java.sql.Timestamp.from(occurred)).param("collected", collected).param("remitted", remitted)
          .param("id", shipmentId).update();
      shipments.history(eventId, shipmentId, "self:" + eventId, row.status(), edge.to(), occurred, e.reason(), json.writeValueAsString(audit), true);
      var view = shipments.byId(shipmentId);
      if (e.type().startsWith("COD_"))
        events.cod(e.type(), view, eventId, e.amount(), e.evidenceRef(), occurred, correlationId);
      else if (!row.status().equals(edge.to()))
        events.statusUpdated(view, occurred, correlationId);
      // Review SHP-02 I3: the stored replay body holds no address or phone; OPS reads the detail with GET.
      var result = jdbc.sql("""
          select id as shipment_id, order_id, status, version, assigned_to, handed_over_at, cod_amount, cod_collected_amount,
            cod_remitted_amount from shipments where id = :id""").param("id", shipmentId).query(Result.class).single();
      keys.finish(actorKey, OPERATION, key, shipmentId, 200, json.writeValueAsString(result));
      return (Object) result;
    });
    if (outcome instanceof Api.Problem rejected) throw rejected; // after commit, so the evidence row stays
    return outcome;
  }

  /**
   * PR #30 review M1 / SHP-02 M8: clock_timestamp() (not the transaction start); never before the shipment or the previous
   * event; at most SKEW ahead for every type. Without occurred_at the event takes max(now, previous), so a slightly fast
   * OPS clock on an earlier event never blocks the next one.
   */
  private Instant occurredAt(UUID shipmentId, Row row, Event e) {
    Instant now = jdbc.sql("select clock_timestamp()").query(java.sql.Timestamp.class).single().toInstant();
    var previous = jdbc.sql("select max(provider_occurred_at) from shipment_status_history where shipment_id = :id")
        .param("id", shipmentId).query(java.sql.Timestamp.class).optional().map(java.sql.Timestamp::toInstant);
    Instant occurred = e.occurredAt() != null ? e.occurredAt() : previous.filter(now::isBefore).orElse(now);
    if (occurred.isBefore(row.createdAt())) throw invalid("occurred_at", "must not be before the shipment was created");
    if (previous.isPresent() && occurred.isBefore(previous.get()))
      throw invalid("occurred_at", "must not be before the previous event (" + previous.get() + ")");
    if (occurred.isAfter(now.plus(SKEW))) throw invalid("occurred_at", "must be at most " + SKEW.toSeconds() + " seconds from now");
    return occurred;
  }

  /** Strict per type: unknown fields, fields the type does not use and missing required fields are 400. */
  public static Event parse(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    for (String name : body.propertyNames())
      if (!FIELDS.contains(name)) errors.add(new Api.FieldError(name, "is not allowed"));
    String type = body.path("event_type").isString() ? body.path("event_type").asString() : null;
    var edge = type == null ? null : EDGES.get(type);
    if (edge == null) {
      errors.add(new Api.FieldError("event_type", "must be one of " + new TreeMap<>(EDGES).keySet()));
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    }
    for (String name : List.of("assigned_to", "evidence_ref", "reason", "amount")) {
      boolean present = body.has(name) && !body.get(name).isNull();
      if (edge.required().contains(name) && !present) errors.add(new Api.FieldError(name, "is required for " + type));
      if (present && !edge.required().contains(name) && !edge.allowed().contains(name))
        errors.add(new Api.FieldError(name, "is not allowed for " + type));
    }
    JsonNode v = body.path("expected_version");
    long expected = v.isIntegralNumber() && v.canConvertToLong() && v.longValue() >= 0 ? v.longValue() : -1;
    if (expected < 0) errors.add(new Api.FieldError("expected_version", "is required, a non-negative integer"));
    Instant occurred = null;
    if (body.has("occurred_at") && !body.get("occurred_at").isNull()) {
      try { occurred = body.get("occurred_at").isString() ? Instant.parse(body.get("occurred_at").asString()) : null; }
      catch (DateTimeParseException ignored) { }
      if (occurred == null) errors.add(new Api.FieldError("occurred_at", "must be an ISO-8601 UTC timestamp"));
    }
    UUID assigned = null;
    if (body.has("assigned_to") && !body.get("assigned_to").isNull()) {
      assigned = body.get("assigned_to").isString() ? Api.uuid(body.get("assigned_to").asString()) : null;
      if (assigned == null) errors.add(new Api.FieldError("assigned_to", "must be a lowercase UUID"));
    }
    String evidence = text(body, "evidence_ref", 200, errors), reason = text(body, "reason", 500, errors);
    Long amount = null;
    if (body.has("amount") && !body.get("amount").isNull()) {
      JsonNode a = body.get("amount");
      if (a.isIntegralNumber() && a.canConvertToLong() && a.longValue() >= 1) amount = a.longValue();
      else errors.add(new Api.FieldError("amount", "must be an integer VND >= 1"));
    }
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    return new Event(type, expected, occurred, assigned, evidence, reason, amount);
  }

  private static String text(JsonNode body, String field, int max, List<Api.FieldError> errors) {
    if (!body.has(field) || body.get(field).isNull()) return null;
    String value = body.get(field).isString() ? body.get(field).asString().strip() : "";
    if (value.isEmpty() || value.length() > max) { errors.add(new Api.FieldError(field, "must be 1.." + max + " characters")); return null; }
    return value;
  }
  private static Api.Problem invalid(String field, String message) {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", List.of(new Api.FieldError(field, message)));
  }
}
