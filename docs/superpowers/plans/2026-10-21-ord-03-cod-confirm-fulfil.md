# ORD-03 COD confirm/fulfil Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OPS xác nhận/hủy từng đơn COD `CONFIRMED`; saga trừ kho → `PACKING` → tạo vận đơn SELF; order theo
`shipping.events` tới `SHIPPING`/`DELIVERED`; end-to-end local qua SELF + FINANCE settlement.

**Architecture:** Trong order-service: package mới `admin/` (auth theo permission, đọc đơn có redact, lệnh confirm/cancel
khóa `orders` + `order_sagas` trong một transaction, idempotency + `audit_logs`); `SagaWorker` thêm step `COMMIT_STOCK` và
`CREATE_SHIPMENT`, kiểm hạn order chỉ trước xác nhận; `deps/` thêm `InventoryClient.commit`, `ShippingClient.create`;
consumer `shipping.events` theo mẫu `PaymentEventsListener`. Mọi HTTP ngoài transaction, intent trước, kết quả CAS lease.

**Tech Stack:** Java 21, Spring Boot 4.0, PostgreSQL 17.11 (Testcontainers), Kafka (một test Testcontainers), JDK `HttpServer`
stub (`StubServers`).

**Spec:** [ORD-03 design](../specs/2026-10-21-ord-03-cod-confirm-fulfil-design.md)

## Global Constraints

- Không HTTP trong transaction DB; intent trước HTTP; kết quả `SagaRepository.write` (CAS lease + version).
- Key cố định: `commit:{order_id}`, `shipment:{order_id}`; giữ `reserve:`, `payment:`, `release:`, `close:` của ORD-02.
- Kiểm `expires_at` của order chỉ ở step `RESERVE`, `CREATE_PAYMENT`, `WAIT_OPS`; từ `COMMIT_STOCK` inventory là trọng tài.
- Ở `COMMIT_STOCK`, intent tính lần thử + backoff nhưng **không** đổi `inventory_state` (giữ `HELD`).
- Commit: `RESERVATION_EXPIRED` → `RELEASED` + CANCEL `EXPIRED`; `RESERVATION_CLOSED` → `RELEASED` + CANCEL `RESERVE_FAILED`;
  từ chối khác → retry rồi MANUAL. Shipment: mọi từ chối → MANUAL; đơn giữ `PACKING`.
- Đơn: `CONFIRMED→PACKING` (commit OK), `CONFIRMED→CANCELLING` (OPS hủy), `PACKING→SHIPPING`, `SHIPPING→DELIVERED`; history +
  `orders.version + 1` + outbox cùng TX; outbox chỉ `ORDER_CANCELLED` (có sẵn) và `ORDER_COMPLETED` (mới phát).
- `cancel_reason` thêm `OPS_CANCELLED`; lý do người nhập vào history `reason` + `audit_logs.reason`.
- Admin: `order.read` (list/detail), `order.fulfill` (confirm-cod, thấy PII), `order.cancel` (cancel). Không `order.fulfill`
  → không trả `contact_phone`, `contact_email`, `shipping_address`, `note`, history `reason`; `q` theo phone bị 403.
- Confirm/cancel: `Idempotency-Key` bắt buộc, body `expected_version` (+ `note` ≤ 500 tùy chọn | `reason` 1..500 bắt buộc);
  `202`; lỗi `409 ORDER_EXPIRED`, `409 INVALID_TRANSITION` (errors `status current=S`), `409 VERSION_CONFLICT`,
  `409 IDEMPOTENCY_KEY_REUSED`. Thứ tự kiểm: auth → key → body.
- `processing_status`: CONFIRMED + saga `WAIT_OPS` → `READY`; CONFIRMED đã qua `WAIT_OPS` → `PROCESSING`; PACKING chưa có
  `shipment_id` → `PROCESSING`, có → `READY`; SHIPPING → `READY`; DELIVERED/CANCELLED → `FINISHED`; MANUAL không đổi quy tắc.
- Test tắt worker (`fashion.order.saga.enabled=false`), gọi `SagaWorker.runOnce()`; `ShiftClock` cho hạn.
- Không stage 10 file `.sh` đang M và `.gitattributes`. Verify theo module, tắt stack compose khi full verify.

## Review Focus

- Hai OPS cùng bấm (một confirm, một cancel) trên cùng version → đúng một `202`, người kia `409 VERSION_CONFLICT`, saga chỉ một
  intent — Task 2 `confirmAndCancelRaceHasOneWinner`.
- OPS bấm xác nhận khi worker đang giữ lease vì đơn vừa hết hạn → confirm `409 ORDER_EXPIRED` (hoặc thua khóa), đơn đi hủy một
  lần, không commit — Task 3 `aWorkerCancellingAtTheDeadlineBeatsALateConfirm`.
- Event shipping đến trùng/không thứ tự (DELIVERED trước DELIVERING, version cũ sau version mới) → đơn chỉ tiến, không lùi,
  `ORDER_COMPLETED` một lần — Task 5 `outOfOrderShippingEventsOnlyMoveForward`.
- Mất response của commit (inventory đã trừ) rồi deadline qua → lần sau cùng key nhận `200 COMMITTED`, `PACKING`, không release
  — Task 3 `aLostCommitResponseAfterTheDeadlineStillPacks`.
- FINANCE gọi list với `q=<số điện thoại>` hoặc đọc detail → 403 cho `q`, detail không có PII — Task 1 `financeSeesNoCustomerContact`.

---

### Task 1: V003, actor trong transitions, admin auth, đọc đơn

**Files:** `db/migration/V003__order_ops.sql`; `saga/OrderTransitions.java`; create `admin/AdminAuth.java`,
`admin/OrderAdminController.java`, `admin/OrderAdminQueries.java`; test `OrderSchemaIntegrationTest`,
`OrderAdminReadIntegrationTest`.

**Interfaces — Produces:**
- `record AdminActor(UUID userId, Set<String> permissions)`; `AdminAuth.require(String authorization, String permission) -> AdminActor`
  (dùng `AccessTokenVerifier` với khóa `fashion.order.jwt.public-keys` có sẵn; 401 `INVALID_ACCESS_TOKEN`, 403 `PERMISSION_REQUIRED`).
- `OrderTransitions.move(UUID orderId, String from, String to, String reason, Actor actor)` với
  `record Actor(String type, UUID id, UUID requestId)`, `Actor.SYSTEM`; overload 4 tham số cũ gọi với `Actor.SYSTEM`. Map
  `EVENTS` thêm `DELIVERED → ORDER_COMPLETED` (payload `OrderCompleted`: identifiers, user_id, order_version, items, delivered_at).
- `OrderAdminQueries.page(String status, String q, String cursor, int limit, boolean pii) -> Page`, `detail(String orderNo, boolean pii) -> Detail`.

- [ ] V003: `audit_logs` (cột như catalog V002 + index `(resource_type, resource_id, created_at)`, `REVOKE UPDATE, DELETE, TRUNCATE ... FROM order_runtime`); `idempotency_requests` (copy `platform-durability/.../durability.sql`); `orders.shipped_at timestamptz`; `order_sagas.shipment_version bigint`.
- [ ] Test schema `auditLogsAreAppendOnly`: runtime insert OK, update/delete → permission denied.
- [ ] Test read: không token 401; token thiếu `order.read` 403; OPS (`order.read`,`order.fulfill`) list `status=CONFIRMED` → chỉ đơn CONFIRMED, sắp `created_at desc`, cursor trang 2 không trùng; `q=order_no` và `q=contact_phone` khớp chính xác; detail có items, history (actor_type, reason), `expires_at`, saga `step/status/last_error`.
- [ ] Test `financeSeesNoCustomerContact`: token `order.read` only → list/detail không có `contact_phone`/`contact_email`/`shipping_address`/`note`, history `reason` null; `q=<phone>` → 403.
- [ ] Test transitions: `move(..., new Actor("ADMIN", u, r))` ghi `actor_type ADMIN`, `actor_id`, `request_id`; overload cũ vẫn SYSTEM (test ORD-02 xanh).
- [ ] Implement; `limit` 1..100 mặc định 20, sai → 400 `VALIDATION_ERROR`; status ngoài enum 400; cursor như `PaymentAdminController`.
- [ ] Verify `./mvnw -B -pl services/order-service verify`; commit `feat(ORD-03): V003, admin auth and OPS order read`.

### Task 2: Lệnh OPS confirm-cod và cancel

**Files:** create `admin/OrderCommands.java`; modify `admin/OrderAdminController.java`; test `OrderCommandsIntegrationTest`.

**Interfaces — Consumes:** Task 1 `AdminAuth`, `OrderTransitions.move(..., Actor)`. **Produces:**
`OrderCommands.confirmCod(AdminActor a, String orderNo, String key, long expectedVersion, String note) -> OrderView`,
`cancel(AdminActor a, String orderNo, String key, long expectedVersion, String reason) -> OrderView`; saga sau confirm:
`step COMMIT_STOCK`, `status RUNNING`, `next_retry_at = now`, `retry_count 0`.

- [ ] Test `anOpsConfirmHandsTheOrderToTheSaga`: đơn CONFIRMED (helper chạy saga ORD-02 với stub OK) → 202, đơn vẫn CONFIRMED, `version + 1`, `processing_status PROCESSING`; saga COMMIT_STOCK/RUNNING; `audit_logs` 1 dòng `ORDER_CONFIRM_COD`, `reason` = note, `actor_id`; không có dòng history mới; `confirmed_at` không đổi.
- [ ] Test confirm lỗi: thiếu key 400; `expected_version` sai 409 `VERSION_CONFLICT`; đã confirm 409 `INVALID_TRANSITION` `status current=CONFIRMED`… (saga đã COMMIT_STOCK); ShiftClock quá `expires_at` → 409 `ORDER_EXPIRED`; note 501 ký tự 400; thiếu `order.fulfill` 403.
- [ ] Test idempotency: cùng key + body → 202 cùng body, audit vẫn 1 dòng; cùng key khác body → 409 `IDEMPOTENCY_KEY_REUSED`.
- [ ] Test `anOpsCancelReleasesAndCloses`: cancel reason "Khách không nghe máy" → 202, CANCELLING, history ADMIN có reason, audit `ORDER_CANCEL`; `runOnce()` → release + close (stub) → CANCELLED, `reason_code OPS_CANCELLED`, outbox `ORDER_CANCELLED` reason `OPS_CANCELLED`.
- [ ] Test cancel lỗi: reason rỗng/thiếu 400; sau confirm (saga COMMIT_STOCK) → 409 `INVALID_TRANSITION`; thiếu `order.cancel` 403; quá hạn nhưng worker chưa chạy → vẫn 202 và hủy.
- [ ] Test `confirmAndCancelRaceHasOneWinner`: hai thread cùng `expected_version` (CountDownLatch) → một 202, một 409 `VERSION_CONFLICT`; saga một intent.
- [ ] Implement: một `TransactionTemplate`, `select ... from orders where order_no = :n for update` rồi `order_sagas for update`; kiểm theo thứ tự status/method/saga step → version → hạn (confirm) → lease còn sống (`lease_until > now` → 409 `INVALID_TRANSITION`); `IdempotencyStore` actor `admin:{user_id}`, operation `confirm-cod`/`cancel`, hash = `Api.sha256` của body chuẩn hóa.
- [ ] Verify; commit `feat(ORD-03): OPS confirm-cod and cancel`.

### Task 3: Saga COMMIT_STOCK và race với hết hạn

**Files:** `deps/InventoryClient.java`; `saga/SagaWorker.java`; test `SagaClientsIntegrationTest`, create `SagaCommitIntegrationTest`.

**Interfaces — Produces:** `InventoryClient.commit(SagaOrder o) -> Outcome` (body `{order_id}`, key `commit:{order_id}`, aud
`inventory-service`; 409 message = code). Saga sau commit OK: `inventory_state COMMITTED`, `step CREATE_SHIPMENT`, đơn PACKING.

- [ ] Test client: key `commit:{id}`, body đúng `CommitRequest`; 409 `RESERVATION_EXPIRED` → REJECTED code đó; 503/timeout → UNKNOWN.
- [ ] Test `aConfirmedOrderIsCommittedAndPacked`: confirm (Task 2) → `runOnce()` → 1 commit call, CONFIRMED→PACKING history SYSTEM, `inventory_state COMMITTED`, step CREATE_SHIPMENT; không outbox mới.
- [ ] Test `inventorySaysExpiredSoTheOrderIsCancelled` (T12a): commit 409 `RESERVATION_EXPIRED` → `inventory_state RELEASED`, CANCELLING `EXPIRED` → `runOnce()` → 0 release (state RELEASED), close → CANCELLED; không bao giờ PACKING.
- [ ] Test `aCommitAfterTheOrderDeadlineStillPacks` (T12b): confirm, ShiftClock quá `expires_at`, commit stub 200 → PACKING (worker không hủy vì hạn order).
- [ ] Test `aWorkerCancellingAtTheDeadlineBeatsALateConfirm` (T12c): ShiftClock quá hạn, worker claim (`claimDue`) và giữ lease; confirm → 409 (`ORDER_EXPIRED`); worker tiếp tục → CANCELLED; 0 commit call.
- [ ] Test `aLostCommitResponseAfterTheDeadlineStillPacks` (T09): commit stub lần 1 delay > timeout, lần 2 `200 COMMITTED`; giữa hai lần ShiftClock quá hạn → lần 2 cùng key, PACKING, 0 release; `inventory_state` sau lần 1 vẫn `HELD`, `retry_count 1`.
- [ ] Test commit `RESERVATION_CONFLICT`/404 → retry, hết ngân sách → MANUAL, đơn vẫn CONFIRMED.
- [ ] Implement: guard hạn ở `advance` chỉ cho `RESERVE`/`CREATE_PAYMENT`/`WAIT_OPS`; `commitStock` dùng intent đếm lần thử không đổi `inventory_state` (thêm nhánh cho `intent` khi `column == null`); move CONFIRMED→PACKING trong cùng `write` với cập nhật saga.
- [ ] Verify; commit `feat(ORD-03): saga commit stock with inventory as arbiter`.

### Task 4: Saga CREATE_SHIPMENT

**Files:** `deps/ShippingClient.java`; `saga/SagaWorker.java`; `saga/SagaOrder.java` (thêm `quoteId`, `toAddress`, `codAmount`
nếu chưa có); test `SagaClientsIntegrationTest`, `SagaCommitIntegrationTest`.

**Interfaces — Produces:** `ShippingClient.create(SagaOrder o) -> Outcome` (POST `/internal/api/v1/shipping/shipments`, key
`shipment:{order_id}`, body `order_id`, `order_no`, `quote_id` = `shipping_quote.quote_id`, `cod_amount` = `total_amount` khi COD,
`to_address` {recipient_name, phone, province_code, district_code?, ward_code, address_line} từ `shipping_address`).

- [ ] Test client: body đúng snapshot (so với `ShipmentCreate`), key cố định; 409 `QUOTE_MISMATCH` → REJECTED; timeout → UNKNOWN.
- [ ] Test `aPackedOrderGetsItsSelfShipment`: sau PACKING `runOnce()` → `shipment_id` lưu, saga `DONE`, đơn PACKING, `processing_status READY`.
- [ ] Test `aRefusedShipmentGoesManualAndKeepsTheStock`: 409 `SHIPMENT_CANCELLED` → MANUAL `last_error` chứa code, 0 release, đơn PACKING.
- [ ] Test `aLostShipmentResponseIsRetriedWithTheSameBody`: timeout rồi 200 → hai request cùng key + body byte-equal; một `shipment_id`.
- [ ] Implement step `CREATE_SHIPMENT` (intent không đổi state column; OK → `shipment_id`, `status DONE`, `step DONE`).
- [ ] Verify; commit `feat(ORD-03): saga creates the SELF shipment`.

### Task 5: `shipping.events` → SHIPPING/DELIVERED

**Files:** create `orders/ShippingEvents.java`, `orders/ShippingEventsListener.java`; `orders/OrderService.java` (mapping
`processing_status`); `application.yaml` (không đổi group); test `ShippingEventsIntegrationTest`,
`ShippingEventsListenerIntegrationTest`, `OrderReadIntegrationTest`.

**Interfaces — Consumes:** Task 1 `OrderTransitions.move` (DELIVERED → `ORDER_COMPLETED`). **Produces:**
`ShippingEvents.apply(EventEnvelope) -> Object` (inbox TX, như `PaymentEvents.apply`).

- [ ] Test happy: PACKING + `SHIPMENT_STATUS_UPDATED` DELIVERING (`handed_over_at` có, version 3) → SHIPPING, `shipped_at = handed_over_at`, `shipment_version 3`; DELIVERED (version 4, `provider_occurred_at`) → DELIVERED, `delivered_at`, outbox `ORDER_COMPLETED` 1 dòng (payload theo schema).
- [ ] Test `outOfOrderShippingEventsOnlyMoveForward`: DELIVERED (v4) khi còn PACKING → hai history PACKING→SHIPPING→DELIVERED; DELIVERING (v3) đến sau → bỏ qua; trùng `event_id` → xử lý một lần; `ORDER_COMPLETED` một lần.
- [ ] Test: `FAILED`/`RETURNING`/`PICKING` → chỉ ghi `shipment_version`, status giữ; đơn CANCELLED nhận DELIVERED → không chuyển, log warn; `SHIPMENT_CREATED` khi saga chưa có `shipment_id` → ghi `shipment_id`; `COD_COLLECTED`/`COD_REMITTED` → chỉ processed; payload hỏng → `PermanentEventException`.
- [ ] Test read: PACKING không shipment → `PROCESSING`; có → `READY`; SHIPPING `READY`; DELIVERED `FINISHED`; CONFIRMED sau confirm `PROCESSING`.
- [ ] Test Kafka listener topic `shipping.events` group `order-service`, ACK sau commit (mẫu `PaymentEventsListenerIntegrationTest`).
- [ ] Implement: lock `orders for update` theo `order_id`, so `shipment_version` của `order_sagas`; `delivered_at = coalesce(provider_occurred_at, now)`.
- [ ] Verify; commit `feat(ORD-03): order follows shipping events to DELIVERED`.

### Task 6: Gateway, contract, smoke end-to-end, mutation, tài liệu

**Files:** `services/gateway/src/main/resources/application.yaml` (+ test route của gateway); `contracts/openapi/order.yaml`
(hoặc `order-admin` theo mẫu payment) + contract test; `contracts/events/registry.json` nếu thiếu producer `ORDER_COMPLETED`;
`scripts/smoke-local.sh`; docs.

- [ ] Gateway route `order-admin` `Path=/admin/api/v1/orders/**` → order-service, cùng filter/CSRF như `payment-admin`; test gateway route (mẫu test payment-admin).
- [ ] Contract: 4 endpoint admin, `reason_code` thêm `OPS_CANCELLED`, mô tả `processing_status` mới; chạy contract test.
- [ ] Smoke sau bước ORD-02: `local_ops_token order.read order.fulfill` → GET detail lấy version → confirm-cod → poll (≤ 20 × 1 s) tới `PACKING READY` → shipping self-events ASSIGN/HANDED_OVER/DELIVERED/COD_COLLECTED (token `shipping.self_event`, mẫu SHP-02) → poll đơn `DELIVERED` → FINANCE `cod-settlements` (mẫu PAY-01) → payment `SUCCESS` → poll đơn có `paid_at` → `ORD-03 COD end-to-end PASS`.
- [ ] Mutation (tạm sửa, chạy test, hoàn nguyên): bỏ giới hạn step của guard hạn (T12b fail); key commit ngẫu nhiên (T09 fail); move PACKING trước khi commit OK (T12a fail); bỏ so `shipment_version` (out-of-order fail).
- [ ] Docs: 03 (admin orders implemented, `reason_code`, order.events `ORDER_COMPLETED`), 05 (V003), 06 §5.2 (đoạn "ORD-03 hiện thực"), 08 (bảng "ORD-03 — quyết định" từ spec §11, gồm tách hủy CONFIRMED khỏi ORD-04), 10 (ORD-03 In review, REQ PAY-03 hoãn), 12 (smoke), 13 nếu cần (redact FINANCE), 14 §3 (processing_status mới), README order-service + gốc, CLAUDE.md, evidence `docs/evidence/ord-03-local-2026-10-21.md`.
- [ ] Verify order-service + gateway + contracts, `py -3 -B scripts/check_docs.py`, unittest docs checker, `git diff --check`; commit `docs(ORD-03): contract, gateway, smoke, docs and evidence`.
