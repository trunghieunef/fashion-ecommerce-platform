package vn.fashion.order.orders;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.fashion.order.saga.OrderTransitions;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

/**
 * ORD-03 spec section 6: shipment facts move the order forward only, inside the inbox transaction. A shipment_version at or
 * below the stored one is stale or a duplicate. COD_COLLECTED/COD_REMITTED belong to payment-service and are only acknowledged.
 */
@Component
public class ShippingEvents {
  private static final Logger LOG = LoggerFactory.getLogger(ShippingEvents.class);
  private static final java.util.Set<String> ATTENTION = java.util.Set.of("FAILED", "RETURNING", "RETURNED", "CANCELLED", "MANUAL");
  private final JdbcClient jdbc;
  private final OrderTransitions transitions;
  private final OrderService orders;
  private final OrderEvents events;
  private final Clock clock;

  public ShippingEvents(JdbcClient jdbc, OrderTransitions transitions, OrderService orders, OrderEvents events, Clock clock) {
    this.jdbc = jdbc; this.transitions = transitions; this.orders = orders; this.events = events; this.clock = clock;
  }

  record Shipment(UUID orderId, UUID shipmentId, String status, Instant handedOverAt, Instant occurredAt, long version) { }

  public Object apply(EventEnvelope event) {
    boolean created = event.eventType().equals("SHIPMENT_CREATED");
    if (!created && !event.eventType().equals("SHIPMENT_STATUS_UPDATED")) return null;
    Shipment s = parse(event);
    // One lock order across the service (SagaWorker, OrderCommands): order_sagas, then orders.
    var stored = jdbc.sql("select shipment_version from order_sagas where order_id = :id for update").param("id", s.orderId())
        .query((rs, n) -> rs.getObject(1, Long.class)).list();
    if (stored.isEmpty()) { LOG.warn("Shipment event for an unknown order: order_id={}", s.orderId()); return null; }
    String status = jdbc.sql("select status from orders where id = :id for update").param("id", s.orderId()).query(String.class).single();
    // The saga may have lost the create response: keep the id (the saga's retry gets the same shipment back).
    if (created) jdbc.sql("update order_sagas set shipment_id = coalesce(shipment_id, :s), updated_at = now() where order_id = :id")
        .param("s", s.shipmentId()).param("id", s.orderId()).update();
    if (stored.get(0) != null && s.version() <= stored.get(0)) return null;
    jdbc.sql("update order_sagas set shipment_version = :v, updated_at = now() where order_id = :id")
        .param("v", s.version()).param("id", s.orderId()).update();
    if (created) return null;
    boolean handedOver = s.status().equals("DELIVERING") && s.handedOverAt() != null;
    boolean delivered = s.status().equals("DELIVERED");
    if (!handedOver && !delivered) {
      // The order stays SHIPPING with the COD payment open until ORD-04 handles it: say so (no PII; PR #34 review M1).
      if (ATTENTION.contains(s.status())) LOG.warn("Shipment {} needs OPS (ORD-04): order_id={} status={}", s.status(), s.orderId(), status);
      return null;   // PICKING, DELIVERING without hand-over: nothing to do
    }
    if (!status.equals("PACKING") && !status.equals("SHIPPING")) {
      if (!status.equals("DELIVERED")) LOG.warn("Shipment {} on a {} order: order_id={}", s.status(), status, s.orderId());
      return null;
    }
    if (status.equals("PACKING")) {
      transitions.move(s.orderId(), "PACKING", "SHIPPING", null);
      // A DELIVERED that overtook DELIVERING may carry no hand-over time: the delivery time stands in.
      Instant shipped = s.handedOverAt() != null ? s.handedOverAt() : s.occurredAt() != null ? s.occurredAt() : clock.instant();
      jdbc.sql("update orders set shipped_at = :t where id = :id").param("t", Timestamp.from(shipped)).param("id", s.orderId()).update();
    }
    if (delivered) {
      Instant at = s.occurredAt() != null ? s.occurredAt() : clock.instant();
      transitions.move(s.orderId(), "SHIPPING", "DELIVERED", null);
      jdbc.sql("update orders set delivered_at = :t where id = :id").param("t", Timestamp.from(at)).param("id", s.orderId()).update();
      UUID userId = jdbc.sql("select user_id from orders where id = :id").param("id", s.orderId())
          .query((rs, n) -> rs.getObject(1, UUID.class)).list().get(0);
      events.completed(orders.view(s.orderId()), userId, at, event.correlationId());
    }
    return null;
  }

  private static Shipment parse(EventEnvelope event) {
    try {
      var p = event.payload();
      var version = p.path("shipment_version");
      String status = p.path("status").asString(null);
      if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0 || status == null)
        throw new IllegalArgumentException();
      return new Shipment(UUID.fromString(p.path("order_id").asString()), UUID.fromString(p.path("shipment_id").asString()), status,
          instant(p.path("handed_over_at").asString(null)), instant(p.path("provider_occurred_at").asString(null)), version.longValue());
    } catch (RuntimeException e) { throw new PermanentEventException("Invalid " + event.eventType() + " payload"); }
  }
  private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }
}
