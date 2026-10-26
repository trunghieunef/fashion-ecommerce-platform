package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.fashion.payment.vnpay.VnPaySigner;

/** TASK:PAY-02 Task 2: VNPAY create returns a signed link, makes no network call and keeps one TxnRef per order. */
class VnPayCreateIntegrationTest extends PaymentTestSupport {
  static Map<String, String> query(String url) {
    var q = new LinkedHashMap<String, String>();
    assertThat(URI.create(url).getHost()).isEqualTo("sandbox.vnpayment.vn");
    for (String pair : URI.create(url).getRawQuery().split("&")) {
      String[] kv = pair.split("=", 2);
      q.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
    }
    return q;
  }

  @Test void vnpayCreatesASignedLinkOnce() {
    String order = UUID.randomUUID().toString();
    Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    var p = data(create("k1", snapshot(order, "ORD202610250001", "VNPAY", 275000, "203.0.113.7")), 200);
    assertThat(p.path("status").asString()).isEqualTo("CREATED");
    var q = query(p.path("payment_url").asString());
    assertThat(q).containsEntry("vnp_TxnRef", "ORD202610250001").containsEntry("vnp_Amount", "27500000")
        .containsEntry("vnp_IpAddr", "203.0.113.7").containsEntry("vnp_Locale", "vn").containsEntry("vnp_TmnCode", "TESTTMN1")
        .containsEntry("vnp_Version", "2.1.0").containsEntry("vnp_Command", "pay").containsEntry("vnp_CurrCode", "VND")
        .containsEntry("vnp_OrderType", "other").containsEntry("vnp_OrderInfo", "Thanh toan don hang ORD202610250001")
        .doesNotContainKey("vnp_BankCode");
    assertThat(VnPaySigner.valid("TEST-HASH-SECRET", q)).isTrue();
    Instant created = jdbc.queryForObject("select provider_created_at from payments", java.sql.Timestamp.class).toInstant();
    assertThat(created).isBetween(before, Instant.now());
    assertThat(VnPaySigner.parse(q.get("vnp_CreateDate"))).isEqualTo(created);
    assertThat(VnPaySigner.parse(q.get("vnp_ExpireDate"))).isEqualTo(created.plus(15, ChronoUnit.MINUTES)); // order expires later
    assertThat(Instant.parse(p.path("payment_url_expires_at").asString())).isEqualTo(created.plus(15, ChronoUnit.MINUTES));
    assertThat(jdbc.queryForObject("select (next_reconcile_at - provider_created_at)::text from payments", String.class)).isEqualTo("00:01:00");
    var again = data(create("k2", snapshot(order, "ORD202610250001", "VNPAY", 275000, "203.0.113.7")), 200);
    assertThat(again.path("payment_url").asString()).isEqualTo(p.path("payment_url").asString());
    assertThat(again.path("payment_url_expires_at").asString()).isEqualTo(p.path("payment_url_expires_at").asString());
    assertThat(count("select count(*) from payments")).isEqualTo(1);
    assertThat(count("select count(*) from payment_transactions where source = 'CREATE'")).isEqualTo(1);
    assertThat(count("select count(*) from outbox_events where event_type = 'PAYMENT_CREATED'")).isEqualTo(1);
    var read = data(send("GET", "/internal/api/v1/payments/by-order/" + order, null, "Authorization", serviceToken("order-service")), 200);
    assertThat(read.path("payment_url").asString()).isEqualTo(p.path("payment_url").asString());
  }

  @Test void localeEnGivesEnglishLink() {
    String body = snapshot(UUID.randomUUID().toString(), "ORD-EN", "VNPAY", 1000, "203.0.113.7").replace("}", ",\"locale\":\"en\"}");
    assertThat(query(data(create("k", body), 200).path("payment_url").asString())).containsEntry("vnp_Locale", "en");
  }

  @Test void linkNeverOutlivesTheOrder() {
    Instant expires = Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    String body = snapshot(UUID.randomUUID().toString(), "ORD-X", "VNPAY", 1000, "203.0.113.7").replace("2030-01-01T00:00:00Z", expires.toString());
    var p = data(create("k", body), 200);
    assertThat(VnPaySigner.parse(query(p.path("payment_url").asString()).get("vnp_ExpireDate"))).isEqualTo(expires);
    assertThat(Instant.parse(p.path("payment_url_expires_at").asString())).isEqualTo(expires);
  }

  @Test void vnpayNeedsAClientIp() {
    String order = UUID.randomUUID().toString();
    int n = 0;
    for (String ip : new String[] {"", "abc", "1.2.3", "<script>", "x".repeat(50), "999.999.999.999", "256.1.1.1", "1:2:3:4:5:6:7:8:9", ":::"})
      assertThat(problem(create("a" + n++, snapshot(order, "ORD-IP", "VNPAY", 1000, ip)), 400)).isEqualTo("INVALID_FIELDS");
    var missing = create("m", snapshot(order, "ORD-IP", "VNPAY", 1000));
    assertThat(problem(missing, 400)).isEqualTo("INVALID_FIELDS");
    assertThat(missing.body()).contains("client_ip");
    var fr = create("l", snapshot(order, "ORD-IP", "VNPAY", 1000, "203.0.113.7").replace("}", ",\"locale\":\"fr\"}"));
    assertThat(problem(fr, 400)).isEqualTo("INVALID_FIELDS");
    assertThat(count("select count(*) from payments")).isZero();
    assertThat(data(create("v6", snapshot(order, "ORD-IP", "VNPAY", 1000, "2001:db8::1")), 200).path("status").asString()).isEqualTo("CREATED");
    // PR #41 review M3: short IPv6 (loopback) is an address too.
    assertThat(data(create("lo", snapshot(UUID.randomUUID().toString(), "ORD-LO", "VNPAY", 1000, "::1")), 200).path("status").asString())
        .isEqualTo("CREATED");
  }

  /** PR #41 review M5: an order already past its deadline gets no link (ExpireDate would not be after CreateDate). */
  @Test void expiredOrderGetsNoLink() {
    String body = snapshot(UUID.randomUUID().toString(), "ORD-OLD", "VNPAY", 1000, "203.0.113.7")
        .replace("2030-01-01T00:00:00Z", Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString());
    assertThat(problem(create("k", body), 409)).isEqualTo("PAYMENT_CLOSED");
    assertThat(count("select count(*) from payments")).isZero();
  }

  @Test void clientIpAndLocaleDoNotChangeTheSnapshotHash() {
    String order = UUID.randomUUID().toString();
    String id = data(create("k1", snapshot(order, "ORD-H", "VNPAY", 1000, "203.0.113.7")), 200).path("payment_id").asString();
    var other = data(create("k2", snapshot(order, "ORD-H", "VNPAY", 1000, "198.51.100.9").replace("}", ",\"locale\":\"en\"}")), 200);
    assertThat(other.path("payment_id").asString()).isEqualTo(id);
    assertThat(query(other.path("payment_url").asString())).containsEntry("vnp_IpAddr", "203.0.113.7");
  }

  @Test void closedVnpayOrderIs409() {
    String order = UUID.randomUUID().toString(), body = snapshot(order, "ORD-C", "VNPAY", 1000, "203.0.113.7");
    data(create("k1", body), 200);
    data(close(order, "c1", withReason(snapshot(order, "ORD-C", "VNPAY", 1000), "cancel")), 200);
    assertThat(problem(create("k2", body), 409)).isEqualTo("PAYMENT_CLOSED");
  }
}
