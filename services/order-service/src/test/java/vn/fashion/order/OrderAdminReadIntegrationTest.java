package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.saga.OrderTransitions;

/** TASK:ORD-03 spec sections 3 and 5: OPS order read, PII only with order.fulfill, actor in transitions. */
class OrderAdminReadIntegrationTest extends OrderTestSupport {
  static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(100000);
  @Autowired OrderTransitions transitions;

  private String ops() { return tokenWith(UUID.randomUUID(), "order.read", "order.fulfill"); }
  private String finance() { return tokenWith(UUID.randomUUID(), "order.read"); }
  private HttpResponse<String> get(String path, String token) { return send("GET", "/admin/api/v1/orders" + path, null, "Authorization", token); }

  /** Inserts an order with an ORD-shaped number, a history row and a saga; returns the order_no. */
  private String seed(String status, int minutesAgo, String phone) {
    UUID id = UUID.randomUUID();
    String orderNo = "ORD202610" + SEQ.incrementAndGet();
    jdbc.update("""
        insert into orders(id, order_no, user_id, actor_key, checkout_key, checkout_hash, cart_id, cart_version, status, payment_method,
          subtotal, shipping_fee, cod_fee, total_amount, contact_phone, contact_email, shipping_address, shipping_quote, note,
          expires_at, created_at)
        values (?, ?, ?, ?, ?, repeat('a', 64), gen_random_uuid(), 1, ?, 'COD', 100000, 20000, 5000, 125000, ?, 'a@example.test',
          '{"recipient_name":"Synthetic Person"}', '{}', 'Giao gio hanh chinh', now() + interval '1 day', now() - make_interval(mins => ?))""",
        id, orderNo, MEMBER, "user:" + MEMBER, id.toString(), status, phone, minutesAgo);
    jdbc.update("""
        insert into order_items(id, order_id, variant_id, sku, name, size, color, quantity, unit_price, line_total, catalog_version,
          cart_item_id, cart_item_version) values (gen_random_uuid(), ?, ?, 'TEE-M', 'Ao thun', 'M', 'Den', 1, 100000, 100000, 7,
          gen_random_uuid(), 1)""", id, V1);
    jdbc.update("insert into order_status_history(id, order_id, from_status, to_status, actor_type, reason) values (gen_random_uuid(), ?, 'PENDING', ?, 'ADMIN', 'secret reason')", id, status);
    jdbc.update("insert into order_sagas(order_id, step, status, last_error) values (?, 'WAIT_OPS', 'WAITING', 'boom')", id);
    return orderNo;
  }

  @Test void readNeedsAnOpsToken() {
    assertThat(send("GET", "/admin/api/v1/orders", null).statusCode()).isEqualTo(401);
    assertThat(problem(get("", "Bearer nope"), 401)).isEqualTo("INVALID_ACCESS_TOKEN");
    assertThat(problem(get("", tokenWith(UUID.randomUUID(), "payment.read")), 403)).isEqualTo("PERMISSION_REQUIRED");
    assertThat(problem(get("/ORD202610000001", tokenWith(UUID.randomUUID())), 403)).isEqualTo("PERMISSION_REQUIRED");
  }

  @Test void listFiltersPagesAndFindsExactly() {
    String a = seed("CONFIRMED", 1, "0911111111"), b = seed("CONFIRMED", 2, "0922222222"), c = seed("CONFIRMED", 3, "0933333333");
    seed("PENDING", 4, "0944444444");
    var first = data(get("?status=CONFIRMED&limit=2", ops()), 200);
    assertThat(first.path("items")).hasSize(2);
    assertThat(first.path("items").get(0).path("order_no").asString()).isEqualTo(a);
    assertThat(first.path("items").get(1).path("order_no").asString()).isEqualTo(b);
    assertThat(first.path("items").get(0).path("contact_phone").asString()).isEqualTo("0911111111");
    var second = data(get("?status=CONFIRMED&limit=2&cursor=" + first.path("next_cursor").asString(), ops()), 200);
    assertThat(second.path("items")).hasSize(1);
    assertThat(second.path("items").get(0).path("order_no").asString()).isEqualTo(c);
    assertThat(second.path("next_cursor").isNull()).isTrue();
    var byNo = data(get("?q=" + b, ops()), 200).path("items");
    assertThat(byNo).hasSize(1);
    assertThat(byNo.get(0).path("order_no").asString()).isEqualTo(b);
    assertThat(data(get("?q=0933333333", ops()), 200).path("items").get(0).path("order_no").asString()).isEqualTo(c);
    assertThat(data(get("?q=093333333", ops()), 200).path("items")).isEmpty();
    for (String bad : new String[] {"?limit=0", "?limit=101", "?limit=x", "?status=NOPE", "?cursor=zzz"})
      assertThat(problem(get(bad, ops()), 400)).isEqualTo("INVALID_PARAMETER");
  }

  @Test void detailHasItemsHistoryAndSaga() {
    String no = seed("CONFIRMED", 1, "0911111111");
    var d = data(get("/" + no, ops()), 200);
    assertThat(d.path("order_no").asString()).isEqualTo(no);
    assertThat(d.path("items")).hasSize(1);
    assertThat(d.path("expires_at").isString()).isTrue();
    assertThat(d.path("history").get(0).path("actor_type").asString()).isEqualTo("ADMIN");
    assertThat(d.path("history").get(0).path("reason").asString()).isEqualTo("secret reason");
    assertThat(d.path("saga").path("step").asString()).isEqualTo("WAIT_OPS");
    assertThat(d.path("saga").path("status").asString()).isEqualTo("WAITING");
    assertThat(d.path("saga").path("last_error").asString()).isEqualTo("boom");
    assertThat(d.path("contact_phone").asString()).isEqualTo("0911111111");
    assertThat(problem(get("/ORD202610999999", ops()), 404)).isEqualTo("ORDER_NOT_FOUND");
  }

  @Test void financeSeesNoCustomerContact() {
    String no = seed("CONFIRMED", 1, "0911111111");
    var item = data(get("", finance()), 200).path("items").get(0);
    var d = data(get("/" + no, finance()), 200);
    for (String field : new String[] {"contact_phone", "contact_email", "shipping_address", "note"}) {
      assertThat(item.has(field)).as("list " + field).isFalse();
      assertThat(d.has(field)).as("detail " + field).isFalse();
    }
    assertThat(d.path("history").get(0).path("reason").isNull()).isTrue();
    assertThat(d.path("history").get(0).path("actor_type").asString()).isEqualTo("ADMIN");
    assertThat(problem(get("?q=0911111111", finance()), 403)).isEqualTo("PERMISSION_REQUIRED");
    assertThat(data(get("?q=" + no, finance()), 200).path("items")).hasSize(1);
  }

  @Test void transitionsRecordTheActor() {
    String no = seed("CONFIRMED", 1, "0911111111");
    UUID id = jdbc.queryForObject("select id from orders where order_no = ?", UUID.class, no);
    UUID admin = UUID.randomUUID(), request = UUID.randomUUID();
    transitions.move(id, "CONFIRMED", "PACKING", "r", new OrderTransitions.Actor("ADMIN", admin, request));
    var row = jdbc.queryForMap("select * from order_status_history where to_status = 'PACKING' and order_id = ?", id);
    assertThat(row).containsEntry("actor_type", "ADMIN").containsEntry("actor_id", admin).containsEntry("request_id", request);
    transitions.move(id, "PACKING", "SHIPPING", null);
    assertThat(jdbc.queryForObject("select actor_type from order_status_history where to_status = 'SHIPPING' and order_id = ?", String.class, id)).isEqualTo("SYSTEM");
  }
}
