package vn.fashion.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.fashion.platform.PostgresTestSupport;
import vn.fashion.platform.idempotency.IdempotencyStore.Status;

class IdempotencyStoreTest extends PostgresTestSupport {
  private static final String ACTOR = "user:11111111-1111-4111-8111-111111111111";
  private static final String OP = "checkout";
  private static final String KEY = "checkout-key-1";
  private static final String HASH = "a".repeat(64);
  private static final UUID ORDER_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private final IdempotencyStore store = new IdempotencyStore(jdbc);

  @Test
  void firstRequestStarts() {
    assertThat(store.begin(ACTOR, OP, KEY, HASH).status()).isEqualTo(Status.STARTED);
  }

  @Test
  void retryWhileProcessingIsInProgress() {
    store.begin(ACTOR, OP, KEY, HASH);
    assertThat(store.begin(ACTOR, OP, KEY, HASH).status()).isEqualTo(Status.IN_PROGRESS);
  }

  @Test
  void retryAfterFinishReplaysStoredResponse() {
    store.begin(ACTOR, OP, KEY, HASH);
    assertThat(store.finish(ACTOR, OP, KEY, ORDER_ID, 201, "{\"order_id\":\"" + ORDER_ID + "\"}")).isTrue();

    var replay = store.begin(ACTOR, OP, KEY, HASH);

    assertThat(replay.status()).isEqualTo(Status.COMPLETED);
    assertThat(replay.resourceId()).isEqualTo(ORDER_ID);
    assertThat(replay.responseCode()).isEqualTo(201);
    assertThat(replay.responseBody()).contains(ORDER_ID.toString());
  }

  @Test
  void sameKeyWithDifferentBodyConflicts() {
    store.begin(ACTOR, OP, KEY, HASH);
    store.finish(ACTOR, OP, KEY, ORDER_ID, 201, "{}");

    assertThat(store.begin(ACTOR, OP, KEY, "b".repeat(64)).status()).isEqualTo(Status.CONFLICT);
  }

  @Test
  void keyIsScopedByActorAndOperation() {
    store.begin(ACTOR, OP, KEY, HASH);
    assertThat(store.begin("guest:other", OP, KEY, "b".repeat(64)).status()).isEqualTo(Status.STARTED);
    assertThat(store.begin(ACTOR, "cancel", KEY, "b".repeat(64)).status()).isEqualTo(Status.STARTED);
  }

  @Test
  void finishIsOneShot() {
    store.begin(ACTOR, OP, KEY, HASH);
    assertThat(store.finish(ACTOR, OP, KEY, ORDER_ID, 201, "{}")).isTrue();
    assertThat(store.finish(ACTOR, OP, KEY, UUID.randomUUID(), 201, "{}")).isFalse();
    assertThat(store.begin(ACTOR, OP, KEY, HASH).resourceId()).isEqualTo(ORDER_ID);
  }
}
