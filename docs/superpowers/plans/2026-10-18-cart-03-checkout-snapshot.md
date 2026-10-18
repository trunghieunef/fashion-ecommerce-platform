# CART-03 Checkout snapshot/cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** cart-service có snapshot/cleanup internal cho order-service và tạm tính có nhãn trong GET cart.

**Architecture:** Hai route `/internal/api/v1/carts/...` trên cart-service, xác thực service token ADR-19
(chỉ `order-service`) như `InternalCallers` của inventory; actor qua header `Cart-Actor`. Cleanup là một
transaction: idempotency theo `order_id` → lock giỏ → xóa item khớp version+quantity. Không migration.

**Tech Stack:** Java 21, Spring Boot 4.0, JdbcClient, platform-security, platform-durability, Testcontainers.

**Spec:** [CART-03 design](../specs/2026-10-18-cart-03-checkout-snapshot-design.md)

## Global Constraints

- Caller allowlist chỉ `order-service`, `aud=cart-service`; thứ tự 401 `INVALID_SERVICE_TOKEN` → 403 `CALLER_NOT_ALLOWED` → header/body.
- `Cart-Actor`: `user:<uuid chuẩn lowercase>` | `guest:<[0-9a-f]{64}>`; khác → 400 `INVALID_ACTOR`.
- Không gọi HTTP trong transaction; giỏ vẫn ACTIVE; không migration; mọi response no-store.
- Cleanup: `Idempotency-Key` = `order_id`; idempotency `actor_key="service:order-service"`, `operation="cart.checkout-cleanup:<cart_id>"`.
- Không stage 10 file `.sh` đang M và `.gitattributes`.

## Review Focus

- `Cart-Actor` member dùng UUID viết hoa/không chuẩn → 400 (không 404 lẫn với giỏ người khác) — test trong Task 1.
- Cleanup có `item_id` trùng trong body → 400, không xóa hai lần — test Task 2.
- Order retry cùng `order_id` sau khi khách thêm lại cùng variant (item mới, id mới) → response cũ, item mới không bị xóa — test Task 2.
- Cleanup 404 rollback bản ghi idempotency (retry với actor đúng vẫn chạy được) — test Task 2.
- Tràn `effective_price × quantity` → `subtotal_preview: null`, không 500 — test Task 3.

---

### Task 1: Internal auth + snapshot

**Files:**
- Create: `services/cart-service/src/main/java/vn/fashion/cart/internal/InternalCallers.java`, `.../internal/InternalCartController.java`
- Modify: `services/cart-service/src/main/resources/application.yaml` (`fashion.cart.internal-callers: ${CART_INTERNAL_CALLERS:}`)
- Test: `services/cart-service/src/test/java/vn/fashion/cart/CartSnapshotIntegrationTest.java`; `CartTestSupport` thêm `serviceToken(String caller)` (issue `aud=cart-service` bằng `KEY`) và property `fashion.cart.internal-callers = "order-service:<pub>,cart-service:<pub>"`.

**Interfaces:**
- Produces: `InternalCallers.require(String authorization)` (allowlist cố định `order-service`); `InternalCallers.actor(String header) -> CartActor`; `GET /internal/api/v1/carts/{cartId}/snapshot?expected_version=` → `{cart_id, version, owner, items[{item_id, variant_id, sku, quantity, item_version, price_snapshot, catalog_version}]}`.

- [ ] Step 1: test `snapshotRequiresOrderServiceToken` (không token → 401 INVALID_SERVICE_TOKEN; `cart-service` → 403 CALLER_NOT_ALLOWED), `snapshotRejectsBadActor` (`user:ABC…` viết hoa, `guest:xyz`, thiếu header → 400 INVALID_ACTOR), `snapshotIsOwnerOnly` (actor khác / guest hết hạn / id sai định dạng → 404 CART_NOT_FOUND), `snapshotChecksVersion` (thiếu → 400 VERSION_REQUIRED; lệch → 409 VERSION_CONFLICT với `current=`), `snapshotReturnsItemsWithoutWrites` (items đúng thứ tự, `updated_at`/`expires_at` không đổi, stub catalog/inventory không nhận request). Seed giỏ/item bằng SQL trực tiếp.
- [ ] Step 2: chạy `./mvnw -pl services/cart-service -Dtest=CartSnapshotIntegrationTest test` → FAIL.
- [ ] Step 3: implement; ownership dùng `CartRepository.findOwned`, version lỗi dùng cùng Problem như `CartItemsService.requireVersion` (đưa về package-visible static, không chép).
- [ ] Step 4: chạy lại → PASS.

### Task 2: Checkout cleanup

**Files:**
- Create: `services/cart-service/src/main/java/vn/fashion/cart/internal/CheckoutCleanupService.java`
- Modify: `InternalCartController` (POST route), `CartRequests` (record `Cleanup(UUID orderId, List<CleanupItem> items)`, `CleanupItem(UUID itemId, Long itemVersion, Integer quantity)`)
- Test: `services/cart-service/src/test/java/vn/fashion/cart/CheckoutCleanupIntegrationTest.java`

**Interfaces:**
- Consumes: `InternalCallers`, `CartRepository.lockOwned`, `IdempotencyStore`.
- Produces: `CheckoutCleanupService.cleanup(CartActor, UUID cartId, String key, Cleanup) -> Object` (view mới hoặc JSON đã lưu); response `{cart_id, cart_version, order_id, removed[], retained[{item_id, reason:"ITEM_CHANGED", current_quantity}], missing[]}`.

- [ ] Step 1: tests — `t18KeepsChangedAndNewItems` (A,B,C snapshot; khách PUT A, DELETE B, add D qua API public; cleanup → removed=[C], retained=[A ITEM_CHANGED current_quantity], missing=[B], D còn, version +1); `retrySameOrderDoesNotDeductTwice` (cleanup; khách add lại variant C → item mới; cleanup lặp → body giống hệt, item mới còn); `sameOrderDifferentBodyIs409`; `keyMustEqualOrderId` (thiếu → 400 IDEMPOTENCY_KEY_REQUIRED, khác → 400 IDEMPOTENCY_KEY_MISMATCH); `validation` (items rỗng, 101 item, item_id trùng, qty 0/100, version âm → 400 VALIDATION_ERROR); `nothingRemovedKeepsVersion`; `parallelSameOrderRemovesOnce` (2 luồng, cả hai 200 cùng body); `notOwnedRollsBackKey` (actor sai → 404, sau đó actor đúng cùng order_id → 200); `authBeforeBody` (token sai + body hỏng → 401).
- [ ] Step 2: chạy `-Dtest=CheckoutCleanupIntegrationTest` → FAIL.
- [ ] Step 3: implement theo spec §4; hash = SHA-256 của `actor.key() + "|" + items sắp theo item_id (id:version:qty)`; mọi việc trong `TransactionTemplate`.
- [ ] Step 4: chạy lại → PASS; chạy cả module cart-service.

### Task 3: subtotal_preview + shipping_fee_status

**Files:**
- Modify: `services/cart-service/src/main/java/vn/fashion/cart/cart/CartView.java` (thêm `Long subtotalPreview`, `String shippingFeeStatus = "NOT_CALCULATED"`)
- Test: `CartPreviewIntegrationTest` thêm case.

- [ ] Step 1: tests `subtotalWhenPreviewOk` (2 item, 1 inactive → chỉ tính active), `subtotalNullWhenUnavailableOrEmpty`, `subtotalNullOnOverflow` (price `Long.MAX_VALUE/2`, qty 3), `shippingFeeNotCalculated`.
- [ ] Step 2: FAIL → implement trong `CartView.of` (`Math.multiplyExact/addExact`, ArithmeticException → null) → PASS.

### Task 4: Contract, config, docs

**Files:**
- Create: `contracts/openapi/cart-internal.yaml`; `docs/evidence/cart-03-local-2026-10-18.md`
- Modify: `contracts/openapi/cart.yaml` (2 field), `infra/local/compose.yaml` (`CART_INTERNAL_CALLERS: ${CART_INTERNAL_CALLERS:-}`), `infra/local/.env.example`, `services/cart-service/README.md`, `docs/design/03_interfaces.md` (§3 GET cart, §4 hai dòng internal), `docs/design/06_service_flows.md` (§4 Sau tạo đơn/Preview), `docs/design/08_decisions.md` (câu hỏi mở cookie guest cho ORD-01), `docs/delivery/10_backlog.md` (CART-03), `README.md`/`CLAUDE.md` nếu liệt kê năng lực cart.

- [ ] Step 1: viết contract + examples; `bash scripts/validate-contracts.sh` PASS.
- [ ] Step 2: cập nhật docs; `python3 -B scripts/check_docs.py` và unittest docs PASS; `git diff --check`.
- [ ] Step 3: commit từng phần (không stage file .sh/.gitattributes).

### Task 5: Verify, review, PR

- [ ] Tắt compose; `./mvnw -pl services/cart-service verify`, `./mvnw -pl services/gateway verify`; `bash scripts/scan-secrets.sh`.
- [ ] Review toàn nhánh bằng subagent; sửa Critical/Important; chạy lại test module.
- [ ] Cập nhật evidence, push `dev`, mở PR vào `main` (không merge).
