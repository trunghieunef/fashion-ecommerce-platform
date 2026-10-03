# Phase 1C Online Payment MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nâng G1 thành G2 MVP bằng VNPay và MoMo sandbox, refund/reconciliation, late-payment/cancel recovery, full security/E2E, L1 load, restore drill and named go/no-go.

**Architecture:** Payment owns provider intents, stable merchant references, callback verification, query/refund and financial ledgers. Provider calls run in leased workers outside transactions; verified callback/query results converge through one idempotent apply function. Order consumes payment evidence, commits inventory before PAID, and creates refund obligations when verified money arrives after closure.

**Tech Stack:** Existing G1 stack; provider-neutral Spring `RestClient`; local deterministic provider stub plus VNPay PAY 2.1.0 GET profile and MoMo notification/query/refund sandbox; Playwright; an approved L1 load runner; AWS staging backup/restore and observability from G0.

**Spec:** `docs/delivery/10_backlog.md` PAY-02/03/04, ORD-04/05, WEB-04, ADM-02, QA-02/03, OPS-01/02; `docs/engineering/15_integrations.md`; `docs/quality/11_test_strategy.md`; `docs/operations/13_operations_security.md`.

## Global Constraints

- Begin after G1 acceptance. O03 must record sandbox merchant profiles, callback domains, query/refund permission and owner through a secure channel; O05/O06/O08/O09/O10 require named conclusions before final go/no-go.
- No production credentials, money, callbacks, customer data or email. Golden fixtures use synthetic test secrets and sanitized sandbox payloads.
- Browser return URL is navigation only. SUCCESS requires verified provider evidence matching signature/auth, merchant, reference, integer amount and currency.
- Timeout is `UNKNOWN`; query the same merchant reference before retry. Never generate a new reference to hide uncertainty.
- Callback/query/refund share one locked, idempotent apply path. ACK follows provider profile and only after local commit.
- `captured - refunded - reserved >= 0`; concurrent refunds cannot exceed captured amount. Partial refund after delivery does not cancel the order.
- Inventory commit must succeed before order PAID. Verified late money after closed reservation/order creates a durable refund obligation.
- G2 is release capability, not automatic production deployment. Commit requires explicit user permission.

## Review Focus

- Valid signature with wrong merchant, reference, amount or currency must never mark payment successful; Tasks 1/2 test U16.
- Callback arriving before create HTTP response, twice, or after return navigation must converge to one effect; Tasks 1/2 test T06/T07.
- Refund timeout followed by retry/query must not create a second refund reference or over-refund; Task 3 tests T10/T11.
- Payment success racing reservation expiry/cancel must either reach PAID after one inventory commit or produce one refund obligation; Task 4 tests T05/T08/T09.
- Restore to an earlier point with provider transactions after the restore point must reconcile by stable reference before traffic reopens; Task 7 tests U25.

---

## File Map

| Path | Responsibility |
|---|---|
| `services/payment-service/src/main/java/vn/fashion/payment/provider/vnpay/` | VNPay create/query/IPN/return/refund profile adapter |
| `services/payment-service/src/main/java/vn/fashion/payment/provider/momo/` | MoMo create/query/notify/return/refund profile adapter |
| `services/payment-service/src/main/java/vn/fashion/payment/refund/`, `services/payment-service/src/main/java/vn/fashion/payment/reconcile/` | Refund ledger, reservation and statement/provider reconciliation |
| `services/order-service/src/main/java/vn/fashion/order/online/` | Online deadline, commit-before-PAID, late success and compensation |
| `infra/local/provider-stub/` | Deterministic success/failure/timeout/duplicate/out-of-order provider |
| `tests/provider-fixtures/` | Sanitized golden canonical/signature/ACK fixtures |
| `tests/e2e/g2-online.spec.ts`, `tests/load/l1/` | Browser and load/chaos evidence |
| `scripts/restore-drill.sh`, `docs/evidence/g2-release-template.md` | Restore/reconcile and go/no-go record |

### Task 1: Implement and sandbox VNPay (`TASK:PAY-02`; `REQ:PAY-01/04/07`)

**Files:**
- Create: `services/payment-service/src/main/java/vn/fashion/payment/provider/vnpay/VnPayClient.java`, `services/payment-service/src/main/java/vn/fashion/payment/provider/vnpay/VnPaySigner.java`, `services/payment-service/src/main/java/vn/fashion/payment/provider/vnpay/VnPayCallbackVerifier.java`, `services/payment-service/src/main/java/vn/fashion/payment/provider/vnpay/VnPayMapper.java`
- Create: `tests/provider-fixtures/vnpay/` valid/invalid/query/refund fixtures
- Create: `services/payment-service/src/test/java/vn/fashion/payment/provider/vnpay/VnPayAdapterContractTest.java`, `services/payment-service/src/test/java/vn/fashion/payment/provider/vnpay/VnPayCallbackIntegrationTest.java`
- Modify: `contracts/openapi/payment.yaml`, `services/payment-service/README.md`, `docs/engineering/15_integrations.md`

**Interfaces:**
- Consumes: PAY 2.1.0 GET profile config, secret injection, stable `provider_request_id`, amount VND, callback/query/refund results.
- Produces: `ProviderResult {VERIFIED_SUCCESS, VERIFIED_FAILURE, PENDING, UNKNOWN}`, payment URL, exact VNPay JSON ACK, sanitized evidence.

- [ ] **Step 1: Write golden canonical/signature and invalid-evidence tests**

```java
@Test void signsAmountMultipliedByOneHundredWithProfileFieldOrder() {
  var request = fixture("create-valid.json");
  assertThat(signer.canonical(request)).isEqualTo(fixtureText("create-canonical.txt"));
  assertThat(signer.sign(request, TEST_SECRET)).isEqualTo(fixtureText("create-signature.txt").trim());
}

@Test void validSignatureButWrongAmountIsRejected() {
  var callback = signedFixture("ipn-success.json").withAmount(49_900_00L);
  assertThat(verifier.verify(callback, expected(500_000, "VND"))).isEqualTo(EVIDENCE_MISMATCH);
}

@Test void duplicateIpnAcksAfterOneCommittedEffect() {
  postIpnTwice("ipn-success.json");
  assertThat(paymentSuccessEffects(PAYMENT_ID)).isEqualTo(1);
  assertThat(lastAck()).containsEntry("RspCode", "00");
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/payment-service -Dtest='VnPayAdapterContractTest,VnPayCallbackIntegrationTest' test`

Expected: FAIL before adapter/fixtures exist.

- [ ] **Step 3: Implement canonical encoding and evidence verification**

```java
VerifiedResult verify(VnPayCallback callback, ExpectedPayment expected) {
  if (!signer.constantTimeValid(callback)) return invalid("signature");
  if (!callback.merchant().equals(expected.merchant()) ||
      !callback.reference().equals(expected.reference()) ||
      callback.amountHundredths() != Math.multiplyExact(expected.amountVnd(), 100L) ||
      !callback.currency().equals("VND")) return invalid("evidence");
  return mapper.map(callback.responseCode(), callback.transactionStatus());
}
```

Persist local intent/reference before the network call. Create/query/refund run outside DB transaction with profile-specific timeout; browser ReturnURL calls only a status read.

- [ ] **Step 4: Pass deterministic stub cases**

Run: `./mvnw -pl services/payment-service -Dtest='VnPay*Test' test`

Expected: success/failure/pending/timeout-after-effect/duplicate/late/wrong-signature/merchant/reference/amount/currency and ACK cases PASS.

- [ ] **Step 5: Run permitted sandbox smoke**

With secrets injected outside Git, run the documented small create/query/IPN/refund sandbox suite. Record profile/version/date, sanitized reference and result. If credentials/domain/permission are missing, PAY-02 remains blocked and G2 cannot pass.

- [ ] **Step 6: Sync docs and checkpoint**

If commit permission exists:

```bash
git add services/payment-service tests/provider-fixtures/vnpay contracts/openapi/payment.yaml docs/engineering/15_integrations.md
git commit -m "feat(PAY-02): integrate verified VNPay payments"
```

### Task 2: Implement and sandbox MoMo (`TASK:PAY-03`; `REQ:PAY-02/04/07`)

**Files:**
- Create: `services/payment-service/src/main/java/vn/fashion/payment/provider/momo/MomoClient.java`, `services/payment-service/src/main/java/vn/fashion/payment/provider/momo/MomoSigner.java`, `services/payment-service/src/main/java/vn/fashion/payment/provider/momo/MomoNotificationVerifier.java`, `services/payment-service/src/main/java/vn/fashion/payment/provider/momo/MomoMapper.java`
- Create: `tests/provider-fixtures/momo/`
- Create: `services/payment-service/src/test/java/vn/fashion/payment/provider/momo/MomoAdapterContractTest.java`, `services/payment-service/src/test/java/vn/fashion/payment/provider/momo/MomoNotificationIntegrationTest.java`
- Modify: `contracts/openapi/payment.yaml`, `services/payment-service/README.md`, `docs/engineering/15_integrations.md`

**Interfaces:**
- Consumes: approved MoMo product/requestType profile, partner credentials, stable request/order IDs and notify/query/refund results.
- Produces: normalized provider result, verified evidence and HTTP 204 no-body notification ACK after commit.

- [ ] **Step 1: Write signature/result/ACK tests**

```java
@Test void notificationCanonicalStringMatchesGoldenFixture() {
  var notification = fixture("notify-success.json");
  assertThat(signer.canonical(notification)).isEqualTo(fixtureText("notify-canonical.txt"));
  assertThat(signer.sign(notification, TEST_SECRET)).isEqualTo(fixtureText("notify-signature.txt").trim());
}

@Test void pendingResultRemainsPendingUntilQueryIsFinal() {
  applyNotification(fixture("notify-pending.json"));
  assertPaymentStatus(PAYMENT_ID, "PENDING");
  applyQuery(fixture("query-success.json"));
  assertPaymentStatus(PAYMENT_ID, "SUCCESS");
}

@Test void notificationAckIsEmpty204AfterCommit() {
  var response = postNotification("notify-success.json");
  assertThat(response.status()).isEqualTo(204); assertThat(response.body()).isEmpty();
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/payment-service -Dtest='MomoAdapterContractTest,MomoNotificationIntegrationTest' test`

Expected: FAIL before adapter/fixtures exist.

- [ ] **Step 3: Implement exact-profile verification and mapping**

Do not reuse VNPay field names/encoding. Verify partner/merchant, request/order reference, amount and currency before mapping result codes. Query and callback call the same `applyVerifiedOutcome(paymentId, providerEventKey, evidence)` transaction.

- [ ] **Step 4: Pass stub and sandbox suites**

Run: `./mvnw -pl services/payment-service -Dtest='Momo*Test' test`

Expected: valid/invalid signature, pending/final, duplicate, callback-before-response, wrong merchant/reference/amount and 204 ACK tests PASS. Then run the permitted sandbox create/query/notify/refund smoke and store sanitized evidence; missing permission blocks PAY-03/G2.

- [ ] **Step 5: Sync docs and checkpoint**

If commit permission exists:

```bash
git add services/payment-service tests/provider-fixtures/momo contracts/openapi/payment.yaml docs/engineering/15_integrations.md
git commit -m "feat(PAY-03): integrate verified MoMo payments"
```

### Task 3: Implement refund and reconciliation (`TASK:PAY-04`; `REQ:PAY-05/06`, `REQ:ORD-08`)

**Files:**
- Create: `services/payment-service/src/main/java/vn/fashion/payment/refund/`, `services/payment-service/src/main/java/vn/fashion/payment/reconcile/`, `services/payment-service/src/main/resources/db/migration/V002__refunds_and_reconciliation.sql`
- Create: `services/payment-service/src/test/java/vn/fashion/payment/refund/ConcurrentRefundIntegrationTest.java`, `services/payment-service/src/test/java/vn/fashion/payment/refund/RefundUnknownRecoveryTest.java`, `services/payment-service/src/test/java/vn/fashion/payment/reconcile/ProviderReconciliationTest.java`
- Modify: `contracts/openapi/payment.yaml`, `contracts/events/payment-events.schema.json`, `contracts/events/refund-events.schema.json`, `docs/design/05_database_design.md`, `docs/design/06_service_flows.md`

**Interfaces:**
- Consumes: FINANCE refund request with stable key/reason/evidence, captured amount, provider query/refund, COD manual evidence.
- Produces: refund intent/result (`PENDING|UNKNOWN|SUCCESS|FAILED|MANUAL`), reserved/refunded counters and reconciliation discrepancy.

- [ ] **Step 1: Write concurrent and timeout tests**

```java
@Test void concurrentRefundsCannotExceedCapturedAmount() {
  capture(PAYMENT_ID, 500_000);
  var results = concurrently(() -> requestRefund("r1", 300_000), () -> requestRefund("r2", 300_000));
  assertThat(results).filteredOn(RefundResult::accepted).hasSize(1);
  assertThat(refundCounters(PAYMENT_ID).reservedPlusRefunded()).isEqualTo(300_000);
}

@Test void timeoutQueriesSameReferenceBeforeRetry() {
  provider.refundCommitsThenTimesOut("refund-ref-1");
  worker.runOnce(); worker.runOnce();
  assertThat(provider.refundCreateReferences()).containsOnly("refund-ref-1");
  assertThat(refundStatus()).isEqualTo("SUCCESS");
}

@Test void bodyChangeWithSameRefundKeyConflicts() {
  requestRefund("key-1", 100_000); requestRefund("key-1", 200_000).expectStatus().isEqualTo(409);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/payment-service -Dtest='ConcurrentRefundIntegrationTest,RefundUnknownRecoveryTest,ProviderReconciliationTest' test`

Expected: FAIL before schema/worker exists.

- [ ] **Step 3: Reserve refundable amount under lock**

```java
@Transactional
Refund reserveRefund(UUID paymentId, long amount, String key, String requestHash) {
  var payment = payments.lock(paymentId);
  long available = Math.subtractExact(payment.captured(), Math.addExact(payment.refunded(), payment.refundReserved()));
  if (amount <= 0 || amount > available) throw new RefundAmountConflict();
  payments.addRefundReserved(paymentId, amount);
  return refunds.insert(paymentId, amount, key, requestHash, stableProviderReference(paymentId, key));
}
```

Provider call occurs outside transaction; apply result converts reserved to refunded exactly once. Unknown remains queryable/manual and never frees reservation without verified failure.

- [ ] **Step 4: Run T10/T11/T26/U16/U17**

Run: `./mvnw -pl services/payment-service test`

Expected: concurrent full/partial, timeout/query, duplicate callback/refund, COD manual and reconciliation cases PASS with counter invariant query.

- [ ] **Step 5: Sync docs and checkpoint**

If commit permission exists:

```bash
git add services/payment-service contracts docs/design/05_database_design.md docs/design/06_service_flows.md
git commit -m "feat(PAY-04): add race-safe refunds and reconciliation"
```

### Task 4: Complete online order recovery (`TASK:ORD-04`, `TASK:ORD-05`; `REQ:ORD-02/04/08/10`)

**Files:**
- Create/modify: `services/order-service/src/main/java/vn/fashion/order/online/`, `services/order-service/src/main/java/vn/fashion/order/cancel/`, `services/order-service/src/main/java/vn/fashion/order/returning/`, `services/order-service/src/main/java/vn/fashion/order/refund/`
- Create: `services/order-service/src/test/java/vn/fashion/order/online/OnlinePaymentRaceIntegrationTest.java`, `services/order-service/src/test/java/vn/fashion/order/online/LatePaymentIntegrationTest.java`
- Modify: `contracts/openapi/order.yaml`, `contracts/openapi/payment.yaml`, `contracts/openapi/inventory.yaml`, `contracts/events/order-events.schema.json`, `contracts/events/payment-events.schema.json`, `contracts/events/stock-events.schema.json`, `docs/design/06_service_flows.md`

**Interfaces:**
- Consumes: online create/query/callback/refund events, inventory reservation/commit/release/return and shipment state.
- Produces: WAITING_PAYMENT/PAID/CANCELLING/CANCELLED/REFUNDING/REFUNDED/RETURNING/RETURNED and durable next work.

- [ ] **Step 1: Write success/expiry/late/failure ordering tests**

```java
@Test void paymentSuccessCommitsInventoryBeforePaid() {
  consumeVerifiedSuccess(ORDER_ID); worker.runUntilIdle();
  assertThat(effectTime("INVENTORY_COMMITTED")).isBefore(effectTime("ORDER_PAID"));
}

@Test void lateSuccessAfterClosedReservationCreatesOneRefundObligation() {
  expireAndClose(ORDER_ID); consumeVerifiedSuccessTwice(ORDER_ID); worker.runUntilIdle();
  assertStatus(ORDER_ID, "REFUNDING");
  assertThat(refundObligations(ORDER_ID)).hasSize(1);
}

@Test void staleProviderFailureDoesNotLowerPaidOrder() {
  markPaid(ORDER_ID); consumeOlderVerifiedFailure(ORDER_ID);
  assertStatus(ORDER_ID, "PAID");
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/order-service -Dtest='OnlinePaymentRaceIntegrationTest,LatePaymentIntegrationTest' test`

Expected: FAIL until online handlers are complete.

- [ ] **Step 3: Implement version/evidence guarded outcomes**

Verified SUCCESS persists payment evidence; the saga issues one inventory commit. Only `INVENTORY_COMMITTED` permits PAID. If reservation/order is closed, insert refund obligation and never re-reserve inventory. Verified FAILURE can move only a matching pending version; late lower-version events are audit-only.

- [ ] **Step 4: Run T05–T09/T16/T17/T26/U12/U13**

Run: `./mvnw -pl services/order-service,services/payment-service,services/inventory-service,services/shipping-service test`

Expected: payment-expiry/cancel races, late money, commit crash, cancellation/return and partial refund cases PASS.

- [ ] **Step 5: Sync state machine and checkpoint**

If commit permission exists:

```bash
git add services/order-service services/payment-service services/inventory-service services/shipping-service contracts docs/design/06_service_flows.md
git commit -m "feat(ORD-05): complete online payment recovery"
```

### Task 5: Integrate truthful online/refund UX (`TASK:WEB-04`, `TASK:ADM-02`)

**Files:**
- Modify: `web/storefront/src/checkout|orders|payments/`
- Modify: `web/admin/src/orders|shipping|payments|refunds|settlement/`
- Create: `tests/e2e/g2-online.spec.ts`, `tests/e2e/g2-refund.spec.ts`
- Modify: `docs/design/14_frontend_behavior.md`

**Interfaces:**
- Consumes: payment URL allowlist, server order/payment status, allowed actions, refund/return/UNKNOWN data.
- Produces: VNPay/MoMo flow, browser return recovery, cancel/refund status and role-scoped AD-03–06.

- [ ] **Step 1: Write return/UNKNOWN/refund browser tests**

```ts
test('provider return never declares success before server evidence', async ({page}) => {
  await page.goto('/payment/return?vnp_ResponseCode=00');
  await expect(page.getByText('Đang xác minh thanh toán')).toBeVisible();
  await expect(page.getByText('Thanh toán thành công')).toHaveCount(0);
});

test('finance double click creates one refund', async ({page}) => {
  await loginAs(page, 'FINANCE'); await page.goto('/admin/payments/PAY-1');
  await page.getByRole('button', {name:'Hoàn tiền'}).dblclick();
  await expect(page.getByText('Đang xử lý hoàn tiền')).toBeVisible();
  expect(await refundCount('PAY-1')).toBe(1);
});
```

- [ ] **Step 2: Run and verify failure**

Run: `npx playwright test tests/e2e/g2-online.spec.ts tests/e2e/g2-refund.spec.ts`

Expected: FAIL before UI integration.

- [ ] **Step 3: Implement server-read return and stable mutation keys**

Return route ignores query success as evidence, extracts only allowlisted order lookup context, then reads server state. Refund/cancel button stores one key/body hash through network retry, renders `UNKNOWN` as “đang xác minh,” and exposes a safe support reference.

- [ ] **Step 4: Run U03/U07/U12/U13/U16/U22/U24**

Run: `npm run typecheck && npm test && npx playwright test tests/e2e/g2-online.spec.ts tests/e2e/g2-refund.spec.ts`

Expected: browser close/return order, double-click, role/ownership, CSRF, 202/409/UNKNOWN, VI/EN and 360px cases PASS.

- [ ] **Step 5: Sync UX docs and checkpoint**

If commit permission exists:

```bash
git add web tests/e2e/g2-online.spec.ts tests/e2e/g2-refund.spec.ts docs/design/14_frontend_behavior.md
git commit -m "feat(WEB-04): integrate online payment and refund UX"
```

### Task 6: Complete MVP functional/security regression (`TASK:QA-02`)

**Files:**
- Create/modify: `tests/integration/mvp/`, `tests/e2e/mvp-regression.spec.ts`
- Create: `scripts/test-mvp-regression.sh`, `docs/evidence/qa02-template.md`
- Modify: `.github/workflows/application-ci.yml`, `docs/quality/11_test_strategy.md`

**Interfaces:**
- Consumes: all MVP synthetic fixtures and deterministic provider stub fault modes.
- Produces: traceability report for T01–T12/T15–T27 MVP portions and U01–U27 MVP portions; Phase 2 cases explicitly excluded rather than passed.

- [ ] **Step 1: Materialize the traceability manifest**

```yaml
required:
  - T01
  - T02
  - T03
  - T04
  - T05
  - T06
  - T07
  - T08
  - T09
  - T10
  - T11
  - T12
  - T15_SELF
  - T16
  - T17
  - T18
  - T19
  - T20_REFRESH
  - T21
  - T22
  - T23
  - T24
  - T25_DB_CACHE_AUTH
  - T26
  - T27
excluded_phase_2: [T13, T14, T20_SOCIAL, T25_OTP_PROMOTION]
```

- [ ] **Step 2: Make missing/unexecuted evidence fail**

`scripts/test-mvp-regression.sh` parses JUnit/Playwright results and traceability manifest; missing, skipped or not-applicable required IDs return non-zero. Each concurrency/money test includes DB invariant output.

- [ ] **Step 3: Run provider-stub and sandbox subsets**

Run: `bash scripts/test-mvp-regression.sh`

Expected: deterministic stub full fault matrix PASS. Then run the small permitted VNPay/MoMo sandbox suite; record separately so stub success cannot replace sandbox evidence.

- [ ] **Step 4: Run scans and full CI**

```bash
./mvnw test
npm ci && npm run typecheck && npm test && npm run build
bash scripts/validate-contracts.sh
bash scripts/scan-secrets.sh
npx playwright test tests/e2e/mvp-regression.spec.ts
python3 -B scripts/check_docs.py
git diff --check
```

Expected: all PASS with no open Sev1/Sev2.

- [ ] **Step 5: Review checkpoint**

Record build/environment/version/digest, test ID, fixture/fault, expected/actual, invariant result, redacted trace, operator/date and defect/waiver. If commit permission exists:

```bash
git add tests scripts/test-mvp-regression.sh docs/evidence/qa02-template.md .github/workflows/application-ci.yml docs/quality/11_test_strategy.md
git commit -m "test(QA-02): complete MVP regression evidence"
```

### Task 7: Prove L1 load and restore/reconcile (`TASK:QA-03`, `TASK:OPS-01`; `REQ:NFR-01–09`)

**Files:**
- Create: `tests/load/l1/`, `scripts/run-l1.sh`
- Create: `scripts/backup-staging.sh`, `scripts/restore-drill.sh`, `docs/evidence/l1-template.md`, `docs/evidence/restore-template.md`
- Modify: `docs/operations/13_operations_security.md`, `docs/engineering/16_aws_deployment.md`

**Interfaces:**
- Consumes: approved O09 runner/workload, staging sizing, 1,000+ synthetic SKUs, provider stub, isolated restore target.
- Produces: p50/p95/p99/error/invariant/resource report and measured RPO/RTO with provider/saga reconciliation.

- [ ] **Step 1: Encode the L1 workload and acceptance math**

```text
100 concurrent users; 10m warm-up + 30m measure
70% browse, 15% cart, 10% checkout, 5% callback
separate hot-SKU-one-unit scenario
checkout p95 < 2s; catalog p95 < 300ms
zero oversell, over-refund, duplicate order/payment/refund effects
```

Runner report includes think time, pools, CPU/RAM/disk, DB, Kafka lag, cache state, seed and provider latency. Valid-attempt denominator excludes only documented business-invalid inputs, never slow 202 readiness.

- [ ] **Step 2: Run L1 and verify absolute invariants**

Run: `bash scripts/run-l1.sh`

Expected: threshold and invariant assertions PASS. If the approved staging node cannot hold full MVP, record actual saturation and keep QA-03/G2 blocked; do not silently increase AWS spend.

- [ ] **Step 3: Back up and restore to an isolated target**

`backup-staging.sh` captures backup ID, WAL/PITR point, encryption/access and failure alert. `restore-drill.sh` blocks writes, restores each service DB to isolated credentials/network, applies migrations, runs counts/constraints, imports verified provider outcomes after restore point by stable reference, replays bounded event IDs and runs MVP E2E.

- [ ] **Step 4: Prove no repeated financial/stock effect after restore**

Run: `bash scripts/restore-drill.sh`

Expected: provider references, refund/payment/order/stock ledger/outbox/inbox reconcile; U25 passes; measured RPO/RTO are within approved targets or G2 remains blocked.

- [ ] **Step 5: Rehearse app rollback and alert delivery**

Rollback only the application digest through GitOps to a schema-compatible image, rerun smoke, then return to current digest. Trigger safe synthetic alerts for outbox age, UNKNOWN refund and disk threshold; named on-call recipient confirms receipt.

- [ ] **Step 6: Sync runbooks and checkpoint**

If commit permission exists:

```bash
git add tests/load scripts/run-l1.sh scripts/backup-staging.sh scripts/restore-drill.sh docs/evidence/l1-template.md docs/evidence/restore-template.md docs/operations/13_operations_security.md docs/engineering/16_aws_deployment.md
git commit -m "test(QA-03): prove MVP load and recovery"
```

### Task 8: Conduct UAT and record the G2 decision (`TASK:OPS-02`)

**Files:**
- Create: `docs/evidence/g2-release-template.md`, `docs/runbooks/g2-operator-checklist.md`
- Modify: `docs/delivery/09_delivery_plan.md`, `docs/design/08_decisions.md`, `docs/operations/13_operations_security.md`, all service READMEs with verified commands/limits

**Interfaces:**
- Consumes: QA-02/03, OPS-01, provider sandbox, O01–O10 conclusions, open-defect list and on-call roster.
- Produces: named PO/TL/OPS/FINANCE/DEVOPS go/no-go with date, build/digests, environment and waivers.

- [ ] **Step 1: Execute role-based UAT**

```text
PO: catalog/VI/EN/price/fee/customer journeys
OPS: COD confirm, pack, SELF handover/delivery, cancel UNKNOWN, return evidence
FINANCE: VNPay/MoMo full+partial refund, late payment, COD gross/fee/net discrepancy
DEVOPS: deploy/rollback, alerts, backup/restore evidence, pause admission
TL: contract/schema/security/defect and compatibility evidence
```

Every line records operator, time, build, expected/actual and linked defect; no shared admin account.

- [ ] **Step 2: Verify release blockers mechanically**

Release script fails if a required test/evidence/decision lacks owner/date, if any Sev1/Sev2 is open, if production/sandbox secrets overlap, if callback domain/profile differs from evidence, or if on-call primary/backup is absent.

- [ ] **Step 3: Review production exclusions**

Record that G2 does not prove production HA, a multi-node database, 30-day 99.9% availability or permission to spend beyond approved staging. Production provisioning/promotion requires a separate reviewed plan.

- [ ] **Step 4: Run final repository verification**

```bash
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
./mvnw test
npm ci && npm run typecheck && npm test && npm run build
bash scripts/validate-contracts.sh
bash scripts/test-mvp-regression.sh
git diff --check
```

Expected: all PASS; QA-03/restore/sandbox evidence is linked even when not rerun in this command.

- [ ] **Step 5: Record go/no-go and checkpoint**

Named owners sign `docs/evidence/g2-release-template.md`. Update 09/08/13 with evidence and remaining risks; do not mark production deployed. If commit permission exists:

```bash
git add docs/evidence/g2-release-template.md docs/runbooks/g2-operator-checklist.md docs/delivery/09_delivery_plan.md docs/design/08_decisions.md docs/operations/13_operations_security.md services/*/README.md
git commit -m "docs(OPS-02): record G2 MVP release decision"
```

## G2 MVP Exit Gate

- G1 plus VNPay/MoMo sandbox, duplicate/late/invalid callbacks, full/partial refund and reconciliation pass.
- Required T/U coverage, security/ownership, provider negative cases and contract compatibility pass with no open Sev1/Sev2.
- L1 meets accepted latency/success/invariant targets on documented sizing; cost and saturation are recorded.
- Backup/restore/provider reconciliation, bounded replay, rollback and alert delivery are rehearsed with measured RPO/RTO.
- O01–O10 relevant conclusions, content/policy/retention/on-call/training and named go/no-go are recorded.
- Outcome is “G2 MVP release-capable”; production deploy remains a separate approved action.
