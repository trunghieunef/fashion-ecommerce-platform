package vn.fashion.order.saga;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the saga table, the cart cleanup and the return slip tasks (ORDER_SAGA_POLL); tests disable it and call runOnce() themselves. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "fashion.order.saga.enabled", havingValue = "true", matchIfMissing = true)
public class SagaScheduler {
  private final SagaWorker worker;
  private final CartCleanupWorker cleanup;
  private final OrderReturnWorker returns;
  public SagaScheduler(SagaWorker worker, CartCleanupWorker cleanup, OrderReturnWorker returns) {
    this.worker = worker; this.cleanup = cleanup; this.returns = returns;
  }

  @Scheduled(fixedDelayString = "${fashion.order.saga.poll:1s}")
  public void tick() { worker.runOnce(); cleanup.runOnce(); returns.runOnce(); }
}
