package vn.fashion.platform.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.fashion.platform.PostgresTestSupport;

class BackgroundTaskRepositoryTest extends PostgresTestSupport {
  private static final String KIND = "CART_CLEANUP";
  private static final Duration LEASE = Duration.ofSeconds(30);
  private final BackgroundTaskRepository tasks = new BackgroundTaskRepository(jdbc);

  @Test
  void enqueueOutsideBusinessTransactionIsRejected() {
    assertThatThrownBy(() -> tasks.enqueue(KIND, "order-1", "{}"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void enqueueIsIdempotentPerKindAndBusinessKey() {
    assertThat(enqueue("order-1")).isTrue();
    assertThat(enqueue("order-1")).isFalse();
    Boolean otherKind = tx.execute(s -> tasks.enqueue("CACHE_INVALIDATION", "order-1", "{}"));
    assertThat(otherKind).isTrue();
    assertThat(jdbc.sql("select count(*) from background_tasks").query(Integer.class).single()).isEqualTo(2);
  }

  @Test
  void claimDueTakesOnlyDueUnleasedTasksOfTheKind() {
    enqueue("due");
    enqueue("later");
    tx.executeWithoutResult(s -> tasks.enqueue("OTHER_KIND", "due", "{}"));
    jdbc.sql("update background_tasks set next_attempt_at = now() + interval '1 hour' where business_key = 'later'")
        .update();

    var claimed = tasks.claimDue(KIND, 10, LEASE);

    assertThat(claimed).extracting(BackgroundTaskRepository.ClaimedTask::businessKey).containsExactly("due");
    assertThat(claimed.getFirst().attempts()).isEqualTo(1);
    assertThat(claimed.getFirst().payloadJson()).isEqualTo("{\"order_id\": \"due\"}");
    assertThat(tasks.claimDue(KIND, 10, LEASE)).as("leased task is not claimed twice").isEmpty();
  }

  @Test
  void runningTaskWithExpiredLeaseIsReclaimed() {
    enqueue("crashed");
    tasks.claimDue(KIND, 10, LEASE);
    expireLeases();

    var reclaimed = tasks.claimDue(KIND, 10, LEASE);

    assertThat(reclaimed).hasSize(1);
    assertThat(reclaimed.getFirst().attempts()).isEqualTo(2);
  }

  @Test
  void staleWorkerCannotCompleteAfterReclaim() {
    enqueue("order-1");
    var old = tasks.claimDue(KIND, 10, LEASE).getFirst();
    expireLeases();
    var current = tasks.claimDue(KIND, 10, LEASE).getFirst();

    assertThat(tasks.complete(old.id(), old.leaseToken())).isFalse();
    assertThat(tasks.complete(current.id(), current.leaseToken())).isTrue();
    assertThat(status()).isEqualTo("DONE");
    assertThat(tasks.claimDue(KIND, 10, LEASE)).isEmpty();
  }

  @Test
  void failureRetriesLaterThenBecomesManualAfterMaxAttempts() {
    enqueue("order-1");
    var first = tasks.claimDue(KIND, 10, LEASE).getFirst();

    assertThat(tasks.fail(first.id(), first.leaseToken(), "cart unavailable", Duration.ofMinutes(1), 2)).isTrue();
    assertThat(status()).isEqualTo("PENDING");
    assertThat(tasks.claimDue(KIND, 10, LEASE)).as("not due before retry delay").isEmpty();

    jdbc.sql("update background_tasks set next_attempt_at = now()").update();
    var second = tasks.claimDue(KIND, 10, LEASE).getFirst();
    assertThat(tasks.fail(second.id(), second.leaseToken(), "cart unavailable", Duration.ofMinutes(1), 2)).isTrue();

    assertThat(status()).isEqualTo("MANUAL");
    assertThat(jdbc.sql("select last_error from background_tasks").query(String.class).single())
        .isEqualTo("cart unavailable");
    jdbc.sql("update background_tasks set next_attempt_at = now()").update();
    assertThat(tasks.claimDue(KIND, 10, LEASE)).as("MANUAL keeps the obligation for an operator").isEmpty();
  }

  @Test
  void claimDueInsideTransactionIsRejected() {
    enqueue("order-1");
    tx.executeWithoutResult(outer -> assertThatThrownBy(() -> tasks.claimDue(KIND, 10, LEASE))
        .isInstanceOf(IllegalStateException.class));
    assertThat(status()).isEqualTo("PENDING");
  }

  private boolean enqueue(String businessKey) {
    return tx.execute(s -> tasks.enqueue(KIND, businessKey, "{\"order_id\":\"" + businessKey + "\"}"));
  }

  private void expireLeases() {
    jdbc.sql("update background_tasks set lease_until = now() - interval '1 second' where status = 'RUNNING'")
        .update();
  }

  private String status() {
    return jdbc.sql("select status from background_tasks where kind = :kind").param("kind", KIND)
        .query(String.class).single();
  }
}
