package vn.fashion.order.saga;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the saga table (ORDER_SAGA_POLL); tests disable it and call SagaWorker.runOnce() themselves. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "fashion.order.saga.enabled", havingValue = "true", matchIfMissing = true)
public class SagaScheduler {
  private final SagaWorker worker;
  public SagaScheduler(SagaWorker worker) { this.worker = worker; }

  @Scheduled(fixedDelayString = "${fashion.order.saga.poll:1s}")
  public void tick() { worker.runOnce(); }
}
