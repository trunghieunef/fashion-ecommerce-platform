# Phase 1B COD Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hoàn thành G1: guest/member xác nhận server quote → COD order → OPS confirm/pack/SELF handover/deliver → FINANCE settlement, có expiry/cancel/recovery/notification và UI thật.

**Architecture:** Order service is the only checkout orchestrator and persists intent/steps before remote calls. Shipping owns SELF quote/shipment/evidence; Payment owns COD obligation and gross/fee/net settlement; Inventory owns stock lifecycle. Each side effect is idempotent, event-driven through outbox/inbox, and recoverable by leased workers.

**Tech Stack:** Existing G0/commerce-core stack; PostgreSQL service databases, Kafka outbox/inbox, Spring MVC/JdbcClient, React/Vite storefront/admin, local mail sink for transactional email, Testcontainers and Playwright.

**Spec:** `docs/delivery/10_backlog.md` SHP-01/02, PAY-01, ORD-01–04 COD scope, NOT-01, WEB-03/04 COD scope, ADM-02 COD scope, QA-02 G1 scope; canonical states in `docs/design/06_service_flows.md`.

## Global Constraints

- Begin only after Phase 1A integration checkpoint. O04 must define SELF area/fee/COD fee/settlement evidence; O05 must define cancel/return evidence and zero-total behavior; O07 must define email sink/provider choice for G1.
- Order is the only checkout orchestrator. REST command and event handler must not independently trigger the same effect.
- Quote expires after 2 minutes; COD reservation expires after 24 hours. Idempotency lookup happens before rejecting an expired quote.
- No network call occurs while a database transaction/row lock is held. Persist intent/next step first, then worker calls another service.
- `SHIPPING` requires `handed_over_at`; `DELIVERED`, `collected` and `settled` are independent evidence points.
- Cancellation remains `CANCELLING` until stock/payment/shipment obligations finish; do not directly set status through admin UI.
- Email acceptance is not inbox delivery. Recipient/template snapshot and send result are durable and deduplicated.
- G1 does not include VNPay/MoMo, refund or full G2 QA. Commit requires explicit user permission.

## Review Focus

- A lost create-order response retried with the same key after quote expiry must return the existing order; a changed body must return 409. Task 3 tests T03/U26.
- Expiry and OPS COD confirmation racing at the deadline must produce exactly one legal outcome and one stock effect. Task 4 tests T12.
- Cancel-before-create tombstones must stop late reservation/payment/shipment creation. Tasks 1–4 test T04 and close-before-create.
- Shipment `CREATED` must not display `SHIPPING`; only durable handover evidence can advance it. Tasks 1/6 test T15.
- Duplicate COD statement lines or a fee/net mismatch must create one discrepancy, never force payment success. Tasks 2/7 test T27/U17.

---

## File Map

| Path | Responsibility |
|---|---|
| `services/shipping-service/` | SELF quote, shipment lifecycle, handover/delivery/return and COD evidence |
| `services/payment-service/` | COD obligation, close/query, statement import and settlement/discrepancy |
| `services/order-service/` | Quote, immutable snapshots, durable saga, expiry/cancel and allowed actions |
| `services/notification-service/` | Transactional email snapshot, lease, dedupe and result |
| `web/storefront/src/checkout|orders/` | SF-06–08 COD flow and interruption recovery |
| `web/admin/src/orders|shipping|settlement/` | AD-03/04/06 scoped operations |
| `tests/e2e/g1-cod.spec.ts`, `tests/integration/g1/` | Full path, race, crash and invariant evidence |

### Task 1: Implement SELF shipping and evidence (`TASK:SHP-01`, `TASK:SHP-02`; `REQ:SHP-01/02/04/06`)

**Files:**
- Create: `services/shipping-service/` application, migration, README and tests
- Create: `contracts/openapi/shipping.yaml`, `contracts/events/shipping-events.schema.json`
- Modify: `docs/design/03_interfaces.md`, `docs/design/05_database_design.md`, `docs/design/06_service_flows.md`

**Interfaces:**
- Consumes: normalized address/weight/COD flag, stable `shipment_id`, order close/cancel and OPS evidence commands.
- Produces: `quote`, `create`, `query`, `cancel`, `handover`, `deliver`, `recordCodCollection`, `recordCodRemittance`; shipping events with evidence IDs.

- [ ] **Step 1: Write state/tombstone/evidence tests**

```java
@Test void createdShipmentIsNotShippingUntilHandoverEvidence() {
  var shipment = createSelfShipment(ORDER_ID);
  assertThat(shipment.status()).isEqualTo("CREATED");
  handover(shipment.id(), evidence("handover-1"));
  assertThat(query(shipment.id()).status()).isEqualTo("SHIPPING");
}

@Test void cancelBeforeCreateLeavesTombstone() {
  cancel(SHIPMENT_ID, "order-cancel-1");
  create(SHIPMENT_ID, ORDER_ID).expectClosed();
  assertThat(shipmentEffects(SHIPMENT_ID)).isZero();
}

@Test void duplicateDeliveryEvidenceHasOneEvent() {
  deliver(SHIPMENT_ID, evidence("delivery-1")); deliver(SHIPMENT_ID, evidence("delivery-1"));
  assertThat(events("SHIPMENT_DELIVERED", SHIPMENT_ID)).hasSize(1);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/shipping-service test`

Expected: FAIL before module/schema exists.

- [ ] **Step 3: Implement quote expiry and explicit transitions**

```java
enum ShipmentStatus { CLOSED, CREATED, PACKING, SHIPPING, DELIVERED, FAILED, RETURNING, RETURNED, MANUAL }

@Transactional
Shipment handover(UUID id, Evidence evidence, long expectedVersion) {
  var shipment = repository.lock(id, expectedVersion);
  shipment.requireStatus(CREATED, PACKING);
  repository.recordEvidenceOnce(id, "HANDOVER", evidence);
  return repository.transition(id, expectedVersion, SHIPPING, "handed_over_at");
}
```

SELF quote includes integer shipping fee/COD fee, area/rule version and expiry. A create timeout/unknown is queried by stable shipment ID; no replacement ID is generated.

- [ ] **Step 4: Run T15–T17/U11 shipping subset**

Run: `./mvnw -pl services/shipping-service test && bash scripts/validate-contracts.sh`

Expected: transition guards, duplicate evidence, tombstone and collection/remittance separation PASS.

- [ ] **Step 5: Sync docs and checkpoint**

If commit permission exists:

```bash
git add services/shipping-service contracts docs/design/03_interfaces.md docs/design/05_database_design.md docs/design/06_service_flows.md
git commit -m "feat(SHP-01): add evidence-driven SELF shipping"
```

### Task 2: Implement COD payment obligation and settlement (`TASK:PAY-01`; `REQ:PAY-03/07/08`)

**Files:**
- Create: `services/payment-service/src/main/java/vn/fashion/payment/cod/`, `services/payment-service/src/main/resources/db/migration/V001__payments_and_cod.sql`, `services/payment-service/README.md`
- Create: `services/payment-service/src/test/java/vn/fashion/payment/cod/CodSettlementIntegrationTest.java`, `services/payment-service/src/test/java/vn/fashion/payment/PaymentCloseIntegrationTest.java`
- Create: `contracts/openapi/payment.yaml`, `contracts/events/payment-events.schema.json`
- Modify: `docs/design/05_database_design.md`, `docs/design/06_service_flows.md`

**Interfaces:**
- Consumes: stable payment/order reference, authoritative order total/currency, close intent, shipping collection/remittance and FINANCE statement line.
- Produces: COD create/query/close, gross/fee/net obligation, settlement/discrepancy and `PAYMENT_STATUS_CHANGED`.

- [ ] **Step 1: Write close-before-create and settlement tests**

```java
@Test void closeBeforeCreatePreventsLatePaymentCreation() {
  close(PAYMENT_ID, "order-cancel-1");
  createCod(PAYMENT_ID, ORDER_ID, 500_000).expectClosed();
  assertThat(paymentRows(PAYMENT_ID)).hasSize(1);
}

@Test void grossFeeNetMustBalanceBeforeSuccess() {
  createCod(PAYMENT_ID, ORDER_ID, 500_000);
  settle(PAYMENT_ID, "stmt-1", 500_000, 20_000, 470_000);
  assertThat(query(PAYMENT_ID).status()).isEqualTo("DISCREPANCY");
}

@Test void replayedStatementDoesNotSettleTwice() {
  settle(PAYMENT_ID, "stmt-2", 500_000, 20_000, 480_000);
  settle(PAYMENT_ID, "stmt-2", 500_000, 20_000, 480_000);
  assertThat(settlementRows("stmt-2")).hasSize(1);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/payment-service -Dtest='CodSettlementIntegrationTest,PaymentCloseIntegrationTest' test`

Expected: FAIL before service/schema exists.

- [ ] **Step 3: Add ledger/counter constraints**

```sql
ALTER TABLE payments ADD CONSTRAINT payment_amount_nonnegative CHECK (amount_vnd >= 0);
CREATE TABLE cod_settlements (
  id uuid PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payments(id), statement_ref varchar(128) NOT NULL,
  gross_vnd bigint NOT NULL CHECK (gross_vnd >= 0), fee_vnd bigint NOT NULL CHECK (fee_vnd >= 0),
  net_vnd bigint NOT NULL CHECK (net_vnd >= 0), status varchar(24) NOT NULL,
  evidence jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(statement_ref,payment_id)
);
```

- [ ] **Step 4: Implement evidence-based reconciliation**

```java
SettlementResult reconcile(long obligation, long gross, long fee, long net) {
  if (Math.subtractExact(gross, fee) != net || gross != obligation) return SettlementResult.discrepancy();
  return SettlementResult.matched();
}
```

Late verified money after close is recorded; it never disappears or reopens an order automatically.

- [ ] **Step 5: Run T27/U17 and checkpoint**

Run: `./mvnw -pl services/payment-service test`

Expected: close, duplicate, overflow, balance and discrepancy cases PASS. Update contract/schema/README; commit if authorized:

```bash
git add services/payment-service contracts docs/design/05_database_design.md docs/design/06_service_flows.md
git commit -m "feat(PAY-01): add COD obligation and settlement"
```

### Task 3: Build quote, order snapshot and durable saga (`TASK:ORD-01`, `TASK:ORD-02`; `REQ:ORD-01/02/05/07/09`)

**Files:**
- Create: `services/order-service/src/main/java/vn/fashion/order/quote/`, `services/order-service/src/main/java/vn/fashion/order/saga/`, `services/order-service/src/main/resources/db/migration/V001__orders_and_saga.sql`, `services/order-service/README.md`
- Create: `services/order-service/src/test/java/vn/fashion/order/OrderIdempotencyIntegrationTest.java`, `services/order-service/src/test/java/vn/fashion/order/saga/OrderSagaRecoveryIntegrationTest.java`
- Create: `contracts/openapi/order.yaml`, `contracts/events/order-events.schema.json`
- Modify: `docs/design/03_interfaces.md`, `docs/design/05_database_design.md`, `docs/design/06_service_flows.md`

**Interfaces:**
- Consumes: cart/version, contact/address, method `COD`, catalog/shipping quote reads, inventory/payment/shipping internal commands.
- Produces: quote token, create `201|202`, status/read/history/allowed actions, immutable item/address/price snapshots and leased saga steps.

- [ ] **Step 1: Write idempotency/price/ownership/crash tests**

```java
@Test void sameKeyReturnsExistingOrderEvenAfterQuoteExpires() {
  var created = createOrder("key-1", quoteToken());
  clock.advance(Duration.ofMinutes(3));
  assertThat(createOrder("key-1", sameBody()).orderId()).isEqualTo(created.orderId());
}

@Test void sameKeyDifferentBodyConflicts() {
  createOrder("key-2", bodyWithAddress("A"));
  createOrder("key-2", bodyWithAddress("B")).expectStatus().isEqualTo(409);
}

@Test void crashAfterInventoryCommitIsRecoveredWithoutSecondCommit() {
  saga.failAfter("INVENTORY_COMMIT"); worker.runOnce(); worker.runOnce();
  assertThat(inventoryCommitEffects(ORDER_ID)).isEqualTo(1);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/order-service test`

Expected: FAIL before order/saga implementation exists.

- [ ] **Step 3: Implement checked quote totals and signed token**

```java
long total(long subtotal, long shipping, long codFee) {
  long result = Math.addExact(Math.addExact(subtotal, shipping), codFee);
  if (result <= 0) throw new InvalidTotal();
  return result;
}
```

Quote stores server snapshots/hash/rule versions and expires at exactly `created_at + 2 minutes`. Create transaction first looks up `(actor,operation,key)`; new requests then verify body hash, quote/cart/version and persist PENDING order + saga work.

- [ ] **Step 4: Implement worker boundaries**

```java
SagaWork claim = work.claimNext(clock.instant(), leaseDuration);
RemoteResult result = clients.execute(claim.command()); // outside DB transaction
work.applyResult(claim.id(), claim.leaseToken(), result); // CAS in short transaction
```

Every remote command uses stable resource/operation IDs. `202` includes `order_no`, `status_url` and `Retry-After`; no worker is fire-and-forget.

- [ ] **Step 5: Run T03/T07/T09/T21/T23/T24/U26/U27**

Run: `./mvnw -pl services/order-service,tests/integration/platform test`

Expected: idempotency lookup, body mismatch, ownership, price change, crash/Kafka outage, aggregate sequence and lease tests PASS.

- [ ] **Step 6: Sync docs and checkpoint**

If commit permission exists:

```bash
git add services/order-service contracts docs/design/03_interfaces.md docs/design/05_database_design.md docs/design/06_service_flows.md
git commit -m "feat(ORD-01): add durable COD order orchestration"
```

### Task 4: Complete COD fulfillment, expiry and cancellation (`TASK:ORD-03`, COD subset `TASK:ORD-04`; `REQ:ORD-03/04/06/10`)

**Files:**
- Create: `services/order-service/src/main/java/vn/fashion/order/fulfillment/CodFulfillmentHandler.java`, `services/order-service/src/main/java/vn/fashion/order/cancel/OrderCancellationHandler.java`, `services/order-service/src/test/java/vn/fashion/order/fulfillment/CodDeadlineRaceIntegrationTest.java`
- Modify: `services/order-service/src/main/java/vn/fashion/order/event/OrderEventConsumer.java`, `services/payment-service/src/main/java/vn/fashion/payment/event/PaymentEventConsumer.java`, `services/shipping-service/src/main/java/vn/fashion/shipping/event/ShippingEventConsumer.java`, `services/inventory-service/src/main/java/vn/fashion/inventory/event/InventoryEventConsumer.java`
- Modify: `contracts/events/order-events.schema.json`, `docs/design/06_service_flows.md`

**Interfaces:**
- Consumes: OPS confirm/pack, payment/shipment/inventory events, cancel request/reason and deadlines.
- Produces: CONFIRMED/PAID/PACKING/SHIPPING/DELIVERED/CANCELLING/CANCELLED transitions and stable compensation commands.

- [ ] **Step 1: Write deadline/cancel/out-of-order tests**

```java
@Test void confirmationAndExpiryHaveOneWinner() {
  seedConfirmedCandidateAtDeadline();
  concurrently(() -> confirmCod(ORDER_ID), () -> expireOrder(ORDER_ID));
  assertThat(orderStatus(ORDER_ID)).isIn("CONFIRMED", "CANCELLED");
  assertThat(activeOrCommittedReservationCount(ORDER_ID)).isLessThanOrEqualTo(1);
}

@Test void cancelStaysCancellingUntilReleaseCompletes() {
  requestCancel(ORDER_ID); assertStatus(ORDER_ID, "CANCELLING");
  consumeInventoryReleased(ORDER_ID); assertStatus(ORDER_ID, "CANCELLED");
}

@Test void staleFailureCannotLowerDeliveredOrder() {
  markDelivered(ORDER_ID); consumeOldShipmentFailedEvent(ORDER_ID);
  assertStatus(ORDER_ID, "DELIVERED");
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/order-service -Dtest=CodDeadlineRaceIntegrationTest test`

Expected: FAIL until guarded transition handlers exist.

- [ ] **Step 3: Implement edge-based state guards**

```java
boolean advance(UUID orderId, long expectedVersion, OrderStatus from, OrderStatus to, String evidenceId) {
  return orders.transitionOnce(orderId, expectedVersion, from, to, evidenceId) == 1;
}
```

Do not compare enum ranks. Confirmation locks the same order/deadline row as expiry. Cancellation writes intents for inventory release/payment close/shipment cancel and waits for required evidence.

- [ ] **Step 4: Run T04/T12/T15–T17/U11/U13**

Run: `./mvnw -pl services/order-service,services/inventory-service,services/payment-service,services/shipping-service test`

Expected: all COD deadline, tombstone, fulfillment and cancellation cases PASS with invariant queries.

- [ ] **Step 5: Sync state machine and checkpoint**

If commit permission exists:

```bash
git add services/order-service services/inventory-service services/payment-service services/shipping-service contracts/events docs/design/06_service_flows.md
git commit -m "feat(ORD-03): complete COD fulfillment and cancellation"
```

### Task 5: Add transactional notifications (`TASK:NOT-01`; `REQ:NOT-01/05/06`)

**Files:**
- Create: `services/notification-service/`, `services/notification-service/src/main/resources/db/migration/V001__notifications.sql`, `services/notification-service/src/main/resources/templates/vi/`, `services/notification-service/src/main/resources/templates/en/`, `services/notification-service/README.md`
- Create: `services/notification-service/src/test/java/vn/fashion/notification/NotificationLeaseIntegrationTest.java`, `services/notification-service/src/test/java/vn/fashion/notification/SecretExpiryIntegrationTest.java`
- Create: `contracts/events/notification-events.schema.json`
- Modify: `docs/engineering/15_integrations.md`, `docs/operations/13_operations_security.md`

**Interfaces:**
- Consumes: only `notification.events`, recipient/template/locale/data snapshot or scoped expiring reset-secret handle.
- Produces: durable send result (`ACCEPTED|FAILED|UNKNOWN|SUPPRESSED`), deduped by notification key; local mail-sink adapter for G1.

- [ ] **Step 1: Write crash/lease/secret tests**

```java
@Test void crashAfterProviderAcceptDoesNotBlindlySendAgain() {
  provider.acceptThenTimeout(); worker.runOnce(); worker.runOnce();
  assertThat(provider.acceptedMessages(NOTIFICATION_ID)).hasSize(1);
  assertThat(notificationStatus()).isIn("ACCEPTED", "UNKNOWN");
}

@Test void staleLeaseCannotOverwriteNewWorkerResult() {
  var old = claimAndExpire(); var current = reclaim();
  assertThat(complete(old, FAILED)).isFalse(); assertThat(complete(current, ACCEPTED)).isTrue();
}

@Test void expiredResetSecretIsNotRenderedOrSent() {
  clock.advanceBeyondSecretExpiry(); worker.runOnce();
  assertThat(provider.requests()).isEmpty();
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/notification-service test`

Expected: FAIL before module exists.

- [ ] **Step 3: Implement snapshot/dedupe/lease sender**

Template version, locale and rendered non-secret content are snapshotted. A secret is resolved just before send through a scoped one-time handle and is never stored in event/log/rendered history. Timeout becomes `UNKNOWN`; query if adapter supports it, otherwise manual state.

- [ ] **Step 4: Run T22/T23/U20 and redaction tests**

Run: `./mvnw -pl services/notification-service test && bash scripts/scan-secrets.sh`

Expected: duplicate, crash, lease, expired secret, template-version and PII-log cases PASS.

- [ ] **Step 5: Sync integration/runbook and checkpoint**

If commit permission exists:

```bash
git add services/notification-service contracts/events docs/engineering/15_integrations.md docs/operations/13_operations_security.md
git commit -m "feat(NOT-01): add durable transactional notifications"
```

### Task 6: Integrate COD storefront and admin (`TASK:WEB-03`, COD subset `TASK:WEB-04`, COD subset `TASK:ADM-02`)

**Files:**
- Create/modify: `web/storefront/src/checkout/`, `web/storefront/src/orders/`
- Create/modify: `web/admin/src/orders/`, `web/admin/src/shipping/`, `web/admin/src/settlement/`
- Create: `tests/e2e/g1-cod.spec.ts`, `tests/e2e/g1-cod-interruption.spec.ts`
- Modify: `docs/design/14_frontend_behavior.md`

**Interfaces:**
- Consumes: quote/create/read/allowed-actions, SELF evidence and COD settlement APIs.
- Produces: SF-06–08 and AD-03/04/06 with same-key retry, 202 polling and permission/version handling.

- [ ] **Step 1: Write interruption and truthful-status browser tests**

```ts
test('lost submit response retries the same order key', async ({page}) => {
  await interceptFirstCreateResponseAsLost(page);
  await submitCodCheckout(page);
  await page.getByRole('button', {name:'Thử lại'}).click();
  await expect(page.getByTestId('order-number')).toHaveText(/^ORD-/);
  expect(await createdOrderCount()).toBe(1);
});

test('created shipment is not shown as shipping', async ({page}) => {
  await seedOrderWithShipment('CREATED'); await page.goto('/account/orders/ORD-1');
  await expect(page.getByText('Đã bàn giao để vận chuyển')).toHaveCount(0);
});
```

- [ ] **Step 2: Run and verify failure**

Run: `npx playwright test tests/e2e/g1-cod.spec.ts tests/e2e/g1-cod-interruption.spec.ts`

Expected: FAIL until checkout/order/admin UI is integrated.

- [ ] **Step 3: Implement stable submission and poll lifecycle**

```ts
type Submission = {key:string; bodyHash:string; orderNo?:string};
// retain Submission in memory/session-scoped state until a definite 201/409;
// network error and 503 retry reuse key + byte-equivalent body.
```

Pause polling while hidden; resume with status GET; honor `Retry-After` and cap backoff. Render server `allowed_actions`; 403/409/UNKNOWN remain visible. Admin requires reason/evidence and never offers free-form status.

- [ ] **Step 4: Run VI/EN/mobile/role E2E**

Run: `npx playwright test tests/e2e/g1-cod*.spec.ts --project=chromium`

Expected: guest/member/OPS/FINANCE paths PASS at 360px and desktop; terminal state stops dense polling.

- [ ] **Step 5: Sync behavior and checkpoint**

If commit permission exists:

```bash
git add web tests/e2e/g1-cod.spec.ts tests/e2e/g1-cod-interruption.spec.ts docs/design/14_frontend_behavior.md
git commit -m "feat(WEB-03): integrate COD checkout and operations"
```

### Task 7: Prove G1 end to end (`TASK:QA-02` G1 scope)

**Files:**
- Create: `tests/integration/g1/`, `scripts/test-g1.sh`, `docs/evidence/g1-template.md`
- Modify: `.github/workflows/application-ci.yml`, `docs/quality/11_test_strategy.md`, service READMEs

**Interfaces:**
- Consumes: synthetic guest/member/OPS/FINANCE, two-SKU catalog, one-unit hot SKU, mail sink and injectable faults.
- Produces: evidence for G1 journey, invariant reconciliation and open defects.

- [ ] **Step 1: Encode final G1 invariant queries**

```sql
SELECT o.id FROM orders o
LEFT JOIN order_items i ON i.order_id=o.id
WHERE o.status IN ('CONFIRMED','PACKING','SHIPPING','DELIVERED')
GROUP BY o.id HAVING count(i.id)=0;
```

Also query stock constraint violations, duplicate ledger/event effects, COD `gross-fee=net`, and terminal orders with unfinished required saga work. Any row fails the suite.

- [ ] **Step 2: Run full G1 journey with injected crashes**

Run: `bash scripts/test-g1.sh`

Expected: guest and member orders traverse quote → COD → confirm → pack → handover → deliver → collect → settle; expiry/cancel and crashes recover with one effect.

- [ ] **Step 3: Run the complete regression gate**

```bash
./mvnw test
npm ci && npm run typecheck && npm test -- --run && npm run build
bash scripts/validate-contracts.sh
npx playwright test tests/e2e/g1-cod.spec.ts tests/e2e/g1-cod-interruption.spec.ts
python3 -B scripts/check_docs.py
git diff --check
```

Expected: all PASS.

- [ ] **Step 4: Conduct OPS/FINANCE demo and review**

OPS performs confirm/pack/handover/delivery/cancel; FINANCE imports matched and discrepant statements. Record build, environment, steps, actual result, invariant output, mail evidence, reviewer and defects.

- [ ] **Step 5: Synchronize gate and checkpoint**

Update 09/11 with evidence while stating VNPay/MoMo/refund/load/restore remain outside G1. If commit permission exists:

```bash
git add tests/integration/g1 scripts/test-g1.sh docs/evidence/g1-template.md .github/workflows/application-ci.yml docs/quality/11_test_strategy.md docs/delivery/09_delivery_plan.md services/*/README.md
git commit -m "test(G1): prove COD SELF vertical slice"
```

## G1 Exit Gate

- Guest/member COD through SELF delivery and FINANCE settlement passes via storefront/admin and service contracts.
- Expiry/confirm race, cancel tombstones, crash recovery, duplicate events and invariant queries pass on PostgreSQL/Kafka.
- Notification dedupe/lease/redaction and VI/EN mail-sink evidence pass.
- UAT records named OPS/FINANCE reviewers and defects; no Sev1/Sev2 in the G1 slice.
- G1 remains a demo milestone; online providers, refunds, L1 load, restore and G2 go/no-go are unclaimed.
