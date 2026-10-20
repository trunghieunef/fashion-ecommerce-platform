# SHP-02 — SELF evidence events, COD collected/remitted, cancel tombstone

`TASK:SHP-02` · `REQ:SHP-02`, `REQ:SHP-04`, `REQ:SHP-06` · dependency SHP-01 (Done local, merge `cb4fe9b`).
Trạng thái: **đã duyệt** 2026-10-20 (chủ dự án duyệt cả các mặc định ở §8).
Acceptance (10): SELF evidence events, cancel tombstone, COD collected/remitted payload; T15–T17; không SHIPPING ngay khi CREATED.

## 1. Mục tiêu và ngoài phạm vi

- OPS ghi **sự kiện SELF có bằng chứng**: gán người giao, bàn giao, giao xong, giao thất bại, giao lại, bắt đầu hoàn, đã hoàn
  về; theo một state machine, có version, audit và idempotency (03 `POST /shipping/shipments/{id}/self-events`).
- OPS ghi **thu COD** (shipper thu đủ tiền) và **nộp COD** (shipper nộp lại cho shop) — event tài chính riêng, không suy từ
  DELIVERED (06 §9).
- Order **hủy shipment** (03 `POST /shipping/shipments/{order_id}/cancel`): trước bàn giao thì CANCELLED; chưa có shipment thì
  lưu **tombstone** để create đến muộn bị từ chối (06 §5, SHP-06).
- Phát `SHIPMENT_STATUS_UPDATED`, `COD_COLLECTED`, `COD_REMITTED` qua outbox (`shipping.events`, key `order_no`).

Ngoài phạm vi: carrier/webhook (SHP-03); consumer phía order/payment (ORD-03/04, PAY-01); kiểm đếm hàng hoàn và cộng kho
(order → inventory, INV-03 đã có); `NOTIFY_SHIPMENT_STATUS` (chưa có notification-service, NOT-01); UI (ADM-02);
trạng thái UNKNOWN/MANUAL/retry (SELF không gọi bên ngoài nên không có UNKNOWN).

## 2. State machine SELF

| Sự kiện (`event_type`) | Từ | Sang | Bắt buộc | Ghi chú |
|---|---|---|---|---|
| `ASSIGN` | CREATED, PICKING | PICKING | `assigned_to` | PICKING = đã gán shipper, **chưa** lấy hàng; gán lại được khi còn PICKING |
| `HANDED_OVER` | PICKING | DELIVERING | `evidence_ref` | Set `handed_over_at` = `occurred_at`; mốc duy nhất để order sang SHIPPING |
| `DELIVERED` | DELIVERING | DELIVERED | `evidence_ref` | Không tự tạo COD_COLLECTED |
| `DELIVERY_FAILED` | DELIVERING | FAILED | `reason` | Không hủy đơn |
| `REDELIVER` | FAILED | DELIVERING | — | Nhánh giao lại hợp lệ (T15) |
| `RETURN_STARTED` | FAILED | RETURNING | `reason` | |
| `RETURNED` | RETURNING | RETURNED | `evidence_ref` | Chỉ ghi hàng đã về; kiểm đếm/cộng kho đi qua order → inventory (T17) |
| `COD_COLLECTED` | DELIVERED | (giữ) | `amount`, `evidence_ref` | `cod_amount > 0`, chưa thu, `amount = cod_amount` (D08: không giao một phần) |
| `COD_REMITTED` | DELIVERED | (giữ) | `amount`, `evidence_ref` | Đã thu, `amount = cod_collected − cod_remitted` (nộp đủ một lần) |

- Terminal: DELIVERED (chỉ còn hai sự kiện COD), RETURNED, CANCELLED. Không có cạnh lùi; cạnh không có trong bảng → 409
  `INVALID_TRANSITION` (errors: status hiện tại). CREATED/PICKING không bao giờ phát `handed_over_at` (T15: chưa SHIPPING
  trước bàn giao).
- `occurred_at` tùy chọn, không trước `created_at` của shipment hay sự kiện trước (review M8), không quá
  `clock_timestamp()` + 60 giây cho mọi loại (PR #30 M1: mốc bàn giao là mốc SHIPPING của order); thiếu thì lấy
  max(now, sự kiện trước). ASSIGN lại khi đang PICKING tăng version nhưng không phát event (không đổi status).
- **Sự kiện trên shipment CANCELLED** (PR #30 I1, 06 §17.2 "không âm thầm bỏ bằng chứng vật lý"): ghi history
  `applied = false` kèm evidence/actor, status giữ CANCELLED, trả 409 `CANCELLED_EVIDENCE_RECORDED`; kiểm trước
  `expected_version` (bản OPS đang cầm đã cũ vì cancel tăng version); 409 được lưu cho replay nên retry không ghi thêm.
  Chuyển MANUAL + incident chờ chủ dự án quyết (08). Trên CANCELLED không kiểm thứ tự `occurred_at` so với sự kiện
  trước (re-review PR #30), chỉ giữ giới hạn 60 giây và mốc tạo shipment.
- `evidence_ref` là mã tham chiếu bằng chứng (số biên nhận, mã ảnh…) 1–200 ký tự, không phải file; upload ảnh để sau.

## 3. API

### 3.1 OPS `POST /admin/api/v1/shipping/shipments/{id}/self-events` (permission `shipping.self_event`)

, expected_version, occurred_at?, assigned_to?, evidence_ref?, reason?, amount?}`, header
`Idempotency-Key` bắt buộc (actor `user:<id>`, operation `shipping.self_event`). Một TX:
1. `IdempotencyStore.begin` (cùng key + body → response cũ; khác body → 409 `IDEMPOTENCY_KEY_REUSED`).
2. `SELECT … FOR UPDATE` shipment (runtime có UPDATE trên `shipments`); không có → 404.
3. `expected_version` ≠ version → 409 `VERSION_CONFLICT` (`current=N`).
4. Kiểm cạnh + trường bắt buộc + số tiền (§2) → cập nhật status/assigned_to/handed_over_at/cod_*; version + 1.
5. History: `provider_event_key = self:<event_id>`, from/to status, `provider_occurred_at`, `applied = true`, `reason`,
   `sanitized_payload = {event_type, actor_id, evidence_ref, assigned_to, amount}` (không địa chỉ/điện thoại).
6. Outbox: đổi status → `SHIPMENT_STATUS_UPDATED`; COD → `COD_COLLECTED` / `COD_REMITTED`.
7. `finish`; trả 200 = **trạng thái** (`shipment_id, order_id, status, version, assigned_to, handed_over_at, cod_*`), không
   địa chỉ, để body lưu cho replay không chứa PII (review I3); chi tiết + history đọc bằng GET. COD sai điều kiện → 409
   `COD_NOT_COLLECTABLE` / `COD_NOT_REMITTABLE`, sai số tiền → 400.

### 3.2 Internal `POST /internal/api/v1/shipping/shipments/by-order/{order_id}/cancel` (caller `order-service`)

Path đặt dưới `by-order/` (khác 03 `{order_id}/cancel`) để không mơ hồ với `GET by-order/{order_id}` (Redocly
`no-ambiguous-paths`); 03 cập nhật theo.

Body = **snapshot create gốc** (`order_id, order_no, quote_id, cod_amount, to_address`, cùng parse/hash với create) + `reason`;
`Idempotency-Key` bắt buộc; `order_id` trong path phải bằng body. Một TX, advisory lock theo `quote_id` (cùng khóa với create):

| Hiện trạng | Kết quả |
|---|---|
| Chưa có shipment | Quote phải tồn tại (404 `QUOTE_NOT_FOUND`), khớp tỉnh/COD (409 `QUOTE_MISMATCH`) và chưa dùng (409 `QUOTE_ALREADY_USED`) → insert **tombstone** CANCELLED (`request_hash` của snapshot, fee/weight từ quote, tracking null) + history `cancel-before-create` + `SHIPMENT_STATUS_UPDATED` → 200 shipment view (như by-order: status, `handed_over_at`, version) |
| CREATED / PICKING | Hash snapshot khác → 409 `SHIPMENT_CONFLICT`; ngược lại → CANCELLED (version+1, history, event) → 200 |
| CANCELLED | 200 trạng thái hiện tại (idempotent theo order, mọi key) |
| DELIVERING / DELIVERED / FAILED / RETURNING / RETURNED | 409 `SHIPMENT_HANDED_OVER` (errors: status) — order đi luồng return, **không restore kho** (SHP-06, T16) |

Create sau tombstone hoặc sau khi hủy → 409 `SHIPMENT_CANCELLED` (kể cả cùng body, kể cả replay cùng key của create trước
khi hủy — review I1). Response 200 cancel là shipment view (`status`, `handed_over_at`, `version`); 409 `SHIPMENT_HANDED_OVER`
chỉ kèm `errors[status]` và được kiểm **trước** so snapshot (review M5); order đọc thêm bằng GET by-order nếu cần.

## 4. Schema (V004, append-only)

- Không cột mới bắt buộc: tombstone dùng cột sẵn có (status CANCELLED, tracking null, `provider_request_id = SELF-<order_id>`).
- `shipments`: thêm `CHECK (handed_over_at IS NOT NULL OR status IN ('PENDING','UNKNOWN','CREATED','PICKING','CANCEL_PENDING','CANCELLED','MANUAL'))`
  — trạng thái sau bàn giao phải có `handed_over_at`.
- Index `shipment_status_history (shipment_id, created_at, id)` đã đủ; không thêm bảng.

## 5. Events và contract

- `contracts/events/shipping-events.schema.json`: `ShipmentStatus` dùng cho `SHIPMENT_STATUS_UPDATED`; thêm `$defs.CodMovement`
  (03 §5.3): `order_id, order_no, payment_id (null — shipping chưa biết), shipment_id, event_ref (= history id),
  gross_amount, fee_amount (0 với SELF), net_amount, currency "VND", evidence_ref, occurred_at`.
- Registry: `SHIPMENT_STATUS_UPDATED`, `COD_COLLECTED`, `COD_REMITTED` → `shipping.events`, partition `order_no`, aggregate
  `shipment_id`; fixture valid cho mỗi loại.
- OpenAPI: `shipping.yaml` thêm self-events; `shipping-internal.yaml` thêm cancel; contract test Python.

## 6. Test (PostgreSQL Testcontainers)

| Nhóm | Ca chính |
|---|---|
| State machine | Đường đủ CREATED→PICKING→DELIVERING→DELIVERED; FAILED→REDELIVER→DELIVERED; FAILED→RETURNING→RETURNED; mọi cạnh sai → 409; terminal không đổi; CREATED/PICKING có `handed_over_at` null (T15) |
| Version/idempotency | stale version 409; cùng key replay không ghi history/outbox lần hai; khác body 409; hai OPS song song cùng version → một thắng |
| COD | thu khác `cod_amount`/đơn không COD/chưa DELIVERED → 409/400; thu hai lần → 409; nộp trước thu → 409; event payload khớp schema |
| Cancel | trước bàn giao → CANCELLED + event; tombstone rồi create (cùng/khác body) → 409 `SHIPMENT_CANCELLED`; cancel song song với create cùng quote → một thứ tự hợp lệ (T16); sau bàn giao → 409 `SHIPMENT_HANDED_OVER`; cancel lặp → idempotent; snapshot lệch → 409; path ≠ body → 400 |
| Auth | self-events cần `shipping.self_event` (401/403 trước body); cancel chỉ `order-service` |
| Audit | history có actor/evidence, không chứa địa chỉ; runtime không UPDATE/DELETE history |

Smoke: không thêm bước qua Gateway cho internal (order-service chưa có); self-events cần token OPS — smoke dùng admin bootstrap
như các bước OPS khác nếu sẵn có, nếu không thì chỉ integration test (ghi rõ ở evidence).

## 7. Tài liệu đồng bộ

03 (self-events, cancel, event payload), 05 (V004 CHECK), 06 §9 (bảng SELF/cancel implemented), 08 (quyết định SHP-02),
10 (trạng thái), 12 (lệnh), README service, evidence.

## 8. Lựa chọn mặc định của agent (cần chủ dự án xác nhận)

| # | Chọn | Lý do / phương án khác |
|---|---|---|
| 1 | PICKING = "đã gán shipper, chưa lấy hàng"; bàn giao là sự kiện riêng `HANDED_OVER` | 06 yêu cầu định nghĩa rõ PICKING; order chỉ SHIPPING ở mốc bàn giao |
| 2 | COD thu đủ một lần (`amount = cod_amount`), nộp đủ một lần | D08 không giao một phần; nộp nhiều đợt để sau nếu OPS cần |
| 3 | `evidence_ref` là chuỗi tham chiếu, chưa upload ảnh | Upload ảnh qua media cần thêm hạ tầng; có thể bổ sung ở ADM-02 |
| 4 | SELF event sai thứ tự/cũ → 409 (không ghi history `applied=false`), **trừ** shipment CANCELLED: ghi `applied=false` làm bằng chứng (PR #30 I1) | OPS cần thấy lỗi ngay; bằng chứng vật lý sau hủy không được mất (06 §17.2) |
| 5 | Tombstone cần quote tồn tại và khớp | Đủ dữ liệu cho các cột NOT NULL; không có quote thì create cũng không thể thành công |
| 6 | Hủy SELF trước bàn giao là tức thì (không giữ CANCEL_PENDING) | SELF không có bên thứ ba cần xác nhận; CANCEL_PENDING cho carrier (SHP-03) |
| 7 | Không phát NOTIFY_SHIPMENT_STATUS | Chưa có notification-service; thêm ở NOT-01 |
