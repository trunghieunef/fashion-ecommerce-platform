package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vn.fashion.order.admin.AdminAuth;
import vn.fashion.order.admin.OrderCommands;
import vn.fashion.order.saga.OrderTransitions;
import vn.fashion.order.saga.SagaWorker;

/** TASK:NOT-01 spec section 4: COD orders with a contact email write NOTIFY_ORDER_* in the transition's transaction. */
class OrderNotificationsIntegrationTest extends OrderTestSupport {
  static final AdminAuth.AdminActor OPS = new AdminAuth.AdminActor(UUID.randomUUID(), Set.of("order.read", "order.fulfill", "order.cancel"));
  @Autowired OrderTransitions transitions;
  @Autowired OrderCommands commands;
  @Autowired SagaWorker worker;
  @Autowired TransactionTemplate tx;

  /** A placed member COD order forced into status (fixtures the runtime role may not write), with a contact email. */
  private UUID order(String status, String email, String cancelReason) {
    UUID id = placeOrder();
    asOwner("update orders set status = '" + status + "', contact_email = " + (email == null ? "null" : "'" + email + "'")
        + ", cancel_reason = " + (cancelReason == null ? "null" : "'" + cancelReason + "'") + " where id = '" + id + "'");
    return id;
  }
  private List<Map<String, Object>> notifications(UUID id) {
    return jdbc.queryForList("select event_type, topic, partition_key, aggregate_id, payload::text as payload from outbox_events"
        + " where topic = 'notification.events' and aggregate_id = ? order by aggregate_sequence", id.toString());
  }
  /** The outbox append joins the caller's transaction, as the saga and listeners do. */
  private void move(UUID id, String from, String to, String reason) {
    tx.executeWithoutResult(s -> transitions.move(id, from, to, reason));
  }
  private JsonNode payload(Map<String, Object> row) { return mapper.readTree((String) row.get("payload")); }
  private String no(UUID id) { return jdbc.queryForObject("select order_no from orders where id = ?", String.class, id); }
  private long version(UUID id) { return jdbc.queryForObject("select version from orders where id = ?", Long.class, id); }
  private JsonNode only(UUID id, String type) {
    var rows = notifications(id);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("event_type", type);
    return payload(rows.get(0));
  }

  @Test void eachTransitionWritesOneNotifyEvent() {
    UUID a = order("PENDING", "buyer@example.test", null);
    move(a, "PENDING", "CONFIRMED", null);
    var confirmed = only(a, "NOTIFY_ORDER_CONFIRMED");
    var row = notifications(a).get(0);
    assertThat(row).containsEntry("topic", "notification.events").containsEntry("partition_key", "order:" + a + ":confirmed");
    assertThat(confirmed.path("dedupe_key").asString()).isEqualTo("order:" + a + ":confirmed");
    assertThat(confirmed.path("template_key").asString()).isEqualTo("ORDER_CONFIRMED");
    assertThat(confirmed.path("recipient").path("email").asString()).isEqualTo("buyer@example.test");
    assertThat(confirmed.path("channel").asString()).isEqualTo("EMAIL");
    assertThat(confirmed.path("locale").asString()).isEqualTo("vi");
    assertThat(confirmed.path("user_id").asString()).isEqualTo(MEMBER.toString());
    var data = confirmed.path("data");
    assertThat(data.path("order_no").asString()).isEqualTo(no(a));
    assertThat(data.path("payment_method").asString()).isEqualTo("COD");
    assertThat(data.path("items")).hasSize(2);
    assertThat(jdbc.queryForObject("select cod_fee from orders where id = ?", Long.class, a)).isPositive();
    assertThat(data.path("subtotal").asLong() + data.path("shipping_fee").asLong()).isEqualTo(data.path("total").asLong());
    assertThat(data.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("name", "size", "color", "quantity", "line_total", "image_url");
    assertMatchesSchema(confirmed);

    move(a, "CONFIRMED", "PACKING", null);
    assertThat(notifications(a)).extracting(r -> r.get("event_type")).containsExactly("NOTIFY_ORDER_CONFIRMED", "NOTIFY_ORDER_PREPARING");
    asOwner("update orders set status = 'SHIPPING' where id = '" + a + "'");
    move(a, "SHIPPING", "DELIVERED", null);
    assertThat(notifications(a)).extracting(r -> r.get("partition_key")).containsExactly(
        "order:" + a + ":confirmed", "order:" + a + ":preparing", "order:" + a + ":delivered");
    notifications(a).forEach(r -> assertMatchesSchema(payload(r)));

    UUID c = order("CANCELLING", "buyer@example.test", "EXPIRED");
    move(c, "CANCELLING", "CANCELLED", null);
    assertThat(only(c, "NOTIFY_ORDER_CANCELLED").path("dedupe_key").asString()).isEqualTo("order:" + c + ":cancelled");

    UUID r = order("RETURNING", "buyer@example.test", "RETURNED");
    move(r, "RETURNING", "RETURNED", null);
    var returned = only(r, "NOTIFY_ORDER_RETURNED");
    assertThat(returned.path("dedupe_key").asString()).isEqualTo("order:" + r + ":returned");
    assertThat(returned.path("data").has("cancel_category")).isFalse();
    assertMatchesSchema(returned);
  }

  /** No JSON Schema validator in the Java test classpath: keys must be exactly the schema's (the Python contract tests validate fully). */
  private void assertMatchesSchema(JsonNode payload) {
    JsonNode defs = mapper.readTree(java.nio.file.Path.of("../../contracts/events/notification-events.schema.json").toFile()).path("$defs");
    assertThat(names(payload)).isEqualTo(names(defs.path("NotifyOrder").path("properties")));
    var data = defs.path("OrderEmailData");
    assertThat(names(payload.path("data"))).containsAll(required(data)).isSubsetOf(names(data.path("properties")));
    assertThat(names(payload.path("data").path("items").get(0))).isEqualTo(required(defs.path("OrderEmailItem")));
  }
  private static Set<String> names(JsonNode n) { return new HashSet<>(n.propertyNames()); }
  private static Set<String> required(JsonNode schema) {
    var out = new HashSet<String>();
    schema.path("required").forEach(x -> out.add(x.asString()));
    return out;
  }

  @Test void confirmedCarriesMaskedShipToOnly() {
    UUID a = order("PENDING", "buyer@example.test", null);
    move(a, "PENDING", "CONFIRMED", null);
    move(a, "CONFIRMED", "PACKING", null);
    var rows = notifications(a);
    var shipTo = payload(rows.get(0)).path("data").path("ship_to");
    assertThat(shipTo.path("name").asString()).isEqualTo("Synthetic Person");
    assertThat(shipTo.path("phone_masked").asString()).isEqualTo("091****678");
    assertThat(shipTo.path("address").asString()).isEqualTo("1 Synthetic St");
    rows.forEach(r -> assertThat((String) r.get("payload")).doesNotContain("0912345678"));
    assertThat(payload(rows.get(1)).path("data").has("ship_to")).isFalse();
  }

  @Test void aShortPhoneIsHiddenAndNeverBlocksTheMove() {
    UUID a = order("PENDING", "buyer@example.test", null);
    asOwner("update orders set shipping_address = jsonb_set(shipping_address, '{phone}', '\"12345\"') where id = '" + a + "'");
    move(a, "PENDING", "CONFIRMED", null);
    assertThat(status(a)).isEqualTo("CONFIRMED");
    assertThat(only(a, "NOTIFY_ORDER_CONFIRMED").path("data").path("ship_to").path("phone_masked").asString()).isEqualTo("****");
  }

  @Test void everyCancelPathSendsOneFixedReason() {
    // Customer cancels a CONFIRMED order (ORD-04), compensation ends CANCELLED.
    UUID customer = order("PENDING", "buyer@example.test", null);
    stubSagaOk();
    worker.runOnce();
    assertThat(status(customer)).isEqualTo("CONFIRMED");
    data(send("POST", "/api/v1/orders/" + no(customer) + "/cancel", "{\"expected_version\":" + version(customer) + "}",
        "Authorization", memberToken(), "Idempotency-Key", "c1"), 202);
    worker.runOnce();
    assertThat(status(customer)).isEqualTo("CANCELLED");
    assertThat(cancelled(customer)).containsExactly("CUSTOMER");

    // OPS cancels with free text that must never reach the event.
    UUID ops = order("PENDING", "buyer@example.test", null);
    worker.runOnce();
    assertThat(status(ops)).isEqualTo("CONFIRMED");
    commands.cancel(OPS, no(ops), "cancel-" + ops, version(ops), "khach bom hang");
    worker.runOnce();
    assertThat(status(ops)).isEqualTo("CANCELLED");
    assertThat(cancelled(ops)).containsExactly("SHOP");
    notifications(ops).forEach(r -> assertThat((String) r.get("payload")).doesNotContain("khach bom hang"));
    assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ? and reason = 'khach bom hang'", Integer.class, ops))
        .isEqualTo(1);   // the text is in history, which is exactly what the event must not read

    // Saga codes written by SagaWorker.startCancel.
    for (var e : Map.of("EXPIRED", "EXPIRED", "OUT_OF_STOCK", "OUT_OF_STOCK", "RESERVE_FAILED", "OTHER", "CANCELLED", "OTHER").entrySet()) {
      UUID id = order("CANCELLING", "buyer@example.test", e.getKey());
      move(id, "CANCELLING", "CANCELLED", null);
      assertThat(cancelled(id)).as(e.getKey()).containsExactly(e.getValue());
    }
  }
  private List<String> cancelled(UUID id) {
    return notifications(id).stream().filter(r -> r.get("event_type").equals("NOTIFY_ORDER_CANCELLED"))
        .map(r -> payload(r).path("data").path("cancel_category").asString()).toList();
  }

  @Test void noEmailNoOnlineNoEvent() {
    UUID noEmail = order("PENDING", null, null);
    move(noEmail, "PENDING", "CONFIRMED", null);
    assertThat(notifications(noEmail)).isEmpty();

    UUID online = order("PENDING", "buyer@example.test", null);
    asOwner("update orders set payment_method = 'VNPAY' where id = '" + online + "'");
    move(online, "PENDING", "CONFIRMED", null);
    assertThat(notifications(online)).isEmpty();

    UUID rolledBack = order("PENDING", "buyer@example.test", null);
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
      move(rolledBack, "PENDING", "CONFIRMED", null);
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(notifications(rolledBack)).isEmpty();
    assertThat(status(rolledBack)).isEqualTo("PENDING");

    UUID quiet = order("DELIVERED", "buyer@example.test", null);
    move(quiet, "DELIVERED", "RETURNING", null);
    asOwner("update orders set status = 'CONFIRMED' where id = '" + quiet + "'");
    move(quiet, "CONFIRMED", "CANCELLING", "x");
    UUID shipping = order("PACKING", "buyer@example.test", null);
    move(shipping, "PACKING", "SHIPPING", null);
    assertThat(notifications(quiet)).isEmpty();
    assertThat(notifications(shipping)).isEmpty();
  }
}
