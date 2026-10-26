package vn.fashion.payment.vnpay;

import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.payment.payments.PaymentEvents;
import vn.fashion.payment.payments.PaymentService;
import vn.fashion.payment.web.Api;

/**
 * TASK:PAY-02 spec section 7: one verified VNPay result (IPN or QueryDR, signature and merchant already checked by the
 * caller) applied in one transaction under the order lock. SUCCESS only on ResponseCode 00 + TransactionStatus 00 with the
 * exact amount x 100; SUCCESS is never downgraded; a repeated fact (same event key) changes nothing.
 */
@Component
public class VnPayResults {
  public record Evidence(String txnRef, long amountHundredths, String responseCode, String transactionStatus, String transactionNo,
                         String payDate, String bankCode, String cardType, String eventKey) { }
  public enum Outcome { APPLIED, ALREADY_CONFIRMED, NOT_FOUND, AMOUNT_MISMATCH }
  private record Target(UUID id, UUID orderId, long amount, String status, String providerTxnId) { }
  /** Money already verified: nothing a later result says can move it (refunds are PAY-04). */
  private static final Set<String> CONFIRMED = Set.of("SUCCESS", "PARTIALLY_REFUNDED", "REFUNDED");
  private static final Set<String> OPEN = Set.of("CREATED", "PROCESSING", "UNKNOWN");
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final PaymentService payments;
  private final PaymentEvents events;
  private final ObjectMapper json;
  private final VnPayConfig config;
  private final Tracer tracer;

  public VnPayResults(JdbcClient jdbc, TransactionTemplate tx, PaymentService payments, PaymentEvents events, ObjectMapper json,
                      VnPayConfig config, Tracer tracer) {
    this.jdbc = jdbc; this.tx = tx; this.payments = payments; this.events = events; this.json = json; this.config = config;
    this.tracer = tracer;
  }

  /** Merchant check shared by IPN and QueryDR: a result for another terminal is never verified for this one. */
  public boolean ours(String tmnCode) { return config.enabled() && config.tmnCode().equals(tmnCode); }

  /** source WEBHOOK|QUERY. A malformed vnp_PayDate throws before any write (the caller answers 99 / retries). */
  public Outcome apply(String source, Evidence e) {
    Instant paidAt = e.payDate() == null ? null : VnPaySigner.parse(e.payDate());
    String kind = kind(source, e);
    if (kind.equals("SUCCESS") && paidAt == null) throw new IllegalArgumentException("vnp_PayDate is required for a success");
    // TASK:PAY-02 review C1: provider_txn_id is unique per method and required by PAYMENT_COMPLETED.
    if (kind.equals("SUCCESS") && (e.transactionNo() == null || e.transactionNo().isBlank()))
      throw new IllegalArgumentException("vnp_TransactionNo is required for a success");
    String correlationId = Api.metadata(tracer).traceId();
    return tx.execute(status -> {
      var orderId = jdbc.sql("select order_id from payments where order_no = :no and method = 'VNPAY'").param("no", e.txnRef())
          .query(UUID.class).optional();
      if (orderId.isEmpty()) return Outcome.NOT_FOUND;
      payments.lockOrder(orderId.get());
      var p = jdbc.sql("select id, order_id, amount, status, provider_txn_id from payments where order_id = :o").param("o", orderId.get())
          .query(Target.class).single();
      String payload = payload(e);
      if (e.amountHundredths() != Math.multiplyExact(p.amount(), 100L)) {
        review(p, e, "AMOUNT_MISMATCH", Math.ceilDiv(e.amountHundredths(), 100L), payload); // review M2: never equals expected
        return Outcome.AMOUNT_MISMATCH;
      }
      boolean fresh = payments.transaction(p.id(), source, e.eventKey(), blankToNull(e.transactionNo()), kind, p.amount(), paidAt,
          payload);
      // Reversal/fraud suspicion is FINANCE's call whatever the state (spec section 4); one item per provider transaction.
      if (kind.equals("PROVIDER_REVIEW")) review(p, e, kind, p.amount(), payload);
      // TASK:PAY-02 review R5: another verified capture after SUCCESS may be a double charge; FINANCE decides, no second event.
      if (kind.equals("SUCCESS") && fresh && CONFIRMED.contains(p.status()) && !java.util.Objects.equals(e.transactionNo(), p.providerTxnId()))
        review(p, e, "PROVIDER_REVIEW", p.amount(), payload);
      if (CONFIRMED.contains(p.status()) || kind.equals("FAILED") && p.status().equals("FAILED")) return Outcome.ALREADY_CONFIRMED;
      if (!fresh) return Outcome.APPLIED; // same fact again, already applied
      if (kind.equals("SUCCESS")) {
        // FAILED (closed or declined) still takes a late success; closed_at stays, the order owes a refund.
        jdbc.sql("""
            update payments set status = 'SUCCESS', paid_at = :at, provider_txn_id = :txn, version = version + 1, updated_at = now()
            where id = :id""").param("at", java.sql.Timestamp.from(paidAt)).param("txn", e.transactionNo()).param("id", p.id()).update();
        events.completed(payments.byId(p.id()), e.transactionNo(), correlationId);
      } else if (kind.equals("FAILED") && OPEN.contains(p.status())) {
        jdbc.sql("update payments set status = 'FAILED', version = version + 1, updated_at = now() where id = :id")
            .param("id", p.id()).update();
        events.failed(payments.byId(p.id()), "PROVIDER_DECLINED", correlationId);
      } else if (kind.equals("PROCESSING") && p.status().equals("CREATED")) {
        jdbc.sql("update payments set status = 'PROCESSING', version = version + 1, updated_at = now() where id = :id")
            .param("id", p.id()).update();
      }
      return Outcome.APPLIED;
    });
  }

  /**
   * Spec section 4: review first (status 04/07, ResponseCode 07 "money taken, suspicious", or a non-00 code contradicting
   * status 00: PR #41 review I1); 00+00 success; 02 (or a non-00 IPN response) failed; 01 pending; else evidence only.
   * A missing code or status (a QueryDR answer may have none) is never a success.
   */
  static String kind(String source, Evidence e) {
    String s = e.transactionStatus() == null ? "" : e.transactionStatus();
    boolean ok = "00".equals(e.responseCode());
    if (s.equals("04") || s.equals("07") || "07".equals(e.responseCode()) || !ok && s.equals("00")) return "PROVIDER_REVIEW";
    if (ok && s.equals("00")) return "SUCCESS";
    if (s.equals("02") || source.equals("WEBHOOK") && !ok) return "FAILED";
    if (s.equals("01")) return "PROCESSING";
    return "OTHER";
  }

  /** TASK:PAY-02 spec section 8: QueryDR 91 after the link expired + 15 minutes; an open payment becomes FAILED EXPIRED. */
  public void expire(UUID paymentId) {
    String correlationId = Api.metadata(tracer).traceId();
    tx.executeWithoutResult(status -> {
      payments.lockOrder(jdbc.sql("select order_id from payments where id = :id").param("id", paymentId).query(UUID.class).single());
      String current = jdbc.sql("select status from payments where id = :id").param("id", paymentId).query(String.class).single();
      // PR #41 review M1: already FAILED (closed) records the 91 too, which ends the queries; no state change, no event.
      boolean open = OPEN.contains(current);
      if (!open && !current.equals("FAILED")) return;
      payments.transaction(paymentId, "QUERY", "91:expired", "FAILED", null, null, "{\"vnp_ResponseCode\":\"91\"}");
      if (!open) return;
      jdbc.sql("update payments set status = 'FAILED', version = version + 1, updated_at = now() where id = :id")
          .param("id", paymentId).update();
      events.failed(payments.byId(paymentId), "EXPIRED", correlationId);
    });
  }

  private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

  /** Allowlist only: no vnp_SecureHash, no vnp_BankTranNo, no OrderInfo. */
  private String payload(Evidence e) {
    var p = new LinkedHashMap<String, Object>();
    p.put("vnp_TxnRef", e.txnRef());
    p.put("vnp_Amount", String.valueOf(e.amountHundredths()));
    p.put("vnp_ResponseCode", e.responseCode());
    p.put("vnp_TransactionStatus", e.transactionStatus());
    p.put("vnp_TransactionNo", e.transactionNo());
    p.put("vnp_BankCode", e.bankCode());
    p.put("vnp_CardType", e.cardType());
    p.put("vnp_PayDate", e.payDate());
    return json.writeValueAsString(p);
  }

  private void review(Target p, Evidence e, String kind, long observed, String payload) {
    jdbc.sql("""
        insert into reconciliation_items(id, payment_id, source_ref, kind, expected_amount, observed_amount, evidence)
        values (:id, :p, :ref, :kind, :expected, :observed, cast(:evidence as jsonb)) on conflict do nothing""")
        .param("id", UUID.randomUUID()).param("p", p.id()).param("ref", e.transactionNo()).param("kind", kind)
        .param("expected", p.amount()).param("observed", observed).param("evidence", payload).update();
  }
}
