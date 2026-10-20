package vn.fashion.order.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.fashion.order.deps.InventoryClient;
import vn.fashion.order.deps.Outcome;
import vn.fashion.order.deps.PaymentClient;
import vn.fashion.platform.work.BackgroundTaskRepository;

/**
 * ORD-02 checkout saga (spec section 3). Per claimed saga: write the intent under the lease, call outside any transaction,
 * then write the result by lease CAS. A lost lease drops the result; the next attempt repeats the same idempotent call.
 */
@Component
public class SagaWorker {
  private static final Logger LOG = LoggerFactory.getLogger(SagaWorker.class);
  static final Duration LEASE = Duration.ofSeconds(30);
  static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
  static final String CART_CLEANUP = "CART_CLEANUP";
  private final SagaRepository sagas;
  private final OrderTransitions transitions;
  private final InventoryClient inventory;
  private final PaymentClient payments;
  private final BackgroundTaskRepository tasks;
  private final Clock clock;
  private final int maxRetries;
  private final Counter manual;

  public SagaWorker(SagaRepository sagas, OrderTransitions transitions, InventoryClient inventory, PaymentClient payments,
                    BackgroundTaskRepository tasks, Clock clock, MeterRegistry meters,
                    @Value("${fashion.order.saga.max-retries:30}") int maxRetries) {
    this.sagas = sagas; this.transitions = transitions; this.inventory = inventory; this.payments = payments; this.tasks = tasks;
    this.clock = clock; this.maxRetries = maxRetries;
    this.manual = Counter.builder("order.saga.manual").description("Sagas moved to MANUAL after the retry budget").register(meters);
  }

  /** Processes every due saga once; returns how many were claimed. */
  public int runOnce() {
    var claims = sagas.claimDue(10, LEASE);
    for (var claim : claims) {
      try { advance(claim); }
      catch (RuntimeException e) { LOG.error("Saga step failed: order_id={}", claim.orderId(), e); }  // lease expires, retried
    }
    return claims.size();
  }

  private void advance(SagaRepository.Claim c) {
    while (true) {
      var saga = sagas.saga(c.orderId());
      var order = sagas.order(c.orderId());
      boolean more = switch (saga.step()) {
        case "RESERVE" -> reserve(c, saga, order);
        case "CREATE_PAYMENT" -> createPayment(c, saga, order);
        default -> { sagas.write(c, jdbc -> { }, true); yield false; }
      };
      if (!more) return;
    }
  }

  private boolean reserve(SagaRepository.Claim c, SagaRepository.Saga saga, SagaOrder order) {
    if (!sagas.write(c, jdbc -> state(jdbc, c.orderId(), "inventory_state", "UNKNOWN"), false)) return false;
    var out = inventory.reserve(order);
    if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
    return sagas.write(c, jdbc -> {
      jdbc.sql("""
          update order_sagas set inventory_state = 'HELD', step = 'CREATE_PAYMENT', retry_count = 0, last_error = null
          where order_id = :id""").param("id", c.orderId()).update();
      // ORD-09: cleanup is its own durable work, enqueued once the stock is held; its failure never touches the order.
      tasks.enqueue(CART_CLEANUP, c.orderId().toString(), "{\"order_id\":\"" + c.orderId() + "\"}");
    }, false);
  }

  private boolean createPayment(SagaRepository.Claim c, SagaRepository.Saga saga, SagaOrder order) {
    if (!sagas.write(c, jdbc -> state(jdbc, c.orderId(), "payment_state", "UNKNOWN"), false)) return false;
    var out = payments.create(order);
    if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
    UUID paymentId = UUID.fromString(out.data().path("payment_id").asString());
    sagas.write(c, jdbc -> {
      // COD: the shop accepted the order and waits for OPS until expires_at (06 section 5.2, ORD-03 confirms).
      jdbc.sql("""
          update order_sagas set payment_id = :p, payment_state = case when payment_state = 'PAID' then 'PAID' else 'CREATED' end,
            step = 'WAIT_OPS', status = 'WAITING', next_retry_at = :deadline, retry_count = 0, last_error = null
          where order_id = :id""")
          .param("p", paymentId).param("deadline", Timestamp.from(order.expiresAt())).param("id", c.orderId()).update();
      transitions.move(c.orderId(), "PENDING", "CONFIRMED", null);
    }, true);
    return false;
  }

  /** UNKNOWN (and, until Task 4, every non-OK): back off with jitter; after the budget the saga is MANUAL, obligations kept. */
  private boolean retry(SagaRepository.Claim c, SagaRepository.Saga saga, Outcome out) {
    int attempts = saga.retryCount() + 1;
    String error = out.result() + " " + out.code();
    if (attempts >= maxRetries) {
      if (sagas.write(c, jdbc -> jdbc.sql("""
          update order_sagas set status = 'MANUAL', retry_count = :n, last_error = :e where order_id = :id""")
          .param("n", attempts).param("e", error).param("id", c.orderId()).update(), true)) {
        manual.increment();
        LOG.warn("Saga moved to MANUAL: order_id={} last_error={}", c.orderId(), error);
      }
      return false;
    }
    long seconds = Math.min(1L << Math.min(attempts, 30), MAX_BACKOFF.toSeconds());
    var next = clock.instant().plusMillis((long) (seconds * 1000 * ThreadLocalRandom.current().nextDouble(0.8, 1.2)));
    sagas.write(c, jdbc -> jdbc.sql("""
        update order_sagas set retry_count = :n, last_error = :e, next_retry_at = :next where order_id = :id""")
        .param("n", attempts).param("e", error).param("next", Timestamp.from(next)).param("id", c.orderId()).update(), true);
    return false;
  }

  private static void state(org.springframework.jdbc.core.simple.JdbcClient jdbc, UUID orderId, String column, String value) {
    jdbc.sql("update order_sagas set " + column + " = case when " + column + " in ('NONE', 'UNKNOWN') then :v else " + column
        + " end where order_id = :id").param("v", value).param("id", orderId).update();
  }
}
