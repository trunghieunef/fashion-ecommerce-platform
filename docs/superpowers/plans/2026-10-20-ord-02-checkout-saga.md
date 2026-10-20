# ORD-02 Checkout saga Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** worker saga đưa đơn PENDING tới CONFIRMED (reserve → payment COD), bù trừ về CANCELLED khi lỗi chắc chắn hoặc quá
hạn, dọn giỏ bằng background task, ghi nhận payment events.

**Architecture:** Trong order-service: `saga/` (repository lease/CAS trên `order_sagas`, worker `@Scheduled`, các bước), client
mới trong `deps/` theo mẫu `JsonHttp`, task dọn giỏ dùng `BackgroundTaskRepository`, consumer `payment.events` theo mẫu
`ShippingEventsListener` của payment (`InboxListenerSupport`). Mọi HTTP ngoài transaction; intent ghi trước, kết quả ghi bằng
CAS `lease_token` + `version`.

**Tech Stack:** Java 21, Spring Boot 4.0, PostgreSQL 17.11, Kafka (một test Testcontainers), JDK `HttpServer` stub.

**Spec:** [ORD-02 design](../specs/2026-10-20-ord-02-checkout-saga-design.md)

## Global Constraints

- Không HTTP trong transaction DB; intent trước HTTP; kết quả CAS theo `lease_token` và `order_sagas.version`.
- Key nghiệp vụ: `reserve:{order_id}`, `payment:{order_id}`, `release:{order_id}`, `close:{order_id}`; cleanup `Idempotency-Key = order_id`.
- `request_hash` reserve = SHA-256 hex của `order_id|order_no|expires_at|SKU1:q1,...` đúng 03 §4 (escape order_no, `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, SKU trim+upper, gộp, sort).
- Lease 30 s; poll `ORDER_SAGA_POLL` 1 s; batch 10; backoff `min(2^n s, 5 phút)` ±20 %; `ORDER_SAGA_MAX_RETRIES` 30 → `MANUAL` + `order_saga_manual_total`.
- Đơn: `PENDING→CONFIRMED`, `PENDING→CANCELLING→CANCELLED`, `CONFIRMED→CANCELLING→CANCELLED` (EXPIRED); history actor `SYSTEM`, `orders.version + 1`, outbox cùng TX.
- `cancel_reason`: `OUT_OF_STOCK`, `RESERVE_FAILED`, `PAYMENT_FAILED`, `EXPIRED`.
- `processing_status`: PENDING/CANCELLING → `PROCESSING`, CONFIRMED → `READY`, CANCELLED → `FINISHED`; `reason_code` = `cancel_reason`.
- Test tắt worker (`fashion.order.saga.enabled=false`) và gọi `SagaWorker.runOnce()`.
- Không stage 10 file `.sh` đang M và `.gitattributes`. Verify theo module, tắt stack compose khi full verify.

## Review Focus

- Worker A hết lease giữa lúc gọi reserve, worker B claim và hoàn tất; A trả kết quả muộn → bị bỏ, đơn chỉ chuyển một lần — Task 3.
- Reserve trả 200 nhưng response mất (timeout) rồi worker crash → lần sau cùng key/hash, HELD một lần, không giữ hai lần (T03) — Task 3.
- Đơn hết hạn đúng lúc payment create đang UNKNOWN → CANCEL vẫn close payment (tombstone) và release; không CANCELLED khi close chưa xác nhận — Task 4.
- `PAYMENT_COMPLETED` đến trước khi saga chạy (đơn còn PENDING) → `paid_at` giữ, saga vẫn tới CONFIRMED, không hạ trạng thái (T07) — Task 5.
- Guest mất response, giỏ đã dọn, token mới + quote cũ hết hạn + key mới → 409 `CART_ALREADY_ORDERED` kèm cookie đọc được đơn — Task 1.

---

### Task 1: V002, wiring local, guest tìm lại đơn

**Files:** `services/order-service/src/main/resources/db/migration/V002__order_saga.sql`; `OrderService.java`; `application.yaml`
(`inventory-url`, `payment-url`, `saga.*`, kafka consumer group `order-service`); compose (`ORDER_INVENTORY_URL`,
`ORDER_PAYMENT_URL`); `scripts/local-up.sh` (`service_key ORDER ... INVENTORY_INTERNAL_CALLERS PAYMENT_INTERNAL_CALLERS`);
test `OrderSchemaIntegrationTest`, `OrderCreateIntegrationTest`.

- [ ] V002: `background_tasks` (copy catalog V008), `processed_events`; `order_sagas` thêm `payment_state varchar(16) NOT NULL DEFAULT 'NONE'` CHECK (NONE/UNKNOWN/CREATED/CLOSED/PAID); `orders` không đổi cột (`cancel_reason` có sẵn); drop constraint `orders_cart_id_cart_version_key`, tạo `CREATE UNIQUE INDEX orders_open_cart_version_idx ON orders (cart_id, cart_version) WHERE status <> 'CANCELLED'`.
- [ ] Test schema `aCancelledOrderFreesItsCartVersion`: hai đơn cùng giỏ/version → lỗi; đơn đầu set CANCELLED (owner) → insert thứ hai OK.
- [ ] Test create `aGuestWhoseCartWasCleanedFindsItsOrderAgain`: tạo đơn guest; clock +10 phút (quote hết hạn); stub cart trả version 5; key mới + body cũ → 409 `CART_ALREADY_ORDERED`, `order_no`, cookie đọc được đơn; member khác cùng body → không lộ (400/404 theo token).
- [ ] Implement trong `OrderService.create`: sau lookup idempotency, `select order_no ... where cart_id=:c and cart_version=:v and actor_key=:a and status <> 'CANCELLED'` → `AlreadyOrdered` (guest grantAccess). Giữ nhánh ON CONFLICT cũ.
- [ ] Verify order-service; commit `feat(ORD-02): V002 saga schema, local wiring and guest order recovery`.

### Task 2: Client inventory/payment/cart cleanup + request hash

**Interfaces — Produces:**
- `ReservationHash.of(UUID orderId, String orderNo, Instant expiresAt, Map<String,Integer> skus) -> String`.
- `InventoryClient.reserve(Order o) -> Outcome`, `release(Order o, String reason) -> Outcome`, `get(UUID orderId) -> Optional<String status>`; `record Outcome(Result result, String code)` với `enum Result { OK, REJECTED, UNKNOWN }` (4xx nghiệp vụ = REJECTED kèm message, timeout/5xx = UNKNOWN).
- `PaymentClient.create(Order o) -> Outcome` (OK kèm `paymentId`), `close(Order o, String reason) -> Outcome`.
- `CartClient.cleanup(UUID cartId, String actorKey, UUID orderId, List<Line>) -> Outcome`.
- `record SagaOrder(UUID id, String orderNo, UUID userId, String actorKey, UUID cartId, String method, long total, Instant expiresAt, List<Item(UUID itemId, long itemVersion, String sku, int quantity)>)` đọc từ DB.

- [ ] Test `ReservationHashTest`: vector cố định (order_no có `\` và `|`, SKU ` tee-m ` + `TEE-M` gộp, expires_at mili giây) = hex tính tay ghi trong test; Python một dòng để tạo vector, dán kết quả.
- [ ] Test `SagaClientsIntegrationTest` (stub): reserve gửi key `reserve:{id}`, body đúng 03 §4, aud `inventory-service`; 409 OUT_OF_STOCK → REJECTED; delay > timeout → UNKNOWN; payment create body snapshot, key `payment:{id}`; close body snapshot + reason; cleanup key = order_id, `Cart-Actor`.
- [ ] Implement trong `deps/`; commit `feat(ORD-02): inventory, payment and cart cleanup clients`.

### Task 3: Saga forward (RESERVE → CREATE_PAYMENT → CONFIRMED) với lease/CAS

**Interfaces — Produces:** `SagaRepository.claimDue(int limit, Duration lease) -> List<Claim(UUID orderId, UUID leaseToken, long version)>`,
`SagaRepository.intent(Claim, String stateColumn, Instant nextRetry) -> boolean`, `SagaRepository.complete(Claim, Consumer<JdbcClient> write) -> boolean` (CAS lease+version, nhả lease, version+1); `SagaWorker.runOnce() -> int`; `OrderTransitions.move(UUID orderId, String from, String to, String reason)` (history + version + outbox ORDER_CONFIRMED/ORDER_CANCELLED).

- [ ] Test `SagaForwardIntegrationTest`: đơn từ ORD-01 + stub OK → `runOnce()` hai lần → CONFIRMED, saga WAIT_OPS/WAITING `next_retry_at = expires_at`, `inventory_state HELD`, `payment_state CREATED`, `payment_id`, history PENDING→CONFIRMED actor SYSTEM, outbox `ORDER_CONFIRMED`, task `CART_CLEANUP` 1 dòng.
- [ ] Test T03 `aLostReserveResponseIsRetriedWithTheSameKey`: stub delay 1.5 s lần đầu (ghi nhận), lần sau 200 → state UNKNOWN rồi HELD; cả hai request cùng key/hash.
- [ ] Test lease `aStaleWorkerCannotOverwrite`: claim A; set `lease_until` quá khứ (owner); claim B và hoàn tất; `complete(A)` → false, không ghi.
- [ ] Test `retriesBackOffThenGoManual`: max-retries=3 (property) + inventory 503 → sau 3 lượt `MANUAL`, `last_error`, metric tăng; `next_retry_at` tăng dần.
- [ ] Implement `saga/SagaRepository`, `saga/SagaWorker`, `saga/OrderTransitions`; SQL claim `FOR UPDATE SKIP LOCKED` + lease (LeaseRepository dùng cột `id`, `order_sagas` dùng `order_id` → SQL riêng). Commit `feat(ORD-02): saga forward steps with lease and CAS`.

### Task 4: Bù trừ và hết hạn

- [ ] Test `outOfStockCancelsWithoutTouchingTheCart`: reserve 409 OUT_OF_STOCK → CANCELLING → close payment (tombstone) → CANCELLED `OUT_OF_STOCK`; không gọi release (state NONE); không task cleanup; `ORDER_CANCELLED`; ORD-01 create cùng giỏ/version (key mới, quote mới) → 202.
- [ ] Test `aRefusedPaymentReleasesTheReservation`: payment 409 PAYMENT_CLOSED → release với original_reservation + original_request_hash đúng hash reserve → close → CANCELLED `PAYMENT_FAILED`.
- [ ] Test `anExpiredConfirmedOrderIsCancelled`: CONFIRMED, ShiftClock quá `expires_at` → release + close → CANCELLED `EXPIRED`; release lỗi 503 → vẫn CANCELLING (không CANCELLED), lượt sau xong.
- [ ] Test `releaseFoundCommittedGoesManual`: release trả status COMMITTED → saga MANUAL, đơn CANCELLING.
- [ ] Implement nhánh intent CANCEL trong worker; commit `feat(ORD-02): compensation and expiry`.

### Task 5: Dọn giỏ và payment events

- [ ] Test `CartCleanupTaskIntegrationTest`: task chạy → cart cleanup đúng body; cart 503 → task retry (attempts+1), đơn vẫn CONFIRMED; `retained` → DONE.
- [ ] Test `PaymentEventsIntegrationTest` (gọi handler trực tiếp qua inbox như CodEvidence test): `PAYMENT_COMPLETED` khi PENDING → `paid_at`, `payment_state PAID`, rồi `runOnce()` vẫn tới CONFIRMED (không ghi đè PAID); trùng event_id một lần; `PAYMENT_FAILED` CLOSED → chỉ processed.
- [ ] Test Kafka (một test Testcontainers) listener `payment.events` group `order-service` ACK sau commit.
- [ ] Implement `saga/CartCleanupWorker` (`@Scheduled`, `BackgroundTaskRepository.claimDue("CART_CLEANUP", 10, 30s)`), `orders/PaymentEventsListener`; commit `feat(ORD-02): cart cleanup task and payment events`.

### Task 6: API, contract, smoke, tài liệu

- [ ] Test `OrderReadIntegrationTest`: CONFIRMED → `READY`, không `retry_after_seconds`; CANCELLED → `FINISHED`, `reason_code OUT_OF_STOCK`.
- [ ] `order.yaml`: `reason_code` (nullable), mô tả processing_status; contract test.
- [ ] Smoke: sau bước ORD-01 poll GET (≤ 20 lần × 1 s) tới `CONFIRMED` → `ORD-02 saga confirm PASS`.
- [ ] Mutation: bỏ CAS lease; bỏ cùng key retry (key ngẫu nhiên); CANCELLED trước khi release xác nhận; dọn giỏ khi OUT_OF_STOCK → test fail.
- [ ] Docs: 03 (internal inventory/payment caller order-service, processing_status), 05 (V002), 06 §5.1/§5.4 (phần ORD-02), 08 (bảng "ORD-02 — quyết định" từ spec §11), 10, 12, 14 §3 (READY/FINISHED, giữ cart_id/version), README service + root, CLAUDE.md, evidence `docs/evidence/ord-02-local-2026-10-20.md`.
- [ ] Verify order-service, contracts, docs check, `git diff --check`; commit `docs(ORD-02): contract, smoke, docs and evidence`.
