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
        .isInstanceOf(DataAccessException.class).hasMessageContaining("orders_open_cart_version_idx");
    order(UUID.randomUUID(), cart, 5, "user:" + MEMBER, MEMBER, null, 125000);
  }

  @Test void aCancelledOrderFreesItsCartVersion() {
    // ORD-02 V002: the cart state of a cancelled order (out of stock) can be ordered again.
    UUID cart = UUID.randomUUID(), first = UUID.randomUUID();
    order(first, cart, 4, "user:" + MEMBER, MEMBER, null, 125000);
    assertThatThrownBy(() -> order(UUID.randomUUID(), cart, 4, "user:" + MEMBER, MEMBER, null, 125000))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("orders_open_cart_version_idx");
    asOwner("update orders set status = 'CANCELLED' where id = '" + first + "'");
    order(UUID.randomUUID(), cart, 4, "user:" + MEMBER, MEMBER, null, 125000);
    assertThat(count("select count(*) from background_tasks")).isZero();
    assertThat(count("select count(*) from processed_events")).isZero();
    assertThat(jdbc.queryForObject("select column_default from information_schema.columns where table_name = 'order_sagas' and column_name = 'payment_state'", String.class))
        .contains("NONE");
  }

  @Test void aGuestOrderNeedsItsAccessHashAndAMemberOrderHasNone() {
    assertThatThrownBy(() -> order(UUID.randomUUID(), UUID.randomUUID(), 1, "guest:" + "b".repeat(64), null, null, 125000))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("orders_check");
    assertThatThrownBy(() -> order(UUID.randomUUID(), UUID.randomUUID(), 1, "user:" + MEMBER, MEMBER, "c".repeat(64), 125000))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("orders_check");
    order(UUID.randomUUID(), UUID.randomUUID(), 1, "guest:" + "b".repeat(64), null, "c".repeat(64), 125000);
  }

  @Test void auditLogsAreAppendOnly() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into audit_logs(id, action, resource_type, resource_id, request_id) values (?, 'ORDER_CONFIRM_COD', 'ORDER', 'x', gen_random_uuid())", id);
    assertThatThrownBy(() -> jdbc.update("update audit_logs set reason = 'x' where id = ?", id)).rootCause().hasMessageContaining("permission denied");
    assertThatThrownBy(() -> jdbc.update("delete from audit_logs where id = ?", id)).rootCause().hasMessageContaining("permission denied");
    assertThat(count("select count(*) from idempotency_requests")).isZero();
    assertThat(count("select count(*) from information_schema.columns where (table_name, column_name) in (('orders','shipped_at'), ('order_sagas','shipment_version'))")).isEqualTo(2);
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

  @Test void returnTablesGuardQuantities() {
    // TASK:ORD-04 V004 (spec section 8): restock <= received, one slip per (order, key), items never rewritten by the runtime.
    UUID order = UUID.randomUUID(), item = UUID.randomUUID(), slip = UUID.randomUUID();
    order(order, UUID.randomUUID(), 1, "user:" + MEMBER, MEMBER, null, 125000);
    jdbc.update("""
        insert into order_items(id, order_id, variant_id, sku, name, size, color, quantity, unit_price, line_total, catalog_version, cart_item_id,
          cart_item_version) values (?, ?, ?, 'TEE-M', 'Ao thun', 'M', 'Den', 2, 50000, 100000, 1, gen_random_uuid(), 1)""", item, order, V1);
    jdbc.update("insert into order_returns(id, order_id, operation_key, request_id) values (?, ?, 'r1', gen_random_uuid())", slip, order);
    assertThat(jdbc.queryForObject("select status from order_returns where id = ?", String.class, slip)).isEqualTo("REQUESTED");
    assertThatThrownBy(() -> jdbc.update("insert into order_returns(id, order_id, operation_key, request_id) values (gen_random_uuid(), ?, 'r1', gen_random_uuid())", order))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("order_returns_order_id_operation_key_key");
    assertThatThrownBy(() -> jdbc.update("insert into order_return_items(return_id, order_item_id, received_quantity, restock_quantity) values (?, ?, 1, 2)", slip, item))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("order_return_items_check");
    jdbc.update("insert into order_return_items(return_id, order_item_id, received_quantity, restock_quantity) values (?, ?, 2, 1)", slip, item);
    assertThatThrownBy(() -> jdbc.update("update order_return_items set restock_quantity = 0 where return_id = ?", slip))
        .rootCause().hasMessageContaining("permission denied");
    assertThatThrownBy(() -> jdbc.update("delete from order_returns where id = ?", slip)).rootCause().hasMessageContaining("permission denied");
    assertThat(jdbc.queryForObject("select column_default from information_schema.columns where table_name = 'order_sagas' and column_name = 'shipment_state'", String.class))
        .contains("NONE");
  }
}
