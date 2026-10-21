package vn.fashion.order.saga;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lease and compare-and-set on order_sagas (06 section 1.2). platform LeaseRepository keys on "id"; order_sagas keys on
 * order_id, so the same SQL lives here. A write only lands while the caller still holds an unexpired lease.
 */
@Repository
public class SagaRepository {
  public record Claim(UUID orderId, UUID leaseToken) { }
  public record Saga(UUID orderId, String intent, String step, String status, String inventoryState, String paymentState,
                     int retryCount) { }

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SagaRepository(JdbcClient jdbc, TransactionTemplate tx, Clock clock) { this.jdbc = jdbc; this.tx = tx; this.clock = clock; }

  /** Due, unleased (or lease expired) sagas, oldest first; SKIP LOCKED so parallel workers never share one. */
  public List<Claim> claimDue(int limit, Duration lease) {
    return jdbc.sql("""
        update order_sagas set lease_token = gen_random_uuid(), lease_until = now() + make_interval(secs => :lease)
        where order_id in (
          select order_id from order_sagas
          where status in ('RUNNING', 'WAITING', 'COMPENSATING') and next_retry_at <= :now
            and (lease_until is null or lease_until <= now())
          order by next_retry_at limit :limit for update skip locked)
        returning order_id, lease_token""")
        .param("lease", lease.toMillis() / 1000.0).param("now", Timestamp.from(clock.instant())).param("limit", limit)
        .query(Claim.class).list();
  }

  /**
   * One transaction: bump version if the lease is still ours (optionally releasing it), then run the write. False, with
   * nothing written, when the lease expired or another worker reclaimed the saga.
   */
  public boolean write(Claim claim, Consumer<JdbcClient> write, boolean release) {
    return Boolean.TRUE.equals(tx.execute(status -> {
      int held = jdbc.sql("update order_sagas set version = version + 1, updated_at = now()"
              + (release ? ", lease_token = null, lease_until = null" : "")
              + " where order_id = :id and lease_token = :token and lease_until > now()")
          .param("id", claim.orderId()).param("token", claim.leaseToken()).update();
      if (held == 0) return false;
      write.accept(jdbc);
      return true;
    }));
  }

  public Saga saga(UUID orderId) {
    return jdbc.sql("""
        select order_id, intent, step, status, inventory_state, payment_state, retry_count from order_sagas where order_id = :id""")
        .param("id", orderId).query(Saga.class).single();
  }

  public String orderStatus(UUID orderId) {
    return jdbc.sql("select status from orders where id = :id").param("id", orderId).query(String.class).single();
  }
  public String cancelReason(UUID orderId) {
    return jdbc.sql("select coalesce(cancel_reason, 'CANCELLED') from orders where id = :id").param("id", orderId)
        .query(String.class).single();
  }

  public SagaOrder order(UUID orderId) {
    var items = jdbc.sql("""
        select cart_item_id, cart_item_version, sku, quantity from order_items where order_id = :id order by sku, cart_item_id""")
        .param("id", orderId).query(SagaOrder.Item.class).list();
    return jdbc.sql("""
        select id, order_no, user_id, actor_key, cart_id, payment_method, total_amount, expires_at, (shipping_quote ->> 'quote_id')::uuid,
          shipping_address ->> 'recipient_name', shipping_address ->> 'phone', shipping_address ->> 'province_code',
          shipping_address ->> 'ward_code', shipping_address ->> 'address_line' from orders where id = :id""")
        .param("id", orderId).query((rs, n) -> new SagaOrder(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
            rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getLong(7), rs.getTimestamp(8).toInstant(), items,
            rs.getObject(9, UUID.class), address(rs)))
        .single();
  }

  /** ShipmentCreate.to_address keys; district_code is not collected, so it is omitted (additionalProperties false). */
  private static java.util.Map<String, String> address(java.sql.ResultSet rs) throws java.sql.SQLException {
    var a = new java.util.LinkedHashMap<String, String>();
    a.put("recipient_name", rs.getString(10)); a.put("phone", rs.getString(11)); a.put("province_code", rs.getString(12));
    a.put("ward_code", rs.getString(13)); a.put("address_line", rs.getString(14));
    return a;
  }
}
