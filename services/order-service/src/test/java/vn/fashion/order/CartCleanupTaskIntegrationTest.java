package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.saga.CartCleanupWorker;
import vn.fashion.order.saga.SagaWorker;

/** REQ ORD-09: durable cart cleanup, independent of the saga; its failure never touches the order. */
class CartCleanupTaskIntegrationTest extends OrderTestSupport {
  @Autowired SagaWorker saga;
  @Autowired CartCleanupWorker cleanup;

  private String task(UUID id) { return jdbc.queryForObject("select status from background_tasks where business_key = ?", String.class, id.toString()); }

  @Test void aConfirmedOrderCleansItsCartOnce() {
    UUID id = placeOrder();
    stubSagaOk();
    saga.runOnce();
    StubServers.CART.reset();
    StubServers.CART.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"removed\":[],\"retained\":[{\"item_id\":\"x\"}],\"missing\":[]}")));
    assertThat(cleanup.runOnce()).isEqualTo(1);
    assertThat(task(id)).isEqualTo("DONE");
    var seen = StubServers.CART.seen.get(0);
    assertThat(seen.path()).endsWith("/checkout-cleanup");
    assertThat(seen.idempotencyKey()).isEqualTo(id.toString());
    assertThat(cleanup.runOnce()).isZero();
  }

  @Test void aFailingCartIsRetriedAndTheOrderStaysConfirmed() {
    UUID id = placeOrder();
    stubSagaOk();
    saga.runOnce();
    StubServers.CART.reset();
    StubServers.CART.reply(s -> new StubServers.Reply(503, StubServers.error("TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE")));
    cleanup.runOnce();
    assertThat(task(id)).isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("select attempts from background_tasks where business_key = ?", Integer.class, id.toString())).isEqualTo(1);
    assertThat(status(id)).isEqualTo("CONFIRMED");
    StubServers.CART.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"removed\":[],\"retained\":[],\"missing\":[]}")));
    asOwner("update background_tasks set next_attempt_at = now() - interval '1 second'");
    cleanup.runOnce();
    assertThat(task(id)).isEqualTo("DONE");
  }

  @Test void aCancelledOrderNeverEmptiesTheCart() {
    // Payment refused after the stock was held: cleanup was enqueued, but the customer keeps the cart.
    UUID id = placeOrder();
    stubSagaOk();
    StubServers.PAYMENT.reply(s -> s.path().endsWith("/close")
        ? new StubServers.Reply(200, StubServers.ok("{\"status\":\"FAILED\"}"))
        : new StubServers.Reply(409, StubServers.error("CONFLICT", "PAYMENT_CLOSED")));
    saga.runOnce();
    assertThat(status(id)).isEqualTo("CANCELLED");
    StubServers.CART.reset();
    cleanup.runOnce();
    assertThat(StubServers.CART.seen).isEmpty();
    assertThat(task(id)).isEqualTo("DONE");
  }
}
