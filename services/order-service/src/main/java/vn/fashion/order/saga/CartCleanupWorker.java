package vn.fashion.order.saga;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.fashion.order.deps.CartClient;
import vn.fashion.order.deps.Outcome;
import vn.fashion.platform.work.BackgroundTaskRepository;

/**
 * REQ ORD-09: removes the ordered lines from the cart (CART-03, key = order_id), independent of the saga. A cancelled
 * order never empties the cart; a failing cart only retries the task, the order is untouched.
 */
@Component
public class CartCleanupWorker {
  private static final Logger LOG = LoggerFactory.getLogger(CartCleanupWorker.class);
  static final int MAX_ATTEMPTS = 30;
  private final BackgroundTaskRepository tasks;
  private final SagaRepository sagas;
  private final CartClient carts;

  public CartCleanupWorker(BackgroundTaskRepository tasks, SagaRepository sagas, CartClient carts) {
    this.tasks = tasks; this.sagas = sagas; this.carts = carts;
  }

  public int runOnce() {
    var claimed = tasks.claimDue(SagaWorker.CART_CLEANUP, 10, SagaWorker.LEASE);
    for (var task : claimed) {
      try { clean(task); }
      catch (RuntimeException e) {
        // One bad task never drops the batch; it spends its attempts like UNKNOWN and ends MANUAL.
        LOG.error("Cart cleanup failed: task_id={}", task.id(), e);
        try { tasks.fail(task.id(), task.leaseToken(), e.getClass().getSimpleName(), SagaWorker.MAX_BACKOFF, MAX_ATTEMPTS); }
        catch (RuntimeException again) { LOG.error("Cart cleanup failure not recorded: task_id={}", task.id(), again); }
      }
    }
    return claimed.size();
  }

  private void clean(BackgroundTaskRepository.ClaimedTask task) {
    UUID orderId = UUID.fromString(task.businessKey());
    String status = sagas.orderStatus(orderId);
    if (status.equals("CANCELLING") || status.equals("CANCELLED")) { tasks.complete(task.id(), task.leaseToken()); return; }
    var out = carts.cleanup(sagas.order(orderId));
    if (out.result() == Outcome.Result.UNKNOWN) {
      long seconds = Math.min(1L << Math.min(task.attempts() + 1, 30), SagaWorker.MAX_BACKOFF.toSeconds());
      tasks.fail(task.id(), task.leaseToken(), out.code(), Duration.ofSeconds(seconds), MAX_ATTEMPTS);
      return;
    }
    // OK (removed/retained/missing) or a refusal that will not change (cart gone, key reused): nothing left to do.
    if (out.result() == Outcome.Result.REJECTED) LOG.warn("Cart cleanup refused: order_id={} code={}", orderId, out.code());
    tasks.complete(task.id(), task.leaseToken());
  }
}
