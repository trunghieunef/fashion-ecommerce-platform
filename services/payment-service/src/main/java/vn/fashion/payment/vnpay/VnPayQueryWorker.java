package vn.fashion.payment.vnpay;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * TASK:PAY-02 spec section 8: asks VNPay about open VNPAY payments (no IPN reaches a local machine). The next query time is
 * committed before the HTTP call, which runs outside any transaction or lock; a verified answer goes through
 * {@link VnPayResults} like an IPN. Errors and timeouts never change the status (a query is a read).
 */
@Component
public class VnPayQueryWorker {
  private static final Logger LOG = LoggerFactory.getLogger(VnPayQueryWorker.class);
  private static final long[] BACKOFF_MINUTES = {1, 2, 5, 10, 30};
  private static final Duration EVERY = Duration.ofMinutes(30);
  record Due(UUID id, String orderNo, Instant providerCreatedAt, Instant paymentUrlExpiresAt) { }
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final VnPayConfig config;
  private final VnPayQueryClient client;
  private final VnPayResults results;

  public VnPayQueryWorker(JdbcClient jdbc, TransactionTemplate tx, VnPayConfig config, VnPayQueryClient client, VnPayResults results) {
    this.jdbc = jdbc; this.tx = tx; this.config = config; this.client = client; this.results = results;
  }

  @Scheduled(initialDelayString = "${fashion.payment.vnpay.query-poll:30s}", fixedDelayString = "${fashion.payment.vnpay.query-poll:30s}")
  void poll() { runOnce(Instant.now()); }

  public void runOnce(Instant now) {
    if (!config.enabled()) return;
    List<Due> due = tx.execute(status -> {
      var rows = jdbc.sql("""
          select id, order_no, provider_created_at, payment_url_expires_at from payments
          where method = 'VNPAY' and status in ('CREATED', 'PROCESSING') and next_reconcile_at <= :now
          order by next_reconcile_at limit 20 for update skip locked""").param("now", Timestamp.from(now)).query(Due.class).list();
      for (Due d : rows) {
        Instant next = next(d.providerCreatedAt(), d.paymentUrlExpiresAt(), now);
        jdbc.sql("update payments set next_reconcile_at = :n where id = :id")
            .param("n", next == null ? null : Timestamp.from(next), Types.TIMESTAMP).param("id", d.id()).update();
      }
      return rows;
    });
    for (Due d : due) {
      try { query(d, now); }
      catch (RuntimeException e) { LOG.warn("VNPay QueryDR for payment {} failed: {}", d.id(), e.getClass().getName()); }
    }
  }

  /** +1, +2, +5, +10, +30 minutes after create, then every 30 minutes; null (stop) after the link expiry + 24 h. */
  static Instant next(Instant created, Instant linkExpires, Instant now) {
    for (long m : BACKOFF_MINUTES) {
      Instant t = created.plus(Duration.ofMinutes(m));
      if (t.isAfter(now)) return t;
    }
    Instant base = created.plus(EVERY);
    Instant t = base.plus(EVERY.multipliedBy(Duration.between(base, now).toMillis() / EVERY.toMillis() + 1));
    return t.isAfter(linkExpires.plus(Duration.ofHours(24))) ? null : t;
  }

  private void query(Due d, Instant now) {
    var r = client.query(d.orderNo(), "Thanh toan don hang " + d.orderNo(), d.providerCreatedAt());
    if (r == null) return;
    JsonNode b = r.body();
    String txnRef = b.path("vnp_TxnRef").asString("");
    if (!results.ours(b.path("vnp_TmnCode").asString("")) || !txnRef.isEmpty() && !txnRef.equals(d.orderNo())) {
      LOG.warn("VNPay QueryDR answer for payment {} rejected: other merchant or reference", d.id());
      return;
    }
    switch (r.responseCode()) {
      case "00" -> found(d, r);
      case "91" -> { if (now.isAfter(d.paymentUrlExpiresAt().plus(Duration.ofMinutes(15)))) results.expire(d.id()); }
      case "02", "97" -> LOG.error("VNPay QueryDR configuration error: ResponseCode {}", r.responseCode());
      default -> LOG.warn("VNPay QueryDR not answered for payment {}: ResponseCode {}", d.id(), r.responseCode());
    }
  }

  private void found(Due d, VnPayQueryClient.Result r) {
    JsonNode b = r.body();
    String amount = b.path("vnp_Amount").asString("");
    if (!amount.matches("[0-9]{1,18}") || !d.orderNo().equals(b.path("vnp_TxnRef").asString(""))) {
      LOG.warn("VNPay QueryDR answer for payment {} has no valid amount or reference", d.id());
      return;
    }
    String status = b.path("vnp_TransactionStatus").asString(""), txn = b.path("vnp_TransactionNo").asString("");
    results.apply("QUERY", new VnPayResults.Evidence(d.orderNo(), Long.parseLong(amount), "00", status, txn,
        orNull(b, "vnp_PayDate"), orNull(b, "vnp_BankCode"), orNull(b, "vnp_CardType"),
        txn + ":00:" + status + ":" + r.responseId()));
  }

  private static String orNull(JsonNode b, String field) {
    String v = b.path(field).asString("");
    return v.isEmpty() ? null : v;
  }
}
