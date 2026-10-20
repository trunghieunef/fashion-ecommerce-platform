package vn.fashion.payment.payments;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

/**
 * PAY-01 spec section 6: COD evidence from shipping.events, applied inside the inbox transaction (InboxGuard).
 * COLLECTED moves COD_PENDING (or a closed FAILED) to COD_COLLECTED when method and amount match, else a reconciliation
 * item; REMITTED is evidence only (T27). DELIVERED never reaches here, so it can never mean "collected".
 */
@Component
public class CodEvidence {
  private record Target(UUID id, String method, long amount, String status, Instant closedAt) { }
  private final JdbcClient jdbc;
  private final PaymentService payments;
  private final ObjectMapper json;
  private final Duration orphanGrace;

  public CodEvidence(JdbcClient jdbc, PaymentService payments, ObjectMapper json,
                     @Value("${fashion.payment.cod-orphan-grace:10m}") Duration orphanGrace) {
    this.jdbc = jdbc; this.payments = payments; this.json = json; this.orphanGrace = orphanGrace;
  }

  public Object apply(EventEnvelope event) {
    JsonNode p = event.payload();
    UUID order = uuid(p, "order_id"), ref = uuid(p, "event_ref");
    long gross = amount(p, "gross_amount"), fee = amount(p, "fee_amount"), net = amount(p, "net_amount");
    if (!"VND".equals(p.path("currency").asString(null))) throw new PermanentEventException("Invalid COD currency");
    Instant occurred;
    try { occurred = Instant.parse(p.path("occurred_at").asString()); }
    catch (RuntimeException e) { throw new PermanentEventException("Invalid COD occurred_at"); }
    boolean collected = event.eventType().equals("COD_COLLECTED");
    payments.lockOrder(order);
    // The payment is created before the shipment, so a missing row is first a short race and is retried. Past the grace
    // (counted from the envelope occurred_at, shipping's clock) it goes to shipping.events.DLT (messaging_dlt_total). Until then
    // the error handler re-seeks the record, so every later event of that partition waits up to the grace.
    var target = jdbc.sql("select id, method, amount, status, closed_at from payments where order_id = :o").param("o", order)
        .query(Target.class).optional().orElseThrow(() -> event.occurredAt().plus(orphanGrace).isBefore(Instant.now())
            ? new PermanentEventException("No payment for COD evidence after the grace period")
            : new IllegalStateException("No payment yet for COD evidence"));
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("event_type", event.eventType());
    evidence.put("shipment_id", p.path("shipment_id").asString());
    evidence.put("event_ref", ref.toString());
    evidence.put("evidence_ref", p.path("evidence_ref").asString());
    evidence.put("gross_amount", gross);
    evidence.put("fee_amount", fee);
    evidence.put("net_amount", net);
    String payload = json.writeValueAsString(evidence);
    if (!payments.transaction(target.id(), "COD", ref.toString(), collected ? "COLLECTED" : "REMITTED", gross, occurred, payload))
      return null; // same carrier fact delivered again
    if (!collected) return null;
    if (!target.method().equals("COD")) return mismatch(target, ref, "COD_METHOD_MISMATCH", gross, payload);
    if (gross != target.amount()) return mismatch(target, ref, "COD_AMOUNT_MISMATCH", gross, payload);
    // Money of this order was already reported under another event_ref: never collected twice silently (FINANCE resolves it).
    if (target.status().equals("COD_COLLECTED") || target.status().equals("SUCCESS"))
      return mismatch(target, ref, "COD_DUPLICATE_COLLECTION", gross, payload);
    boolean open = target.status().equals("COD_PENDING") || (target.status().equals("FAILED") && target.closedAt() != null);
    if (open)
      jdbc.sql("update payments set status = 'COD_COLLECTED', version = version + 1, updated_at = now() where id = :id")
          .param("id", target.id()).update();
    return null;
  }

  /** Never forced to match: FINANCE resolves the item (PAY-05). */
  private Object mismatch(Target target, UUID ref, String kind, long observed, String payload) {
    jdbc.sql("""
        insert into reconciliation_items(id, payment_id, source_ref, kind, expected_amount, observed_amount, evidence)
        values (:id, :p, :ref, :kind, :expected, :observed, cast(:evidence as jsonb)) on conflict do nothing""")
        .param("id", UUID.randomUUID()).param("p", target.id()).param("ref", ref.toString()).param("kind", kind)
        .param("expected", target.amount()).param("observed", observed).param("evidence", payload).update();
    return null;
  }
  private static long amount(JsonNode payload, String field) {
    JsonNode v = payload.path(field);
    if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0) throw new PermanentEventException("Invalid COD " + field);
    return v.longValue();
  }
  private static UUID uuid(JsonNode payload, String field) {
    try { return UUID.fromString(payload.path(field).asString()); }
    catch (RuntimeException e) { throw new PermanentEventException("Invalid COD " + field); }
  }
}
