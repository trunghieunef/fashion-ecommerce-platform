# INV-02 — reservation đa SKU: reserve, commit, release, expire và tombstone

**Trạng thái: đã duyệt ngày 2026-10-16** (phương án A). Plan:
[INV-02](../plans/2026-10-16-inv-02-reservations.md); làm trên `dev`, chỉ push/mở PR khi chủ dự án yêu cầu.
Khi lập plan chốt cách biểu diễn trong envelope `ApiError` chung: OUT_OF_STOCK dùng
`errors[{field: "items[<SKU>]", message: "requested=<n> available=<m>"}]`; "409 CONFLICT" khác hash có
message `RESERVATION_CONFLICT`; mảng items thô tối đa 100 phần tử; body có field lạ → 400.

`TASK:INV-02` · `REQ:INV-02`, `REQ:INV-03`, `REQ:INV-06`.
Dependency: INV-01 (Done local 2026-10-16), PLT-02 (contract core), PLT-03 (outbox), SEC-01
(service token ADR-19). Base là `dev/7530411`. Nguồn chủ quản:
[03 Interfaces](../../design/03_interfaces.md) §1.3, §4 (internal inventory) và §5,
[05 Database](../../design/05_database_design.md) §8,
[06 Flows](../../design/06_service_flows.md) §6.1 và §13 (T01–T05, T09),
[11 Test strategy](../../quality/11_test_strategy.md),
[spec INV-01](2026-10-15-inv-01-stock-ledger-design.md).

## 1. Phạm vi và quyết định đã chốt

INV-02 cho phép order-service giữ hàng cho **toàn bộ đơn** (all-or-nothing), rồi commit (trừ kho),
release, hoặc để reservation tự hết hạn; retry, request đến muộn và thao tác đồng thời không được
làm lệch `on_hand`/`reserved`. Đây là nền cho ORD-02 (saga), QA-01 và checkout G1/G2.

Quyết định trong phiên 2026-10-16:

- **Phương án A:** bảng reservation là nguồn sự thật; dedupe bằng `UNIQUE(order_id)` + `request_hash`
  và state machine, không lưu response vào `idempotency_requests`; job hết hạn quét bảng reservation
  bằng `FOR UPDATE SKIP LOCKED`, không dùng `background_tasks` hay lease. Loại phương án order-service
  tự release khi đến hạn (trái 05: hàng bị giữ mãi nếu order chết).
- **Giới hạn input:** 1–100 SKU khác nhau sau khi gộp, quantity 1–99 mỗi SKU sau khi gộp,
  `now() < expires_at <= now() + 24h` (COD giữ 24h, online 15 phút theo 06). Vi phạm → 400.
- **Thiếu hàng:** `409 OUT_OF_STOCK` kèm từng SKU `{sku, requested, available}`; SKU không tồn tại
  hoặc `INACTIVE` tính là thiếu với `available = 0`.
- **Caller:** chỉ `order-service`. Order-service chưa tồn tại nên kiểm bằng integration test với
  service token synthetic; Compose khai báo sẵn caller cho ORD-02.

Không thuộc INV-02: return/restore (INV-03), gia hạn reservation, UI admin xem reservation,
consumer phía order, alert theo ngưỡng (chỉ có metric), Kafka SASL/TLS staging (PLT-04).

## 2. Schema (V002)

`V002__stock_reservations.sql` trong DB `inventory`, append-only, không sửa V001.

| Bảng | Cột | Ràng buộc |
|---|---|---|
| `stock_reservations` | `id uuid PK`, `order_id uuid`, `order_no varchar(32)`, `request_hash char(64)`, `status varchar(16)`, `expires_at timestamptz`, `committed_at timestamptz?`, `released_at timestamptz?`, `release_reason text?`, `version bigint`, `created_at`, `updated_at` | UNIQUE `order_id`; status ACTIVE/COMMITTED/RELEASED/EXPIRED; `version >= 1`; `committed_at` có khi và chỉ khi COMMITTED; `released_at` có khi RELEASED/EXPIRED; partial index `(expires_at) WHERE status='ACTIVE'` |
| `stock_reservation_items` | `reservation_id uuid FK`, `sku varchar(64) FK stock_items`, `quantity int` | PK `(reservation_id, sku)`; quantity 1–99 |

Khác 05: thêm `version` (03 §5 yêu cầu event reservation có version theo reservation) và
`release_reason`. `version` = 1 khi reserve hoặc tạo tombstone, +1 mỗi lần chuyển trạng thái.
Tombstone giữ items từ snapshot gốc; FK tới `stock_items` vẫn áp dụng nên SKU lạ trong snapshot
release → 400. Quyền runtime: SELECT/INSERT/UPDATE, không DELETE/TRUNCATE. Cập nhật 05 cùng thay đổi.

## 3. Internal API

Chỉ `order-service`; thứ tự kiểm: service token (401 `INVALID_SERVICE_TOKEN`) → caller
(403 `CALLER_NOT_ALLOWED`) → body. Mọi response `Cache-Control: no-store`, kể cả lỗi. Không route
qua Gateway (Gateway `/internal/**` trả 404). Mutation yêu cầu header `Idempotency-Key`
(thiếu → 400); hiệu ứng được dedupe theo `order_id`, ledger dùng `operation_key = order:<order_id>:<OP>`.

| Route | Request | Response |
|---|---|---|
| `POST /internal/api/v1/inventory/reserve` | `order_id, order_no, items[{sku, quantity}], expires_at, request_hash` | `201` tạo mới; `200` cùng order và cùng hash (trả reservation hiện có); `400 REQUEST_HASH_MISMATCH` hash không khớp snapshot đã normalize; `409 CONFLICT` cùng order khác hash; `409 RESERVATION_CLOSED` tombstone/terminal; `409 OUT_OF_STOCK` kèm `items[{sku, requested, available}]` |
| `POST /internal/api/v1/inventory/commit` | `order_id` | `200 COMMITTED` (lặp lại vẫn 200, không trừ thêm); `409 RESERVATION_EXPIRED` (đã chuyển EXPIRED và nhả hàng); `409 RESERVATION_CLOSED` khi RELEASED; `404 RESERVATION_NOT_FOUND` |
| `POST /internal/api/v1/inventory/release` | `order_id, original_reservation{order_no, items, expires_at}, original_request_hash, reason` | `200` với `status` RELEASED/EXPIRED/COMMITTED; COMMITTED kèm `restore_required: true`; chưa có reservation → tạo tombstone RELEASED; `400 REQUEST_HASH_MISMATCH`; `409 CONFLICT` khi hash khác reservation đã lưu |
| `GET /internal/api/v1/inventory/reservations/{order_id}` | — | `200` reservation; `404 RESERVATION_NOT_FOUND` |

Reservation trả về: `reservation_id, order_id, order_no, status, expires_at, items[{sku, quantity}],
version, committed_at?, released_at?, release_reason?`.

**`request_hash`** = SHA-256 hex (lowercase) của chuỗi UTF-8:
`order_id|order_no|expires_at|SKU1:q1,SKU2:q2,...`

- `order_id` dạng UUID lowercase; `order_no` 1–32 ký tự, escape `\` thành `\\` rồi `|` thành `\|`
  theo quy tắc canonicalization ở 03; `expires_at` UTC định dạng `yyyy-MM-ddTHH:mm:ss.SSSZ`
  (input có độ chính xác dưới mili giây → 400).
- SKU chuẩn hóa như INV-01 (`Sku.canonical`: trim, chữ hoa ASCII), gộp SKU trùng bằng cách cộng
  quantity, sắp xếp tăng dần theo SKU. Ký tự SKU không chứa `| : ,` nên không cần escape.
- Inventory tự tính lại hash và so với `request_hash`/`original_request_hash` của caller; release
  tính từ `original_reservation`, không từ body release. Quy tắc ghi vào 03 để order-service dùng.

## 4. Luồng transaction

Mọi thao tác khóa theo thứ tự cố định: **reservation trước, rồi SKU `ORDER BY sku FOR UPDATE`**
(dùng lại `lockForUpdate` của INV-01, cùng thứ tự với adjust/import nên không deadlock). Không gọi
HTTP trong transaction. Thời gian luôn là `now()` của DB.

**Reserve** (một transaction):

1. Validate + normalize trước khi chạm DB; lỗi → 400.
2. `INSERT ... ON CONFLICT (order_id) DO NOTHING`; nếu đã có, `SELECT ... FOR UPDATE`:
   khác hash → 409 CONFLICT; cùng hash và ACTIVE/COMMITTED → 200 hiện trạng (T03); cùng hash và
   RELEASED/EXPIRED (gồm tombstone) → 409 RESERVATION_CLOSED (T04). Reserve đồng thời cùng order
   xếp hàng ở unique index.
3. Khóa SKU; một SKU thiếu, không tồn tại hoặc INACTIVE → **rollback toàn bộ**, kể cả row reservation
   vừa insert: không giữ phần nào, không outbox (T01, T02). Response 409 dựng từ dữ liệu đã đọc.
4. Mỗi SKU `reserved += q`, `version + 1`, `updated_at = now()`; insert items; ledger `RESERVE`
   (`reserved_delta = +q`, `on_hand_delta = 0`, `order_id`); outbox `INVENTORY_RESERVED` +
   `INVENTORY_UPDATED` mỗi SKU.

**Commit:** khóa reservation.

- ACTIVE và `now() < expires_at` → khóa SKU; `on_hand -= q`, `reserved -= q`; COMMITTED,
  `committed_at`, `version + 1`; ledger `COMMIT` (`on_hand_delta = -q`, `reserved_delta = -q`);
  outbox `INVENTORY_DEDUCTED` + `INVENTORY_UPDATED`.
- ACTIVE nhưng đã quá hạn → thực hiện expire (như job) trong cùng transaction, **commit**, rồi trả
  409 RESERVATION_EXPIRED. Service trả kết quả, không ném exception để không rollback.
- COMMITTED → 200 hiện trạng (T09). RELEASED/EXPIRED → 409 tương ứng.

**Release:** tính hash từ `original_reservation`, phải bằng `original_request_hash` (400 nếu sai).
Snapshot gốc chỉ được validate hình dạng (SKU, quantity, số SKU, định dạng thời gian), không áp
giới hạn `expires_at > now()` vì release có thể đến sau hạn.

- Chưa có reservation → insert tombstone RELEASED (`released_at`, `release_reason`, items snapshot,
  `version = 1`), outbox `INVENTORY_RELEASED`; không đụng `stock_items`.
- Có nhưng `request_hash` đã lưu khác → 409 CONFLICT.
- ACTIVE → `reserved -= q`; RELEASED; ledger `RELEASE` (`reserved_delta = -q`); outbox.
- COMMITTED → 200 `restore_required: true`, không đụng kho (restore là INV-03).
- RELEASED/EXPIRED → no-op, 200 hiện trạng.

**Expire job:** `@Scheduled(fixedDelay)` mặc định 5 s, batch 100, cấu hình
`fashion.inventory.reservation-sweep.*`; tắt cùng cờ scheduling như media jobs của catalog.

- Lấy id `status = 'ACTIVE' AND expires_at <= now() ORDER BY expires_at LIMIT :batch` (không khóa).
- Mỗi id một transaction: `SELECT ... FOR UPDATE SKIP LOCKED` + kiểm lại status/hạn; bỏ qua nếu commit
  đang giữ hoặc đã thắng (T05). `reserved -= q`, EXPIRED, `release_reason = 'expired'`, ledger
  `RELEASE`, outbox. Lỗi một id không chặn id khác; vòng sau thử lại.
- Nhiều instance chạy đồng thời an toàn nhờ row lock + kiểm status; độ trễ tối đa bằng chu kỳ quét,
  không ảnh hưởng đúng sai vì commit tự kiểm hạn.

## 5. Event

`stock-events.schema.json` thêm `$defs/ReservationLifecycle`:
`order_id, order_no, reservation_id, status, expires_at, items[{sku, quantity}], reason (nullable),
version`, `additionalProperties: false`.

| Event | Khi nào | Topic / partition / aggregate |
|---|---|---|
| `INVENTORY_RESERVED` | Reserve thành công | `inventory.events` / `order_no` / `reservation_id` |
| `INVENTORY_DEDUCTED` | Commit | như trên |
| `INVENTORY_RELEASED` | Release, expire hoặc tombstone; phân biệt bằng `status` | như trên |

`aggregate_version` = `version` của reservation. `INVENTORY_UPDATED` phát cho mỗi SKU có
`reserved`/`on_hand` đổi; `INVENTORY_RESTOCKED` giữ quy tắc INV-01 (available từ 0 lên dương), nên
release/expire làm hàng có lại cũng phát RESTOCKED. Registry thêm ba dòng mới.

## 6. Vận hành

- Metric: counter `inventory_reservations_total{operation, outcome}` (reserve
  created/replayed/out_of_stock/conflict/closed; commit committed/replayed/expired/closed; release
  released/tombstone/noop/restore_required; expire expired); gauge số reservation ACTIVE đã quá hạn
  chưa quét để phát hiện job kẹt.
- Log JSON không chứa token; chỉ order_id/order_no/reservation_id và SKU.
- Compose/local-up: thêm public key caller `order-service` vào `INVENTORY_INTERNAL_CALLERS` theo
  mẫu `cart-service`. Smoke chưa gọi reservation vì chưa có caller thật; ORD-02 bổ sung.

## 7. Kiểm thử

Integration với PostgreSQL thật (Testcontainers); race dùng latch/thread thật, không mock.

| Nhóm | Case |
|---|---|
| T01 | Hai đơn đồng thời tranh SKU cuối: đúng một reservation; stock không âm; một dòng ledger RESERVE |
| T02 | SKU A đủ, B thiếu → không giữ A, không reservation, không outbox; tương tự SKU không tồn tại/INACTIVE |
| T03 | Retry cùng hash tuần tự và đồng thời → một reservation, `reserved` cộng một lần; khác hash 409; hash sai snapshot 400 |
| T04 | Release trước reserve → tombstone; reserve đến sau 409 RESERVATION_CLOSED, không giữ hàng; release lặp no-op |
| T05 | Commit và job đồng thời trên reservation vừa hết hạn → đúng một kết quả, ledger khớp; commit sau hạn 409 và EXPIRED vẫn được lưu |
| T09 | Commit lặp 200 không trừ thêm; query trả COMMITTED |
| Khác | Release sau commit → `restore_required`, kho không đổi; reserve đa SKU đồng thời với adjust/import không deadlock; biên: 0/101 SKU, qty 0/100 sau gộp, `expires_at` quá khứ/quá 24h/độ chính xác dưới ms, SKU trùng gộp; 401/403 trước validate; `no-store`; thiếu `Idempotency-Key` 400 |
| Event | Outbox đúng loại/partition/version; payload hợp lệ schema mới; relay đến Kafka |

Thêm: contract test schema/registry và `inventory.yaml` (Redocly + examples), Gateway boundary
404 cho route reservation, mutation các guard chính (kiểm hạn ở commit, kiểm status ở job, so hash,
rollback thiếu hàng) phải bị test bắt.

## 8. Tài liệu đồng bộ

03 (route, mã lỗi, quy tắc `request_hash`), 05 (V002, `version`, `release_reason`), 06 §6.1
(trạng thái hiện thực), 10 (trạng thái task), 12 (lệnh), README inventory-service, contracts,
evidence và handoff INV-02. 08 chỉ cập nhật nếu phát sinh quyết định mới.
