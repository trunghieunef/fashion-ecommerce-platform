package vn.fashion.order.saga;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.fashion.order.orders.OrderEvents;
import vn.fashion.order.orders.OrderService;

/** Order status edges of 06 section 5.3 used by the saga: status + version + history + outbox in the caller's transaction. */
@Component
public class OrderTransitions {
  private static final Map<String, String> EVENTS = Map.of("CONFIRMED", "ORDER_CONFIRMED", "CANCELLED", "ORDER_CANCELLED");
  private final JdbcClient jdbc;
  private final OrderService orders;
  private final OrderEvents events;

  public OrderTransitions(JdbcClient jdbc, OrderService orders, OrderEvents events) { this.jdbc = jdbc; this.orders = orders; this.events = events; }

  /** Who moved the order (history actor_type SYSTEM | CUSTOMER | ADMIN, actor_id, request_id). */
  public record Actor(String type, UUID id, UUID requestId) {
    public static final Actor SYSTEM = new Actor("SYSTEM", null, null);
  }

  public void move(UUID orderId, String from, String to, String reason) { move(orderId, from, to, reason, Actor.SYSTEM); }

  public void move(UUID orderId, String from, String to, String reason, Actor actor) { move(orderId, from, to, reason, reason, actor); }

  /**
   * Moves from -> to (only from the expected status) or throws, rolling the caller's transaction back. reason goes to history,
   * cancelReason (when not null) to orders.cancel_reason and ORDER_CANCELLED (ORD-03: the OPS text vs OPS_CANCELLED).
   */
  public void move(UUID orderId, String from, String to, String reason, String cancelReason, Actor actor) {
    int moved = jdbc.sql("""
        update orders set status = :to, version = version + 1, updated_at = now(),
          cancel_reason = coalesce(:reason, cancel_reason),
          confirmed_at = case when :to = 'CONFIRMED' then now() else confirmed_at end
        where id = :id and status = :from""")
        .param("to", to).param("reason", cancelReason).param("id", orderId).param("from", from).update();
    if (moved != 1) throw new IllegalStateException("order " + orderId + " is not " + from);
    jdbc.sql("""
        insert into order_status_history(id, order_id, from_status, to_status, actor_type, actor_id, reason, request_id)
        values (:id, :order, :from, :to, :type, :actor, :reason, :request)""")
        .param("id", UUID.randomUUID()).param("order", orderId).param("from", from).param("to", to).param("reason", reason)
        .param("type", actor.type()).param("actor", actor.id()).param("request", actor.requestId()).update();
    String type = EVENTS.get(to);
    if (type != null) {
      UUID userId = jdbc.sql("select user_id from orders where id = :id").param("id", orderId)
          .query((rs, n) -> rs.getObject(1, UUID.class)).list().get(0);
      events.lifecycle(type, orders.view(orderId), userId, to.equals("CANCELLED") ? cancelReason : null, "saga:" + orderId);
    }
  }
}
