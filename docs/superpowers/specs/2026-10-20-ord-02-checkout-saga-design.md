# ORD-02 — saga checkout bền vững: giữ hàng, tạo payment COD, bù trừ, dọn giỏ

`TASK:ORD-02` · `REQ:ORD-02`, `REQ:ORD-09` · dependency ORD-01, INV-02, PAY-01, PLT-03 (đều Done local); T03, T07, T09, T23.
Trạng thái: **đã duyệt** 2026-10-20. Chủ dự án giao agent tự chốt mặc định và duyệt spec (gồm các lựa chọn §11).
Nguồn: 06 §1.1–§1.3, §5.1–§5.4, §13; 05 §7 và §15 (`background_tasks`); 03 §1.3 và §4 (inventory reserve/release/query,
payment create/close, cart checkout-cleanup); 08 ORD-01 (bump version giỏ, guest mất response); 02 ORD-02/09.

## 1. Mục tiêu và ngoài phạm vi

- Đơn `PENDING` do ORD-01 tạo được một **worker saga** đưa tiếp, bền vững qua crash: **giữ hàng** (inventory reserve toàn
  bộ SKU) → **tạo payment COD** (payment create) → đơn `CONFIRMED` (chờ OPS, ORD-03). Mỗi bước ghi intent trước khi gọi
  HTTP, ghi kết quả bằng compare-and-set theo lease token; không HTTP trong transaction DB (REQ ORD-02).
- Lỗi chắc chắn khi chuẩn bị (hết hàng, payment từ chối) hoặc **quá hạn** → intent `CANCEL`: nhả giữ hàng (release bằng
  snapshot gốc), đóng payment (close/tombstone) → `CANCELLED` có `cancel_reason`. Không báo `CANCELLED` khi nghĩa vụ chưa
  xử lý xong.
- Kết quả chưa rõ (timeout, 5xx) → trạng thái tài nguyên `UNKNOWN`, retry **cùng khóa nghiệp vụ** (T03); hết số lần → `MANUAL`
  + metric, không xóa nghĩa vụ.
- **Dọn giỏ** sau khi đơn đã giữ được hàng: background task bền vững, độc lập saga; lỗi dọn giỏ không hủy đơn (REQ ORD-09).
- Ghi nhận `PAYMENT_COMPLETED`/`PAYMENT_FAILED` từ `payment.events` bền vững (inbox), không hạ trạng thái (T07).
- GET đơn phản ánh `processing_status` và `reason_code`; create vẫn `202`.

Không làm: OPS xác nhận COD/đóng gói/commit kho/tạo shipment và race commit↔expire (ORD-03); khách/OPS hủy đơn và hoàn hàng
(ORD-04); online VNPay/MoMo (PAY-02/03, saga đã có nhánh `WAIT_PAYMENT` nhưng phương thức chưa bật); promotion (Phase 2);
refund (PAY-04); admin order (ORD-03/ADM-02); alert dashboard (PLT-05).

Anti-goals: hai reservation/payment cho một đơn; reserve hoặc create chạy lại với khóa/hash mới; worker hết lease ghi đè
worker mới; `CANCELLED` trước khi release/close xong; event payment cũ hạ trạng thái mới; dọn giỏ của đơn bị hủy vì hết hàng.

## 2. Hạ tầng

- order-service gọi thêm: inventory `POST /internal/api/v1/inventory/reserve`, `POST .../release`, `GET .../reservations/{order_id}`
  (aud `inventory-service`); payment `POST /internal/api/v1/payments`, `POST .../by-order/{order_id}/close`, `GET
  .../by-order/{order_id}` (aud `payment-service`); cart `POST /internal/api/v1/carts/{id}/checkout-cleanup` (đã là caller).
  local-up thêm khóa `order-service` vào `INVENTORY_INTERNAL_CALLERS` và `PAYMENT_INTERNAL_CALLERS`.
- Kafka: tiêu thụ `payment.events` (group `order-service`, inbox `processed_events`, ACK sau commit, retry/DLT của
  platform-messaging); phát thêm `ORDER_CONFIRMED`, `ORDER_CANCELLED` (schema đã có).
- Migration **V002** (append-only, V001 đã merge): `background_tasks` (05 §15), `processed_events`, cột saga phụ (§4), thay
  UNIQUE `(cart_id, cart_version)` bằng **unique index một phần** `WHERE status <> 'CANCELLED'` để khách đặt lại được sau khi
  đơn hủy vì hết hàng (giỏ không đổi version) — khuyến nghị review PR #32.

## 3. Saga (theo 05 §7 `order_sagas`, 06 §5.1/§5.2)

| Step | Việc | Thành công | Lỗi chắc chắn | Chưa rõ |
|---|---|---|---|---|
| `RESERVE` | reserve mọi SKU của `order_items`, `expires_at` = đơn, `request_hash` theo 03 §4, key `reserve:{order_id}` | `inventory_state=HELD` → `CREATE_PAYMENT` | 409 `OUT_OF_STOCK`/`RESERVATION_CLOSED`/`RESERVATION_CONFLICT` → CANCEL (`OUT_OF_STOCK` hoặc `RESERVE_FAILED`) | `UNKNOWN`, retry cùng key/hash |
| `CREATE_PAYMENT` | payment create từ snapshot đơn (order_id, order_no, user_id, method, amount = total, VND, expires_at), key `payment:{order_id}` | lưu `payment_id`; COD `COD_PENDING` → đơn `CONFIRMED`, saga `WAIT_OPS`/`WAITING`, `next_retry_at = expires_at` | 409 `PAYMENT_CLOSED`/422 → CANCEL (`PAYMENT_FAILED`) | `payment_state=UNKNOWN`, retry cùng key |
| `WAIT_OPS` | chờ OPS (ORD-03) | — | quá `expires_at` → CANCEL (`EXPIRED`) | — |
| `COMPENSATE` (intent CANCEL) | release reservation (snapshot + hash gốc, reason) nếu `inventory_state` ≠ NONE; close payment (snapshot + reason) **luôn** (tombstone chặn create đến muộn) | cả hai xác nhận → đơn `CANCELLED`, saga `DONE` | release trả `COMMITTED` → `MANUAL` (không xảy ra trước ORD-03) | retry |

- Đơn: `PENDING → CONFIRMED` (COD), `PENDING → CANCELLING → CANCELLED`, `CONFIRMED → CANCELLING → CANCELLED` (hết hạn).
  Mỗi chuyển trạng thái ghi `order_status_history` (actor `SYSTEM`) + outbox cùng transaction, `orders.version + 1`.
- `payment_state` mới trên saga (NONE/UNKNOWN/CREATED/CLOSED) và `cancel_reason` trên đơn; `reason_code` của GET = lý do hủy.
- Trước khi gọi HTTP: transaction ghi intent (`*_state = UNKNOWN`, `retry_count + 1`, `next_retry_at` backoff) dưới lease;
  sau HTTP: transaction CAS theo `lease_token` + `version` ghi kết quả. Mất lease → bỏ kết quả (bước gọi lại idempotent).
- Online (`WAIT_PAYMENT`, deadline 15 phút) có trong state machine nhưng không đạt được tới PAY-02/03.

## 4. Worker và lease

- `@Scheduled` (fixed delay `ORDER_SAGA_POLL` 1 s), claim tối đa 10 saga `status IN (RUNNING, WAITING, COMPENSATING)` và
  `next_retry_at <= now()` bằng `FOR UPDATE SKIP LOCKED`, đặt `lease_token`/`lease_until = now + 30 s` (dùng `LeaseRepository`
  nếu khớp, không thì SQL tương đương trong order-service).
- Backoff lỗi: `min(2^retry × 1 s, 5 phút)` + jitter ±20 %; quá `ORDER_SAGA_MAX_RETRIES` (30) → saga `MANUAL`, `last_error`,
  metric `order_saga_manual_total`; nghĩa vụ giữ nguyên để OPS xử lý.
- Tắt worker trong test bằng `fashion.order.saga.enabled=false` và gọi `SagaWorker.runOnce()` trực tiếp.

## 5. Dọn giỏ (REQ ORD-09)

- Khi `RESERVE` thành công, cùng transaction `enqueue` task `CART_CLEANUP` (business key `order_id`, payload cart_id,
  actor_key, items item_id/item_version/quantity). Task gọi cart checkout-cleanup (`Idempotency-Key = order_id`,
  `Cart-Actor = actor_key`); `retained`/`missing` là kết quả hợp lệ. Lỗi → retry theo `BackgroundTaskRepository`, hết lượt →
  `MANUAL`; **không** ảnh hưởng trạng thái đơn.
- Đơn hủy vì hết hàng không dọn giỏ (khách còn giỏ để sửa và đặt lại; unique một phần §2 cho phép).

## 6. Payment events (T07)

- `PAYMENT_COMPLETED`: ghi `orders.paid_at` (nếu null) và `payment_state = PAID` trên saga, trong transaction inbox; không đổi
  `status` (COD: tiền về sau giao — ORD-03/04 xử lý; đơn đã `CANCELLED` mà nhận tiền → ghi lại và log cảnh báo, refund là
  PAY-04/ORD-04).
- `PAYMENT_FAILED` (reason `CLOSED` do chính order đóng) và `PAYMENT_CREATED`: chỉ đánh dấu đã xử lý.
- Event cũ hơn trạng thái hiện tại không hạ trạng thái.

## 7. Guest mất response khi tạo đơn

Ràng buộc ở 08 ORD-01: guest mất response, token hết hạn, giỏ đã được dọn. Giỏ vẫn `ACTIVE` sau cleanup nên guest lấy được
checkout token mới; nhưng ORD-01 kiểm quote và snapshot giỏ trước khi chạm UNIQUE giỏ/version, nên request với quote cũ dừng
ở `QUOTE_EXPIRED`/`VERSION_CONFLICT` và không tìm lại được đơn.

Sửa trong ORD-02: ngay sau lookup idempotency (trước kiểm quote), nếu đã có đơn **chưa hủy** của **cùng actor** với
`(cart_id, cart_version)` của request → `409 CART_ALREADY_ORDERED` + `order_no` (guest kèm cookie `order_access`, như ORD-01).
Chỉ dùng dữ liệu chính request đã gửi và actor đã xác minh (checkout token gắn đúng giỏ), nên không lộ đơn của ai khác (T21).
FE (14 §3) giữ `cart_id` + `cart_version` đã submit (không phải secret, có thể ở sessionStorage) để gửi lại. Test: guest tạo
đơn, giỏ được dọn, token mới + quote_token cũ đã hết hạn + key mới → 409 kèm cookie đọc được đơn.

## 8. API thay đổi

- `GET /api/v1/orders/{order_no}` và create: `processing_status` = `PROCESSING` (PENDING hoặc CANCELLING), `READY` (CONFIRMED),
  `FINISHED` (CANCELLED); `reason_code` = `cancel_reason` khi hủy; `retry_after_seconds` chỉ khi PROCESSING.
- `GET /orders/mine` lọc được các trạng thái mới (đã có enum).
- Contract `order.yaml` cập nhật mô tả + ví dụ; không endpoint mới.

## 9. Test (PostgreSQL Testcontainers; inventory/payment/cart là HTTP stub, worker gọi tay)

- Happy COD: PENDING → reserve → payment → CONFIRMED; history 2 dòng; outbox `ORDER_CONFIRMED`; task cleanup một dòng; key/hash
  reserve đúng công thức 03 §4 (so với vector cố định).
- T03: reserve timeout → `UNKNOWN`, lần sau gửi cùng key/hash, nhận 200 reservation cũ → HELD một lần.
- Hết hàng → CANCELLING → release không cần (state NONE) + close payment → CANCELLED `OUT_OF_STOCK`, giỏ không dọn, khách tạo
  đơn mới cùng giỏ/version được (unique một phần).
- Payment 409 `PAYMENT_CLOSED` → release reservation (snapshot gốc + hash) + close → CANCELLED.
- Hết hạn ở `WAIT_OPS` (ShiftClock) → release + close → CANCELLED `EXPIRED`, `ORDER_CANCELLED`.
- Lease: worker A lấy lease, lease hết, worker B hoàn tất; kết quả muộn của A bị bỏ (CAS) — không ghi đè.
- Hết retry → MANUAL; dependency lỗi không làm mất nghĩa vụ.
- Cleanup: cart 503 → task retry, đơn vẫn CONFIRMED; trả `retained` vẫn DONE.
- T07: `PAYMENT_COMPLETED` đến khi đơn còn PENDING → `paid_at` ghi, saga vẫn tiếp tục tới CONFIRMED; event trùng một lần.
- T23: outbox cùng TX với chuyển trạng thái (rollback khi CAS thua → không event).
- Mutation như ORD-01 (CAS lease, cùng key retry, release trước CANCELLED).
- Smoke: tạo đơn guest (bước ORD-01) rồi poll GET tới `CONFIRMED` qua Gateway (`ORD-02 saga confirm PASS`).
- Tài liệu: 03, 05 (V002), 06 §5.1/§5.4, 08, 10, 12, 14 (processing_status), README, CLAUDE.md, evidence.

## 10. Rủi ro

- Worker một instance local; nhiều instance dựa vào `SKIP LOCKED` + lease (đã thiết kế, chưa đo tải).
- `MANUAL` chưa có UI/alert (ADM-02/PLT-05) — chỉ metric và log.
- Release trả `COMMITTED` chỉ có thể xảy ra sau ORD-03; xử lý đầy đủ (return/restore) thuộc ORD-04.

## 11. Lựa chọn mặc định của agent (chủ dự án duyệt cùng spec 2026-10-20)

1. Saga chạy bằng **worker nền**, create không chạy inline → luôn `202`, FE poll (14 §3). Không tối ưu `201` nhanh.
2. Thứ tự `RESERVE → CREATE_PAYMENT` (06 §5.1); COD xong payment là `CONFIRMED` + `WAIT_OPS` tới `expires_at` (24 h).
3. **Hết hạn trong ORD-02** (worker hủy saga quá deadline, release + close); ORD-03 thêm OPS xác nhận và race commit/expire.
4. CANCEL **luôn close payment** kể cả chưa từng tạo (tombstone chặn create đến muộn, PAY-08).
5. Key nghiệp vụ cố định: `reserve:{order_id}`, `payment:{order_id}`, `release:{order_id}`, `close:{order_id}`, cleanup = `order_id`.
6. Backoff tối đa 5 phút, 30 lần rồi `MANUAL`; lease 30 s; poll 1 s, batch 10.
7. V002 đổi UNIQUE `(cart_id, cart_version)` thành unique một phần (bỏ qua đơn `CANCELLED`) thay vì bump version giỏ khi
   bù trừ (không thêm API cart).
8. Dọn giỏ chỉ sau khi giữ hàng thành công; đơn hủy vì hết hàng giữ nguyên giỏ.
9. `PAYMENT_COMPLETED` chỉ ghi `paid_at`, không đổi `status` trong ORD-02.
10. Không phát `ORDER_PAID` (COD dùng ở ORD-03/04).
11. Guest tìm lại đơn bằng `(cart_id, cart_version)` cùng actor trước kiểm quote (§7), thay vì API tra cứu mới.
