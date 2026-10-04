package vn.fashion.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.fashion.platform.PostgresTestSupport;

class OutboxRepositoryTest extends PostgresTestSupport {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private final OutboxRepository outbox = new OutboxRepository(jdbc);

  @Test
  void appendOutsideBusinessTransactionIsRejected() {
    assertThatThrownBy(() -> outbox.append(event("order-a", 0)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count()).isZero();
  }

  @Test
  void eventsAtTheSameBusinessVersionGetIncreasingSequence() {
    var first = event("order-a", 3);
    var second = event("order-a", 3);
    tx.executeWithoutResult(s -> {
      assertThat(outbox.append(first)).isEqualTo(1);
      assertThat(outbox.append(second)).isEqualTo(2);
    });
    assertThat(append(event("order-b", 3))).isEqualTo(1);
  }

  @Test
  void claimTakesOnlyTheEarliestUnsentEventOfEachAggregate() {
    var a1 = event("order-a", 0);
    var a2 = event("order-a", 0);
    var b1 = event("order-b", 0);
    append(a1);
    append(a2);
    append(b1);

    assertThat(ids(outbox.claim(10, LEASE))).containsExactlyInAnyOrder(a1.eventId(), b1.eventId());
    assertThat(outbox.claim(10, LEASE)).as("leased events are not claimed twice").isEmpty();
  }

  @Test
  void expiredPredecessorIsReclaimedBeforeItsSuccessor() {
    var a1 = event("order-a", 0);
    var a2 = event("order-a", 0);
    append(a1);
    append(a2);
    outbox.claim(10, LEASE);
    expireLeases();

    assertThat(ids(outbox.claim(10, LEASE))).containsExactly(a1.eventId());
  }

  @Test
  void successorIsClaimableOnlyAfterPredecessorIsSent() {
    var a1 = event("order-a", 0);
    var a2 = event("order-a", 0);
    append(a1);
    append(a2);
    var claimed = outbox.claim(10, LEASE).getFirst();

    assertThat(outbox.markSent(claimed.eventId(), claimed.leaseToken())).isTrue();

    var next = outbox.claim(10, LEASE);
    assertThat(ids(next)).containsExactly(a2.eventId());
    assertThat(next.getFirst().aggregateSequence()).isEqualTo(2);
  }

  @Test
  void staleLeaseCannotMarkSentAfterAnotherRelayReclaimed() {
    append(event("order-a", 0));
    var old = outbox.claim(10, LEASE).getFirst();
    expireLeases();
    var current = outbox.claim(10, LEASE).getFirst();

    assertThat(current.eventId()).isEqualTo(old.eventId());
    assertThat(outbox.markSent(old.eventId(), old.leaseToken())).isFalse();
    assertThat(outbox.markSent(current.eventId(), current.leaseToken())).isTrue();
    assertThat(status(old.eventId())).isEqualTo("SENT");
  }

  @Test
  void expiredLeaseCannotMarkSentEvenWithoutCompetitor() {
    append(event("order-a", 0));
    var claimed = outbox.claim(10, LEASE).getFirst();
    expireLeases();

    assertThat(outbox.markSent(claimed.eventId(), claimed.leaseToken())).isFalse();
  }

  @Test
  void envelopeUsesTheWireNamesOf03() {
    var e = event("order-a", 4);
    append(e);
    var message = outbox.claim(10, LEASE).getFirst();

    var keys = jdbc.sql("select jsonb_object_keys(cast(:env as jsonb)) order by 1")
        .param("env", message.envelopeJson()).query(String.class).list();
    assertThat(keys).containsExactly("aggregate_id", "aggregate_sequence", "aggregate_version",
        "correlation_id", "event_id", "event_type", "occurred_at", "payload", "version");
    assertThat(field(message, "event_id")).isEqualTo(e.eventId().toString());
    assertThat(field(message, "version")).isEqualTo("1");
    assertThat(field(message, "aggregate_version")).isEqualTo("4");
    assertThat(field(message, "aggregate_sequence")).isEqualTo("1");
    assertThat(field(message, "occurred_at")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{6}Z");
    assertThat(field(message, "payload")).isEqualTo("{\"order_no\": \"FS-SYNTH-0001\"}");
    assertThat(message.topic()).isEqualTo("order.events");
    assertThat(message.partitionKey()).isEqualTo("FS-SYNTH-0001");
  }

  @Test
  void relayMarksPublishedEventsSentAndKeepsFailuresForRetry() {
    var ok = event("order-a", 0);
    var failing = event("order-b", 0);
    append(ok);
    append(failing);
    var published = new ArrayList<UUID>();

    int sent = outbox.relay(10, LEASE, message -> {
      if (message.eventId().equals(failing.eventId())) {
        throw new IllegalStateException("broker unavailable");
      }
      published.add(message.eventId());
    });

    assertThat(sent).isEqualTo(1);
    assertThat(published).containsExactly(ok.eventId());
    assertThat(status(ok.eventId())).isEqualTo("SENT");
    assertThat(status(failing.eventId())).isEqualTo("IN_FLIGHT");
    assertThat(jdbc.sql("select last_error from outbox_events where id = :id")
        .param("id", failing.eventId()).query(String.class).single()).contains("broker unavailable");
    expireLeases();
    assertThat(ids(outbox.claim(10, LEASE))).containsExactly(failing.eventId());
  }

  @Test
  void relayInsideBusinessTransactionIsRejectedBeforePublishing() {
    var published = new AtomicInteger();
    tx.executeWithoutResult(outer -> {
      outbox.append(event("order-a", 0));
      assertThatThrownBy(() -> outbox.relay(10, LEASE, message -> published.incrementAndGet()))
          .isInstanceOf(IllegalStateException.class);
      outer.setRollbackOnly();
    });

    assertThat(published).hasValue(0);
    assertThat(count()).isZero();
  }

  @Test
  void claimInsideTransactionIsRejected() {
    append(event("order-a", 0));
    tx.executeWithoutResult(outer -> assertThatThrownBy(() -> outbox.claim(10, LEASE))
        .isInstanceOf(IllegalStateException.class));
    assertThat(status(jdbc.sql("select id from outbox_events").query(UUID.class).single())).isEqualTo("PENDING");
  }

  static OutboxEvent event(String aggregateId, long version) {
    return new OutboxEvent(UUID.randomUUID(), "order", aggregateId, version, "ORDER_CREATED", 1,
        "order.events", "FS-SYNTH-0001", "synthetic-correlation", "{\"order_no\":\"FS-SYNTH-0001\"}");
  }

  private long append(OutboxEvent e) {
    return tx.execute(s -> outbox.append(e));
  }

  private void expireLeases() {
    jdbc.sql("update outbox_events set lease_until = now() - interval '1 second' where status = 'IN_FLIGHT'")
        .update();
  }

  private String field(OutboxMessage message, String name) {
    return jdbc.sql("select cast(:env as jsonb) ->> :name").param("env", message.envelopeJson())
        .param("name", name).query(String.class).single();
  }

  private String status(UUID id) {
    return jdbc.sql("select status from outbox_events where id = :id").param("id", id)
        .query(String.class).single();
  }

  private int count() {
    return jdbc.sql("select count(*) from outbox_events").query(Integer.class).single();
  }

  private static List<UUID> ids(List<OutboxMessage> messages) {
    return messages.stream().map(OutboxMessage::eventId).toList();
  }
}
