package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** TASK:PAY-02: VNPAY disabled (blank tmn-code) is 422, COD unaffected. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"fashion.messaging.relay.enabled=false", "spring.kafka.listener.auto-startup=false", "fashion.payment.vnpay.tmn-code="})
class VnPayDisabledIntegrationTest extends PaymentTestSupport {
  @Test void vnpayDisabledKeepsCodAndAnswers422() {
    assertThat(problem(create("v", snapshot(UUID.randomUUID().toString(), "ORD-D", "VNPAY", 1000, "203.0.113.7")), 422))
        .isEqualTo("PAYMENT_METHOD_UNAVAILABLE");
    var cod = data(create("c", snapshot(UUID.randomUUID().toString(), "ORD-D2", "COD", 1000)), 200);
    assertThat(cod.path("status").asString()).isEqualTo("COD_PENDING");
    assertThat(cod.path("payment_url").isNull()).isTrue();
  }
}
