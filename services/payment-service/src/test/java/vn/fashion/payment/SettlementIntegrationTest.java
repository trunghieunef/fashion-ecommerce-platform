package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

class SettlementIntegrationTest extends PaymentTestSupport {
  final String finance = staffToken("payment.read", "payment.settle");

  String collected(String order, long amount) {
    String id = cod(order, amount);
    jdbc.update("update payments set status = 'COD_COLLECTED', version = 1 where id = cast(? as uuid)", id);
    return id;
  }
  HttpResponse<String> settle(String id, String key, String ref, long gross, long fee, long net, long version) {
    return send("POST", "/admin/api/v1/payments/" + id + "/cod-settlements", "{\"settlement_ref\":\"" + ref + "\",\"gross_amount\":" + gross
        + ",\"fee_amount\":" + fee + ",\"net_amount\":" + net + ",\"evidence_ref\":\"SAO-KE-1\",\"expected_version\":" + version + "}",
        "Authorization", finance, "Idempotency-Key", key);
  }

  @Test void twoBatchesCompleteThePaymentOnce() {
    String id = collected(UUID.randomUUID().toString(), 100000);
    var first = data(settle(id, "s1", "BATCH-1", 60000, 3000, 57000, 1), 200);
    assertThat(first.path("status").asString()).isEqualTo("COD_COLLECTED");
    assertThat(first.path("settled_amount").asLong()).isEqualTo(60000);
    assertThat(data(settle(id, "s1", "BATCH-1", 60000, 3000, 57000, 1), 200).path("version").asLong()).isEqualTo(2); // replay
    var done = data(settle(id, "s2", "BATCH-2", 40000, 0, 40000, 2), 200);
    assertThat(done.path("status").asString()).isEqualTo("SUCCESS");
    assertThat(done.path("paid_at").isNull()).isFalse();
    assertThat(count("select count(*) from outbox_events where event_type = 'PAYMENT_COMPLETED'")).isEqualTo(1);
    assertThat(count("select count(*) from cod_settlements")).isEqualTo(2);
    assertThat(count("select count(*) from payment_transactions where source = 'SETTLEMENT'")).isEqualTo(2);
    assertThat(problem(settle(id, "s3", "BATCH-3", 1, 0, 1, 3), 409)).isEqualTo("PAYMENT_NOT_SETTLEABLE");
  }

  @Test void wrongSettlementsAreRefused() {
    String id = collected(UUID.randomUUID().toString(), 100000);
    assertThat(problem(settle(id, "a", "R", 100000, 1000, 98000, 1), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(settle(id, "b", "R", 100001, 0, 100001, 1), 409)).isEqualTo("SETTLEMENT_EXCEEDS_AMOUNT");
    assertThat(problem(settle(id, "c", "R", 1000, 0, 1000, 0), 409)).isEqualTo("VERSION_CONFLICT");
    data(settle(id, "d", "R", 1000, 0, 1000, 1), 200);
    assertThat(problem(settle(id, "e", "R", 2000, 0, 2000, 2), 409)).isEqualTo("SETTLEMENT_REF_REUSED");
    String pending = cod(UUID.randomUUID().toString(), 5000);
    assertThat(problem(settle(pending, "f", "R", 5000, 0, 5000, 0), 409)).isEqualTo("PAYMENT_NOT_SETTLEABLE");
    assertThat(problem(settle(UUID.randomUUID().toString(), "g", "R", 1, 0, 1, 0), 404)).isEqualTo("PAYMENT_NOT_FOUND");
    var readOnly = send("POST", "/admin/api/v1/payments/" + id + "/cod-settlements", "{}", "Authorization", staffToken("payment.read"),
        "Idempotency-Key", "h");
    assertThat(problem(readOnly, 403)).isEqualTo("PERMISSION_REQUIRED");
  }

  @Test void parallelSettlementsNeverExceedTheAmount() {
    String id = collected(UUID.randomUUID().toString(), 80000);
    var barrier = new CyclicBarrier(2);
    var a = CompletableFuture.supplyAsync(() -> { PaymentCreateIntegrationTest.await(barrier); return settle(id, "x", "A", 80000, 0, 80000, 1); });
    var b = CompletableFuture.supplyAsync(() -> { PaymentCreateIntegrationTest.await(barrier); return settle(id, "y", "B", 80000, 0, 80000, 1); });
    var codes = java.util.List.of(a.join().statusCode(), b.join().statusCode());
    assertThat(codes).containsExactlyInAnyOrder(200, 409);
    assertThat(jdbc.queryForObject("select sum(gross_amount) from cod_settlements", Long.class)).isEqualTo(80000);
    assertThat(count("select count(*) from outbox_events where event_type = 'PAYMENT_COMPLETED'")).isEqualTo(1);
  }

  @Test void settlementAfterALateCollectionOnAClosedPaymentStillCompletes() {
    String order = UUID.randomUUID().toString(), body = snapshot(order, "Z-1", "COD", 30000);
    String id = data(create("k", body), 200).path("payment_id").asString();
    data(close(order, "c", withReason(body, "khách hủy")), 200);
    jdbc.update("update payments set status = 'COD_COLLECTED', version = version + 1 where id = cast(? as uuid)", id);
    var done = data(settle(id, "s", "LATE-1", 30000, 0, 30000, 2), 200);
    assertThat(done.path("status").asString()).isEqualTo("SUCCESS");
    assertThat(done.path("closed_at").isNull()).isFalse();
    assertThat(count("select count(*) from outbox_events where event_type = 'PAYMENT_COMPLETED'")).isEqualTo(1);
  }
}
