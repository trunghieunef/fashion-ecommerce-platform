package vn.fashion.catalog.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.fashion.platform.work.BackgroundTaskRepository;

/**
 * CAT-02b spec section 3: every admin change enqueues a task in its own transaction; one INCR of the
 * generation finishes every claimed task. Redis failure keeps the obligation (retry, then MANUAL).
 */
@Component
public class CacheInvalidation {
  static final String KIND = "catalog.cache.invalidate";
  private static final int BATCH = 100, MAX_ATTEMPTS = 10;
  private final BackgroundTaskRepository tasks;
  private final PublicCache cache;
  private final MeterRegistry meters;

  public CacheInvalidation(JdbcClient jdbc, PublicCache cache, MeterRegistry meters) {
    this.tasks = new BackgroundTaskRepository(jdbc); this.cache = cache; this.meters = meters;
  }

  /** Must join the business transaction (the repository rejects a call outside one). */
  public void enqueue() { tasks.enqueue(KIND, UUID.randomUUID().toString(), "{}"); }

  /** Returns how many tasks were finished; 0 when none were due or Redis failed. */
  // ponytail: DONE rows are kept; add a retention purge if background_tasks growth shows up in DB size.
  public int runOnce() {
    var claimed = tasks.claimDue(KIND, BATCH, Duration.ofSeconds(30));
    if (claimed.isEmpty()) return 0;
    try {
      cache.bumpGeneration();
    } catch (RuntimeException e) {
      for (var t : claimed) {
        var retry = Duration.ofSeconds(Math.min(60, 1L << Math.min(t.attempts(), 6)));
        tasks.fail(t.id(), t.leaseToken(), "REDIS_UNAVAILABLE", retry, MAX_ATTEMPTS);
      }
      meters.counter("catalog.cache.invalidation", "outcome", "error").increment();
      return 0;
    }
    int done = 0;
    for (var t : claimed) if (tasks.complete(t.id(), t.leaseToken())) done++;
    meters.counter("catalog.cache.invalidation", "outcome", "done").increment(done);
    return done;
  }

  /** Trigger lives on a conditional bean: @EnableScheduling is app-wide (messaging relay), as in MediaConfig. */
  @Configuration @EnableScheduling @ConditionalOnBooleanProperty(name = "fashion.catalog.cache.invalidation-enabled", matchIfMissing = true)
  static class Worker {
    private final CacheInvalidation invalidation;
    Worker(CacheInvalidation invalidation) { this.invalidation = invalidation; }
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS) void run() { invalidation.runOnce(); }
  }
}
