# CART-02 — gộp giỏ guest → member (cart-service)

`TASK:CART-02` · `REQ:CART-03` · dependency CART-01 (Done local). Liên quan CART-03 (snapshot/cleanup, Done local).
Trạng thái: **đã duyệt** 2026-10-18. Chủ dự án giao agent tự chốt mặc định; lựa chọn của agent ghi ở §7.
UI login/merge (WEB-02) nằm ngoài phần này.

## 1. Mục tiêu

- Sau khi đăng nhập, khách gộp giỏ guest (cookie) vào giỏ ACTIVE của tài khoản: số lượng cộng **đúng một lần**
  dù retry hay gửi song song (T19); cả hai credential phải hợp lệ.
- Item không còn bán / hết hàng / vượt 99 / vượt 100 dòng được điều chỉnh và **báo lại** cho UI
  (`adjustments`), không âm thầm bỏ (14 SF-05).

Không làm: tự gộp khi login (client gọi rõ ràng), gộp member → member, giữ hàng, UI.

Anti-goals: cộng hai lần; gộp giỏ guest của người khác; ghi đè thay đổi member mới hơn; gọi HTTP khi giữ lock;
mất item mà không báo.

## 2. API `POST /api/v1/carts/merge`

- Cần **cả** Bearer member hợp lệ (401 nếu thiếu/sai) **và** cookie `cart_token` hợp lệ; thiếu cookie hoặc giỏ
  guest không thuộc cookie → 404 `CART_NOT_FOUND`. Cookie mutation đã qua CSRF origin của Gateway
  (path `/api/v1/carts/**`).
- `Idempotency-Key` bắt buộc (03 §1.3, scope `user:<id>` + `cart.merge` + key; cùng key khác body → 409).
- Body `{guest_cart_id, guest_expected_version, member_expected_version}`. Member chưa có giỏ ACTIVE thì được tạo
  trước (như `POST /carts`), version 0.
- Response 200 `{cart: <Cart như GET, preview NOT_LOADED>, merged_from: guest_cart_id, adjustments: [...]}`
  và `Set-Cookie: cart_token=; Max-Age=0` (xóa cookie guest đã dùng).

## 3. Xử lý

Trước transaction: đọc giỏ guest theo id + hash cookie (ACTIVE chưa hết hạn, **hoặc** MERGED — để retry sau khi
đã gộp vẫn tới được bước 3; không thấy → 404). ACTIVE thì gọi catalog quote + inventory stock cho các variant của
giỏ guest (như add; lỗi/timeout → 503 không ghi gì); MERGED thì bỏ qua dependency.

Một transaction:

1. `IdempotencyStore.begin`; COMPLETED cùng hash → response đã lưu.
2. Lock **hai** giỏ theo thứ tự `id` tăng dần (`for update`) — cùng thứ tự với mọi merge khác nên không deadlock.
3. Giỏ guest đã `MERGED` với `merged_into` = giỏ member → 200 giỏ member hiện tại, `adjustments: []`,
   `already_merged: true` (retry bằng key khác không cộng lại). MERGED vào giỏ khác / không còn ACTIVE → 404.
4. Kiểm `guest_expected_version`, `member_expected_version` → 409 `VERSION_CONFLICT` (`errors` field tương ứng,
   `current=N`).
5. Với mỗi item guest (theo `created_at`):
   - quote không found/không active → bỏ, adjustment `UNAVAILABLE`;
   - `target = min(member_qty + guest_qty, 99, available)`, nhưng không thấp hơn `member_qty` (không giảm
     item member có sẵn); `available` = 0 và member chưa có dòng → bỏ, `OUT_OF_STOCK`;
     `target < member_qty + guest_qty` → `QUANTITY_CAPPED` (`requested`, `applied`);
   - dòng mới khi giỏ member đã 100 dòng → bỏ, `CART_ITEM_LIMIT`;
   - ghi: dòng có sẵn → cập nhật quantity, `price_snapshot`/`catalog_version` từ quote, `version+1`,
     `created_at = least(hai dòng)`; dòng mới → insert với `created_at` của item guest.
6. Giỏ guest → `status = MERGED`, `merged_into = member`, `version+1` (items guest giữ làm lịch sử);
   giỏ member `version+1`. `idempotency.finish` cùng transaction.

Adjustment: `{variant_id, sku, reason: UNAVAILABLE|OUT_OF_STOCK|QUANTITY_CAPPED|CART_ITEM_LIMIT, requested, applied}`
(`applied` = số đã thêm vào giỏ member từ phần guest, 0 khi bỏ).

Tồn là **tham khảo** (quan sát trước transaction), không giữ hàng — đúng 06 §4.

## 4. Contract và tài liệu

- `cart.yaml`: route merge + schema `MergeRequest/MergeResponse/Adjustment`, examples; `test_cart.py` (8 operations).
- 03 §3 dòng `/carts/merge` (implemented), 06 §4 dòng "Merge login", 10 backlog, cart-service README, evidence
  `docs/evidence/cart-02-local-2026-10-18.md`. Không migration (cột `merged_into`/`MERGED` đã có từ V001).

## 5. Kiểm thử

Integration PostgreSQL thật; catalog/inventory là stub như CART-01.

| Nhóm | Case |
|---|---|
| Danh tính | không Bearer → 401; Bearer sai → 401; không cookie / cookie khác / guest_cart_id đoán → 404; giỏ guest hết hạn → 404 |
| Gộp | variant trùng cộng qty, created_at giữ cũ hơn; variant mới thêm dòng; guest MERGED + merged_into; version cả hai +1; cookie bị xóa |
| T19 | retry cùng key → response cũ; retry key khác sau khi đã merge → `already_merged`, không cộng; hai merge song song → đúng một lần cộng |
| Version | stale guest/member version → 409, không ghi |
| Điều chỉnh | inactive/không tồn tại → UNAVAILABLE; hết hàng → OUT_OF_STOCK; tổng > 99 hoặc > available → QUANTITY_CAPPED; dòng 101 → CART_ITEM_LIMIT; member qty không bị giảm |
| Dependency | catalog/inventory lỗi → 503, hai giỏ không đổi |
| Lock | gọi dependency không giữ lock (stub chậm không chặn đọc giỏ) |

Mutation: bỏ check MERGED, bỏ thứ tự lock/khóa giỏ guest phải bị test bắt. Verify theo module, contract, docs, CI.

## 6. Ghi chú

- Sau merge, cookie cũ không còn dùng được (giỏ MERGED); `POST /carts` với cookie đó cấp giỏ guest mới như hiện nay.

## 7. Quyết định của agent (cần duyệt)

1. Merge là lệnh client gọi rõ ràng sau login (`POST /carts/merge`), không tự động trong auth.
2. Retry đã merge (kể cả key khác) → 200 `already_merged: true`, `adjustments: []`.
3. Cap theo `min(99, available quan sát)` nhưng không giảm số lượng member có sẵn; item bỏ/giảm đều có adjustment.
4. Member chưa có giỏ thì tạo trong merge (`member_expected_version` = 0).
5. Giỏ guest giữ items, chỉ đổi `MERGED` + `merged_into`; xóa cookie guest trong response.
6. Kiểm tồn trước transaction (như add); không giữ hàng.
