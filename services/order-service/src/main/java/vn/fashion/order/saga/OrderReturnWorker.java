package vn.fashion.order.saga;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.order.admin.OrderReturns;
import vn.fashion.order.deps.InventoryClient;
import vn.fashion.order.deps.Outcome;
import vn.fashion.platform.work.BackgroundTaskRepository;

/**
 * ORD-04 spec section 5: the ORDER_RETURN task restocks a counted slip once (/returns, key return:{return_id}, only SKUs
 * with restock > 0). Completing the slip on a RETURNING order parked at WAIT_RETURN hands the saga CLOSE_PAYMENT in the same
 * transaction; money and the order status stay with the saga, this task only adds stock.
 */
@Component
public class OrderReturnWorker {
  private static final Logger LOG = LoggerFactory.getLogger(OrderReturnWorker.class);
  static final int MAX_ATTEMPTS = 30;
  private final BackgroundTaskRepository tasks;
  private final SagaRepository sagas;
  private final InventoryClient inventory;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final MeterRegistry meters;

  public OrderReturnWorker(BackgroundTaskRepository tasks, SagaRepository sagas, InventoryClient inventory, JdbcClient jdbc,
                           TransactionTemplate tx, Clock clock, MeterRegistry meters) {
    this.tasks = tasks; this.sagas = sagas; this.inventory = inventory; this.jdbc = jdbc; this.tx = tx; this.clock = clock; this.meters = meters;
  }

  public int runOnce() {
    var claimed = tasks.claimDue(OrderReturns.TASK, 10, SagaWorker.LEASE);
    for (var task : claimed) {
      try { restock(task); }
      catch (RuntimeException e) {
        LOG.error("Return restock failed: task_id={}", task.id(), e);
        try { tasks.fail(task.id(), task.leaseToken(), e.getClass().getSimpleName(), SagaWorker.MAX_BACKOFF, MAX_ATTEMPTS); }
        catch (RuntimeException again) { LOG.error("Return restock failure not recorded: task_id={}", task.id(), again); }
      }
    }
    return claimed.size();
  }

  private record Slip(UUID orderId, String reason, String evidenceRef, String status) { }

  private void restock(BackgroundTaskRepository.ClaimedTask task) {
    UUID returnId = UUID.fromString(task.businessKey());
    var slip = jdbc.sql("select order_id, reason, evidence_ref, status from order_returns where id = :id").param("id", returnId)
        .query((rs, n) -> new Slip(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).single();
    // Read without a lock: only this task (one per slip, held by our lease) moves a slip off REQUESTED, and the final
    // updates re-check status = 'REQUESTED'; a stale read at worst replays the same idempotent restore.
    if (!slip.status().equals("REQUESTED")) { tasks.complete(task.id(), task.leaseToken()); return; }
    var skus = new LinkedHashMap<String, Integer>();
    jdbc.sql("""
        select oi.sku, ri.restock_quantity from order_return_items ri join order_items oi on oi.id = ri.order_item_id
        where ri.return_id = :id and ri.restock_quantity > 0""").param("id", returnId)
        .query((rs, n) -> skus.merge(rs.getString(1), rs.getInt(2), Integer::sum)).list();
    // Nothing resellable (all damaged): the count is done without a stock call.
    if (!skus.isEmpty()) {
      var out = inventory.restore(sagas.order(slip.orderId()), "return:" + returnId, slip.reason(), slip.evidenceRef(), skus);
      if (out.result() == Outcome.Result.UNKNOWN && task.attempts() < MAX_ATTEMPTS) {
        long seconds = Math.min(1L << Math.min(task.attempts() + 1, 30), SagaWorker.MAX_BACKOFF.toSeconds());
        tasks.fail(task.id(), task.leaseToken(), out.code(), Duration.ofSeconds(seconds), MAX_ATTEMPTS);
        return;
      }
      if (out.result() != Outcome.Result.OK) {
        // RETURN_EXCEEDS_COMMITTED / RESERVATION_NOT_COMMITTED or an exhausted budget: OPS decides, nothing is retried.
        LOG.warn("Return restock needs OPS: return_id={} order_id={} code={}", returnId, slip.orderId(), out.code());
        tx.executeWithoutResult(s -> {
          if (!tasks.fail(task.id(), task.leaseToken(), out.result() + " " + out.code(), Duration.ZERO, 0)) throw new IllegalStateException("lease lost");
          jdbc.sql("update order_returns set status = 'MANUAL', updated_at = now() where id = :id and status = 'REQUESTED'")
              .param("id", returnId).update();
        });
        // No API exit for a MANUAL slip on a RETURNING order (it still counts in the received sum): visible for OPS runbook.
        Counter.builder("order.return.manual").description("Return slips moved to MANUAL").register(meters).increment();
        return;
      }
    }
    tx.executeWithoutResult(s -> {
      // Lock order of the service: order_sagas, then orders.
      jdbc.sql("select 1 from order_sagas where order_id = :id for update").param("id", slip.orderId()).query(Integer.class).list();
      String status = jdbc.sql("select status from orders where id = :id for update").param("id", slip.orderId()).query(String.class).single();
      // A lost lease rolls the slip back; the next claim replays the same key (inventory adds nothing twice).
      if (!tasks.complete(task.id(), task.leaseToken())) throw new IllegalStateException("lease lost");
      jdbc.sql("update order_returns set status = 'COMPLETED', updated_at = now() where id = :id and status = 'REQUESTED'")
          .param("id", returnId).update();
      // The first counted slip of a returned shipment lets the saga close the payment (RETURNED, DELIVERED: stock only).
      if (status.equals("RETURNING"))
        jdbc.sql("""
            update order_sagas set step = 'CLOSE_PAYMENT', status = 'RUNNING', next_retry_at = :now, retry_count = 0, last_error = null,
              lease_token = null, lease_until = null, version = version + 1, updated_at = now()
            where order_id = :id and step = 'WAIT_RETURN'""")
            .param("now", Timestamp.from(clock.instant())).param("id", slip.orderId()).update();
    });
  }
}
