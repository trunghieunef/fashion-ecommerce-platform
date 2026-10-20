package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentAdminIntegrationTest extends PaymentTestSupport {
  final String finance = staffToken("payment.read");

  @Test void listFiltersByStatusAndPagesNewestFirst() {
    for (int i = 0; i < 3; i++) cod(UUID.randomUUID().toString(), 1000 + i);
    String closedOrder = UUID.randomUUID().toString(), body = snapshot(closedOrder, "A-X", "COD", 10);
    data(close(closedOrder, "c", withReason(body, "hủy")), 200);
    var page = data(send("GET", "/admin/api/v1/payments?status=COD_PENDING&limit=2", null, "Authorization", finance), 200);
    assertThat(page.path("items")).hasSize(2);
    assertThat(page.path("items").get(0).path("amount").asLong()).isEqualTo(1002);
    var rest = data(send("GET", "/admin/api/v1/payments?status=COD_PENDING&limit=2&cursor=" + page.path("next_cursor").asString(), null,
        "Authorization", finance), 200);
    assertThat(rest.path("items")).hasSize(1);
    assertThat(rest.path("next_cursor").isNull()).isTrue();
    assertThat(problem(send("GET", "/admin/api/v1/payments?status=NOPE", null, "Authorization", finance), 400)).isEqualTo("INVALID_PARAMETER");
  }

  @Test void detailShowsEvidenceSettlementsAndReconciliation() {
    String order = UUID.randomUUID().toString(), id = cod(order, 5000);
    jdbc.update("""
        insert into reconciliation_items(id, payment_id, source_ref, kind, expected_amount, observed_amount)
        values (gen_random_uuid(), cast(? as uuid), 'ref-1', 'COD_AMOUNT_MISMATCH', 5000, 4000)""", id);
    var detail = data(send("GET", "/admin/api/v1/payments/" + id, null, "Authorization", finance), 200);
    assertThat(detail.path("payment_id").asString()).isEqualTo(id);
    assertThat(detail.path("provider_request_id").asString()).isEqualTo("P" + order.substring(0, 8));
    assertThat(detail.path("transactions").get(0).path("source").asString()).isEqualTo("CREATE");
    assertThat(detail.path("settlements")).isEmpty();
    assertThat(detail.path("reconciliation_items").get(0).path("kind").asString()).isEqualTo("COD_AMOUNT_MISMATCH");
    assertThat(problem(send("GET", "/admin/api/v1/payments/" + UUID.randomUUID(), null, "Authorization", finance), 404))
        .isEqualTo("PAYMENT_NOT_FOUND");
    assertThat(problem(send("GET", "/admin/api/v1/payments/" + id, null, "Authorization", staffToken("shipping.read")), 403))
        .isEqualTo("PERMISSION_REQUIRED");
    assertThat(problem(send("GET", "/admin/api/v1/payments", null), 401)).isEqualTo("INVALID_ACCESS_TOKEN");
  }
}
