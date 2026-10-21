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
import vn.fashion.order.saga.SagaWorker;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

/**
 * ORD-03 spec section 6: shipment facts move the order forward only, inside the inbox transaction. A shipment_version at or
 * below the stored one is stale or a duplicate. COD_COLLECTED/COD_REMITTED belong to payment-service and are only acknowledged.
 * ORD-04 spec sections 5/7: returns (RETURNING), a delivery after a cancel, own cancels and foreign shipments.
 */
@Component
public class ShippingEvents {
  private static final Logger LOG = LoggerFactory.getLogger(ShippingEvents.class);
  private static final java.util.Set<String> ATTENTION = java.util.Set.of("FAILED", "MANUAL");
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
    record Stored(Long version, UUID shipmentId) { }
    var stored = jdbc.sql("select shipment_version, shipment_id from order_sagas where order_id = :id for update").param("id", s.orderId())
        .query((rs, n) -> new Stored(rs.getObject(1, Long.class), rs.getObject(2, UUID.class))).list();
    if (stored.isEmpty()) { LOG.warn("Shipment event for an unknown order: order_id={}", s.orderId()); return null; }
    String status = jdbc.sql("select status from orders where id = :id for update").param("id", s.orderId()).query(String.class).single();
    // ORD-04 spec section 7: another shipment of the order never moves it nor its shipment_version.
    if (stored.get(0).shipmentId() != null && !stored.get(0).shipmentId().equals(s.shipmentId())) {
      LOG.warn("Event of a foreign shipment ignored: order_id={} status={}", s.orderId(), s.status());
      return null;
    }
    // The saga may have lost the create response: keep the id (the saga's retry gets the same shipment back).
    if (created) jdbc.sql("update order_sagas set shipment_id = coalesce(shipment_id, :s), updated_at = now() where order_id = :id")
        .param("s", s.shipmentId()).param("id", s.orderId()).update();
    Long version = stored.get(0).version();
    if (version != null && s.version() <= version) return null;
    jdbc.sql("update order_sagas set shipment_version = :v, updated_at = now() where order_id = :id")
        .param("v", s.version()).param("id", s.orderId()).update();
    if (created) return null;
    switch (s.status()) {
      case "RETURNING", "RETURNED" -> {
        // ORD-04 spec section 5: goods on the way back; a RETURNED that overtook RETURNING moves the order the same way.
        // Stock waits for the OPS count (return slip), never this event. On PACKING the hand-over was overtaken or lost:
        // ship the order first, as an overtaking DELIVERED does.
        if (status.equals("PACKING")) { ship(s, status); returning(s.orderId(), "SHIPPING"); }
        else if (status.equals("SHIPPING") || status.equals("CANCELLING")) returning(s.orderId(), status);
        else if (!status.equals("RETURNING") && !status.equals("RETURNED"))
          LOG.warn("Shipment {} on a {} order: order_id={}", s.status(), status, s.orderId());
        if (s.status().equals("RETURNED")) LOG.info("Shipment back at the shop, awaiting the OPS count: order_id={}", s.orderId());
        return null;
      }
      case "CANCELLED" -> {
        // Our own compensation cancelled it: nothing to say.
        if (!status.equals("CANCELLING") && !status.equals("CANCELLED"))
          LOG.warn("Shipment CANCELLED on a {} order: order_id={}", status, s.orderId());
        return null;
      }
      default -> { }
    }
    boolean handedOver = s.status().equals("DELIVERING") && s.handedOverAt() != null;
    boolean delivered = s.status().equals("DELIVERED");
    if (!handedOver && !delivered) {
      if (ATTENTION.contains(s.status())) LOG.warn("Shipment {} needs OPS: order_id={} status={}", s.status(), s.orderId(), status);
      return null;   // PICKING, DELIVERING without hand-over: nothing to do
    }
    // ORD-04 spec section 5: a hand-over on a CANCELLING order only records the version (compensation parks on 409).
    if (status.equals("CANCELLING") && !delivered) return null;
    if (!status.equals("PACKING") && !status.equals("SHIPPING") && !status.equals("CANCELLING")) {
      if (!status.equals("DELIVERED")) LOG.warn("Shipment {} on a {} order: order_id={}", s.status(), status, s.orderId());
      return null;
    }
    if (status.equals("CANCELLING")) {
      // Physical evidence wins over the cancel: the goods reached the customer (CANCELLING -> SHIPPING -> DELIVERED).
      LOG.warn("Cancelled order delivered anyway: order_id={} status={} shipment={}", s.orderId(), status, s.status());
      jdbc.sql("""
          update order_sagas set intent = 'FULFILL', status = 'DONE', step = 'DONE', lease_token = null, lease_until = null,
            last_error = null, version = version + 1, updated_at = now() where order_id = :id""").param("id", s.orderId()).update();
      // A delivered order carries no cancel reason (PR #35 review M2); the cancel stays in history and audit_logs.
      jdbc.sql("update orders set cancel_reason = null where id = :id").param("id", s.orderId()).update();
    }
    if (!status.equals("SHIPPING")) ship(s, status);
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

  private void ship(Shipment s, String from) {
    transitions.move(s.orderId(), from, "SHIPPING", null);
    // An event that overtook DELIVERING may carry no hand-over time: the event time stands in.
    Instant shipped = s.handedOverAt() != null ? s.handedOverAt() : s.occurredAt() != null ? s.occurredAt() : clock.instant();
    jdbc.sql("update orders set shipped_at = coalesce(shipped_at, :t) where id = :id").param("t", Timestamp.from(shipped))
        .param("id", s.orderId()).update();
  }

  /**
   * SHIPPING/CANCELLING -> RETURNING: the saga waits (CANCEL, WAIT_RETURN, parked like SagaWorker.park) for the return slip that
   * restocks and closes the payment (ORD-04 spec section 5). A SHIPPING order gets cancel_reason RETURNED; an OPS/customer
   * cancel keeps its own. The lease is cleared so an in-flight worker's CAS write loses.
   */
  private void returning(UUID orderId, String from) {
    jdbc.sql("""
        update order_sagas set intent = 'CANCEL', step = 'WAIT_RETURN', status = 'WAITING', next_retry_at = :park, retry_count = 0,
          last_error = null, lease_token = null, lease_until = null, shipment_state = 'HANDED_OVER', version = version + 1,
          updated_at = now() where order_id = :id""")
        .param("park", Timestamp.from(clock.instant().plus(SagaWorker.RETURN_PARK))).param("id", orderId).update();
    transitions.move(orderId, from, "RETURNING", null);
    jdbc.sql("update orders set cancel_reason = coalesce(cancel_reason, 'RETURNED') where id = :id").param("id", orderId).update();
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
