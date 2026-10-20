package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class OrderSchemaIntegrationTest extends OrderTestSupport {
  /** Inserts a member order as the runtime role; total and the other columns are given by the caller. */
  private void order(UUID id, UUID cart, long cartVersion, String actorKey, UUID userId, String accessHash, long total) {
    jdbc.update("""
        insert into orders(id, order_no, user_id, actor_key, guest_access_hash, checkout_key, checkout_hash, cart_id, cart_version,
          payment_method, subtotal, shipping_fee, cod_fee, total_amount, contact_phone, shipping_address, shipping_quote, expires_at)
        values (?, ?, ?, ?, ?, ?, repeat('a', 64), ?, ?, 'COD', 100000, 20000, 5000, ?, '0912345678', '{}', '{}', now())""",
        id, "S" + id.toString().substring(0, 8), userId, actorKey, accessHash, id.toString(), cart, cartVersion, total);
  }

  @Test void totalsMustAddUp() {
    assertThatThrownBy(() -> order(UUID.randomUUID(), UUID.randomUUID(), 1, "user:" + MEMBER, MEMBER, null, 124999))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("orders_check");
  }

  @Test void oneOrderPerCartVersion() {
    UUID cart = UUID.randomUUID();
    order(UUID.randomUUID(), cart, 4, "user:" + MEMBER, MEMBER, null, 125000);
    assertThatThrownBy(() -> order(UUID.randomUUID(), cart, 4, "user:" + MEMBER, MEMBER, null, 125000))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("orders_cart_id_cart_version_key");
    order(UUID.randomUUID(), cart, 5, "user:" + MEMBER, MEMBER, null, 125000);
  }

  @Test void aGuestOrderNeedsItsAccessHashAndAMemberOrderHasNone() {
    assertThatThrownBy(() -> order(UUID.randomUUID(), UUID.randomUUID(), 1, "guest:" + "b".repeat(64), null, null, 125000))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("orders_check");
    assertThatThrownBy(() -> order(UUID.randomUUID(), UUID.randomUUID(), 1, "user:" + MEMBER, MEMBER, "c".repeat(64), 125000))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("orders_check");
    order(UUID.randomUUID(), UUID.randomUUID(), 1, "guest:" + "b".repeat(64), null, "c".repeat(64), 125000);
  }

  @Test void theRuntimeNeverDeletesOrdersNorRewritesHistory() {
    UUID id = UUID.randomUUID();
    order(id, UUID.randomUUID(), 1, "user:" + MEMBER, MEMBER, null, 125000);
    jdbc.update("insert into order_status_history(id, order_id, to_status, actor_type) values (gen_random_uuid(), ?, 'PENDING', 'CUSTOMER')", id);
    assertThatThrownBy(() -> jdbc.update("delete from orders where id = ?", id))
        .rootCause().hasMessageContaining("permission denied");
    assertThatThrownBy(() -> jdbc.update("update order_status_history set reason = 'x' where order_id = ?", id))
        .rootCause().hasMessageContaining("permission denied");
    assertThat(jdbc.queryForObject("select nextval('order_no_seq')", Long.class)).isPositive();
  }
}
