package vn.fashion.order.orders;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

/**
 * ORD-02 spec section 6 (T07): PAYMENT_COMPLETED records paid_at (first value wins) and payment_state PAID, inside the inbox
 * transaction; the order status is not changed here (COD money arrives after delivery: ORD-03/04). Others are acknowledged.
 */
@Component
public class PaymentEvents {
  private static final Logger LOG = LoggerFactory.getLogger(PaymentEvents.class);
  private final JdbcClient jdbc;

  public PaymentEvents(JdbcClient jdbc) { this.jdbc = jdbc; }

  public Object apply(EventEnvelope event) {
    if (!event.eventType().equals("PAYMENT_COMPLETED")) return null;
    UUID orderId;
    Instant paidAt;
    try {
      orderId = UUID.fromString(event.payload().path("order_id").asString());
      String paid = event.payload().path("paid_at").asString(null);
      paidAt = paid == null ? event.occurredAt() : Instant.parse(paid);
    } catch (RuntimeException e) { throw new PermanentEventException("Invalid PAYMENT_COMPLETED payload"); }
    // Service-wide lock order: order_sagas then orders (as saga worker, OPS commands, ShippingEvents). No saga row: nothing to lock.
    jdbc.sql("select order_id from order_sagas where order_id = :id for update").param("id", orderId).query().listOfRows();
    int orders = jdbc.sql("update orders set paid_at = coalesce(paid_at, :paid) where id = :id")
        .param("paid", Timestamp.from(paidAt)).param("id", orderId).update();
    if (orders == 0) { LOG.warn("PAYMENT_COMPLETED for an unknown order: order_id={}", orderId); return null; }
    jdbc.sql("update order_sagas set payment_state = 'PAID' where order_id = :id").param("id", orderId).update();
    String status = jdbc.sql("select status from orders where id = :id").param("id", orderId).query(String.class).single();
    // Money after the order was closed: kept, refund belongs to PAY-04/ORD-04 (06 section 5.4, ORD-08).
    if (status.equals("CANCELLING") || status.equals("CANCELLED")) LOG.warn("Payment completed on a cancelled order: order_id={}", orderId);
    return null;
  }
}
