# INV-03 — return/restore hàng đã commit (inventory-service)

`TASK:INV-03` · `REQ:INV-07` · dependency INV-02 (Done local).
Trạng thái: **đã duyệt** 2026-10-18. Chủ dự án giao agent tự chốt mặc định; lựa chọn của agent ghi ở §7.
Order return/kiểm đếm phía order (`order_returns`, ORD-04), UI OPS và restock notification (NOT-02) nằm ngoài phần này.

## 1. Mục tiêu

- Order-service cộng lại kho cho hàng **đã commit** (đã trừ `on_hand`) trong hai trường hợp: hủy sau commit trước
  bàn giao (release trả `restore_required: true`) và hàng hoàn về sau giao đã được OPS kiểm đếm.
- Mỗi lần return có `operation_key` + lý do + `evidence_ref`; retry cùng key không cộng lại (T17); tổng trả
  theo SKU không bao giờ vượt số đã commit, kể cả khi nhiều return chạy đồng thời.

Không làm: tạo return từ webhook carrier (06 §9: chỉ sau kiểm đếm), phân loại hàng hỏng (order chỉ gửi
`restock_quantity`), trạng thái reservation mới, event loại mới, UI.

Anti-goals: cộng kho hai lần cho một lần kiểm đếm; tổng trả > đã commit; dùng release cho hàng đã commit;
return cho reservation chưa commit; gọi HTTP trong transaction.

## 2. Schema (V004, append-only)

`stock_returns` theo 05 §8 với hai điều chỉnh (ghi vào 05):

- `id uuid PK`, `reservation_id uuid FK stock_reservations`, `sku varchar(64) FK stock_items`,
  `operation_key varchar(128)`, `quantity int CHECK (quantity BETWEEN 1 AND 99)`, `reason text` (1–500),
  `evidence_ref text` (1–500, **mới** — 03 §4 yêu cầu evidence), `actor_id uuid?`, `created_at`.
- UNIQUE `(reservation_id, operation_key, sku)` thay cho `(operation_key, sku)`: `order_returns.operation_key`
  chỉ unique theo order (05, `order_returns` UNIQUE(order_id, operation_key)), nên hai order có thể dùng cùng key.
- Runtime chỉ SELECT/INSERT (không UPDATE/DELETE/TRUNCATE), như ledger.

## 3. API `POST /internal/api/v1/inventory/returns`

Caller chỉ `order-service`; thứ tự kiểm như INV-02: token (401) → caller (403) → `Idempotency-Key` (400) → body.

Body: `{order_id, operation_key, reason, evidence_ref, items: [{sku, quantity}]}`; `Idempotency-Key` phải bằng
`operation_key` (khác → 400 `IDEMPOTENCY_KEY_MISMATCH`). 1–100 SKU, SKU canonical (`Sku.canonical`), trùng SKU →
400; quantity 1–99; reason/evidence_ref 1–500 ký tự sau trim.

Một transaction, không HTTP:

1. Lock reservation theo `order_id` (`ReservationRepository.lock`). Không có → 404 `RESERVATION_NOT_FOUND`.
   Không phải `COMMITTED` → 409 `RESERVATION_NOT_COMMITTED`.
2. Đã có return rows cho `(reservation, operation_key)` → so với request (cùng tập sku/quantity, reason,
   evidence_ref): khớp → 200 replay, kho không đổi; khác → 409 `RETURN_CONFLICT`.
3. SKU không thuộc reservation → 400 `INVALID_INPUT`. Lock `stock_items` theo thứ tự SKU (`lockForUpdate`).
4. Với mỗi SKU: `đã trả trước đó + quantity ≤ quantity đã commit` (từ `stock_reservation_items`), nếu không →
   409 `RETURN_EXCEEDS_COMMITTED` kèm `errors[items[SKU]] = committed=N returned=M requested=K`; cả request
   rollback.
5. Insert `stock_returns`, `move(on_hand +quantity, reserved 0, type RETURN)` với ledger key
   `order:<order_id>:RETURN:<operation_key>` → `INVENTORY_UPDATED` (và `INVENTORY_RESTOCKED` khi available 0→dương)
   qua outbox như mọi movement hiện có.

Vì mọi return của một order đều lock cùng reservation row trước, các return đồng thời của một order chạy tuần tự
và guard cộng dồn luôn đúng.

Response 200: `{order_id, reservation_id, operation_key, items: [{sku, quantity, committed, returned_total}]}`.
SKU INACTIVE vẫn được cộng `on_hand` (hàng vật lý đã về kho).

`GET /internal/api/v1/inventory/reservations/{order_id}` thêm `returned` theo item (tổng đã trả), để order recovery
đọc lại sau timeout `UNKNOWN`.

## 4. Contract và tài liệu

- `contracts/openapi/inventory.yaml`: route mới + field `returned`; test contract inventory.
- 03 §4 dòng `POST /inventory/returns` (implemented), 05 §8 (`evidence_ref`, unique mới), 06 §6/§9 (restore sau
  `restore_required`), 10 backlog, inventory-service README, evidence `docs/evidence/inv-03-local-2026-10-18.md`.

## 5. Kiểm thử

Integration PostgreSQL thật (Testcontainers), service token thật.

| Nhóm | Case |
|---|---|
| Auth/validation | 401/403 trước body; thiếu key, key ≠ operation_key, SKU trùng/0/101 item, qty 0/100, reason/evidence rỗng → 400 |
| Trạng thái | không có reservation → 404; ACTIVE/RELEASED/EXPIRED → 409 NOT_COMMITTED |
| Restore | commit 3 → return 2: on_hand +2, reserved không đổi, ledger RETURN, outbox snapshot; available 0→2 phát RESTOCKED |
| T17 | cùng operation_key gửi lại (tuần tự và song song) → một lần cộng, response giống nhau; cùng key khác items → 409 |
| Cumulative | return 2 rồi return 2 (key khác) với commit 3 → lần hai 409, kho không đổi; hai return song song key khác, tổng > commit → đúng một cái thành công |
| SKU lạ | SKU không thuộc reservation → 400, không ghi |
| GET | `returned` phản ánh tổng đã trả |

Mutation: bỏ guard cộng dồn, bỏ replay theo operation_key phải bị test bắt. Verify theo module (tắt compose),
contract, docs, CI.

## 6. Ranh giới với order

- Order gọi returns sau `release` trả `restore_required` (hủy trước bàn giao, số lượng = toàn bộ commit) hoặc sau
  OPS kiểm đếm (`restock_quantity` > 0 từng item). Order giữ intent ở `order_returns` trước khi gọi; timeout →
  `UNKNOWN`, retry cùng operation_key.

## 7. Quyết định của agent (cần duyệt)

1. Một endpoint returns cho cả restore-sau-hủy và return-sau-giao; reservation vẫn `COMMITTED` (không thêm trạng thái).
2. Thêm cột `evidence_ref`; unique `(reservation_id, operation_key, sku)` thay vì `(operation_key, sku)` — sửa 05.
3. `Idempotency-Key` bắt buộc và phải bằng `operation_key`; replay so sánh dữ liệu đã lưu, không thêm cột hash.
4. Vượt tổng commit → 409 cả request (không cộng một phần).
5. Không event loại mới: dùng snapshot `INVENTORY_UPDATED/RESTOCKED` hiện có.
6. SKU INACTIVE vẫn nhận lại `on_hand`.
