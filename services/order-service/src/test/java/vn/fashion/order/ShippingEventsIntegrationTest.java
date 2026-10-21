package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import vn.fashion.order.orders.ShippingEvents;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

/**
 * TASK:ORD-03 spec section 6: shipment facts move the order forward only (PACKING -> SHIPPING -> DELIVERED). TASK:ORD-04 spec
 * sections 5/7: returns after hand-over, a delivery after a cancel, own-cancel and foreign-shipment events.
 */
class ShippingEventsIntegrationTest extends OrderTestSupport {
  static final UUID SHIPMENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
  @Autowired ShippingEvents events;
  @Autowired InboxGuard inbox;

  /** A placed order forced to PACKING (the saga path is covered by SagaCommitIntegrationTest). */
  private UUID packing() {
    UUID id = placeOrder();
    jdbc.update("update orders set status = 'PACKING' where id = ?", id);
    return id;
  }
  EventEnvelope status(UUID eventId, String type, UUID orderId, String status, String handedOverAt, String occurredAt, long version) {
    return status(eventId, type, orderId, SHIPMENT, status, handedOverAt, occurredAt, version);
  }
  EventEnvelope status(UUID eventId, String type, UUID orderId, UUID shipmentId, String status, String handedOverAt, String occurredAt,
      long version) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", orderId.toString()); payload.put("order_no", "ORD1"); payload.put("shipment_id", shipmentId.toString());
    payload.put("carrier", "SELF"); payload.put("tracking_code", null); payload.put("status", status);
    payload.put("handed_over_at", handedOverAt); payload.put("provider_occurred_at", occurredAt); payload.put("shipment_version", version);
    return new EventEnvelope(eventId, type, 1, Instant.now(), SHIPMENT.toString(), version, version, "corr", mapper.valueToTree(payload));
  }
  EventEnvelope updated(UUID orderId, String status, String handedOverAt, String occurredAt, long version) {
    return status(UUID.randomUUID(), "SHIPMENT_STATUS_UPDATED", orderId, status, handedOverAt, occurredAt, version);
  }
  final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  @BeforeEach void captureLogs() { logs.start(); ((Logger) LoggerFactory.getLogger(ShippingEvents.class)).addAppender(logs); }
  @AfterEach void releaseLogs() { ((Logger) LoggerFactory.getLogger(ShippingEvents.class)).detachAppender(logs); }
  private java.util.List<String> warns() {
    return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
  }
  void deliver(EventEnvelope e) { inbox.applyOnce("order-service", e.eventId(), () -> events.apply(e)); }
  private int history(UUID id, String from, String to) {
    return count("select count(*) from order_status_history where order_id = ? and from_status = ? and to_status = ? and actor_type = 'SYSTEM'",
        id, from, to);
  }
  private Long shipmentVersion(UUID id) { return (Long) saga(id).get("shipment_version"); }
  private int completed() { return count("select count(*) from outbox_events where event_type = 'ORDER_COMPLETED'"); }
  private long version(UUID id) { return jdbc.queryForObject("select version from orders where id = ?", Long.class, id); }

  @Test void aHandedOverShipmentShipsAndADeliveredOneCompletes() throws Exception {
    UUID id = packing();
    long v0 = version(id);
    deliver(updated(id, "DELIVERING", "2026-10-21T03:00:00Z", "2026-10-21T03:00:00Z", 3));
    assertThat(status(id)).isEqualTo("SHIPPING");
    assertThat(jdbc.queryForObject("select shipped_at::text from orders where id = ?", String.class, id)).startsWith("2026-10-21 03:00:00");
    assertThat(shipmentVersion(id)).isEqualTo(3L);
    assertThat(history(id, "PACKING", "SHIPPING")).isEqualTo(1);
    assertThat(completed()).isZero();
    deliver(updated(id, "DELIVERED", "2026-10-21T03:00:00Z", "2026-10-21T08:00:00Z", 4));
    assertThat(status(id)).isEqualTo("DELIVERED");
    assertThat(jdbc.queryForObject("select delivered_at::text from orders where id = ?", String.class, id)).startsWith("2026-10-21 08:00:00");
    assertThat(shipmentVersion(id)).isEqualTo(4L);
    assertThat(history(id, "SHIPPING", "DELIVERED")).isEqualTo(1);
    assertThat(version(id)).isEqualTo(v0 + 2);
    assertThat(completed()).isEqualTo(1);
    // OrderCompleted payload: exactly the schema's fields (additionalProperties false), never the lifecycle payload.
    JsonNode payload = mapper.readTree(jdbc.queryForObject(
        "select payload::text from outbox_events where event_type = 'ORDER_COMPLETED'", String.class));
    JsonNode schema = mapper.readTree(java.nio.file.Path.of("../../contracts/events/order-events.schema.json").toFile())
        .path("$defs").path("OrderCompleted");   // surefire runs in the module directory
    var required = new HashSet<String>();
    schema.path("required").forEach(n -> required.add(n.asString()));
    assertThat(new HashSet<>(payload.propertyNames())).isEqualTo(required);
    assertThat(payload.path("order_id").asString()).isEqualTo(id.toString());
    assertThat(payload.path("user_id").asString()).isEqualTo(MEMBER.toString());
    assertThat(payload.path("order_version").asLong()).isEqualTo(v0 + 2);
    assertThat(payload.path("delivered_at").asString()).isEqualTo("2026-10-21T08:00:00Z");
    assertThat(payload.path("items")).hasSize(2);
    assertThat(new HashSet<>(payload.path("items").get(0).propertyNames())).containsExactlyInAnyOrder("variant_id", "sku", "quantity");
    var row = jdbc.queryForMap("select topic, partition_key, aggregate_id from outbox_events where event_type = 'ORDER_COMPLETED'");
    assertThat(row).containsEntry("topic", "order.events").containsEntry("aggregate_id", id.toString())
        .containsEntry("partition_key", payload.path("order_no").asString());
  }

  @Test void outOfOrderShippingEventsOnlyMoveForward() {
    UUID id = packing();
    var delivered = updated(id, "DELIVERED", "2026-10-21T03:00:00Z", null, 4);
    deliver(delivered);
    assertThat(status(id)).isEqualTo("DELIVERED");
    assertThat(history(id, "PACKING", "SHIPPING")).isEqualTo(1);
    assertThat(history(id, "SHIPPING", "DELIVERED")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select delivered_at is not null from orders where id = ?", Boolean.class, id)).isTrue();
    deliver(updated(id, "DELIVERING", "2026-10-21T03:00:00Z", null, 3));   // older version: ignored
    assertThat(shipmentVersion(id)).isEqualTo(4L);
    deliver(delivered);                                                    // same event_id: once
    deliver(updated(id, "DELIVERED", "2026-10-21T03:00:00Z", null, 4));     // same version, new event_id: ignored
    assertThat(status(id)).isEqualTo("DELIVERED");
    assertThat(shipmentVersion(id)).isEqualTo(4L);
    assertThat(count("select count(*) from order_status_history where order_id = ?", id)).isEqualTo(3);  // PENDING, SHIPPING, DELIVERED
    assertThat(completed()).isEqualTo(1);
    // A stale hand-over behind a newer fact (shipment cancelled before hand-over) never ships a PACKING order.
    UUID stale = packing();
    deliver(updated(stale, "CANCELLED", null, null, 5));
    deliver(updated(stale, "DELIVERING", "2026-10-21T03:00:00Z", null, 3));
    assertThat(status(stale)).isEqualTo("PACKING");
    assertThat(shipmentVersion(stale)).isEqualTo(5L);
  }

  @Test void otherShipmentFactsOnlyRecordTheVersion() {
    UUID id = packing();
    deliver(updated(id, "PICKING", null, null, 2));
    assertThat(shipmentVersion(id)).isEqualTo(2L);
    deliver(updated(id, "FAILED", null, null, 5));
    deliver(updated(id, "RETURNING", null, null, 6));
    deliver(updated(id, "DELIVERING", null, null, 7));                       // not handed over yet: no move
    assertThat(status(id)).isEqualTo("PACKING");
    assertThat(shipmentVersion(id)).isEqualTo(7L);
    // A cancelled order never follows a delivery: version kept, status kept.
    UUID cancelled = placeOrder();
    jdbc.update("update orders set status = 'CANCELLED' where id = ?", cancelled);
    deliver(updated(cancelled, "DELIVERED", "2026-10-21T03:00:00Z", null, 4));
    assertThat(status(cancelled)).isEqualTo("CANCELLED");
    assertThat(shipmentVersion(cancelled)).isEqualTo(4L);
    assertThat(completed()).isZero();
  }

  @Test void shipmentCreatedStoresAMissingShipmentIdOnly() {
    UUID id = packing();
    var before = saga(id);
    deliver(status(UUID.randomUUID(), "SHIPMENT_CREATED", id, "CREATED", null, null, 1));
    assertThat(saga(id)).containsEntry("shipment_id", SHIPMENT).containsEntry("step", before.get("step"))
        .containsEntry("status", before.get("status")).containsEntry("shipment_version", 1L);
    assertThat(status(id)).isEqualTo("PACKING");
  }

  @Test void codMovementsAreOnlyAcknowledgedAndBadPayloadsArePermanent() {
    UUID id = packing();
    var cod = new EventEnvelope(UUID.randomUUID(), "COD_COLLECTED", 1, Instant.now(), SHIPMENT.toString(), 1, 1, "corr",
        mapper.valueToTree(java.util.Map.of("order_id", id.toString())));
    deliver(cod);
    deliver(new EventEnvelope(UUID.randomUUID(), "COD_REMITTED", 1, Instant.now(), SHIPMENT.toString(), 1, 1, "corr", cod.payload()));
    assertThat(count("select count(*) from processed_events where consumer_name = 'order-service'")).isEqualTo(2);
    assertThat(saga(id).get("shipment_version")).isNull();
    var bad = new EventEnvelope(UUID.randomUUID(), "SHIPMENT_STATUS_UPDATED", 1, Instant.now(), SHIPMENT.toString(), 1, 1, "corr",
        mapper.valueToTree(java.util.Map.of("order_id", "nope", "status", "DELIVERED")));
    assertThatThrownBy(() -> events.apply(bad)).isInstanceOf(PermanentEventException.class);
    assertThat(status(id)).isEqualTo("PACKING");
  }

  /** An order in `status` whose saga holds SHIPMENT at shipment_version 3 (and a live lease the event must clear). */
  private UUID withShipment(String status, String step, String sagaStatus, String intent, String shipmentState) {
    UUID id = placeOrder();
    jdbc.update("update orders set status = ? where id = ?", status, id);
    jdbc.update("""
        update order_sagas set shipment_id = ?, step = ?, status = ?, intent = ?, shipment_state = ?, shipment_version = 3,
          retry_count = 2, last_error = 'X', lease_token = gen_random_uuid(), lease_until = now() + interval '1 minute' where order_id = ?""",
        SHIPMENT, step, sagaStatus, intent, shipmentState, id);
    return id;
  }
  private void assertParked(UUID id) {
    var saga = saga(id);
    assertThat(saga).containsEntry("intent", "CANCEL").containsEntry("step", "WAIT_RETURN").containsEntry("status", "WAITING")
        .containsEntry("retry_count", 0).containsEntry("shipment_state", "HANDED_OVER");
    assertThat(saga.get("last_error")).isNull();
    assertThat(saga.get("lease_token")).isNull();
    assertThat(saga.get("lease_until")).isNull();
    assertThat(((java.sql.Timestamp) saga.get("next_retry_at")).toInstant()).isAfter(Instant.now().plus(java.time.Duration.ofDays(29)));
  }
  private String cancelReason(UUID id) { return jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, id); }

  @Test void aFailedDeliveryComingBackMovesTheOrderToReturning() {
    UUID id = withShipment("SHIPPING", "DONE", "DONE", "FULFILL", "CREATED");
    long sagaVersion = (Long) saga(id).get("version");
    deliver(updated(id, "FAILED", "2026-10-21T03:00:00Z", null, 4));
    assertThat(status(id)).isEqualTo("SHIPPING");
    assertThat(shipmentVersion(id)).isEqualTo(4L);
    deliver(updated(id, "RETURNING", "2026-10-21T03:00:00Z", null, 5));
    assertThat(status(id)).isEqualTo("RETURNING");
    assertThat(history(id, "SHIPPING", "RETURNING")).isEqualTo(1);
    assertThat(cancelReason(id)).isEqualTo("RETURNED");
    assertParked(id);
    assertThat((Long) saga(id).get("version")).isEqualTo(sagaVersion + 1);
    assertThat(shipmentVersion(id)).isEqualTo(5L);
    String orderNo = jdbc.queryForObject("select order_no from orders where id = ?", String.class, id);
    assertThat(data(send("GET", "/api/v1/orders/" + orderNo, null, "Authorization", memberToken()), 200)
        .path("processing_status").asString()).isEqualTo("READY");
  }

  @Test void aReturnAfterAnOpsCancelKeepsTheCancelReasonAndReturnedOnlyRecordsTheVersion() {
    UUID id = withShipment("CANCELLING", "WAIT_RETURN", "WAITING", "CANCEL", "HANDED_OVER");
    jdbc.update("update orders set cancel_reason = 'OPS_CANCELLED' where id = ?", id);
    deliver(updated(id, "RETURNING", "2026-10-21T03:00:00Z", null, 5));
    assertThat(status(id)).isEqualTo("RETURNING");
    assertThat(history(id, "CANCELLING", "RETURNING")).isEqualTo(1);
    assertThat(cancelReason(id)).isEqualTo("OPS_CANCELLED");
    assertParked(id);
    // RETURNED (goods back at the shop): version only, no stock call until OPS counts them (spec section 5).
    deliver(updated(id, "RETURNED", "2026-10-21T03:00:00Z", null, 6));
    assertThat(status(id)).isEqualTo("RETURNING");
    assertThat(shipmentVersion(id)).isEqualTo(6L);
    assertParked(id);
    assertThat(StubServers.INVENTORY.seen).isEmpty();
    assertThat(warns()).isEmpty();
  }

  @Test void aCancelledOrderThatStillGotDeliveredIsDelivered() {
    UUID id = withShipment("CANCELLING", "WAIT_RETURN", "WAITING", "CANCEL", "HANDED_OVER");
    deliver(updated(id, "DELIVERING", "2026-10-21T03:00:00Z", null, 4));   // version only
    assertThat(status(id)).isEqualTo("CANCELLING");
    assertThat(shipmentVersion(id)).isEqualTo(4L);
    assertThat(warns()).isEmpty();
    var delivered = updated(id, "DELIVERED", "2026-10-21T03:00:00Z", "2026-10-21T08:00:00Z", 5);
    deliver(delivered);
    deliver(delivered);
    assertThat(status(id)).isEqualTo("DELIVERED");
    assertThat(history(id, "CANCELLING", "SHIPPING")).isEqualTo(1);
    assertThat(history(id, "SHIPPING", "DELIVERED")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select shipped_at::text from orders where id = ?", String.class, id)).startsWith("2026-10-21 03:00:00");
    assertThat(jdbc.queryForObject("select delivered_at::text from orders where id = ?", String.class, id)).startsWith("2026-10-21 08:00:00");
    assertThat(completed()).isEqualTo(1);
    assertThat(saga(id)).containsEntry("status", "DONE").containsEntry("step", "DONE");
    assertThat(saga(id).get("lease_token")).isNull();
    assertThat(warns()).hasSize(1).allMatch(w -> w.contains(id.toString()) && w.contains("CANCELLING"));
  }

  @Test void ownCancelAndForeignShipmentEventsAreQuiet() {
    UUID cancelling = withShipment("CANCELLING", "COMPENSATE", "COMPENSATING", "CANCEL", "CREATED");
    UUID cancelled = withShipment("CANCELLED", "DONE", "DONE", "CANCEL", "CANCELLED");
    deliver(updated(cancelling, "CANCELLED", null, null, 4));
    deliver(updated(cancelled, "CANCELLED", null, null, 4));
    assertThat(status(cancelling)).isEqualTo("CANCELLING");
    assertThat(status(cancelled)).isEqualTo("CANCELLED");
    assertThat(shipmentVersion(cancelling)).isEqualTo(4L);
    assertThat(warns()).isEmpty();
    // Another shipment of the order (ORD-03 M8, spec section 7): ignored, version untouched, warned without PII.
    UUID shipping = withShipment("SHIPPING", "DONE", "DONE", "FULFILL", "CREATED");
    deliver(status(UUID.randomUUID(), "SHIPMENT_STATUS_UPDATED", shipping, UUID.randomUUID(), "RETURNING", "2026-10-21T03:00:00Z", null, 9));
    assertThat(status(shipping)).isEqualTo("SHIPPING");
    assertThat(shipmentVersion(shipping)).isEqualTo(3L);
    assertThat(warns()).hasSize(1).allMatch(w -> w.contains(shipping.toString()));
  }
}
