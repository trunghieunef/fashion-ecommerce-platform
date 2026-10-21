package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.saga.SagaWorker;

/** TASK:ORD-04 spec section 3: the customer cancels an order still waiting for the shop; ORD-02 compensation releases and closes. */
class CustomerCancelIntegrationTest extends OrderTestSupport {
  static final UUID OPS = UUID.randomUUID();
  @Autowired SagaWorker worker;

  /** A member COD order the saga confirmed (CONFIRMED / WAIT_OPS). */
  private UUID confirmed() {
    UUID id = placeOrder();
    stubSagaOk();
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CONFIRMED");
    assertThat(saga(id)).containsEntry("step", "WAIT_OPS");
    return id;
  }
  private String no(UUID id) { return jdbc.queryForObject("select order_no from orders where id = ?", String.class, id); }
  private long version(UUID id) { return jdbc.queryForObject("select version from orders where id = ?", Long.class, id); }
  private static String body(long version, String reason) {
    return "{\"expected_version\":" + version + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
  }
  private HttpResponse<String> cancel(String orderNo, String key, String body, String... auth) {
    var headers = new ArrayList<>(List.of(auth));
    if (key != null) { headers.add("Idempotency-Key"); headers.add(key); }
    return send("POST", "/api/v1/orders/" + orderNo + "/cancel", body, headers.toArray(String[]::new));
  }
  private HttpResponse<String> member(UUID id, String key, long version, String reason) {
    return cancel(no(id), key, body(version, reason), "Authorization", memberToken());
  }

  @Test void aMemberCancelsAnOrderWaitingForTheShop() {
    UUID id = confirmed();
    long v = version(id);
    var d = data(member(id, "c1", v, "Doi y"), 202);
    assertThat(d.path("status").asString()).isEqualTo("CANCELLING");
    assertThat(d.path("version").asLong()).isEqualTo(v + 1);
    assertThat(d.path("contact").path("phone").asString()).isEqualTo("0912345678");   // the customer view, like the GET
    assertThat(saga(id)).containsEntry("intent", "CANCEL").containsEntry("step", "COMPENSATE").containsEntry("status", "COMPENSATING");
    var history = jdbc.queryForMap("select * from order_status_history where order_id = ? and to_status = 'CANCELLING'", id);
    assertThat(history).containsEntry("actor_type", "CUSTOMER").containsEntry("actor_id", MEMBER).containsEntry("reason", "Doi y");
    assertThat(history.get("request_id")).isNotNull();
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(StubServers.INVENTORY.seen.stream().filter(s -> s.path().endsWith("/release")).count()).isEqualTo(1);
    assertThat(StubServers.PAYMENT.seen.stream().filter(s -> s.path().endsWith("/close")).count()).isEqualTo(1);
    var read = data(send("GET", "/api/v1/orders/" + no(id), null, "Authorization", memberToken()), 200);
    assertThat(read.path("reason_code").asString()).isEqualTo("CUSTOMER_CANCELLED");
    assertThat(count("select count(*) from audit_logs")).isZero();
  }

  @Test void aGuestCancelsByItsCookieAndOthersSeeNothing() {
    UUID cart = UUID.randomUUID();
    stubCheckout(cart, 150000, 99000);
    String checkout = checkoutToken(GUEST_HASH, cart);
    var created = create("g1", createBody(cart, 4, quoteToken(cart, "Checkout-Token", checkout)), "Checkout-Token", checkout);
    UUID id = UUID.fromString(data(created, 202).path("order_id").asString());
    String access = OrderReadIntegrationTest.cookieValue(created);
    stubSagaOk();
    worker.runOnce();
    UUID mine = confirmed();
    long v = version(id);
    // Another member, a cookie of another order, a member on a guest order, no credential: the same 404 (T21).
    assertThat(problem(cancel(no(mine), "k", body(version(mine), null), "Authorization", memberToken(UUID.randomUUID())), 404))
        .isEqualTo("ORDER_NOT_FOUND");
    assertThat(problem(cancel(no(mine), "k", body(version(mine), null), "Cookie", "order_access=" + access), 404)).isEqualTo("ORDER_NOT_FOUND");
    assertThat(problem(cancel(no(id), "k", body(v, null), "Authorization", memberToken()), 404)).isEqualTo("ORDER_NOT_FOUND");
    assertThat(problem(cancel(no(id), "k", body(v, null)), 404)).isEqualTo("ORDER_NOT_FOUND");
    assertThat(problem(cancel("ORD202610999999", "k", body(v, null), "Cookie", "order_access=" + access), 404)).isEqualTo("ORDER_NOT_FOUND");
    var d = data(cancel(no(id), "k", body(v, null), "Cookie", "order_access=" + access), 202);
    assertThat(d.path("status").asString()).isEqualTo("CANCELLING");
    var history = jdbc.queryForMap("select * from order_status_history where order_id = ? and to_status = 'CANCELLING'", id);
    assertThat(history).containsEntry("actor_type", "CUSTOMER").containsEntry("actor_id", null).containsEntry("reason", null);
    assertThat(status(mine)).isEqualTo("CONFIRMED");
  }

  @Test void aCustomerCannotCancelOnceTheShopConfirmed() {
    UUID id = confirmed();
    long v = version(id);
    assertThat(problem(member(id, null, v, null), 400)).isEqualTo("INVALID_KEY");
    assertThat(problem(cancel(no(id), "k", "{}", "Authorization", memberToken()), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(member(id, "k", v, "x".repeat(501)), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(member(id, "k", v - 1, null), 409)).isEqualTo("VERSION_CONFLICT");
    data(send("POST", "/admin/api/v1/orders/" + no(id) + "/confirm-cod", body(v, null), "Authorization",
        tokenWith(OPS, "order.read", "order.fulfill"), "Idempotency-Key", "ops"), 202);
    var late = member(id, "k2", v + 1, null);
    assertThat(problem(late, 409)).isEqualTo("INVALID_TRANSITION");
    assertThat(json(late).path("errors").get(0).path("field").asString()).isEqualTo("status");
    assertThat(json(late).path("errors").get(0).path("message").asString()).isEqualTo("current=CONFIRMED");
    // PENDING: the saga still holds the stock.
    UUID pending = placeOrder();
    assertThat(problem(member(pending, "k3", version(pending), null), 409)).isEqualTo("INVALID_TRANSITION");
    assertThat(status(pending)).isEqualTo("PENDING");
  }

  @Test void aCustomerCancelReplayReturnsTheSameResponse() {
    UUID id = confirmed();
    long v = version(id);
    var first = data(member(id, "same", v, "r"), 202);
    worker.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(data(member(id, "same", v, "r"), 202)).isEqualTo(first);
    assertThat(problem(member(id, "same", v, "other"), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }
}
