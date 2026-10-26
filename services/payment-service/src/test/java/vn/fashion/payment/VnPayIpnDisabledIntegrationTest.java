package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** TASK:PAY-02: with VNPAY disabled even a well-formed IPN answers 99 (nothing can be verified). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"fashion.messaging.relay.enabled=false", "spring.kafka.listener.auto-startup=false", "fashion.payment.vnpay.hash-secret="})
class VnPayIpnDisabledIntegrationTest extends PaymentTestSupport {
  @Test void ipnWhenDisabledIs99() {
    var p = VnPayIpnIntegrationTest.signed(VnPayIpnIntegrationTest.success(100, "14000001", "20261025100405"));
    var r = send("GET", "/api/v1/payments/vnpay/ipn?" + VnPayIpnIntegrationTest.query(p), null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(json(r).path("RspCode").asString()).isEqualTo("99");
  }
}
