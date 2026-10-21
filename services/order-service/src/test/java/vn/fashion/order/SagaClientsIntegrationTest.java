package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.order.deps.CartClient;
import vn.fashion.order.deps.InventoryClient;
import vn.fashion.order.deps.Outcome;
import vn.fashion.order.deps.PaymentClient;
import vn.fashion.order.saga.SagaOrder;

/** ORD-02 Task 2: fixed business keys, bodies per 03 section 4, outcome classes OK / REJECTED / UNKNOWN. */
class SagaClientsIntegrationTest extends OrderTestSupport {
  @Autowired InventoryClient inventory;
  @Autowired PaymentClient payments;
  @Autowired vn.fashion.order.deps.ShippingClient shipping;
  @Autowired CartClient carts;
  static final UUID ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  static final SagaOrder ORDER = new SagaOrder(ID, "ORD202610000001", null, "guest:" + "d".repeat(64),
      UUID.fromString("11111111-1111-4111-8111-111111111111"), "COD", 340000, Instant.parse("2026-10-21T03:00:30.123456Z"),
      List.of(new SagaOrder.Item(ITEM1, 1, "TEE-M", 2), new SagaOrder.Item(ITEM2, 3, "tee-l", 1)),
      UUID.fromString("33333333-3333-4333-8333-333333333333"),
      java.util.Map.of("recipient_name", "Synthetic Person", "phone", "0912345678", "province_code", "79", "ward_code", "26734",
          "address_line", "1 Synthetic St"));

  @Test void reserveAndReleaseUseFixedKeysAndTheOriginalSnapshot() throws Exception {
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"ACTIVE\"}")));
    assertThat(inventory.reserve(ORDER).result()).isEqualTo(Outcome.Result.OK);
    var seen = StubServers.INVENTORY.seen.get(0);
    assertThat(seen.path()).isEqualTo("/internal/api/v1/inventory/reserve");
    assertThat(seen.idempotencyKey()).isEqualTo("reserve:" + ID);
    var body = mapper.readTree(seen.body());
    assertThat(body.path("expires_at").asString()).isEqualTo("2026-10-21T03:00:30.123Z");
    assertThat(body.path("items").get(0).path("sku").asString()).isEqualTo("TEE-L");
    assertThat(body.path("request_hash").asString()).hasSize(64);
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"RELEASED\"}")));
    assertThat(inventory.release(ORDER, "OUT_OF_STOCK").result()).isEqualTo(Outcome.Result.OK);
    var release = StubServers.INVENTORY.seen.get(1);
    assertThat(release.idempotencyKey()).isEqualTo("release:" + ID);
    var rb = mapper.readTree(release.body());
    assertThat(rb.path("original_request_hash").asString()).isEqualTo(body.path("request_hash").asString());
    assertThat(rb.path("original_reservation").path("expires_at").asString()).isEqualTo("2026-10-21T03:00:30.123Z");
    assertThat(rb.path("original_reservation").path("order_no").asString()).isEqualTo("ORD202610000001");
    assertThat(rb.path("reason").asString()).isEqualTo("OUT_OF_STOCK");
    // ORD-04: a release that finds the stock committed is OK and says restore_required; the saga restores it via /returns.
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"COMMITTED\",\"restore_required\":true}")));
    var committed = inventory.release(ORDER, "EXPIRED");
    assertThat(committed.result()).isEqualTo(Outcome.Result.OK);
    assertThat(committed.data().path("restore_required").asBoolean()).isTrue();
  }

  @Test void restoreSendsAReturnRequestUnderItsOperationKey() throws Exception {
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"items\":[]}")));
    assertThat(inventory.restore(ORDER, "cancel:" + ID, "OPS_CANCELLED", "order-cancel:" + ID).result()).isEqualTo(Outcome.Result.OK);
    var seen = StubServers.INVENTORY.seen.get(0);
    assertThat(seen.path()).isEqualTo("/internal/api/v1/inventory/returns");
    assertThat(seen.idempotencyKey()).isEqualTo("cancel:" + ID);
    assertThat(mapper.readTree(seen.body())).isEqualTo(mapper.readTree("{\"order_id\":\"" + ID + "\",\"operation_key\":\"cancel:" + ID
        + "\",\"reason\":\"OPS_CANCELLED\",\"evidence_ref\":\"order-cancel:" + ID
        + "\",\"items\":[{\"sku\":\"TEE-L\",\"quantity\":1},{\"sku\":\"TEE-M\",\"quantity\":2}]}"));
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(409, StubServers.error("CONFLICT", "RETURN_EXCEEDS_COMMITTED")));
    var refused = inventory.restore(ORDER, "cancel:" + ID, "OPS_CANCELLED", "order-cancel:" + ID);
    assertThat(refused.result()).isEqualTo(Outcome.Result.REJECTED);
    assertThat(refused.code()).isEqualTo("RETURN_EXCEEDS_COMMITTED");
  }

  @Test void shipmentCancelSendsTheCreateSnapshotAndTheReasonCode() throws Exception {
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"CANCELLED\"}")));
    shipping.create(ORDER);
    assertThat(shipping.cancel(ORDER, "OPS_CANCELLED").result()).isEqualTo(Outcome.Result.OK);
    var create = StubServers.SHIPPING.seen.get(0);
    var cancel = StubServers.SHIPPING.seen.get(1);
    assertThat(cancel.path()).isEqualTo("/internal/api/v1/shipping/shipments/by-order/" + ID + "/cancel");
    assertThat(cancel.idempotencyKey()).isEqualTo("shipment-cancel:" + ID);
    var body = (tools.jackson.databind.node.ObjectNode) mapper.readTree(cancel.body());
    assertThat(body.path("reason").asString()).isEqualTo("OPS_CANCELLED");
    assertThat(body.without("reason")).isEqualTo(mapper.readTree(create.body()));
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(409, StubServers.error("CONFLICT", "SHIPMENT_HANDED_OVER")));
    var handed = shipping.cancel(ORDER, "OPS_CANCELLED");
    assertThat(handed.result()).isEqualTo(Outcome.Result.REJECTED);
    assertThat(handed.code()).isEqualTo("SHIPMENT_HANDED_OVER");
  }

  @Test void businessRefusalsAreRejectedAndTransportFailuresUnknown() {
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(409, StubServers.error("OUT_OF_STOCK", "OUT_OF_STOCK")));
    var out = inventory.reserve(ORDER);
    assertThat(out.result()).isEqualTo(Outcome.Result.REJECTED);
    assertThat(out.code()).isEqualTo("OUT_OF_STOCK");
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(503, StubServers.error("TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE")));
    assertThat(inventory.reserve(ORDER).result()).isEqualTo(Outcome.Result.UNKNOWN);
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"ACTIVE\"}")));
    StubServers.INVENTORY.delay(1500);
    assertThat(inventory.reserve(ORDER).result()).isEqualTo(Outcome.Result.UNKNOWN);
    StubServers.INVENTORY.delay(0);
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(401, StubServers.error("UNAUTHORIZED", "INVALID_SERVICE_TOKEN")));
    assertThat(inventory.reserve(ORDER).result()).isEqualTo(Outcome.Result.UNKNOWN);
  }

  @Test void commitSendsOnlyTheOrderIdUnderAFixedKey() throws Exception {
    // ORD-03: CommitRequest is {order_id} (additionalProperties false); inventory is the arbiter of the deadline.
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"COMMITTED\"}")));
    assertThat(inventory.commit(ORDER).result()).isEqualTo(Outcome.Result.OK);
    var seen = StubServers.INVENTORY.seen.get(0);
    assertThat(seen.path()).isEqualTo("/internal/api/v1/inventory/commit");
    assertThat(seen.idempotencyKey()).isEqualTo("commit:" + ID);
    assertThat(seen.authorization()).startsWith("Bearer ");
    assertThat(mapper.readTree(seen.body())).isEqualTo(mapper.readTree("{\"order_id\":\"" + ID + "\"}"));
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(409, StubServers.error("CONFLICT", "RESERVATION_EXPIRED")));
    var expired = inventory.commit(ORDER);
    assertThat(expired.result()).isEqualTo(Outcome.Result.REJECTED);
    assertThat(expired.code()).isEqualTo("RESERVATION_EXPIRED");
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(503, StubServers.error("TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE")));
    assertThat(inventory.commit(ORDER).result()).isEqualTo(Outcome.Result.UNKNOWN);
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"COMMITTED\"}")));
    StubServers.INVENTORY.delay(1500);
    assertThat(inventory.commit(ORDER).result()).isEqualTo(Outcome.Result.UNKNOWN);
  }

  @Test void paymentCreateAndCloseShareOneSnapshot() throws Exception {
    StubServers.PAYMENT.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"payment_id\":\"55555555-5555-4555-8555-555555555591\",\"status\":\"COD_PENDING\"}")));
    var created = payments.create(ORDER);
    assertThat(created.result()).isEqualTo(Outcome.Result.OK);
    assertThat(created.data().path("payment_id").asString()).isEqualTo("55555555-5555-4555-8555-555555555591");
    var create = StubServers.PAYMENT.seen.get(0);
    assertThat(create.path()).isEqualTo("/internal/api/v1/payments");
    assertThat(create.idempotencyKey()).isEqualTo("payment:" + ID);
    var cb = mapper.readTree(create.body());
    assertThat(cb.path("amount").asLong()).isEqualTo(340000);
    assertThat(cb.path("currency").asString()).isEqualTo("VND");
    assertThat(cb.path("user_id").isNull()).isTrue();
    StubServers.PAYMENT.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"status\":\"FAILED\"}")));
    assertThat(payments.close(ORDER, "OUT_OF_STOCK").result()).isEqualTo(Outcome.Result.OK);
    var close = StubServers.PAYMENT.seen.get(1);
    assertThat(close.path()).isEqualTo("/internal/api/v1/payments/by-order/" + ID + "/close");
    assertThat(close.idempotencyKey()).isEqualTo("close:" + ID);
    var xb = mapper.readTree(close.body());
    assertThat(xb.path("reason").asString()).isEqualTo("OUT_OF_STOCK");
    xb = ((tools.jackson.databind.node.ObjectNode) xb).without("reason");
    assertThat(xb).isEqualTo(cb);
    StubServers.PAYMENT.reply(s -> new StubServers.Reply(409, StubServers.error("CONFLICT", "PAYMENT_CLOSED")));
    assertThat(payments.create(ORDER).code()).isEqualTo("PAYMENT_CLOSED");
  }

  @Test void shipmentCreateSendsTheOrderSnapshotUnderAFixedKey() throws Exception {
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"shipment_id\":\"44444444-4444-4444-8444-444444444444\"}")));
    var out = shipping.create(ORDER);
    assertThat(out.result()).isEqualTo(Outcome.Result.OK);
    assertThat(out.data().path("shipment_id").asString()).isEqualTo("44444444-4444-4444-8444-444444444444");
    var seen = StubServers.SHIPPING.seen.get(0);
    assertThat(seen.path()).isEqualTo("/internal/api/v1/shipping/shipments");
    assertThat(seen.idempotencyKey()).isEqualTo("shipment:" + ID);
    assertThat(seen.authorization()).startsWith("Bearer ");
    assertThat(mapper.readTree(seen.body())).isEqualTo(mapper.readTree("{\"order_id\":\"" + ID + "\",\"order_no\":\"ORD202610000001\","
        + "\"quote_id\":\"33333333-3333-4333-8333-333333333333\",\"cod_amount\":340000,\"to_address\":{\"recipient_name\":\"Synthetic Person\","
        + "\"phone\":\"0912345678\",\"province_code\":\"79\",\"ward_code\":\"26734\",\"address_line\":\"1 Synthetic St\"}}"));
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(409, StubServers.error("CONFLICT", "QUOTE_MISMATCH")));
    var refused = shipping.create(ORDER);
    assertThat(refused.result()).isEqualTo(Outcome.Result.REJECTED);
    assertThat(refused.code()).isEqualTo("QUOTE_MISMATCH");
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(200, StubServers.ok("{}")));
    StubServers.SHIPPING.delay(1500);
    assertThat(shipping.create(ORDER).result()).isEqualTo(Outcome.Result.UNKNOWN);
  }

  @Test void shipmentCodAmountIsZeroForOnlineMethods() throws Exception {
    StubServers.SHIPPING.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"shipment_id\":\"44444444-4444-4444-8444-444444444444\"}")));
    var online = new SagaOrder(ORDER.id(), ORDER.orderNo(), null, ORDER.actorKey(), ORDER.cartId(), "VNPAY", 340000, ORDER.expiresAt(),
        ORDER.items(), ORDER.quoteId(), ORDER.toAddress());
    shipping.create(online);
    assertThat(mapper.readTree(StubServers.SHIPPING.seen.get(0).body()).path("cod_amount").asLong()).isZero();
  }

  @Test void cartCleanupUsesTheOrderIdAsKey() throws Exception {
    StubServers.CART.reply(s -> new StubServers.Reply(200, StubServers.ok("{\"removed\":[],\"retained\":[],\"missing\":[]}")));
    assertThat(carts.cleanup(ORDER).result()).isEqualTo(Outcome.Result.OK);
    var seen = StubServers.CART.seen.get(0);
    assertThat(seen.path()).isEqualTo("/internal/api/v1/carts/" + ORDER.cartId() + "/checkout-cleanup");
    assertThat(seen.idempotencyKey()).isEqualTo(ID.toString());
    assertThat(seen.cartActor()).isEqualTo(ORDER.actorKey());
    var body = mapper.readTree(seen.body());
    assertThat(body.path("order_id").asString()).isEqualTo(ID.toString());
    assertThat(body.path("items").get(1).path("item_version").asLong()).isEqualTo(3);
  }
}
