package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.fashion.payment.vnpay.VnPaySigner;

/** TASK:PAY-02 Task 3: verified IPN applies once, the browser return only redirects. */
class VnPayIpnIntegrationTest extends PaymentTestSupport {
  static final String NO = "ORD202610250001";
  String order;

  /** A fresh VNPAY payment of 275000 VND for order_no NO. */
  void payment() {
    order = UUID.randomUUID().toString();
    data(create(UUID.randomUUID().toString(), snapshot(order, NO, "VNPAY", 275000, "203.0.113.7")), 200);
  }
  static Map<String, String> result(long amount, String txn, String response, String status, String payDate) {
    var p = new LinkedHashMap<String, String>();
    p.put("vnp_TmnCode", "TESTTMN1"); p.put("vnp_Amount", String.valueOf(amount)); p.put("vnp_BankCode", "NCB");
    p.put("vnp_BankTranNo", "VNP14000001"); p.put("vnp_CardType", "ATM"); p.put("vnp_OrderInfo", "Thanh toan don hang " + NO);
    p.put("vnp_PayDate", payDate); p.put("vnp_ResponseCode", response); p.put("vnp_TransactionNo", txn);
    p.put("vnp_TransactionStatus", status); p.put("vnp_TxnRef", NO);
    return p;
  }
  static Map<String, String> success(long amount, String txn, String payDate) { return result(amount, txn, "00", "00", payDate); }
  static Map<String, String> signed(Map<String, String> p) {
    var s = new LinkedHashMap<>(p);
    s.put("vnp_SecureHashType", "HmacSHA512");
    s.put("vnp_SecureHash", VnPaySigner.hmac("TEST-HASH-SECRET", VnPaySigner.canonical(p)));
    return s;
  }
  static String query(Map<String, String> p) {
    return p.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }
  Map<String, String> ipn(Map<String, String> p) {
    var r = send("GET", "/api/v1/payments/vnpay/ipn?" + query(p), null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
    return mapper.readValue(r.body(), Map.class);
  }
  Map<String, Object> row() { return jdbc.queryForMap("select status, provider_txn_id, closed_at from payments"); }
  Instant paidAt() { return jdbc.queryForObject("select paid_at from payments", java.sql.Timestamp.class).toInstant(); }
  List<JsonNode> events(String type) {
    return jdbc.queryForList("select payload::text from outbox_events where event_type = ? order by created_at", String.class, type)
        .stream().map(mapper::readTree).toList();
  }

  @Test void successfulIpnCompletesOnce() {
    payment();
    var r = ipn(signed(success(27500000, "14000001", "20261025100405")));
    assertThat(r).containsEntry("RspCode", "00").containsEntry("Message", "Confirm Success");
    assertThat(row()).containsEntry("status", "SUCCESS").containsEntry("provider_txn_id", "14000001");
    assertThat(paidAt()).isEqualTo(Instant.parse("2026-10-25T03:04:05Z"));
    assertThat(events("PAYMENT_COMPLETED")).hasSize(1).first()
        .satisfies(e -> assertThat(e.path("provider_txn_id").asString()).isEqualTo("14000001"));
    assertThat(ipn(signed(success(27500000, "14000001", "20261025100405")))).containsEntry("RspCode", "02")
        .containsEntry("Message", "Order already confirmed");
    assertThat(events("PAYMENT_COMPLETED")).hasSize(1);
    assertThat(count("select count(*) from payment_transactions where source = 'WEBHOOK'")).isEqualTo(1);
    var payload = mapper.readValue(jdbc.queryForObject(
        "select sanitized_payload::text from payment_transactions where source = 'WEBHOOK'", String.class), Map.class);
    assertThat(payload).doesNotContainKeys("vnp_SecureHash", "vnp_BankTranNo", "vnp_OrderInfo", "vnp_TmnCode")
        .containsEntry("vnp_TransactionNo", "14000001").containsEntry("vnp_PayDate", "20261025100405");
    assertThat(jdbc.queryForObject("select provider_event_key from payment_transactions where source = 'WEBHOOK'", String.class))
        .isEqualTo("14000001:00:00");
    // A different (signed) failure after SUCCESS: recorded, never a downgrade.
    assertThat(ipn(signed(result(27500000, "14000002", "24", "02", "20261025100500")))).containsEntry("RspCode", "02");
    assertThat(row()).containsEntry("status", "SUCCESS");
    assertThat(count("select count(*) from payment_transactions where source = 'WEBHOOK'")).isEqualTo(2);
    assertThat(events("PAYMENT_FAILED")).isEmpty();
  }

  @Test void badSignatureMerchantAmountOrReferenceNeverSucceeds() {
    payment();
    var tampered = signed(success(27500000, "14000001", "20261025100405"));
    tampered.put("vnp_Amount", "100");
    assertThat(ipn(tampered)).containsEntry("RspCode", "97").containsEntry("Message", "Invalid signature");
    var otherMerchant = success(27500000, "14000001", "20261025100405");
    otherMerchant.put("vnp_TmnCode", "OTHER123");
    assertThat(ipn(signed(otherMerchant))).containsEntry("RspCode", "97");
    assertThat(count("select count(*) from payment_transactions where source = 'WEBHOOK'")).isZero();
    assertThat(ipn(signed(success(100, "14000001", "20261025100405")))).containsEntry("RspCode", "04").containsEntry("Message", "invalid amount");
    assertThat(ipn(signed(success(100, "14000001", "20261025100405")))).containsEntry("RspCode", "04");
    assertThat(count("select count(*) from reconciliation_items where kind = 'AMOUNT_MISMATCH' and observed_amount = 1 and expected_amount = 275000"))
        .isEqualTo(1);
    var unknown = success(27500000, "14000001", "20261025100405");
    unknown.put("vnp_TxnRef", "ORD-NOPE");
    assertThat(ipn(signed(unknown))).containsEntry("RspCode", "01").containsEntry("Message", "Order not found");
    assertThat(row()).containsEntry("status", "CREATED");
    assertThat(events("PAYMENT_COMPLETED")).isEmpty();
    // Missing parameters, malformed dates or amounts: 99, nothing written.
    assertThat(ipn(Map.of())).containsEntry("RspCode", "99").containsEntry("Message", "Unknow error");
    var noHash = success(27500000, "14000001", "20261025100405");
    assertThat(ipn(noHash)).containsEntry("RspCode", "99");
    assertThat(ipn(signed(success(27500000, "14000001", "2026-10-25")))).containsEntry("RspCode", "99");
    var badAmount = success(27500000, "14000001", "20261025100405");
    badAmount.put("vnp_Amount", "27500000.5");
    assertThat(ipn(signed(badAmount))).containsEntry("RspCode", "99");
    assertThat(row()).containsEntry("status", "CREATED");
  }

  @Test void declinedIpnFailsThenLateSuccessWins() {
    payment();
    assertThat(ipn(signed(result(27500000, "0", "24", "02", "20261025100405")))).containsEntry("RspCode", "00");
    assertThat(row()).containsEntry("status", "FAILED");
    assertThat(events("PAYMENT_FAILED")).hasSize(1).first()
        .satisfies(e -> assertThat(e.path("reason").asString()).isEqualTo("PROVIDER_DECLINED"));
    assertThat(ipn(signed(result(27500000, "14000009", "51", "02", "20261025100505")))).containsEntry("RspCode", "02");
    assertThat(events("PAYMENT_FAILED")).hasSize(1);
    assertThat(ipn(signed(success(27500000, "14000010", "20261025100605")))).containsEntry("RspCode", "00");
    assertThat(row()).containsEntry("status", "SUCCESS").containsEntry("provider_txn_id", "14000010");
    assertThat(events("PAYMENT_COMPLETED")).hasSize(1);
  }

  @Test void successAfterCloseKeepsClosedAt() {
    payment();
    data(close(order, "c1", withReason(snapshot(order, NO, "VNPAY", 275000), "expired")), 200);
    Object closedAt = row().get("closed_at");
    assertThat(closedAt).isNotNull();
    assertThat(ipn(signed(success(27500000, "14000001", "20261025100405")))).containsEntry("RspCode", "00");
    assertThat(row()).containsEntry("status", "SUCCESS").containsEntry("closed_at", closedAt);
    assertThat(events("PAYMENT_COMPLETED")).hasSize(1);
  }

  @Test void suspiciousStatusOpensReviewWithoutChangingState() {
    payment();
    for (String status : new String[] {"07", "04"}) {
      assertThat(ipn(signed(result(27500000, "14000001", "00", status, "20261025100405")))).containsEntry("RspCode", "00");
      assertThat(ipn(signed(result(27500000, "14000001", "00", status, "20261025100405")))).containsEntry("RspCode", "00");
    }
    assertThat(count("select count(*) from reconciliation_items where kind = 'PROVIDER_REVIEW'")).isEqualTo(1);
    assertThat(row()).containsEntry("status", "CREATED");
    assertThat(count("select count(*) from outbox_events where event_type <> 'PAYMENT_CREATED'")).isZero();
  }

  @Test void ipnWithSpacesInOrderInfoVerifies() {
    payment();
    var p = success(27500000, "14000001", "20261025100405");
    p.put("vnp_OrderInfo", "Thanh toan don hang: " + NO + " (test)");
    assertThat(ipn(signed(p))).containsEntry("RspCode", "00");
  }

  @Test void returnRedirectsOnlyToTheStorefrontOrder() {
    assertThat(location(send("GET", "/api/v1/payments/vnpay/return?vnp_TxnRef=" + NO + "&vnp_ResponseCode=00", null)))
        .isEqualTo("http://localhost:5173/orders/" + NO);
    assertThat(location(send("GET", "/api/v1/payments/vnpay/return?vnp_TxnRef=https://evil.test", null))).isEqualTo("http://localhost:5173/");
    assertThat(location(send("GET", "/api/v1/payments/vnpay/return?vnp_TxnRef=" + "A".repeat(33), null))).isEqualTo("http://localhost:5173/");
    assertThat(location(send("GET", "/api/v1/payments/vnpay/return", null))).isEqualTo("http://localhost:5173/");
    assertThat(count("select count(*) from payment_transactions")).isZero();
  }
  static String location(HttpResponse<String> r) {
    assertThat(r.statusCode()).isEqualTo(302);
    return r.headers().firstValue("Location").orElseThrow();
  }
}
