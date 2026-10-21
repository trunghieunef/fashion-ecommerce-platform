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
import vn.fashion.order.deps.ShippingClient;
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
  // ORD-03 spec section 4: the order deadline applies only up to the OPS confirm; from COMMIT_STOCK inventory decides.
  static final java.util.Set<String> DEADLINE_STEPS = java.util.Set.of("RESERVE", "CREATE_PAYMENT", "WAIT_OPS");
  // ORD-04 ruling: WAIT_RETURN is moved by shipping events / return slips only; a claim just re-parks it.
  static final Duration RETURN_PARK = Duration.ofDays(30);
  private final SagaRepository sagas;
  private final OrderTransitions transitions;
  private final InventoryClient inventory;
  private final PaymentClient payments;
  private final ShippingClient shipping;
  private final BackgroundTaskRepository tasks;
  private final Clock clock;
  private final int maxRetries;
  private final MeterRegistry meters;
  private final Counter handedOver;

  public SagaWorker(SagaRepository sagas, OrderTransitions transitions, InventoryClient inventory, PaymentClient payments, ShippingClient shipping,
                    BackgroundTaskRepository tasks, Clock clock, MeterRegistry meters,
                    @Value("${fashion.order.saga.max-retries:30}") int maxRetries) {
    this.sagas = sagas; this.transitions = transitions; this.inventory = inventory; this.payments = payments; this.shipping = shipping; this.tasks = tasks;
    this.clock = clock; this.maxRetries = maxRetries;
    this.meters = meters;
    this.handedOver = Counter.builder("order.saga.handed_over").description("Cancels that found the shipment already handed over")
        .register(meters);
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
      boolean more = saga.intent().equals("FULFILL") && DEADLINE_STEPS.contains(saga.step())
          && !clock.instant().isBefore(order.expiresAt())
          ? startCancel(c, "EXPIRED")
          : switch (saga.step()) {
              case "RESERVE" -> reserve(c, saga, order);
              case "CREATE_PAYMENT" -> createPayment(c, saga, order);
              case "COMMIT_STOCK" -> commitStock(c, saga, order);
              case "CREATE_SHIPMENT" -> createShipment(c, saga, order);
              case "COMPENSATE" -> compensate(c, saga, order);
              case "WAIT_RETURN" -> { park(c, null); yield false; }
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

  /**
   * ORD-03 spec section 4: inventory is the arbiter of the deadline. The intent keeps HELD (UNKNOWN would let compensation
   * release committed stock, T09); a lost answer retries the same key. EXPIRED/CLOSED: inventory already released it.
   */
  private boolean commitStock(SagaRepository.Claim c, SagaRepository.Saga saga, SagaOrder order) {
    if (!intent(c, saga, null)) return false;
    var out = inventory.commit(order);
    boolean expired = "RESERVATION_EXPIRED".equals(out.code());
    if (out.result() == Outcome.Result.REJECTED && (expired || "RESERVATION_CLOSED".equals(out.code()))) {
      if (!sagas.write(c, jdbc -> jdbc.sql("update order_sagas set inventory_state = 'RELEASED' where order_id = :id")
          .param("id", c.orderId()).update(), false)) return false;
      return startCancel(c, expired ? "EXPIRED" : "RESERVE_FAILED");
    }
    if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
    sagas.write(c, jdbc -> {
      jdbc.sql("""
          update order_sagas set inventory_state = 'COMMITTED', step = 'CREATE_SHIPMENT', retry_count = 0, last_error = null,
            next_retry_at = :now
          where order_id = :id""").param("now", Timestamp.from(clock.instant())).param("id", c.orderId()).update();
      transitions.move(c.orderId(), "CONFIRMED", "PACKING", null);
    }, true);
    return false;   // the shipment is its own pass: a lost lease or crash here cannot repeat the commit
  }

  /** ORD-03 spec section 4: any refusal is about our request or a deploy (stock stays committed), so MANUAL; UNKNOWN retries. */
  private boolean createShipment(SagaRepository.Claim c, SagaRepository.Saga saga, SagaOrder order) {
    if (!intent(c, saga, null)) return false;
    var out = shipping.create(order);
    if (out.result() == Outcome.Result.REJECTED) return manual(c, saga, "REJECTED " + out.code());
    if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
    UUID shipmentId = UUID.fromString(out.data().path("shipment_id").asString());
    sagas.write(c, jdbc -> jdbc.sql("""
        update order_sagas set shipment_id = :s, shipment_state = 'CREATED', status = 'DONE', step = 'DONE', retry_count = 0, last_error = null
        where order_id = :id""").param("s", shipmentId).param("id", c.orderId()).update(), true);
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

  /**
   * ORD-04 spec section 4, one obligation per pass, each call preceded by an intent that only counts the attempt (never
   * UNKNOWN): shipment cancel, then stock (release; restore via /returns when inventory says committed), payment, CANCELLED.
   */
  private boolean compensate(SagaRepository.Claim c, SagaRepository.Saga saga, SagaOrder order) {
    String reason = sagas.cancelReason(c.orderId());
    String stock = saga.inventoryState();
    String ship = saga.shipmentState();
    // The goods left the shop: never restore stock; the return flow (shipping events) takes over.
    if (ship.equals("HANDED_OVER")) { park(c, null); return false; }
    // A shipment is only created after the commit, so this also covers a create whose response was lost.
    boolean shipmentMayExist = stock.equals("COMMITTED") || stock.equals("RESTORED") || saga.shipmentId() != null || !ship.equals("NONE");
    if (shipmentMayExist && !ship.equals("CANCELLED")) {
      if (!intent(c, saga, null)) return false;
      var out = shipping.cancel(order, reason);
      if (out.result() == Outcome.Result.REJECTED && "SHIPMENT_HANDED_OVER".equals(out.code())) {
        if (park(c, "HANDED_OVER")) {
          handedOver.increment();
          LOG.warn("Cancel found the shipment handed over, waiting for the return: order_id={}", c.orderId());
        }
        return false;
      }
      if (out.result() == Outcome.Result.REJECTED) return manual(c, saga, "shipment-cancel " + out.code());
      // In doubt the goods may still leave: retry the same key, never touch the stock (T16).
      if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
      return settled(c, "shipment_state = 'CANCELLED'");
    }
    if (!stock.equals("RELEASED") && !stock.equals("RESTORED")) {
      if (!intent(c, saga, null)) return false;
      // Always release, even NONE: the tombstone stops a reserve still in flight from holding stock (review I2); inventory
      // also arbitrates a commit still in flight.
      var out = inventory.release(order, reason);
      // NONE + INVALID_INPUT: an unknown SKU (inventory answered OUT_OF_STOCK, rolls the tombstone back) was never held.
      if (stock.equals("NONE") && Outcome.invalid("inventory", "INVALID_INPUT").equals(out.code()))
        return settled(c, "inventory_state = 'RELEASED'");
      if (out.result() == Outcome.Result.REJECTED) return manual(c, saga, "release " + out.code());
      if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
      String held = out.data().path("status").asString("");
      // Committed stock was taken: a release frees nothing, it must be restored (never recorded as RELEASED). A commit we
      // did not know of is recorded first, so the shipment step runs before any restore (T16 holds on every path).
      if (out.data().path("restore_required").asBoolean(false) || held.equals("COMMITTED"))
        return stock.equals("COMMITTED") ? restore(c, order, reason) : settled(c, "inventory_state = 'COMMITTED'");
      // We recorded the commit yet inventory says released: a contradiction for OPS, never trusted silently.
      if (stock.equals("COMMITTED")) return manual(c, saga, "release " + held);
      return settled(c, "inventory_state = 'RELEASED'");
    }
    // PAYMENT_COMPLETED already recorded: the money is in, the order owes a refund (PAY-04/ORD-04).
    if (saga.paymentState().equals("PAID")) return manual(c, saga, "PAID_NEEDS_REFUND");
    if (!saga.paymentState().equals("CLOSED")) {
      if (!intent(c, saga, null)) return false;
      var out = payments.close(order, reason);
      if (out.result() == Outcome.Result.REJECTED) return manual(c, saga, "close " + out.code());
      if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
      String money = out.data().path("status").asString("");
      // Money already collected: closing keeps it, the order owes a refund (PAY-04/ORD-04) - not a plain CANCELLED.
      if (money.equals("COD_COLLECTED") || money.equals("SUCCESS")) return manual(c, saga, "PAID_NEEDS_REFUND");
      // A PAYMENT_COMPLETED recorded meanwhile stays PAID (paid_at kept too).
      return settled(c, "payment_state = case when payment_state = 'PAID' then 'PAID' else 'CLOSED' end");
    }
    sagas.write(c, jdbc -> {
      jdbc.sql("update order_sagas set status = 'DONE', step = 'DONE', retry_count = 0, last_error = null where order_id = :id")
          .param("id", c.orderId()).update();
      transitions.move(c.orderId(), "CANCELLING", "CANCELLED", reason);
    }, true);
    return false;
  }

  /**
   * Committed stock goes back through /returns: every SKU at its full ordered quantity, fixed key and evidence so a replay
   * (after a crash the release replays first) matches. Its own intent: the release intent counted the other call.
   */
  private boolean restore(SagaRepository.Claim c, SagaOrder order, String reason) {
    var saga = sagas.saga(c.orderId());
    if (!intent(c, saga, null)) return false;
    var out = inventory.restore(order, "cancel:" + order.id(), reason, "order-cancel:" + order.id());
    if (out.result() == Outcome.Result.REJECTED) return manual(c, saga, "restore " + out.code());
    if (out.result() != Outcome.Result.OK) return retry(c, saga, out);
    return settled(c, "inventory_state = 'RESTORED'");
  }

  /** One compensation obligation settled: record it and reset the budget for the next one (as forward steps do). */
  private boolean settled(SagaRepository.Claim c, String set) {
    return sagas.write(c, jdbc -> jdbc.sql("update order_sagas set " + set + ", retry_count = 0, last_error = null where order_id = :id")
        .param("id", c.orderId()).update(), false);
  }

  /** WAIT_RETURN (ORD-04 ruling): parked RETURN_PARK, lease released; a non-null shipment state is recorded with it. */
  private boolean park(SagaRepository.Claim c, String shipment) {
    return sagas.write(c, jdbc -> jdbc.sql("""
        update order_sagas set status = 'WAITING', step = 'WAIT_RETURN', next_retry_at = :park, retry_count = 0,
          shipment_state = coalesce(cast(:ship as varchar), shipment_state),
          last_error = coalesce('SHIPMENT_' || cast(:ship as varchar), last_error)
        where order_id = :id""")
        .param("park", Timestamp.from(clock.instant().plus(RETURN_PARK))).param("ship", shipment).param("id", c.orderId()).update(), true);
  }

  /** A business refusal that retrying cannot change: MANUAL now, every obligation kept for OPS. */
  private boolean manual(SagaRepository.Claim c, SagaRepository.Saga saga, String error) { return manual(c, saga.retryCount() + 1, error); }

  private boolean manual(SagaRepository.Claim c, int attempts, String error) {
    String step = sagas.saga(c.orderId()).step();
    if (sagas.write(c, jdbc -> jdbc.sql("""
        update order_sagas set status = 'MANUAL', retry_count = :n, last_error = :e where order_id = :id""")
        .param("n", attempts).param("e", error).param("id", c.orderId()).update(), true)) {
      Counter.builder("order.saga.manual").tag("step", step).description("Sagas moved to MANUAL").register(meters).increment();
      LOG.warn("Saga moved to MANUAL: order_id={} step={} last_error={}", c.orderId(), step, error);
    }
    return false;
  }

  /**
   * Before a forward call: column = UNKNOWN and the attempt counted with its backoff (spec section 3), so a crash before the
   * result is retried later and still ends MANUAL. A result resets retry_count; retry() records the same attempt number.
   * column null (COMMIT_STOCK): only the attempt is counted, the step itself marks the call in flight.
   */
  private boolean intent(SagaRepository.Claim c, SagaRepository.Saga saga, String column) {
    int attempts = saga.retryCount() + 1;
    var next = backoff(attempts);
    return sagas.write(c, jdbc -> {
      if (column != null) state(jdbc, c.orderId(), column, "UNKNOWN");
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
