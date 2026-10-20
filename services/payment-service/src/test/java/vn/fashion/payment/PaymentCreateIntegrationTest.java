package vn.fashion.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

class PaymentCreateIntegrationTest extends PaymentTestSupport {
  @Test void codCreatesOnePendingPaymentWithEvidenceAndEvent() {
    String order = UUID.randomUUID().toString();
    var created = data(create("k1", snapshot(order, "ORD-1", "COD", 275000)), 200);
    assertThat(created.path("status").asString()).isEqualTo("COD_PENDING");
    assertThat(created.path("method").asString()).isEqualTo("COD");
    assertThat(created.path("amount").asLong()).isEqualTo(275000);
    assertThat(created.path("closed_at").isNull()).isTrue();
    String id = created.path("payment_id").asString();
    assertThat(jdbc.queryForObject("select provider_request_id from payments where id = cast(? as uuid)", String.class, id)).isEqualTo("ORD-1");
    assertThat(count("select count(*) from payment_transactions where payment_id = cast(? as uuid) and source = 'CREATE'", id)).isEqualTo(1);
    var outbox = jdbc.queryForMap("select event_type, topic, partition_key, aggregate_id, payload::text as payload from outbox_events");
    assertThat(outbox).containsEntry("event_type", "PAYMENT_CREATED").containsEntry("topic", "payment.events")
        .containsEntry("partition_key", "ORD-1").containsEntry("aggregate_id", id);
    var payload = mapper.readTree((String) outbox.get("payload"));
    assertThat(payload.path("status").asString()).isEqualTo("COD_PENDING");
    assertThat(payload.path("reason").isNull()).isTrue();
    // Read back by order (order-service), no address or user data.
    var read = data(send("GET", "/internal/api/v1/payments/by-order/" + order, null, "Authorization", serviceToken("order-service")), 200);
    assertThat(read.path("payment_id").asString()).isEqualTo(id);
    assertThat(read.path("version").asLong()).isEqualTo(0);
  }

  @Test void retriesReturnTheSamePaymentAndAnotherBodyIsAConflict() {
    String order = UUID.randomUUID().toString(), body = snapshot(order, "ORD-2", "COD", 100000);
    String id = data(create("k1", body), 200).path("payment_id").asString();
    assertThat(data(create("k1", body), 200).path("payment_id").asString()).isEqualTo(id);
    assertThat(data(create("k2", body), 200).path("payment_id").asString()).isEqualTo(id);
    assertThat(problem(create("k3", snapshot(order, "ORD-2", "COD", 100001)), 409)).isEqualTo("PAYMENT_CONFLICT");
    assertThat(problem(create("k1", snapshot(order, "ORD-2", "COD", 100001)), 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(count("select count(*) from outbox_events")).isEqualTo(1);
    assertThat(count("select count(*) from payment_transactions")).isEqualTo(1);
  }

  @Test void parallelCreatesOfOneOrderMakeOnePayment() {
    String order = UUID.randomUUID().toString(), body = snapshot(order, "ORD-3", "COD", 50000);
    var barrier = new CyclicBarrier(8);
    var calls = new ArrayList<CompletableFuture<String>>();
    for (int i = 0; i < 8; i++) {
      String key = "p" + i;
      calls.add(CompletableFuture.supplyAsync(() -> { await(barrier); return data(create(key, body), 200).path("payment_id").asString(); }));
    }
    assertThat(calls.stream().map(CompletableFuture::join).distinct()).hasSize(1);
    assertThat(count("select count(*) from payments")).isEqualTo(1);
    assertThat(count("select count(*) from outbox_events")).isEqualTo(1);
  }

  @Test void invalidInputsAreRefusedBeforeAnyWrite() {
    String order = UUID.randomUUID().toString();
    var zero = create("a", snapshot(order, "ORD-4", "COD", 0));
    assertThat(problem(zero, 422)).isEqualTo("PAYMENT_AMOUNT_UNSUPPORTED");
    assertThat(json(zero).path("code").asString()).isEqualTo("PAYMENT_AMOUNT_UNSUPPORTED"); // 03 section 1 error code
    assertThat(problem(create("b", snapshot(order, "ORD-4", "VNPAY", 1000)), 422)).isEqualTo("PAYMENT_METHOD_UNAVAILABLE");
    assertThat(problem(create("c", snapshot(order, "ORD-4", "COD", 1000).replace("\"VND\"", "\"USD\"")), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(create("d", snapshot(order, "ORD-4", "CARD", 1000)), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(create("e", snapshot(order, "ORD-4", "COD", 1000).replace("}", ",\"payment_url\":\"x\"}")), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(create("f", "{\"order_id\":\"" + order + "\"}"), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(problem(create("g", snapshot(order, "ORD-4", "COD", 1_000_000_000_001L)), 400)).isEqualTo("INVALID_FIELDS");
    assertThat(count("select count(*) from payments")).isZero();
  }

  @Test void onlyOrderServiceMayCallBeforeTheBodyIsRead() {
    var other = send("POST", "/internal/api/v1/payments", "not json", "Authorization", serviceToken("cart-service"), "Idempotency-Key", "x");
    assertThat(problem(other, 403)).isEqualTo("CALLER_NOT_ALLOWED");
    assertThat(problem(send("POST", "/internal/api/v1/payments", "not json"), 401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("GET", "/internal/api/v1/payments/by-order/" + UUID.randomUUID(), null,
        "Authorization", serviceToken("order-service")), 404)).isEqualTo("PAYMENT_NOT_FOUND");
    assertThat(problem(send("POST", "/internal/api/v1/payments", snapshot(UUID.randomUUID().toString(), "ORD-5", "COD", 1),
        "Authorization", serviceToken("order-service")), 400)).isEqualTo("INVALID_KEY");
  }

  static void await(CyclicBarrier barrier) {
    try { barrier.await(); } catch (Exception e) { throw new AssertionError(e); }
  }
}
