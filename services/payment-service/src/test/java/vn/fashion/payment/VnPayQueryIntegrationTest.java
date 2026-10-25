package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.fashion.payment.VnPayIpnIntegrationTest.NO;
import static vn.fashion.payment.VnPayIpnIntegrationTest.signed;
import static vn.fashion.payment.VnPayIpnIntegrationTest.success;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.fashion.payment.vnpay.VnPayQueryWorker;
import vn.fashion.payment.vnpay.VnPaySigner;

/** TASK:PAY-02 Task 4: QueryDR against a signing VNPay stub; the worker is driven with explicit clocks. */
@ExtendWith(OutputCaptureExtension.class)
class VnPayQueryIntegrationTest extends PaymentTestSupport {
  static final Stub STUB = new Stub();
  @Autowired VnPayQueryWorker worker;
  String order;
  Instant created, expires;

  @DynamicPropertySource static void vnpay(DynamicPropertyRegistry registry) {
    registry.add("fashion.payment.vnpay.api-url", () -> "http://127.0.0.1:" + STUB.server.getAddress().getPort() + "/merchant_webapi/api/transaction");
    registry.add("fashion.payment.vnpay.query-timeout", () -> "1s");
  }

  @BeforeEach @Override protected void reset() throws Exception {
    super.reset();
    STUB.requests.clear(); STUB.responses.clear();
    STUB.answer = r -> answer(r, "99", null); STUB.badHash = false; STUB.delayMillis = 0; STUB.before = null;
  }

  void payment() {
    order = UUID.randomUUID().toString();
    data(create(UUID.randomUUID().toString(), snapshot(order, NO, "VNPAY", 275000, "203.0.113.7")), 200);
    var r = jdbc.queryForMap("select provider_created_at, payment_url_expires_at from payments where order_no = ?", NO);
    created = ((Timestamp) r.get("provider_created_at")).toInstant();
    expires = ((Timestamp) r.get("payment_url_expires_at")).toInstant();
  }
  Instant next() {
    var t = jdbc.queryForObject("select next_reconcile_at from payments where order_no = ?", Timestamp.class, NO);
    return t == null ? null : t.toInstant();
  }
  String status() { return jdbc.queryForObject("select status from payments where order_no = ?", String.class, NO); }
  int events(String type) { return count("select count(*) from outbox_events where event_type = ?", type); }
  int evidence(String source) { return count("select count(*) from payment_transactions where source = ?", source); }
  Map<String, String> ipn(Map<String, String> p) {
    var r = send("GET", "/api/v1/payments/vnpay/ipn?" + VnPayIpnIntegrationTest.query(p), null);
    assertThat(r.statusCode()).isEqualTo(200);
    return mapper.readValue(r.body(), Map.class);
  }
  static Map<String, String> ipnSuccess() { return signed(success(27500000, "14000001", "20261025100405")); }

  /** Unsigned QueryDR answer; status null leaves vnp_TransactionStatus out (as a 91 does). */
  static Map<String, String> answer(JsonNode req, String code, String status) {
    var m = new LinkedHashMap<String, String>();
    m.put("vnp_ResponseId", UUID.randomUUID().toString().replace("-", ""));
    m.put("vnp_Command", "querydr"); m.put("vnp_ResponseCode", code); m.put("vnp_Message", "QueryDR");
    m.put("vnp_TmnCode", "TESTTMN1"); m.put("vnp_TxnRef", req.path("vnp_TxnRef").asString());
    if (code.equals("00")) {
      m.put("vnp_Amount", "27500000"); m.put("vnp_OrderInfo", req.path("vnp_OrderInfo").asString()); m.put("vnp_BankCode", "NCB");
      m.put("vnp_PayDate", "20261025100405"); m.put("vnp_TransactionNo", "14000001"); m.put("vnp_TransactionType", "01");
      if (status != null) m.put("vnp_TransactionStatus", status);
    }
    return m;
  }

  @Test void querySuccessHasTheSameEffectAsIpn() {
    payment();
    STUB.answer = r -> answer(r, "00", "00");
    assertThat(next()).isEqualTo(created.plusSeconds(60));
    worker.runOnce(created.plusSeconds(60));
    assertThat(STUB.requests).hasSize(1);
    JsonNode req = STUB.requests.get(0);
    assertThat(req.path("vnp_RequestId").asString()).matches("[0-9a-f]{32}");
    assertThat(req.path("vnp_Version").asString()).isEqualTo("2.1.0");
    assertThat(req.path("vnp_Command").asString()).isEqualTo("querydr");
    assertThat(req.path("vnp_TmnCode").asString()).isEqualTo("TESTTMN1");
    assertThat(req.path("vnp_TxnRef").asString()).isEqualTo(NO);
    assertThat(req.path("vnp_OrderInfo").asString()).isEqualTo("Thanh toan don hang " + NO);
    assertThat(req.path("vnp_TransactionDate").asString()).isEqualTo(VnPaySigner.format(created));
    // vnp_CreateDate is the real request time (VNPay checks it), not the worker's due time.
    assertThat(VnPaySigner.parse(req.path("vnp_CreateDate").asString())).isBetween(Instant.now().minusSeconds(60), Instant.now());
    assertThat(req.path("vnp_IpAddr").asString()).isEqualTo("127.0.0.1");
    var fields = new LinkedHashMap<String, String>();
    req.properties().forEach(e -> fields.put(e.getKey(), e.getValue().asString()));
    assertThat(req.path("vnp_SecureHash").asString()).isEqualTo(VnPaySigner.hmac("TEST-HASH-SECRET", VnPaySigner.queryChecksumData(fields)));
    assertThat(jdbc.queryForMap("select status, provider_txn_id, paid_at from payments"))
        .containsEntry("status", "SUCCESS").containsEntry("provider_txn_id", "14000001")
        .containsEntry("paid_at", Timestamp.from(Instant.parse("2026-10-25T03:04:05Z")));
    assertThat(events("PAYMENT_COMPLETED")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select provider_event_key from payment_transactions where source = 'QUERY'", String.class))
        .isEqualTo("14000001:00:00:" + STUB.responses.get(0).get("vnp_ResponseId"));
    worker.runOnce(created.plus(Duration.ofHours(1)));
    assertThat(STUB.requests).hasSize(1);
  }

  @Test void ipnThenQueryAndQueryThenIpnStayOneEffect() throws Exception {
    // IPN lands while the query is in flight: the query's answer only adds evidence.
    payment();
    STUB.answer = r -> answer(r, "00", "00");
    var ipnAnswer = new AtomicReference<String>();
    STUB.before = () -> ipnAnswer.set(ipn(ipnSuccess()).get("RspCode"));
    worker.runOnce(created.plusSeconds(60));
    assertThat(ipnAnswer.get()).isEqualTo("00");
    assertThat(status()).isEqualTo("SUCCESS");
    assertThat(events("PAYMENT_COMPLETED")).isEqualTo(1);
    assertThat(evidence("QUERY")).isEqualTo(1);
    // Query first, then the IPN is told "already confirmed".
    reset();
    payment();
    STUB.answer = r -> answer(r, "00", "00");
    worker.runOnce(created.plusSeconds(60));
    assertThat(status()).isEqualTo("SUCCESS");
    assertThat(ipn(ipnSuccess())).containsEntry("RspCode", "02");
    assertThat(events("PAYMENT_COMPLETED")).isEqualTo(1);
    assertThat(count("select count(*) from reconciliation_items")).isZero();
  }

  @Test void parallelIpnAndQueryMakeOneEffect() {
    payment();
    STUB.answer = r -> answer(r, "00", "00");
    var barrier = new CyclicBarrier(2);
    var ipnCall = CompletableFuture.supplyAsync(() -> { await(barrier); return ipn(ipnSuccess()).get("RspCode"); });
    var query = CompletableFuture.runAsync(() -> { await(barrier); worker.runOnce(created.plusSeconds(60)); });
    query.join();
    assertThat(ipnCall.join()).isIn("00", "02");
    assertThat(status()).isEqualTo("SUCCESS");
    assertThat(events("PAYMENT_COMPLETED")).isEqualTo(1);
    assertThat(evidence("WEBHOOK")).isEqualTo(1);
    assertThat(evidence("QUERY")).isEqualTo(1);
  }
  static void await(CyclicBarrier barrier) {
    try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); }
  }

  @Test void notFoundFailsOnlyAfterTheLinkExpiredPlus15Minutes() {
    payment();
    STUB.answer = r -> answer(r, "91", null);
    worker.runOnce(created.plusSeconds(60));
    assertThat(status()).isEqualTo("CREATED");
    assertThat(next()).isEqualTo(created.plus(Duration.ofMinutes(2)));
    worker.runOnce(expires.plus(Duration.ofMinutes(15)));
    assertThat(status()).isEqualTo("CREATED");
    assertThat(events("PAYMENT_FAILED")).isZero();
    Instant late = next();
    assertThat(late).isAfter(expires.plus(Duration.ofMinutes(16)));
    worker.runOnce(late);
    assertThat(status()).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("select payload->>'reason' from outbox_events where event_type = 'PAYMENT_FAILED'", String.class))
        .isEqualTo("EXPIRED");
    assertThat(events("PAYMENT_FAILED")).isEqualTo(1);
    assertThat(evidence("QUERY")).isEqualTo(1);
    int sent = STUB.requests.size();
    worker.runOnce(late.plus(Duration.ofHours(1)));
    assertThat(STUB.requests).hasSize(sent);
  }

  @Test void pendingAtVnpayBecomesProcessing() {
    payment();
    // ResponseCode 00 without a TransactionStatus: evidence only.
    STUB.answer = r -> answer(r, "00", null);
    worker.runOnce(next());
    assertThat(status()).isEqualTo("CREATED");
    STUB.answer = r -> answer(r, "00", "01");
    worker.runOnce(next());
    assertThat(status()).isEqualTo("PROCESSING");
    assertThat(count("select count(*) from outbox_events where event_type <> 'PAYMENT_CREATED'")).isZero();
    STUB.answer = r -> answer(r, "00", "00");
    worker.runOnce(next());
    assertThat(status()).isEqualTo("SUCCESS");
    assertThat(events("PAYMENT_COMPLETED")).isEqualTo(1);
    assertThat(evidence("QUERY")).isEqualTo(3);
  }

  @Test void badChecksumAndTimeoutChangeNothing(CapturedOutput output) {
    payment();
    STUB.answer = r -> answer(r, "00", "00");
    STUB.badHash = true;
    worker.runOnce(created.plusSeconds(60));
    assertThat(STUB.requests).hasSize(1);
    assertThat(status()).isEqualTo("CREATED");
    assertThat(next()).isEqualTo(created.plus(Duration.ofMinutes(2)));
    assertThat(output).contains("VNPay QueryDR response rejected").doesNotContain(STUB.responses.get(0).get("vnp_ResponseId"));
    STUB.badHash = false;
    STUB.delayMillis = 2500; // client read timeout is 1 s here
    worker.runOnce(next());
    assertThat(STUB.requests).hasSize(2);
    assertThat(status()).isEqualTo("CREATED");
    assertThat(next()).isEqualTo(created.plus(Duration.ofMinutes(5)));
    assertThat(evidence("QUERY")).isZero();
    assertThat(count("select count(*) from outbox_events where event_type <> 'PAYMENT_CREATED'")).isZero();
  }

  @Test void scheduleBacksOffAndStopsADayAfterExpiry() {
    payment();
    var expected = new ArrayList<>(List.of(1L, 2L, 5L, 10L, 30L));
    for (long m = 60; !created.plus(Duration.ofMinutes(m)).isAfter(expires.plus(Duration.ofHours(24))); m += 30) expected.add(m);
    var seen = new ArrayList<Long>();
    for (Instant n = next(); n != null && seen.size() < 200; n = next()) {
      seen.add(Duration.between(created, n).toMinutes());
      worker.runOnce(n);
    }
    assertThat(seen).isEqualTo(expected);
    assertThat(seen.getLast()).isEqualTo(24 * 60L); // link TTL 15 min: the last query at created + 24 h
    assertThat(STUB.requests).hasSize(expected.size());
    assertThat(status()).isEqualTo("CREATED");
  }

  @Test void codAndSuccessfulPaymentsAreNeverQueried() {
    cod(UUID.randomUUID().toString(), 50000);
    payment();
    assertThat(ipn(ipnSuccess())).containsEntry("RspCode", "00");
    String closed = UUID.randomUUID().toString();
    data(create(UUID.randomUUID().toString(), snapshot(closed, "ORD202610250002", "VNPAY", 1000, "203.0.113.7")), 200);
    data(close(closed, "c1", withReason(snapshot(closed, "ORD202610250002", "VNPAY", 1000), "expired")), 200);
    asOwner("update payments set next_reconcile_at = now() - interval '1 hour'");
    worker.runOnce(Instant.now().plus(Duration.ofDays(3)));
    assertThat(STUB.requests).isEmpty();
  }

  /** VNPay QueryDR stand-in: records requests, answers signed with TEST-HASH-SECRET (or a wrong key). */
  static final class Stub implements HttpHandler {
    static final JsonMapper JSON = JsonMapper.builder().build();
    final HttpServer server;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> responses = new CopyOnWriteArrayList<>();
    volatile Function<JsonNode, Map<String, String>> answer;
    volatile boolean badHash;
    volatile long delayMillis;
    volatile Runnable before;

    Stub() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
      } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        JsonNode req = JSON.readTree(exchange.getRequestBody().readAllBytes());
        requests.add(req);
        if (before != null) before.run();
        if (delayMillis > 0) Thread.sleep(delayMillis);
        var body = new LinkedHashMap<>(answer.apply(req));
        String data = VnPaySigner.queryResponseChecksumData(JSON.valueToTree(body));
        body.put("vnp_SecureHash", VnPaySigner.hmac(badHash ? "WRONG-SECRET" : "TEST-HASH-SECRET", data));
        responses.add(body);
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // the client gave up (timeout test)
      }
    }
  }
}
