package vn.fashion.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import vn.fashion.platform.PostgresTestSupport;

class InboxGuardTest extends PostgresTestSupport {
  private static final UUID EVENT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private final InboxGuard inbox = new InboxGuard(jdbc, tx);

  @Test
  void concurrentDuplicateDeliveryCommitsOneEffect() throws Exception {
    var firstInsideTx = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var first = CompletableFuture.supplyAsync(() -> inbox.applyOnce("test-consumer", EVENT_ID, () -> {
      recordEffect("first");
      firstInsideTx.countDown();
      await(releaseFirst);
      return "first";
    }));
    assertThat(firstInsideTx.await(10, TimeUnit.SECONDS)).isTrue();
    // The duplicate blocks on the uncommitted inbox row, then sees it once the first commits.
    var second = CompletableFuture.supplyAsync(
        () -> inbox.applyOnce("test-consumer", EVENT_ID, () -> recordEffect("second")));
    releaseFirst.countDown();

    assertThat(first.get(10, TimeUnit.SECONDS)).contains("first");
    assertThat(second.get(10, TimeUnit.SECONDS)).isEmpty();
    assertThat(effects()).isEqualTo(1);
  }

  @Test
  void failedEffectRollsBackInboxSoRedeliveryRetries() {
    assertThatThrownBy(() -> inbox.applyOnce("test-consumer", EVENT_ID, () -> {
      recordEffect("partial");
      throw new IllegalStateException("effect failed");
    })).isInstanceOf(IllegalStateException.class);

    Optional<String> retried = inbox.applyOnce("test-consumer", EVENT_ID, () -> recordEffect("retry"));

    assertThat(retried).contains("retry");
    assertThat(effects()).isEqualTo(1);
  }

  @Test
  void eachConsumerAppliesTheSameEventOnce() {
    assertThat(inbox.applyOnce("catalog-projection", EVENT_ID, () -> recordEffect("a"))).isPresent();
    assertThat(inbox.applyOnce("inventory-register", EVENT_ID, () -> recordEffect("b"))).isPresent();
    assertThat(effects()).isEqualTo(2);
  }

  private String recordEffect(String note) {
    jdbc.sql("insert into test_effects(event_id, note) values (:id, :note)")
        .param("id", EVENT_ID).param("note", note).update();
    return note;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
