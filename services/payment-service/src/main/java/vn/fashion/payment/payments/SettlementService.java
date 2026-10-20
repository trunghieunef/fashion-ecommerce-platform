package vn.fashion.payment.payments;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * PAY-03 (spec section 5): FINANCE proof that COD money reached the shop, gross = fee + net. Batches add up under the
 * order lock; the sum never exceeds the amount, and reaching it is SUCCESS + PAYMENT_COMPLETED (also on a closed payment).
 */
@Service
public class SettlementService {
  public record Settlement(String settlementRef, long grossAmount, long feeAmount, long netAmount, String evidenceRef,
                           long expectedVersion) { }
  public record Settled(UUID paymentId, String status, long amount, long settledAmount, Instant paidAt, Instant closedAt, long version) { }
  private record Row(UUID id, UUID orderId, long amount, String status, long version) { }
  private static final Set<String> FIELDS = Set.of("settlement_ref", "gross_amount", "fee_amount", "net_amount", "evidence_ref",
      "expected_version");
  private static final String OPERATION = "payment.settle";
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final IdempotencyStore keys;
  private final PaymentService payments;
  private final PaymentEvents events;
  private final ObjectMapper json;

  public SettlementService(JdbcClient jdbc, TransactionTemplate tx, IdempotencyStore keys, PaymentService payments,
                           PaymentEvents events, ObjectMapper json) {
    this.jdbc = jdbc; this.tx = tx; this.keys = keys; this.payments = payments; this.events = events; this.json = json;
  }

  public Object settle(UUID paymentId, UUID actor, String key, Settlement s, String correlationId) {
    var canonical = new TreeMap<String, Object>();
    canonical.put("payment_id", paymentId.toString());
    canonical.put("settlement_ref", s.settlementRef());
    canonical.put("gross_amount", s.grossAmount());
    canonical.put("fee_amount", s.feeAmount());
    canonical.put("net_amount", s.netAmount());
    canonical.put("evidence_ref", s.evidenceRef());
    canonical.put("expected_version", s.expectedVersion());
    String hash = Api.sha256(json.writeValueAsString(canonical));
    String actorKey = "user:" + actor;
    return tx.execute(status -> {
      var replay = Api.replay(keys.begin(actorKey, OPERATION, key, hash), json);
      if (replay != null) return replay;
      UUID order = jdbc.sql("select order_id from payments where id = :id").param("id", paymentId).query(UUID.class).optional()
          .orElseThrow(PaymentController::notFound);
      payments.lockOrder(order); // same lock as close and COD evidence: none of them can overwrite this status
      var row = jdbc.sql("select id, order_id, amount, status, version from payments where id = :id for update")
          .param("id", paymentId).query(Row.class).single();
      if (row.version() != s.expectedVersion())
        throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "VERSION_CONFLICT",
            List.of(new Api.FieldError("expected_version", "current=" + row.version())));
      if (!row.status().equals("COD_COLLECTED"))
        throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "PAYMENT_NOT_SETTLEABLE", List.of(new Api.FieldError("status", "current=" + row.status())));
      if (jdbc.sql("select count(*) from cod_settlements where payment_id = :p and settlement_ref = :r").param("p", paymentId)
          .param("r", s.settlementRef()).query(Long.class).single() > 0) throw PaymentService.conflict("SETTLEMENT_REF_REUSED");
      long settled = settled(paymentId);
      if (s.grossAmount() > row.amount() - settled) throw PaymentService.conflict("SETTLEMENT_EXCEEDS_AMOUNT");
      jdbc.sql("""
          insert into cod_settlements(id, payment_id, settlement_ref, gross_amount, fee_amount, net_amount, evidence_ref, confirmed_by)
          values (:id, :p, :r, :g, :f, :n, :e, :by)""")
          .param("id", UUID.randomUUID()).param("p", paymentId).param("r", s.settlementRef()).param("g", s.grossAmount())
          .param("f", s.feeAmount()).param("n", s.netAmount()).param("e", s.evidenceRef()).param("by", actor).update();
      payments.transaction(paymentId, "SETTLEMENT", s.settlementRef(), "SETTLED", s.grossAmount(), null,
          json.writeValueAsString(canonical));
      boolean complete = settled + s.grossAmount() == row.amount();
      jdbc.sql("""
          update payments set status = case when :done then 'SUCCESS' else status end,
            paid_at = case when :done then now() else paid_at end, version = version + 1, updated_at = now() where id = :id""")
          .param("done", complete).param("id", paymentId).update();
      if (complete) events.completed(payments.byId(paymentId), correlationId);
      var result = jdbc.sql("""
          select id as payment_id, status, amount, (select coalesce(sum(gross_amount), 0) from cod_settlements where payment_id = :id)
            as settled_amount, paid_at, closed_at, version from payments where id = :id""")
          .param("id", paymentId).query(Settled.class).single();
      keys.finish(actorKey, OPERATION, key, paymentId, 200, json.writeValueAsString(result));
      return result;
    });
  }

  long settled(UUID paymentId) {
    return jdbc.sql("select coalesce(sum(gross_amount), 0) from cod_settlements where payment_id = :p").param("p", paymentId)
        .query(Long.class).single();
  }

  /** Strict: unknown fields, gross != fee + net, amounts outside 0..10^12 and bad refs are 400. */
  public static Settlement parse(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    for (String name : body.propertyNames()) if (!FIELDS.contains(name)) errors.add(new Api.FieldError(name, "is not allowed"));
    String ref = text(body.path("settlement_ref"), "settlement_ref", 128, errors);
    String evidence = text(body.path("evidence_ref"), "evidence_ref", 500, errors);
    long gross = amount(body.path("gross_amount"), "gross_amount", errors);
    long fee = amount(body.path("fee_amount"), "fee_amount", errors);
    long net = amount(body.path("net_amount"), "net_amount", errors);
    if (errors.isEmpty() && gross != fee + net) errors.add(new Api.FieldError("gross_amount", "must equal fee_amount + net_amount"));
    if (errors.isEmpty() && gross == 0) errors.add(new Api.FieldError("gross_amount", "must be > 0"));
    JsonNode v = body.path("expected_version");
    long version = v.isIntegralNumber() && v.canConvertToLong() && v.longValue() >= 0 ? v.longValue() : -1;
    if (version < 0) errors.add(new Api.FieldError("expected_version", "must be an integer >= 0"));
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    return new Settlement(ref, gross, fee, net, evidence, version);
  }
  private static long amount(JsonNode node, String field, List<Api.FieldError> errors) {
    if (node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0 && node.longValue() <= PaymentService.MAX_AMOUNT)
      return node.longValue();
    errors.add(new Api.FieldError(field, "must be an integer VND 0.." + PaymentService.MAX_AMOUNT));
    return 0;
  }
  private static String text(JsonNode node, String field, int max, List<Api.FieldError> errors) {
    String value = node.isString() ? node.asString().strip() : "";
    if (value.isEmpty() || value.length() > max) { errors.add(new Api.FieldError(field, "must be 1.." + max + " characters")); return null; }
    return value;
  }
}
