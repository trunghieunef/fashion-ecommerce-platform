# ORD-03 — OPS xác nhận/hủy đơn COD, commit kho, tạo vận đơn SELF, theo dõi giao hàng

`TASK:ORD-03` · `REQ:ORD-03`, `REQ:ORD-06`, `REQ:PAY-03` (COD thu → FINANCE → SUCCESS, kiểm qua smoke; sửa khi nghiệm thu — trước ghi nhầm là phần online) · dependency ORD-02, SHP-02 (Done local);
T09, T12, T27.
Trạng thái: **đã duyệt** 2026-10-21 (chủ dự án); đã qua builder check (subagent), các điểm sửa đánh dấu "(builder check)".
Nguồn: 06 §5.1 bước 8–9, §5.2, §5.3, bảng shipping/payment §8–§9; 03 §2 (admin orders), §3 (inventory commit, shipping create),
§5 (`shipping.events`, `ORDER_COMPLETED`); 13 (quyền `order.read`/`order.fulfill`/`order.cancel`, FINANCE redacted);
spec [ORD-02](2026-10-20-ord-02-checkout-saga-design.md).

## 1. Vì sao, ai dùng

Sau ORD-02, đơn COD dừng ở `CONFIRMED` (shop đã tiếp nhận, đã giữ hàng, có payment `COD_PENDING`) và tự hủy khi quá 24 giờ.
Shop vận hành thế này (chủ dự án mô tả): **một người OPS mở từng đơn đang chờ, gọi điện cho khách**.

- Khách đồng ý → OPS bấm **xác nhận**, có thể ghi chú cuộc gọi (tùy chọn). Hệ thống trừ kho, chuyển đóng gói, tự tạo vận đơn
  SELF; shipper giao qua các thao tác SHP-02 đã có; đơn tự đi `SHIPPING` → `DELIVERED`.
- Khách không nghe máy hoặc không lấy nữa → OPS bấm **hủy**, **bắt buộc** ghi lý do → nhả hàng, đóng payment, `CANCELLED`.

ORD-03 là đường găng tới demo G1 COD: đặt hàng → OPS xác nhận → giao SELF → thu COD → FINANCE đối soát, chạy end-to-end.

## 2. Mục tiêu, ngoài phạm vi, anti-goals

Mục tiêu:
- API admin cho OPS: danh sách/chi tiết đơn, xác nhận COD, hủy đơn **còn `CONFIRMED`**.
- Saga tiếp tục sau xác nhận: `COMMIT_STOCK` → `CONFIRMED → PACKING` → `CREATE_SHIPMENT`; bền vững, lease + CAS như ORD-02.
- Race OPS xác nhận ↔ hết hạn (T12) có đúng một kết quả: hoặc trừ kho và `PACKING`, hoặc hủy và nhả hàng; không bao giờ
  `PACKING` khi chưa trừ kho.
- Order nghe `shipping.events`: bàn giao → `SHIPPING`, giao thành công → `DELIVERED` + `ORDER_COMPLETED`.
- End-to-end local qua SELF + FINANCE settlement (smoke).

Ngoài phạm vi:
- Hủy đơn từ `PENDING`, `PACKING` trở đi, khách tự hủy, giao thất bại/hoàn hàng (`RETURNING`/`RETURNED`), shipment `UNKNOWN`,
  restore kho — **ORD-04**. Chủ dự án đồng ý (2026-10-21) kéo riêng phần hủy đơn `CONFIRMED` vào ORD-03.
- `POST /orders/{order_no}/pack` (online `PAID → PACKING`) — chờ PAY-02/03.
- Màn hình admin (ADM-01/02), email cho khách (NOT-01: ORD-03 chỉ phát event), refund (PAY-04), promotion (Phase 2).

Anti-goals: trừ kho hai lần; `PACKING` khi commit chưa thành công; hai vận đơn cho một đơn; đơn đã OPS xác nhận bị worker hủy
vì hạn *của order* trong khi inventory đã trừ kho; OPS hủy được đơn đã bắt đầu commit; event shipping cũ hạ trạng thái đơn;
FINANCE thấy số điện thoại/địa chỉ khách.

## 3. API admin (Gateway `/admin/api/v1/orders/**` → order-service; order-service tự kiểm quyền)

Xác thực giống `PaymentAdminController`: Bearer JWT của user-service, kiểm permission trong token (thêm `AdminAuth` cho order).
Thứ tự kiểm: auth → key → body.

| Endpoint | Quyền | Hành vi |
|---|---|---|
| `GET /admin/api/v1/orders` | `order.read` | Lọc `status` (enum đơn), `q` (khớp chính xác `order_no` hoặc `contact_phone`), keyset `cursor`, `limit` 1..100 mặc định 20; sắp `created_at desc, id desc`. Mặc định OPS lọc `status=CONFIRMED` để ra danh sách cần gọi |
| `GET /admin/api/v1/orders/{order_no}` | `order.read` | Đơn + items + history (actor, reason) + `expires_at` + saga (`step`, `status`, `last_error`) |
| `POST /admin/api/v1/orders/{order_no}/confirm-cod` | `order.fulfill` | Body `{expected_version, note?}` (note ≤ 500, tùy chọn); `Idempotency-Key` bắt buộc |
| `POST /admin/api/v1/orders/{order_no}/cancel` | `order.cancel` | Body `{expected_version, reason}` (reason 1..500, bắt buộc); `Idempotency-Key` bắt buộc |

- **Redact cho FINANCE:** người gọi không có `order.fulfill` không nhận `contact_phone`, `contact_email`, `shipping_address`,
  `note` và `reason` trong history (13 dòng 240); `q` theo số điện thoại chỉ cho người có `order.fulfill`.
- **Confirm** trong một transaction, lock `orders` + `order_sagas` (`FOR UPDATE`): đơn `CONFIRMED`, `payment_method = COD`,
  `orders.version = expected_version`, saga `intent = FULFILL`, `step = WAIT_OPS`, không có lease còn sống, `now < expires_at`.
  Thỏa → saga `step = COMMIT_STOCK`, `status = RUNNING`, `next_retry_at = now`, `retry_count = 0`, `order_sagas.version + 1`
  (worker đang giữ claim cũ thua CAS); **`orders.version + 1`** để một cancel/confirm cầm version cũ nhận `VERSION_CONFLICT`
  (builder check). **Đơn vẫn `CONFIRMED`** (chỉ sang `PACKING` khi commit thành công); `orders.confirmed_at` giữ nguyên nghĩa
  ORD-02 (shop tiếp nhận). Thời điểm/người/ghi chú xác nhận ghi vào `audit_logs` (§5). Trả `202` + đơn
  (`processing_status = PROCESSING`).
  - Đã quá hạn → `409 ORDER_EXPIRED`; status khác/đã có OPS khác xác nhận → `409 INVALID_TRANSITION` (errors `status current=S`);
    sai version → `409 VERSION_CONFLICT`.
- **Cancel** cùng điều kiện khóa (đơn `CONFIRMED`, saga `WAIT_OPS`, version) — đơn đã được xác nhận (saga đã sang
  `COMMIT_STOCK`) → `409 INVALID_TRANSITION`, phải đi ORD-04. Thỏa → saga `intent = CANCEL`, `step = COMPENSATE`,
  `status = COMPENSATING`, `next_retry_at = now`; đơn `CONFIRMED → CANCELLING`, `cancel_reason = OPS_CANCELLED`, history
  actor `ADMIN` + `actor_id` + `request_id` + `reason` = lý do OPS nhập, outbox như ORD-02, cùng một dòng `audit_logs`.
  `OrderTransitions.move` hiện cố định actor `SYSTEM`, phải mở rộng nhận actor/actor_id/request_id (builder check). Phần nhả hàng/đóng payment/`CANCELLED` dùng
  nguyên `compensate` của ORD-02. Trả `202`. Hủy được cả khi đã quá hạn nhưng worker chưa kịp chạy (cùng kết quả).
- **Idempotency:** `IdempotencyStore` của platform-durability (actor = `admin:{user_id}`, operation `confirm-cod`/`cancel`,
  hash body). Replay cùng key + body → response đã lưu; khác body → `409 IDEMPOTENCY_KEY_REUSED`.
- `processing_status`: `PROCESSING` cho `CONFIRMED` khi saga đã qua `WAIT_OPS` và cho `PACKING` khi chưa có shipment; `READY`
  cho `CONFIRMED` chờ OPS, `PACKING` đã có shipment, `SHIPPING`; `FINISHED` cho `DELIVERED`/`CANCELLED`. GET của khách dùng
  cùng quy tắc. Saga `MANUAL` vẫn trả `PROCESSING` như ORD-02, FE giới hạn poll theo 14 §3; không dùng `ACTION_REQUIRED` (dành
  cho việc khách phải làm, ví dụ thanh toán online). `reason_code` công khai thêm `OPS_CANCELLED` (đổi contract `order.yaml` +
  03 §2, builder check).

## 4. Saga sau xác nhận

| Step | Việc | Thành công | Từ chối | Chưa rõ |
|---|---|---|---|---|
| `COMMIT_STOCK` | inventory `POST /internal/api/v1/inventory/commit` `{order_id}`, key `commit:{order_id}` | `inventory_state = COMMITTED`; đơn `CONFIRMED → PACKING` (history SYSTEM, không outbox — §11.6), step `CREATE_SHIPMENT` | `409 RESERVATION_EXPIRED` → inventory đã tự nhả: `inventory_state = RELEASED`, CANCEL `EXPIRED`; `409 RESERVATION_CLOSED` → `RELEASED`, CANCEL `RESERVE_FAILED`; mã khác (404, 400, `*_CONFLICT`) → retry rồi MANUAL | `UNKNOWN`, retry cùng key |
| `CREATE_SHIPMENT` | shipping `POST /internal/api/v1/shipping/shipments`, key `shipment:{order_id}`, body từ snapshot: `order_id`, `order_no`, `quote_id` (từ `shipping_quote`), `cod_amount = total_amount`, `to_address` từ `shipping_address` | lưu `shipment_id`; saga `DONE` | mọi từ chối (`QUOTE_MISMATCH`, `QUOTE_ALREADY_USED`, `QUOTE_NOT_FOUND`, `SHIPMENT_CONFLICT`, `SHIPMENT_CANCELLED`) → **MANUAL** (kho đã trừ, không tự hủy — ORD-04) | retry cùng key/body |

- **Hạn của order không còn áp dụng sau xác nhận.** Hiện `advance` (ORD-02) kiểm `intent = FULFILL && now >= expires_at` ở mọi
  step; ORD-03 **sửa** để chỉ kiểm với step `RESERVE`, `CREATE_PAYMENT`, `WAIT_OPS` (builder check). Ở `COMMIT_STOCK`, inventory là trọng tài (06 §5.2): nó so `now < expires_at` của reservation
  trong transaction của nó. OPS xác nhận lúc 23:59 mà commit tới 24:01 → inventory trả `RESERVATION_EXPIRED` → đơn hủy
  `EXPIRED`; commit kịp → `PACKING` dù order đã quá `expires_at`. Kết quả duy nhất do inventory quyết định (T12).
- **T09 (worker chết sau khi inventory commit):** trước HTTP ghi intent (tính một lần thử + backoff như ORD-02) nhưng
  **`inventory_state` giữ `HELD`** — helper `state()` chỉ đổi NONE/UNKNOWN, và đặt UNKNOWN sẽ khiến `compensate` hiểu là có thể
  release (builder check). Step `COMMIT_STOCK` chính là dấu "đang commit"; lần sau gọi lại cùng key, inventory trả `200
  COMMITTED` không trừ lần hai → tiếp tục. Từ `COMMIT_STOCK` chỉ `RESERVATION_EXPIRED`/`CLOSED` (inventory đã nhả, ghi
  `RELEASED` trước) mới vào bù trừ.
- Bù trừ ORD-02 gặp `COMMITTED` vẫn → MANUAL (không đổi).
- Ngân sách retry/backoff/MANUAL/metric như ORD-02 (30 lần, tối đa 5 phút). Saga `DONE` sau khi có shipment; phần giao hàng
  do consumer §6 đẩy, không phải saga.

## 5. Dữ liệu (migration V003, append-only)

- `audit_logs` theo 05 §3 (cùng cột như catalog/user-service, `REVOKE UPDATE, DELETE, TRUNCATE` khỏi `order_runtime`):
  `action` `ORDER_CONFIRM_COD`/`ORDER_CANCEL`, `resource_type = order`, `resource_id = order_no`, `reason` = ghi chú cuộc gọi
  hoặc lý do hủy, `before_data`/`after_data` = status + version, `request_id`. Dùng bảng chuẩn thay vì bảng riêng (builder check).
- `idempotency_requests`: chép từ `platform-durability/db/platform/durability.sql` (order-service chưa có bảng này).
- `orders.shipped_at` (đặt khi sang `SHIPPING` = `handed_over_at` của event); `delivered_at` đã có ở V001.
- `order_sagas.shipment_version bigint` (bản shipping đã áp dụng, chặn event cũ); `shipment_id` đã có.
- Quyền: như V001/V002, dựa default privileges rồi REVOKE (không GRANT riêng); `idempotency_requests` cần UPDATE
  (`IdempotencyStore.finish`).

## 6. `shipping.events` (consumer group `order-service`, inbox `processed_events`, ACK sau commit)

- Chỉ xử lý `SHIPMENT_STATUS_UPDATED` (và `SHIPMENT_CREATED` chỉ để ghi `shipment_id` nếu saga chưa có); COD_COLLECTED/
  COD_REMITTED là việc của payment → đánh dấu đã xử lý.
- Trong một transaction lock đơn theo `order_id`: bỏ qua nếu `shipment_version <= order_sagas.shipment_version` (event cũ/trùng).
  - `status = DELIVERING` và `handed_over_at` có giá trị, đơn `PACKING` → `SHIPPING`, `shipped_at = handed_over_at`.
  - `status = DELIVERED`, đơn `PACKING` hoặc `SHIPPING` → (qua `SHIPPING` nếu còn `PACKING`, hai dòng history) `DELIVERED`,
    `delivered_at = provider_occurred_at` (null thì thời điểm nhận), outbox `ORDER_COMPLETED` (schema đã có).
  - `FAILED`, `RETURNING`, `RETURNED`, `CANCELLED`, `PICKING`… → chỉ ghi `shipment_version`, đơn giữ nguyên (ORD-04).
  - Đơn ở trạng thái không khớp (ví dụ `CANCELLED`) → ghi version, log cảnh báo, không chuyển.
- Event có thể đến trước khi saga ghi `shipment_id` (response create bị mất): vẫn áp dụng vì đơn đã `PACKING`; saga lần sau gọi
  create cùng body nhận lại shipment cũ.
- Thu tiền: payment tự tiêu thụ `COD_COLLECTED`; FINANCE settlement (PAY-01) → `PAYMENT_COMPLETED` → order ghi `paid_at` (ORD-02).
  `DELIVERED` không có nghĩa đã thu tiền (T27).

## 7. Hạ tầng

- order-service gọi thêm inventory commit (đã là caller của inventory) và shipping create (đã là caller của shipping cho quote).
- Gateway: route `order-admin` `/admin/api/v1/orders/**` → order-service (CSRF/ranh giới như các route admin khác).
- Admin auth dùng lại khóa JWT `OrderIdentity` đã có (token đã mang permissions); không thêm config.
- Kafka: subscribe thêm `shipping.events`; phát thêm `ORDER_COMPLETED`.

## 8. Contract và tài liệu

- `contracts/openapi/order.yaml` thêm 4 endpoint admin (hoặc file admin riêng theo cách payment làm), thêm `OPS_CANCELLED` vào
  `reason_code` và các giá trị `processing_status` mới cho `PACKING`/`SHIPPING`/`DELIVERED`; contract test.
- `order-events.schema.json`/registry: xác nhận `ORDER_COMPLETED` đã đủ.
- Tài liệu: 03 (admin orders implemented, order.events, `reason_code`), 05 (V003), 06 §5.2 (đoạn "ORD-03 hiện thực"),
  08 (bảng quyết định ORD-03, gồm tách hủy `CONFIRMED` khỏi ORD-04), 10 (gồm REQ `PAY-03` hoãn tới PAY-02/03), 12 (smoke), 13 nếu đổi redact, README order-service/gốc, CLAUDE.md, evidence.

## 9. Test (PostgreSQL Testcontainers; inventory/shipping là HTTP stub, worker gọi tay, Kafka như ORD-02)

- Happy: CONFIRMED → OPS confirm (note) → commit → `PACKING` → create shipment (body đúng snapshot, key `shipment:{id}`) → saga
  `DONE`; event `DELIVERING` → `SHIPPING`; `DELIVERED` → `DELIVERED` + `ORDER_COMPLETED` một lần.
- Quyền: không token 401; thiếu permission 403; FINANCE GET không thấy phone/address/note; q theo phone bị chặn với FINANCE.
- Confirm: sai version 409; replay cùng key 202 cùng body; khác body 409; đơn đã confirm bởi OPS khác 409; quá hạn 409
  `ORDER_EXPIRED`; online/không phải COD 409.
- **T12:** (a) confirm thắng, deadline qua trước commit, inventory `RESERVATION_EXPIRED` → `CANCELLED EXPIRED`, không `PACKING`;
  (b) confirm, commit OK sau deadline → `PACKING` (worker không hủy vì hạn order); (c) worker claim vì hết hạn rồi OPS confirm →
  một bên thua CAS/khóa, chỉ một kết quả; (d) confirm và cancel đồng thời → đúng một thắng.
- **T09:** commit trả 200 nhưng response mất (stub timeout sau khi ghi) → retry cùng key → `PACKING` một lần, không release.
- Cancel: CONFIRMED → CANCELLING → release + close → CANCELLED `OPS_CANCELLED`, history ADMIN có reason; reason rỗng 400; đơn đã
  sang `COMMIT_STOCK` → 409.
- Shipment: từ chối → MANUAL, đơn vẫn `PACKING`; timeout → retry cùng key/body; event đến trước khi saga ghi shipment_id.
- Shipping event cũ (version thấp hơn) không hạ trạng thái; event trùng xử lý một lần; `FAILED` không đổi đơn.
- Mutation: bỏ guard hạn-order chỉ trước confirm; commit bằng key ngẫu nhiên; `PACKING` trước commit OK; bỏ check version event.
- Smoke `ORD-03 COD end-to-end PASS` qua Gateway: tạo đơn guest → poll CONFIRMED → OPS confirm → poll PACKING → shipping
  self-events ASSIGN/HANDED_OVER/DELIVERED/COD_COLLECTED → đơn DELIVERED → FINANCE settlement → payment SUCCESS, đơn có `paid_at`.

## 10. Rủi ro

- Inventory làm trọng tài bằng đồng hồ DB của nó, order kiểm hạn bằng đồng hồ app: lệch đồng hồ chỉ làm confirm sát hạn bị
  từ chối hoặc hủy, không tạo hai kết quả.
- Shipment từ chối sau khi đã trừ kho → MANUAL, chưa có UI/alert (ADM-02/PLT-05); xử lý tiếp là ORD-04.
- Đơn `CONFIRMED` chờ OPS dài tới 24 giờ giữ hàng; số OPS và giờ làm việc thật chưa biết (O04/vận hành).

## 11. Lựa chọn mặc định của agent (chủ dự án duyệt cùng spec 2026-10-21)

1. Từ chủ dự án: OPS xác nhận **từng đơn** sau khi gọi điện; ghi chú cuộc gọi **tùy chọn**; hủy **bắt buộc lý do**; hủy đơn
   `CONFIRMED` thuộc ORD-03, phần hủy còn lại ORD-04.
2. Confirm trả `202`, đơn vẫn `CONFIRMED` tới khi commit xong (06: "CONFIRMED → PACKING: OPS xác nhận và commit thành công").
3. Sau confirm, hạn của order không còn hủy đơn; inventory quyết định.
4. Vận đơn tạo tự động ngay sau `PACKING`, OPS không bấm thêm (06 §5.1 bước 9).
5. Shipment bị từ chối sau khi trừ kho → MANUAL, không tự hủy.
6. Không thêm event `ORDER_PACKING`/`ORDER_SHIPPED` (03 §5 chưa có, chưa consumer nào cần; NOT-01 dùng `NOTIFY_SHIPMENT_STATUS`
   của shipping); chuyển trạng thái vẫn có history. Thêm khi NOT-01/ADM cần.
7. Giao thất bại/hoàn hàng giữ đơn ở `SHIPPING`, chỉ ghi version (ORD-04).
8. FINANCE nhận bản redacted bằng cách "không có `order.fulfill`" thay vì permission riêng.
9. `q` chỉ khớp chính xác (order_no/phone), không full-text.
10. Key cố định `commit:{order_id}`, `shipment:{order_id}`; `cancel_reason = OPS_CANCELLED`, lý do người nhập ở history/audit.
11. Audit OPS vào `audit_logs` chuẩn 05 §3; `confirmed_at` giữ nghĩa ORD-02, không thêm cột "OPS xác nhận lúc" (đọc từ audit).
12. Confirm/cancel tăng `orders.version`; `order_version` trong event có thể nhảy (không có event cho mỗi version).
13. Saga `MANUAL` hiển thị `PROCESSING` cho khách (như ORD-02 + 14 §3), không `ACTION_REQUIRED`.
