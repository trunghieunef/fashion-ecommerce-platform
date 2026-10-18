# CART-03 — snapshot và cleanup giỏ cho order (cart-service)

`TASK:CART-03` · `REQ:CART-05/06` · dependency CART-01 (Done local), PLT-03 (primitives idempotency đã có).
Trạng thái: **đã duyệt** 2026-10-18; plan [CART-03](../plans/2026-10-18-cart-03-checkout-snapshot.md). Chủ dự án giao agent tự chốt mặc định; lựa chọn của agent ghi ở §8.
Merge giỏ (CART-02), order quote/create (ORD-01), fee estimate của shipping (SHP-01) và UI (WEB-02/03)
nằm ngoài phần này.

## 1. Mục tiêu

- Order-service đọc được **snapshot giỏ** (items + version) của đúng actor, đúng version, để quote/tạo đơn
  từ server chứ không từ items client gửi (06 §4, ORD-01).
- Sau khi tạo đơn, order-service gọi **checkout-cleanup** với `order_id` và items đã snapshot: chỉ xóa item
  còn khớp snapshot; item khách thêm/sửa trong lúc chờ được giữ và báo lại; retry cùng `order_id` không trừ
  lần hai (T18, CART-06).
- Giỏ cung cấp **tạm tính** có nhãn và nói rõ phí ship chưa tính, để UI không hiển thị tổng như giá chốt
  (CART-05, fee-preview integration contract).

Không làm: order-service, background task cleanup phía order (thuộc ORD-01: order insert `background_tasks`
cùng TX tạo đơn, 06 §12), gọi shipping/promotion từ cart, job dọn giỏ guest hết hạn (O06 chưa chốt), Redis,
event Kafka, UI.

Anti-goals: cleanup xóa sạch giỏ hiện tại; cleanup trừ hai lần khi order retry; item khách vừa sửa bị mất;
route internal lộ qua Gateway; tin actor do client gửi; tổng tạm tính bị trình bày như tổng đã chốt.

## 2. Ranh giới và xác thực

- Hai route mới trên cart-service, chỉ ở prefix `/internal/api/v1/carts/...` (Gateway chỉ route
  `/api/v1/carts/**`, nên internal không public — có test Gateway trả 404 cho path internal).
- Service token ADR-19, `aud=cart-service`, caller allowlist **chỉ `order-service`**. Thứ tự kiểm như
  inventory: token (401 `INVALID_SERVICE_TOKEN`) → caller (403 `CALLER_NOT_ALLOWED`) → header/body.
  Public key caller qua `CART_INTERNAL_CALLERS` (`order-service:<base64>`, rỗng = mọi call 401).
  Order-service chưa tồn tại nên kiểm bằng integration test với khóa sinh trong test (như INV-02).
- **Actor**: order-service đã xác minh danh tính của request checkout và chuyển xuống bằng header
  `Cart-Actor`: `user:<uuid>` hoặc `guest:<sha256 hex 64 ký tự thường của cart_token>` — đúng định dạng
  `CartActor.key()` hiện có. Cart không nhận raw cookie qua internal. Sai định dạng → 400 `INVALID_ACTOR`.
  Ownership dùng đúng quy tắc CART-01 (`findOwned`/`lockOwned`: ACTIVE, user khớp hoặc hash khớp và chưa
  hết hạn); sai/không có/UUID không chuẩn → 404 `CART_NOT_FOUND` như public.
- Mọi response internal `Cache-Control: no-store` (NoStoreFilter đã áp cho cả service).

## 3. `GET /internal/api/v1/carts/{cart_id}/snapshot?expected_version=N`

- `expected_version` bắt buộc (400 `VERSION_REQUIRED`); lệch → 409 `VERSION_CONFLICT` kèm `current_version`
  (cùng shape lỗi public).
- 200: `cart_id`, `version`, `owner` (`GUEST`/`MEMBER`), `items[]` gồm `item_id`, `variant_id`, `sku`,
  `quantity`, `item_version`, `price_snapshot`, `catalog_version` (thứ tự `created_at, id`). Giỏ rỗng trả
  `items: []` — order quyết định từ chối.
- Chỉ đọc: không gọi catalog/inventory (order tự quote), không gia hạn `expires_at`, không ghi DB.

## 4. `POST /internal/api/v1/carts/{cart_id}/checkout-cleanup`

Request: header `Cart-Actor`, `Idempotency-Key` bắt buộc và **phải bằng `order_id`** (thiếu → 400
`IDEMPOTENCY_KEY_REQUIRED`, khác → 400 `IDEMPOTENCY_KEY_MISMATCH`), body
`{ order_id, items: [{ item_id, item_version, quantity }] }`; 1–100 item, `item_id` không trùng,
`quantity` 1–99, `item_version` ≥ 0 (sai → 400 `VALIDATION_ERROR`).

Xử lý trong **một transaction**, không gọi HTTP:

1. `IdempotencyStore.begin(actor_key="service:order-service", operation="cart.checkout-cleanup:<cart_id>",
   key=order_id, hash)`; hash = SHA-256 của actor + items đã sắp theo `item_id`. Đã COMPLETED cùng hash →
   trả response đã lưu (200), không đổi giỏ. Khác hash → 409 `IDEMPOTENCY_KEY_REUSED`.
2. `lockOwned(cart_id, actor)` (404 nếu không thuộc actor) — cùng row lock với mutation của khách nên
   cleanup và sửa giỏ tuần tự hóa.
3. Mỗi item snapshot:
   - còn trong giỏ, `version` **và** `quantity` khớp → xóa → `removed`;
   - còn trong giỏ nhưng version hoặc quantity khác (khách sửa sau snapshot) → giữ nguyên → `retained`
     với `reason: ITEM_CHANGED`, kèm `current_quantity`;
   - không còn trong giỏ (khách đã xóa) → `missing`.
   Item không nằm trong snapshot (khách thêm mới) không bị đụng tới.
4. Có ít nhất một xóa → `carts.version + 1` (thiết bị khác thấy 409 và refresh); không xóa gì → giữ version.
   Giỏ **vẫn ACTIVE**, không dùng `CHECKED_OUT`.
5. Lưu response vào `idempotency_requests` cùng transaction rồi commit.

Response 200: `cart_id`, `cart_version` (sau cleanup), `order_id`, `removed: [item_id]`,
`retained: [{item_id, reason, current_quantity}]`, `missing: [item_id]`.

`cart_version` không được kiểm ở cleanup: giỏ được phép đổi sau snapshot; item version là guard.

## 5. Tạm tính và phí (CART-05)

- `GET /api/v1/carts/{id}` (public) thêm `subtotal_preview`: tổng `effective_price × quantity` của item
  `active` khi `preview_status = OK`; ngược lại `null`. Cộng bằng `Math.multiplyExact/addExact` (VND long;
  tràn → `null`, không 500).
- Thêm `shipping_fee_status: "NOT_CALCULATED"` cố định: cart **không** gọi shipping. Phí xem trước là
  `POST /shipping/fee/estimate` (SHP-01, 03 §3) với cart/version/địa chỉ/phương thức; tổng chốt chỉ có ở order
  quote. Ghi vào `cart.yaml` và 03 để WEB-03 không hiển thị `subtotal_preview` như tổng thanh toán.
- Preview không giữ kho/quota (đã đúng từ CART-01).

## 6. Contract và tài liệu

- `contracts/openapi/cart-internal.yaml` mới (2 route, security service token, examples); `cart.yaml` thêm
  2 field §5. Redocly lint qua `scripts/validate-contracts.sh` (cập nhật danh sách nếu script liệt kê file).
- 03 §4 (2 dòng internal cart: implemented + path), 03 §3 GET cart (field mới), 06 §4 dòng "Sau tạo đơn",
  10 backlog (CART-03 trạng thái), cart-service README (route internal, `CART_INTERNAL_CALLERS`),
  compose/`.env.example` (`CART_INTERNAL_CALLERS` rỗng), evidence `docs/evidence/cart-03-local-2026-10-18.md`.
- Không đổi schema: không migration mới (dùng `cart_items.version` và `idempotency_requests` có sẵn).

## 7. Kiểm thử và acceptance

Integration PostgreSQL thật (Testcontainers), service token thật sinh trong test.

| Nhóm | Case |
|---|---|
| Auth | không token / token sai aud → 401; caller `cart-service` → 403; trước khi đọc body/header khác |
| Actor | `Cart-Actor` sai định dạng → 400; actor khác chủ / guest hết hạn / cart_id đoán → 404 |
| Snapshot | đúng version → items/version; lệch → 409 + current_version; thiếu version → 400; không ghi DB |
| Cleanup T18 | snapshot 3 item; khách sửa qty item A, xóa item B, thêm item D; cleanup → C removed, A retained ITEM_CHANGED, B missing, D giữ; cart version +1 |
| Retry | cùng order_id + body → cùng response, giỏ không đổi lần hai (kể cả sau khi khách thêm lại variant đã xóa); khác body → 409; key ≠ order_id → 400 |
| Song song | hai cleanup cùng order_id song song → request sau chờ PK của `idempotency_requests`, thấy COMPLETED và trả đúng response đã lưu; chỉ một lần xóa |
| Lỗi giữa chừng | cleanup 404 (sai actor) rollback cả bản ghi idempotency → không khóa chết key |
| Không xóa gì | mọi item đã đổi → version giữ nguyên |
| Preview | `subtotal_preview` đúng khi OK, null khi UNAVAILABLE/EMPTY; item inactive không tính; `shipping_fee_status` |
| Gateway | `/internal/api/v1/carts/**` qua Gateway → 404 |

Mutation các guard chính (so khớp version/quantity, idempotency theo order_id, caller allowlist) phải bị test
bắt. Không có smoke internal local (chưa có order-service, giống INV-02). Full verify theo module (tắt compose
trước), contract lint, scan-secrets, docs checker, CI.

## 8. Quyết định của agent (cần duyệt)

1. Route ở `/internal/api/v1/carts/...`, chỉ caller `order-service` (03 §4 chỉ ghi order → cart; shipping
   tự thêm vào allowlist ở SHP-01 nếu cần).
2. Actor truyền bằng header `Cart-Actor` (`user:<uuid>` | `guest:<sha256>`), order chịu trách nhiệm xác minh.
   **Hệ quả cần biết cho ORD-01**: cookie `cart_token` có `Path=/api/v1/carts` nên trình duyệt không gửi nó
   tới `/api/v1/orders`; ORD-01/WEB-03 phải chọn cách chuyển credential guest (mở rộng Path cookie hoặc
   cơ chế khác). CART-03 không đổi cookie; ghi blocker này vào 08 như câu hỏi mở cho ORD-01.
3. Idempotency key cleanup = `order_id` (header bắt buộc và phải bằng `order_id`), để order retry bằng key
   khác vẫn không trừ hai lần.
4. Item khớp khi **cả version và quantity** khớp → xóa toàn bộ dòng; mọi khác biệt → giữ nguyên dòng
   (không "giảm phần còn lại"), báo `ITEM_CHANGED`. Đơn giản và đúng "giữ item đã sửa".
5. Giỏ vẫn ACTIVE sau cleanup (không `CHECKED_OUT`), tránh đụng unique ACTIVE của member và giữ item còn lại.
6. Snapshot không gọi catalog/inventory và không gia hạn giỏ guest.
7. Fee-preview: cart chỉ trả `subtotal_preview` + `shipping_fee_status: NOT_CALCULATED`; không gọi shipping.
8. Không migration, không background job trong cart.
