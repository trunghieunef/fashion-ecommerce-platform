package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.fashion.payment.payments.PaymentService;

/** TASK:PAY-02: VNPAY disabled (blank tmn-code) is 422, COD unaffected. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"fashion.messaging.relay.enabled=false", "spring.kafka.listener.auto-startup=false", "fashion.payment.vnpay.tmn-code="})
class VnPayDisabledIntegrationTest extends PaymentTestSupport {
  @Autowired PaymentService payments;

  @Test void vnpayDisabledKeepsCodAndAnswers422() {
    assertThat(problem(create("v", snapshot(UUID.randomUUID().toString(), "ORD-D", "VNPAY", 1000, "203.0.113.7")), 422))
        .isEqualTo("PAYMENT_METHOD_UNAVAILABLE");
    var cod = data(create("c", snapshot(UUID.randomUUID().toString(), "ORD-D2", "COD", 1000)), 200);
    assertThat(cod.path("status").asString()).isEqualTo("COD_PENDING");
    assertThat(cod.path("payment_url").isNull()).isTrue();
  }

  /** PR #41 review M4: VNPAY switched off with open payments; a retry still gets its payment, never a 422 that cancels the order. */
  @Test void retryAfterVnpayIsDisabledGetsTheExistingPayment() {
    String order = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
    String body = snapshot(order, "ORD-R", "VNPAY", 1000, "203.0.113.7");
    String hash = payments.hash(PaymentService.parse(mapper.readTree(body), Set.of("client_ip", "locale")));
    asOwner("insert into payments(id, order_id, order_no, method, amount, status, provider_request_id, request_hash, snapshot, expires_at)"
        + " values ('" + id + "', '" + order + "', 'ORD-R', 'VNPAY', 1000, 'CREATED', 'ORD-R', '" + hash + "', '{}', now())");
    assertThat(data(create("other-key", body), 200).path("payment_id").asString()).isEqualTo(id);
  }
}
