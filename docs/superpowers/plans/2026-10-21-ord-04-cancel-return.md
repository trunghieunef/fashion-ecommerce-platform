# ORD-04 Cancel/return Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** khách tự hủy đơn chờ OPS; OPS hủy sau xác nhận trước bàn giao (vận đơn → kho → payment); hoàn hàng sau bàn giao
và trả hàng sau giao qua phiếu kiểm đếm cộng kho đúng một lần; follow-up ORD-02/03.

**Architecture:** order-service: lõi lệnh dùng chung (khóa `order_sagas` → `orders`, kiểm version → trạng thái → lease) cho lối
vào admin và khách; `SagaWorker.compensate` thêm bước hủy vận đơn và trả kho (`/returns` khi release báo `restore_required`),
step mới `WAIT_RETURN`, `CLOSE_PAYMENT`; consumer `shipping.events` thêm nhánh hoàn/giao-sau-hủy; phiếu `order_returns` +
task nền `ORDER_RETURN` cộng kho. HTTP ngoài transaction, intent trước, CAS lease.

**Tech Stack:** Java 21, Spring Boot 4.0, PostgreSQL 17.11 (Testcontainers), Kafka, JDK `HttpServer` stub (`StubServers`).

**Spec:** [ORD-04 design](../specs/2026-10-21-ord-04-cancel-return-design.md)

## Global Constraints

- Thứ tự khóa toàn service: `order_sagas` rồi `orders`. Không HTTP trong transaction; intent trước HTTP; kết quả `SagaRepository.write`.
- Intent bù trừ tính lần thử + backoff, **không** đổi cột trạng thái tài nguyên (`inventory_state` không bao giờ thành `UNKNOWN` ở bù trừ).
- Thứ tự bù trừ: vận đơn (khi `inventory_state ∈ {COMMITTED, RESTORED}` hoặc `shipment_id` có hoặc `shipment_state ≠ NONE`) → kho → payment → `CANCELLED`. Vận đơn chưa `CANCELLED` thì không gọi release/returns (T16).
- Key cố định: `shipment-cancel:{order_id}`, `cancel:{order_id}` (returns khi hủy, `evidence_ref = order-cancel:{order_id}`), `return:{return_id}`, `close:{order_id}`, `release:{order_id}`.
- `reason` gửi shipping/inventory = mã `cancel_reason`; lời người nhập chỉ ở history/audit/phiếu.
- `cancel_reason` mới: `CUSTOMER_CANCELLED`, `RETURNED`. Step mới: `WAIT_RETURN`, `CLOSE_PAYMENT`. `shipment_state`: NONE/CREATED/CANCELLED/HANDED_OVER.
- Khách hủy: chỉ `CONFIRMED` + `FULFILL`/`WAIT_OPS`; 404 khi không thuộc actor; 409 `INVALID_TRANSITION` khác; không `audit_logs`; history actor `CUSTOMER`.
- OPS hủy: `CONFIRMED` (`WAIT_OPS`/`COMMIT_STOCK`) và `PACKING` (`CREATE_SHIPMENT`/`DONE`), saga status bất kỳ trừ lease sống (409 `SAGA_BUSY`); `SHIPPING`+ → 409.
- `SHIPMENT_HANDED_OVER` → `shipment_state HANDED_OVER`, saga `WAITING`/`WAIT_RETURN`, không trả kho.
- Phiếu: quyền `order.return`; đơn `RETURNING`/`RETURNED`/`DELIVERED`; Σ received mọi phiếu ≤ quantity; `restock ≤ received`; chỉ SKU `restock > 0` gửi inventory.
- `processing_status`: `RETURNING` → `READY`, `RETURNED` → `FINISHED`. Đơn đã thu tiền → `MANUAL PAID_NEEDS_REFUND`, không CANCELLED/RETURNED.
- Không stage 10 file `.sh` đang M và `.gitattributes`. Docs tiếng Việt, comment code tiếng Anh. Test tắt worker, gọi `runOnce()`.

## Review Focus

- OPS hủy đúng lúc worker đang tạo vận đơn (create mất response, shipment đã tồn tại ở shipping) → shipment-cancel vẫn được gọi trước khi trả kho, kho trả đúng một lần — Task 3 `aCancelDuringALostShipmentCreateCancelsTheShipmentFirst`.
- Hai OPS gửi hai phiếu kiểm đếm cùng lúc cho cùng đơn với tổng vượt quantity → một phiếu thắng, phiếu kia 409, kho chỉ cộng phần thắng — Task 5 `concurrentSlipsNeverExceedTheQuantity`.
- Hàng hoàn về rồi OPS gửi phiếu, đơn đã thu COD (`payment_state = PAID`) → kho vẫn cộng, đơn dừng `MANUAL PAID_NEEDS_REFUND` chứ không `RETURNED` — Task 5 `aCollectedReturnStillRestocksButNeedsARefund`.
- Khách bấm hủy hai lần (mất response) với cùng key sau khi đơn đã CANCELLED → replay đúng response, không 409 — Task 1 `aCustomerCancelReplayReturnsTheSameResponse`.
- Event shipping `CANCELLED` do chính order hủy và event shipment lạ (shipment_id khác) → không cảnh báo, không chuyển trạng thái — Task 4 `ownCancelAndForeignShipmentEventsAreQuiet`.

---

### Task 1: V004, lõi lệnh dùng chung, khách tự hủy

**Files:** `db/migration/V004__order_returns.sql`; `admin/OrderCommands.java` (tách lõi); create `orders/CustomerCancel.java`
(hoặc method trong `OrderController`/`OrderService` theo mẫu create); `orders/OrderController.java`; `orders/OrderService.java`
(`allowed_actions`); `saga/OrderTransitions.java` (actor `CUSTOMER`); test `OrderSchemaIntegrationTest`, create
`CustomerCancelIntegrationTest`, `OrderReadIntegrationTest`.

**Interfaces — Produces:** lõi `OrderCommands.execute(Command c) -> JsonNode` với
`record Command(String idempotencyActor, String operation, String orderNo, String key, long expectedVersion, String text,
Predicate<Locked> admits, Work work, Audit audit /* null = none */, Function<UUID, JsonNode> view)`; `OrderTransitions.Actor`
`CUSTOMER` (`new Actor("CUSTOMER", userIdOrNull, requestId)`). Admin `confirmCod`/`cancel` giữ nguyên signature.

- [ ] V004: `order_returns` (id uuid PK, order_id FK, operation_key varchar(128), reason text, evidence_ref text, actor_id uuid, status varchar(32) DEFAULT 'REQUESTED' CHECK (REQUESTED/PROCESSING/COMPLETED/MANUAL), request_id uuid, created_at, updated_at, UNIQUE(order_id, operation_key)); `order_return_items` (return_id FK, order_item_id FK, received_quantity int > 0, restock_quantity int >= 0, condition text, PK(return_id, order_item_id), CHECK restock <= received); `order_sagas.shipment_state varchar(16) NOT NULL DEFAULT 'NONE' CHECK (NONE/CREATED/CANCELLED/HANDED_OVER)`, backfill `CREATED` where `shipment_id is not null`; REVOKE DELETE/TRUNCATE trên hai bảng, REVOKE UPDATE trên `order_return_items` khỏi `order_runtime`. Reset test xóa hai bảng mới.
- [ ] Test schema `returnTablesGuardQuantities`: restock > received → lỗi; trùng (order_id, operation_key) → lỗi; runtime update items → denied.
- [ ] Test `aMemberCancelsAnOrderWaitingForTheShop`: đơn CONFIRMED/WAIT_OPS → `POST /api/v1/orders/{no}/cancel` Bearer, key, `{expected_version}` → 202 CANCELLING, history actor `CUSTOMER` actor_id = user; `runOnce()` → release + close → CANCELLED `reason_code CUSTOMER_CANCELLED`; 0 dòng `audit_logs`.
- [ ] Test guest bằng cookie `order_access` → 202; member khác / cookie đơn khác / không credential → 404 `ORDER_NOT_FOUND`.
- [ ] Test `aCustomerCannotCancelOnceTheShopConfirmed`: sau OPS confirm → 409 `INVALID_TRANSITION` `status current=CONFIRMED`; PENDING → 409; version cũ → 409 `VERSION_CONFLICT`; reason 501 ký tự → 400.
- [ ] Test `aCustomerCancelReplayReturnsTheSameResponse`: cùng key + body sau khi đã CANCELLED → 202 cùng body; khác body → 409 `IDEMPOTENCY_KEY_REUSED`.
- [ ] Test read: `allowed_actions` = `["CANCEL"]` khi CONFIRMED/WAIT_OPS, `[]` sau confirm và khi CANCELLED.
- [ ] Implement; idempotency actor = `orders.actor_key` của đơn đã xác thực, operation `customer-cancel`; ORD-03 admin tests vẫn xanh.
- [ ] Verify `./mvnw -B -pl services/order-service verify`; commit `feat(ORD-04): V004, shared command core, customer cancel`.

### Task 2: OPS hủy sau xác nhận (nhận lệnh)

**Files:** `admin/OrderCommands.java`; test `OrderCommandsIntegrationTest`.

**Interfaces — Consumes:** Task 1 `execute`. **Produces:** OPS cancel nhận `CONFIRMED` (`WAIT_OPS`/`COMMIT_STOCK`) và `PACKING`
(`CREATE_SHIPMENT`/`DONE`) ở mọi saga status; transition `{status} → CANCELLING` từ trạng thái đọc được dưới khóa; saga
`intent CANCEL, step COMPENSATE, status COMPENSATING, retry_count 0, next_retry_at now`, lease xóa.

- [ ] Test `opsCancelsAPackingOrder`: PACKING + saga DONE (có shipment_id) → 202 CANCELLING, history ADMIN, audit `ORDER_CANCEL`, saga COMPENSATE.
- [ ] Test `opsCancelsAStuckSaga`: PACKING + saga `MANUAL` ở CREATE_SHIPMENT → 202; CONFIRMED + saga `COMMIT_STOCK` RUNNING → 202.
- [ ] Test refusals: SHIPPING → 409 `INVALID_TRANSITION` `current=SHIPPING`; CANCELLING → 409; lease sống → 409 `SAGA_BUSY`.
- [ ] Implement admission theo bảng spec §4; confirm-cod giữ nguyên điều kiện ORD-03.
- [ ] Verify; commit `feat(ORD-04): OPS cancel after confirmation`.

### Task 3: Bù trừ — hủy vận đơn, trả kho, intent bù trừ

**Files:** `deps/ShippingClient.java` (`cancel`), `deps/InventoryClient.java` (`restore`), `saga/SagaWorker.java`
(`compensate`, `createShipment` đặt `shipment_state CREATED`, metric tag `step`); test `SagaClientsIntegrationTest`, create
`SagaCancelAfterConfirmIntegrationTest`, `SagaCompensationIntegrationTest`, `SagaManualIntegrationTest`.

**Interfaces — Produces:** `ShippingClient.cancel(SagaOrder o, String reason) -> Outcome` (POST
`/internal/api/v1/shipping/shipments/by-order/{order_id}/cancel`, key `shipment-cancel:{order_id}`, body = create snapshot +
`reason`; 409 message = code); `InventoryClient.restore(SagaOrder o, String operationKey, String reason, String evidenceRef) ->
Outcome` (POST `/internal/api/v1/inventory/returns`, `Idempotency-Key = operation_key`, body `{order_id, operation_key, reason,
evidence_ref, items:[{sku, quantity}]}`); `InventoryClient.release` trả OK kèm `data.restore_required` (không còn đổi thành
REJECTED "COMMITTED").

- [ ] Test clients: cancel body = snapshot create + reason, key cố định; 409 `SHIPMENT_HANDED_OVER` → REJECTED code đó; restore key = operation_key, body đúng `ReturnRequest`; release COMMITTED → OK `restore_required true`.
- [ ] Test `cancellingAPackedOrderCancelsTheShipmentThenRestoresThenCloses`: OPS cancel (Task 2) → `runOnce()` nhiều lượt → thứ tự request: shipment-cancel, release, returns (`cancel:{id}`, đủ SKU, `evidence_ref order-cancel:{id}`), close → CANCELLED `OPS_CANCELLED`; `shipment_state CANCELLED`, `inventory_state RESTORED`.
- [ ] Test **T16** `aShipmentCancelInDoubtNeverRestoresStock`: shipment-cancel timeout → 0 release/returns, `inventory_state COMMITTED`; lượt sau 200 → mới release/returns.
- [ ] Test `aHandedOverShipmentWaitsForTheReturn`: shipment-cancel 409 `SHIPMENT_HANDED_OVER` → 0 release/returns, `shipment_state HANDED_OVER`, saga `WAITING`/`WAIT_RETURN`, đơn CANCELLING; metric/log.
- [ ] Test `aCancelDuringALostShipmentCreateCancelsTheShipmentFirst`: saga CREATE_SHIPMENT có intent (shipment_id null, inventory COMMITTED) → OPS cancel → shipment-cancel được gọi trước release.
- [ ] Test commit đang bay: inventory_state HELD, release trả `restore_required false` → `RELEASED`, 0 returns, 0 shipment-cancel; trả `restore_required true` → returns.
- [ ] Test `aCrashAfterACompensationIntentStillEndsManual` (ORD-02 follow-up): stub treo mọi bước bù trừ, max-retries 3 → MANUAL sau 3 lượt, `inventory_state` không thành UNKNOWN.
- [ ] Test ORD-02 cũ: `committedStockIsNeverTreatedAsReleased` đổi kỳ vọng — COMMITTED giờ được trả bằng returns (không MANUAL); các test bù trừ ORD-02 còn lại xanh.
- [ ] Implement `compensate` theo spec §4 (một nghĩa vụ mỗi lượt); intent bù trừ dùng `intent(c, saga, null)`; `PAID` → MANUAL `PAID_NEEDS_REFUND` như cũ.
- [ ] Verify; commit `feat(ORD-04): compensation cancels the shipment and restores committed stock`.

### Task 4: `shipping.events` — hoàn hàng, giao sau hủy, đối chiếu shipment

**Files:** `orders/ShippingEvents.java`; `orders/OrderService.java` (`processing_status` RETURNING → READY); test `ShippingEventsIntegrationTest`, `OrderReadIntegrationTest`.

**Interfaces — Produces:** đơn `RETURNING` với saga `intent CANCEL, step WAIT_RETURN, status WAITING` (đơn SHIPPING chưa có
`cancel_reason` → `RETURNED`); transition `CANCELLING → SHIPPING`.

- [ ] Test `aFailedDeliveryComingBackMovesTheOrderToReturning`: SHIPPING + event FAILED (chỉ version) rồi RETURNING → đơn RETURNING, history SYSTEM, saga WAIT_RETURN, `cancel_reason RETURNED`; `processing_status READY`.
- [ ] Test: CANCELLING (saga WAIT_RETURN, shipment HANDED_OVER) + RETURNING → RETURNING, `cancel_reason` giữ `OPS_CANCELLED`.
- [ ] Test `aCancelledOrderThatStillGotDeliveredIsDelivered`: CANCELLING (WAIT_RETURN) + DELIVERED → history CANCELLING→SHIPPING→DELIVERED, `ORDER_COMPLETED` một lần, saga DONE, log warn.
- [ ] Test `ownCancelAndForeignShipmentEventsAreQuiet`: CANCELLED event trên đơn CANCELLING/CANCELLED → không warn, không chuyển; event có `shipment_id` khác saga → bỏ, không ghi version.
- [ ] Test RETURNED event → chỉ version, đơn vẫn RETURNING, kho không gọi.
- [ ] Implement theo spec §5 (lock `order_sagas` → `orders` như hiện tại).
- [ ] Verify; commit `feat(ORD-04): shipping events drive returns and late deliveries`.

### Task 5: Phiếu kiểm đếm, task cộng kho, đóng payment → RETURNED

**Files:** create `admin/OrderReturns.java` (lệnh + đọc phiếu), `saga/OrderReturnWorker.java` (mẫu `CartCleanupWorker`);
`admin/OrderAdminController.java` (`POST /{order_no}/returns`, detail kèm `returns`); `saga/SagaWorker.java` (step
`CLOSE_PAYMENT`); `orders/OrderService.java` (`RETURNED` → FINISHED); test create `OrderReturnsIntegrationTest`.

**Interfaces — Consumes:** Task 3 `InventoryClient.restore`; Task 4 saga `WAIT_RETURN`. **Produces:** task kind `ORDER_RETURN`
(business key `return_id`, payload `{return_id}`); saga `CLOSE_PAYMENT` → close (key `close:{order_id}`) → `RETURNING →
RETURNED`, saga DONE.

- [ ] Test `aCountedReturnRestocksAndFinishesTheOrder`: RETURNING → POST returns (`order.return`, key, `{expected_version, reason, evidence_ref, items:[{order_item_id, received 2, restock 1, condition "rách"}]}`) → 202, phiếu REQUESTED + items, audit `ORDER_RETURN`; `OrderReturnWorker.runOnce()` → inventory returns key `return:{return_id}` chỉ SKU đó quantity 1 → phiếu COMPLETED, saga CLOSE_PAYMENT; `SagaWorker.runOnce()` → close → RETURNED, `processing_status FINISHED`.
- [ ] Test `aReturnAfterDeliveryKeepsTheOrderDelivered`: DELIVERED → phiếu → restock, đơn DELIVERED, 0 close, 0 `ORDER_COMPLETED` mới.
- [ ] Test T17: cùng key + body → 202 replay, một phiếu, inventory một request hiệu lực; phiếu thứ hai vượt Σ quantity → 409 (`RETURN_EXCEEDS_ORDERED`), không ghi; restock > received → 400; item không thuộc đơn → 400; đơn SHIPPING/CANCELLED → 409 `INVALID_TRANSITION`; thiếu `order.return` → 403; FINANCE → 403.
- [ ] Test `concurrentSlipsNeverExceedTheQuantity`: hai thread, hai key, mỗi phiếu received = quantity → một 202, một 409.
- [ ] Test phiếu restock 0 toàn bộ → COMPLETED không gọi inventory; inventory `RETURN_EXCEEDS_COMMITTED` → phiếu + task MANUAL; inventory 503 → task retry.
- [ ] Test `aCollectedReturnStillRestocksButNeedsARefund`: `payment_state PAID` → kho cộng, saga MANUAL `PAID_NEEDS_REFUND`, đơn vẫn RETURNING.
- [ ] Test phiếu muộn trên RETURNED → chỉ cộng kho, trạng thái giữ.
- [ ] Implement: lệnh trong một TX khóa `order_sagas` → `orders` → tổng received hiện có; phiếu COMPLETED trên RETURNING đặt saga `CLOSE_PAYMENT`/RUNNING cùng TX với `COMPLETED`; `@Scheduled` worker bật/tắt cùng `fashion.order.saga.enabled`.
- [ ] Verify; commit `feat(ORD-04): OPS return slips restock once and close returned orders`.

### Task 6: Contract, Gateway, smoke, mutation, tài liệu

**Files:** `contracts/openapi/order.yaml` + contract tests; `scripts/smoke-local.sh`; docs.

- [ ] Contract: customer cancel (202/400/404/409), admin returns (202/400/403/409), admin cancel mở rộng, `allowed_actions`, `reason_code` `CUSTOMER_CANCELLED`/`RETURNED`, `processing_status` RETURNING/RETURNED, detail `returns`. Gateway: route public/admin đã phủ `/api/v1/orders/**`, `/admin/api/v1/orders/**` — thêm test cho hai path mới.
- [ ] Smoke sau bước ORD-03: đơn A → confirm → PACKING → OPS cancel → poll CANCELLED, tồn kho SKU bằng trước khi đặt → `ORD-04 cancel after confirm PASS`; đơn B → confirm → ASSIGN/HANDED_OVER/DELIVERY_FAILED/RETURN_STARTED/RETURNED → poll RETURNING → POST returns → poll RETURNED, tồn kho cộng đúng → `ORD-04 return PASS`. Khách hủy đơn C (guest cookie) → CANCELLED.
- [ ] Mutation (tạm sửa, chạy test, khôi phục): trả kho trước khi shipment-cancel OK; bỏ guard Σ received; key returns ngẫu nhiên; ghi `RELEASED` khi `restore_required`.
- [ ] Docs: 03 (§2 customer cancel 202/409, §3 admin cancel/returns), 05 (V004), 06 §5.3 (cạnh `CANCELLING → SHIPPING`), §5.4 ("ORD-04 hiện thực", key `cancel:{order_id}`), 08 ("ORD-04 — quyết định" từ spec §11/§12 + phán quyết SDD), 10 (ORD-04 In review), 12, 13, 14 §3, README order-service + gốc, CLAUDE.md, evidence `docs/evidence/ord-04-local-2026-10-21.md`.
- [ ] Verify order-service + gateway, contract, docs checker, `git diff --check`; commit `docs(ORD-04): contract, smoke, docs and evidence`.
