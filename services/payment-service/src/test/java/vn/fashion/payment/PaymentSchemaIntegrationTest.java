package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class PaymentSchemaIntegrationTest extends PaymentTestSupport {
  private String payment(long amount) {
    String id = UUID.randomUUID().toString(), order = UUID.randomUUID().toString();
    asOwner("insert into payments(id, order_id, order_no, method, amount, status, provider_request_id, request_hash, snapshot, expires_at)"
        + " values ('" + id + "', '" + order + "', 'S" + order.substring(0, 8) + "', 'COD', " + amount + ", 'COD_COLLECTED', 'S"
        + order.substring(0, 8) + "', repeat('a', 64), '{}', now())");
    return id;
  }

  @Test void settlementMustBeGrossEqualsFeePlusNet() {
    String id = payment(100);
    assertThatThrownBy(() -> jdbc.update("""
        insert into cod_settlements(id, payment_id, settlement_ref, gross_amount, fee_amount, net_amount, evidence_ref, confirmed_by)
        values (gen_random_uuid(), cast(? as uuid), 'R1', 100, 10, 80, 'E', gen_random_uuid())""", id))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("cod_settlements_check");
  }

  @Test void refundsNeverExceedTheAmount() {
    String id = payment(100);
    assertThatThrownBy(() -> jdbc.update("update payments set refunded_amount = 60, refund_reserved_amount = 41 where id = cast(? as uuid)", id))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("payments_check");
  }

  @Test void oneRowPerOrderAndSuccessNeedsPaidAt() {
    String id = payment(100);
    String order = jdbc.queryForObject("select order_id::text from payments where id = cast(? as uuid)", String.class, id);
    assertThatThrownBy(() -> jdbc.update("""
        insert into payments(id, order_id, order_no, method, amount, status, provider_request_id, request_hash, snapshot, expires_at)
        values (gen_random_uuid(), cast(? as uuid), 'OTHER', 'COD', 1, 'COD_PENDING', 'OTHER', repeat('a', 64), '{}', now())""", order))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("payments_order_id_key");
    assertThatThrownBy(() -> jdbc.update("update payments set status = 'SUCCESS' where id = cast(? as uuid)", id))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("payments_check");
  }

  @Test void runtimeCannotDeleteMoneyOrRewriteEvidence() {
    String id = payment(100);
    jdbc.update("""
        insert into cod_settlements(id, payment_id, settlement_ref, gross_amount, fee_amount, net_amount, evidence_ref, confirmed_by)
        values (gen_random_uuid(), cast(? as uuid), 'R1', 100, 10, 90, 'E', gen_random_uuid())""", id);
    assertThatThrownBy(() -> jdbc.update("delete from payments where id = cast(? as uuid)", id))
        .rootCause().hasMessageContaining("permission denied");
    assertThatThrownBy(() -> jdbc.update("update cod_settlements set net_amount = 0, fee_amount = 100 where payment_id = cast(? as uuid)", id))
        .rootCause().hasMessageContaining("permission denied");
    assertThatThrownBy(() -> jdbc.update("delete from cod_settlements where payment_id = cast(? as uuid)", id))
        .rootCause().hasMessageContaining("permission denied");
  }
}
