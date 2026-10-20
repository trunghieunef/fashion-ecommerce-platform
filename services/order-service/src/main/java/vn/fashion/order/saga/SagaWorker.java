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
  // Only these answers are about the order itself (spec section 3, PR #33 review I1); any other refusal (404, *_CONFLICT,
  // IDEMPOTENCY_KEY_REUSED) means our own request or a deploy is wrong: retried into MANUAL, never a cancelled order.
  static final java.util.Set<String> RESERVE_REFUSALS = java.util.Set.of("OUT_OF_STOCK", "RESERVATION_CLOSED");
  static final java.util.Set<String> PAYMENT_REFUSALS =
      java.util.Set.of("PAYMENT_CLOSED", "PAYMENT_METHOD_UNAVAILABLE", "PAYMENT_AMOUNT_UNSUPPORTED");
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
      int before = sagas.saga(claim.orderId()).retryCount();
      try { advance(claim); }
      catch (RuntimeException e) {
        // A bug or bad answer (e.g. 200 without payment_id) spends the retry budget like UNKNOWN, so it ends MANUAL; an
        // intent written in this pass already counted the attempt.
        LOG.error("Saga step failed: order_id={}", claim.orderId(), e);
        try { retry(claim, Math.max(sagas.saga(claim.orderId()).retryCount(), before + 1), Outcome.unknown(e.getClass().getSimpleName())); }
        catch (RuntimeException again) { LOG.error("Saga retry not recorded: order_id={}", claim.orderId(), again); }  // lease expires
      }
    }
    return claims.size();
  }

  private void advance(SagaRepository.Claim c) {
    while (true) {
      var saga = sagas.saga(c.orderId());
      var order = sagas.order(c.orderId());
      // The intent spends the budget too, so a worker dying mid-call on every attempt still ends MANUAL (review M1).
      if (saga.retryCount() >= maxRetries) { manual(c, saga, "RETRY_BUDGET"); return; }
      // Deadline passed (online 15 min / COD 24 h, also while a call is UNKNOWN): cancel, never confirm late.
      boolean more = saga.intent().equals("FULFILL") && !clock.instant().isBefore(order.expiresAt())
          ? startCancel(c, "EXPIRED")
          : switch (saga.step()) {
              case "RESERVE" -> reserve(c, saga, order);
              case "CREATE_PAYMENT" -> createPayment(c, saga, order);
              case "COMPENSATE" -> compensate(c, saga, order);
              default -> { sagas.write(c, jdbc -> { }, true); yield false; }
            };
      if (!more) return;
    }
  }

  private boolean reserve(SagaRepository.Claim c, SagaRepository.Saga saga, SagaOrder order) {
    if (!intent(c, saga, "inventory_state")) return false;
    var out = inventory.reserve(order);
    if (out.result() == Outcome.Result.REJECTED && RESERVE_REFUSALS.contains(out.code())) {
      // This call holds nothing, but an earlier attempt with the same key may still be in flight: compensation releases
      // anyway (tombstone, review I2). NONE marks "refused, never held".
      if (!sagas.write(c, jdbc -> jdbc.sql("update order_sagas set inventory_state = 'NONE' where order_id = :id")
          .param("id", c.orderId()).update(), false)) return false;
      return startCancel(c, "OUT_OF_STOCK".equals(out.code()) ? "OUT_OF_STOCK" : "RESERVE_FAILED");
    }
    if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
    return sagas.write(c, jdbc -> jdbc.sql("""
        update order_sagas set inventory_state = 'HELD', step = 'CREATE_PAYMENT', retry_count = 0, last_error = null
        where order_id = :id""").param("id", c.orderId()).update(), false);
  }

  private boolean createPayment(SagaRepository.Claim c, SagaRepository.Saga saga, SagaOrder order) {
    if (!intent(c, saga, "payment_state")) return false;
    var out = payments.create(order);
    if (out.result() == Outcome.Result.REJECTED && PAYMENT_REFUSALS.contains(out.code())) return startCancel(c, "PAYMENT_FAILED");
    if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
    // The deadline passed during the call: never confirm late (review M2); the next pass cancels EXPIRED and closes it.
    if (!clock.instant().isBefore(order.expiresAt())) return true;
    UUID paymentId = UUID.fromString(out.data().path("payment_id").asString());
    sagas.write(c, jdbc -> {
      // COD: the shop accepted the order and waits for OPS until expires_at (06 section 5.2, ORD-03 confirms).
      jdbc.sql("""
          update order_sagas set payment_id = :p, payment_state = case when payment_state = 'PAID' then 'PAID' else 'CREATED' end,
            step = 'WAIT_OPS', status = 'WAITING', next_retry_at = :deadline, retry_count = 0, last_error = null
          where order_id = :id""")
          .param("p", paymentId).param("deadline", Timestamp.from(order.expiresAt())).param("id", c.orderId()).update();
      transitions.move(c.orderId(), "PENDING", "CONFIRMED", null);
      // ORD-09: cleanup is its own durable work, enqueued once the order is accepted (stock held, payment created), so an
      // order cancelled before that keeps the cart; its failure never touches the order.
      tasks.enqueue(CART_CLEANUP, c.orderId().toString(), "{\"order_id\":\"" + c.orderId() + "\"}");
    }, true);
    return false;
  }

  /** Intent CANCEL first (06 section 5.4), then compensation: CANCELLING now, CANCELLED only after release and close. */
  private boolean startCancel(SagaRepository.Claim c, String reason) {
    String from = sagas.orderStatus(c.orderId());
    return sagas.write(c, jdbc -> {
      jdbc.sql("""
          update order_sagas set intent = 'CANCEL', step = 'COMPENSATE', status = 'COMPENSATING', retry_count = 0, last_error = null,
            next_retry_at = :now where order_id = :id""")
          .param("now", Timestamp.from(clock.instant())).param("id", c.orderId()).update();
      transitions.move(c.orderId(), from, "CANCELLING", reason);
    }, false);
  }

  /** One obligation per pass: release what may be held, close the payment (always: tombstone), then CANCELLED. */
  private boolean compensate(SagaRepository.Claim c, SagaRepository.Saga saga, SagaOrder order) {
    String reason = sagas.cancelReason(c.orderId());
    String stock = saga.inventoryState();
    // Committed or restored stock needs return/restore (ORD-03/04), never a plain release (review M3).
    if (stock.equals("COMMITTED") || stock.equals("RESTORED")) return manual(c, saga, "inventory " + stock);
    if (!stock.equals("RELEASED")) {
      // Always release, even NONE: the tombstone stops a reserve still in flight from holding stock (review I2).
      var out = inventory.release(order, reason);
      // NONE + INVALID_INPUT: an unknown SKU (inventory answered OUT_OF_STOCK, rolls the tombstone back) was never held.
      boolean nothingHeld = stock.equals("NONE") && Outcome.invalid("inventory", "INVALID_INPUT").equals(out.code());
      if (!nothingHeld) {
        if (out.result() == Outcome.Result.REJECTED) return manual(c, saga, "release " + out.code());
        if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
      }
      return sagas.write(c, jdbc -> state(jdbc, c.orderId(), "inventory_state", "RELEASED", "NONE", "HELD", "UNKNOWN"), false);
    }
    // PAYMENT_COMPLETED already recorded: the money is in, the order owes a refund (PAY-04/ORD-04).
    if (saga.paymentState().equals("PAID")) return manual(c, saga, "PAID_NEEDS_REFUND");
    if (!saga.paymentState().equals("CLOSED")) {
      var out = payments.close(order, reason);
      if (out.result() == Outcome.Result.REJECTED) return manual(c, saga, "close " + out.code());
      if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
      String money = out.data().path("status").asString("");
      // Money already collected: closing keeps it, the order owes a refund (PAY-04/ORD-04) - not a plain CANCELLED.
      if (money.equals("COD_COLLECTED") || money.equals("SUCCESS")) return manual(c, saga, "PAID_NEEDS_REFUND");
      // A PAYMENT_COMPLETED recorded meanwhile stays PAID (paid_at kept too).
      return sagas.write(c, jdbc -> jdbc.sql("""
          update order_sagas set payment_state = case when payment_state = 'PAID' then 'PAID' else 'CLOSED' end where order_id = :id""")
          .param("id", c.orderId()).update(), false);
    }
    sagas.write(c, jdbc -> {
      jdbc.sql("update order_sagas set status = 'DONE', step = 'DONE', retry_count = 0, last_error = null where order_id = :id")
          .param("id", c.orderId()).update();
      transitions.move(c.orderId(), "CANCELLING", "CANCELLED", reason);
    }, true);
    return false;
  }

  /** A business refusal that retrying cannot change: MANUAL now, every obligation kept for OPS. */
  private boolean manual(SagaRepository.Claim c, SagaRepository.Saga saga, String error) { return manual(c, saga.retryCount() + 1, error); }

  private boolean manual(SagaRepository.Claim c, int attempts, String error) {
    if (sagas.write(c, jdbc -> jdbc.sql("""
        update order_sagas set status = 'MANUAL', retry_count = :n, last_error = :e where order_id = :id""")
        .param("n", attempts).param("e", error).param("id", c.orderId()).update(), true)) {
      manual.increment();
      LOG.warn("Saga moved to MANUAL: order_id={} last_error={}", c.orderId(), error);
    }
    return false;
  }

  /**
   * Before a forward call: column = UNKNOWN and the attempt counted with its backoff (spec section 3), so a crash before the
   * result is retried later and still ends MANUAL. A result resets retry_count; retry() records the same attempt number.
   */
  private boolean intent(SagaRepository.Claim c, SagaRepository.Saga saga, String column) {
    int attempts = saga.retryCount() + 1;
    var next = backoff(attempts);
    return sagas.write(c, jdbc -> {
      state(jdbc, c.orderId(), column, "UNKNOWN");
      jdbc.sql("update order_sagas set retry_count = :n, next_retry_at = :next where order_id = :id")
          .param("n", attempts).param("next", Timestamp.from(next)).param("id", c.orderId()).update();
    }, false);
  }

  private java.time.Instant backoff(int attempts) {
    long seconds = Math.min(1L << Math.min(attempts, 30), MAX_BACKOFF.toSeconds());
    return clock.instant().plusMillis((long) (seconds * 1000 * ThreadLocalRandom.current().nextDouble(0.8, 1.2)));
  }

  /** UNKNOWN: back off with jitter; after the budget the saga is MANUAL, obligations kept. */
  private boolean retry(SagaRepository.Claim c, SagaRepository.Saga saga, Outcome out) { return retry(c, saga.retryCount() + 1, out); }

  private boolean retry(SagaRepository.Claim c, int attempts, Outcome out) {
    String error = out.result() + " " + out.code();
    if (attempts >= maxRetries) return manual(c, attempts, error);
    var next = backoff(attempts);
    sagas.write(c, jdbc -> jdbc.sql("""
        update order_sagas set retry_count = :n, last_error = :e, next_retry_at = :next where order_id = :id""")
        .param("n", attempts).param("e", error).param("next", Timestamp.from(next)).param("id", c.orderId()).update(), true);
    return false;
  }

  /** Sets column to value only from the listed states (default NONE/UNKNOWN), so a known result is never overwritten. */
  private static void state(org.springframework.jdbc.core.simple.JdbcClient jdbc, UUID orderId, String column, String value,
                            String... from) {
    var states = from.length == 0 ? java.util.List.of("NONE", "UNKNOWN") : java.util.List.of(from);
    jdbc.sql("update order_sagas set " + column + " = case when " + column + " in (:from) then :v else " + column
        + " end where order_id = :id").param("from", states).param("v", value).param("id", orderId).update();
  }
}
