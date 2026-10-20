package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.payment.payments.CodEvidence;
import vn.fashion.platform.inbox.InboxGuard;
import vn.fashion.platform.messaging.EventEnvelope;

class CodEvidenceIntegrationTest extends PaymentTestSupport {
  @Autowired CodEvidence evidence;
  @Autowired InboxGuard inbox;

  /** Delivers one shipping event through the inbox, as the listener does. */
  void deliver(String type, String order, String eventRef, long gross) { deliver(type, order, eventRef, gross, Instant.now()); }
  void deliver(String type, String order, String eventRef, long gross, Instant sentAt) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order_id", order);
    payload.put("order_no", "P" + order.substring(0, 8));
    payload.put("payment_id", null);
    payload.put("shipment_id", UUID.randomUUID().toString());
    payload.put("event_ref", eventRef);
    payload.put("gross_amount", gross);
    payload.put("fee_amount", 0);
    payload.put("net_amount", gross);
    payload.put("currency", "VND");
    payload.put("evidence_ref", "BIEN-NHAN-1");
    payload.put("occurred_at", "2026-10-20T05:00:00Z");
    var envelope = new EventEnvelope(UUID.randomUUID(), type, 1, sentAt, UUID.randomUUID().toString(), 1, 1, "corr",
        mapper.valueToTree(payload));
    inbox.applyOnce("payment-service", envelope.eventId(), () -> evidence.apply(envelope));
  }
  String status(String id) { return jdbc.queryForObject("select status from payments where id = cast(? as uuid)", String.class, id); }

  @Test void collectedMovesPendingToCollectedOnceAndRemittedIsOnlyEvidence() {
    String order = UUID.randomUUID().toString(), id = cod(order, 275000), ref = UUID.randomUUID().toString();
    deliver("COD_COLLECTED", order, ref, 275000);
    assertThat(status(id)).isEqualTo("COD_COLLECTED");
    deliver("COD_COLLECTED", order, ref, 275000); // redelivered with another event_id: same event_ref, no effect
    assertThat(count("select count(*) from payment_transactions where source = 'COD' and status = 'COLLECTED'")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select version from payments where id = cast(? as uuid)", Long.class, id)).isEqualTo(1);
    // T27: the carrier remitted, but no money reached the shop yet -> still COD_COLLECTED, never SUCCESS.
    deliver("COD_REMITTED", order, UUID.randomUUID().toString(), 275000);
    assertThat(status(id)).isEqualTo("COD_COLLECTED");
    assertThat(count("select count(*) from payment_transactions where source = 'COD' and status = 'REMITTED'")).isEqualTo(1);
    assertThat(count("select count(*) from outbox_events where event_type = 'PAYMENT_COMPLETED'")).isZero();
  }

  @Test void mismatchesOpenAReconciliationItemAndKeepTheState() {
    String order = UUID.randomUUID().toString(), id = cod(order, 100000);
    deliver("COD_COLLECTED", order, UUID.randomUUID().toString(), 90000);
    assertThat(status(id)).isEqualTo("COD_PENDING");
    Map<String, Object> item = jdbc.queryForMap("select kind, expected_amount, observed_amount, status from reconciliation_items");
    assertThat(item).containsEntry("kind", "COD_AMOUNT_MISMATCH").containsEntry("expected_amount", 100000L)
        .containsEntry("observed_amount", 90000L).containsEntry("status", "OPEN");
    // A non-COD payment (fixture inserted as owner) gets COD evidence: method mismatch, state kept.
    String online = UUID.randomUUID().toString(), onlineId = UUID.randomUUID().toString();
    asOwner("insert into payments(id, order_id, order_no, method, amount, status, provider_request_id, request_hash, snapshot, expires_at)"
        + " values ('" + onlineId + "', '" + online + "', 'VN1', 'VNPAY', 5000, 'CREATED', 'VN1', repeat('a', 64), '{}', now())");
    deliver("COD_COLLECTED", online, UUID.randomUUID().toString(), 5000);
    assertThat(status(onlineId)).isEqualTo("CREATED");
    assertThat(count("select count(*) from reconciliation_items where kind = 'COD_METHOD_MISMATCH'")).isEqualTo(1);
  }

  @Test void lateCollectionOnAClosedPaymentIsRecordedAndKeepsClosedAt() {
    String order = UUID.randomUUID().toString(), body = snapshot(order, "L-1", "COD", 60000);
    String id = data(create("k", body), 200).path("payment_id").asString();
    data(close(order, "c", withReason(body, "khách hủy")), 200);
    deliver("COD_COLLECTED", order, UUID.randomUUID().toString(), 60000);
    assertThat(status(id)).isEqualTo("COD_COLLECTED");
    assertThat(jdbc.queryForObject("select closed_at is not null from payments where id = cast(? as uuid)", Boolean.class, id)).isTrue();
  }

  @Test void evidenceBeforeThePaymentExistsIsRetriedAndWritesNothing() {
    String order = UUID.randomUUID().toString();
    assertThatThrownBy(() -> deliver("COD_COLLECTED", order, UUID.randomUUID().toString(), 1000))
        .isNotInstanceOf(vn.fashion.platform.messaging.PermanentEventException.class);
    assertThat(count("select count(*) from processed_events")).isZero();
    assertThat(count("select count(*) from payment_transactions")).isZero();
  }

  @Test void evidenceStillWithoutAPaymentAfterTheGraceGoesToTheDltInsteadOfBlockingThePartition() {
    // PR review I1: an orphan COD event must not retry forever and stall every later event of its partition.
    assertThatThrownBy(() -> deliver("COD_COLLECTED", UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1000,
        Instant.now().minusSeconds(11 * 60))).isInstanceOf(vn.fashion.platform.messaging.PermanentEventException.class);
    assertThat(count("select count(*) from processed_events")).isZero();
  }
}
