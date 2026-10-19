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
    return tx.execute(status -> {
      var replay = Api.replay(keys.begin(actorKey, OPERATION, key, hash), json);
      if (replay != null) return replay;
      var row = jdbc.sql("""
          select id, status, cod_amount, cod_collected_amount, cod_remitted_amount, version, created_at
          from shipments where id = :id for update""").param("id", shipmentId).query(Row.class).optional()
          .orElseThrow(ShipmentController::notFound);
      if (row.version() != e.expectedVersion())
        throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "VERSION_CONFLICT",
            List.of(new Api.FieldError("expected_version", "current=" + row.version())));
      var edge = EDGES.get(e.type());
      if (!edge.from().contains(row.status()))
        throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "INVALID_TRANSITION", List.of(new Api.FieldError("status", "current=" + row.status())));
      Instant now = jdbc.sql("select now()").query(java.sql.Timestamp.class).single().toInstant();
      Instant occurred = e.occurredAt() == null ? now : e.occurredAt();
      if (occurred.isBefore(row.createdAt()) || occurred.isAfter(now.plusSeconds(300)))
        throw invalid("occurred_at", "must be between the shipment creation and 5 minutes from now");
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
      UUID eventId = UUID.randomUUID();
      var audit = new LinkedHashMap<String, Object>();
      audit.put("event_type", e.type());
      audit.put("actor_id", actor.toString());
      audit.put("evidence_ref", e.evidenceRef());
      audit.put("assigned_to", e.assignedTo() == null ? null : e.assignedTo().toString());
      audit.put("amount", e.amount());
      shipments.history(shipmentId, "self:" + eventId, row.status(), edge.to(), occurred, e.reason(), json.writeValueAsString(audit));
      var view = shipments.byId(shipmentId);
      if (e.type().startsWith("COD_"))
        events.cod(e.type(), view, eventId, e.amount(), e.evidenceRef(), occurred, correlationId);
      else if (!row.status().equals(edge.to()))
        events.statusUpdated(view, occurred, correlationId);
      var detail = shipments.adminDetail(shipmentId);
      keys.finish(actorKey, OPERATION, key, shipmentId, 200, json.writeValueAsString(detail));
      return (Object) detail;
    });
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
