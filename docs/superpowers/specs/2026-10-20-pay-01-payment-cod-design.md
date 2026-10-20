# PAY-01 — payment-service: payment COD, close/tombstone, bằng chứng thu, settlement gross-fee-net

`TASK:PAY-01` · `REQ:PAY-03`, `REQ:PAY-07`, `REQ:PAY-08` · dependency PLT-03, USR-02, SHP-02 (đều Done local).
Trạng thái: **đã duyệt** 2026-10-20. Chủ dự án giao agent tự chốt mặc định; lựa chọn của agent ghi ở §9.
Nguồn: 03 §2.3/§3/§4/§5.3, 05 §9 + bảng bổ sung `cod_settlements`, 06 §7 và §17.1, 13 (quyền `payment.*`), T27 (06 §13).

## 1. Mục tiêu và ngoài phạm vi

- Order (ORD-02/03) tạo **một payment cho một đơn** từ snapshot đã xác thực, giữ một business reference suốt vòng đời
  (PAY-07). Phương thức trong PAY-01: **COD** → `COD_PENDING` ngay.
- Order **close** payment khi hủy: có row thì đóng, chưa có row thì lưu **tombstone** `FAILED` + `closed_at` để create đến
  sau không mở lại (PAY-08). `closed_at` độc lập trạng thái tiền: bằng chứng thu đến muộn vẫn được ghi.
- Bằng chứng thu COD từ shipping (`COD_COLLECTED`) → `COD_COLLECTED`; `COD_REMITTED` chỉ là bằng chứng. DELIVERED không
  bao giờ tự tạo thu tiền (T27).
- FINANCE ghi **settlement** `gross = fee + net`; đủ nghĩa vụ gross → `SUCCESS` + `PAYMENT_COMPLETED` (PAY-03).
- FINANCE xem danh sách/chi tiết payment (giao dịch, settlement, mục đối soát mở).

Không làm: VNPay/MoMo adapter, URL, IPN/return, query UNKNOWN worker (PAY-02/03 — schema đã có cột); refund (PAY-04, bảng
`refunds` thêm migration sau); resolve đối soát và batch import (PAY-05); route public `GET /orders/{order_no}/payment`
(thuộc order, ORD); notification (NOT-01); UI (ADM-02).

Anti-goals: amount/method từ client làm authority; hai payment hoặc hai reference cho một đơn; close xóa dấu vết tiền đã
thu; coi DELIVERED/COD_REMITTED là tiền đã về; tự ép khớp khi số tiền lệch; ACK Kafka trước commit.

## 2. Service và hạ tầng

- `services/payment-service` (Java 21, Spring Boot 4.0, JdbcClient, Flyway), port **8086**, DB `payment` (role
  `payment_migration`/`payment_runtime`, init idempotent như shipping). platform-durability (idempotency, outbox, inbox),
  platform-security (Access token FINANCE, service token ADR-19), platform-messaging (outbox relay + inbox consumer như
  inventory).
- Gateway: `/admin/api/v1/payments/**` → payment; không có route public; `/internal` không route.
- Kafka: phát `payment.events` (key `order_no`); tiêu thụ `shipping.events` (group `payment-service`), chỉ xử lý
  `COD_COLLECTED`/`COD_REMITTED`, bỏ qua loại khác. local-up tạo topic `payment.events` idempotent.
- Internal callers: `PAYMENT_INTERNAL_CALLERS` (mặc định `order-service`; local-up sinh key, rỗng tới khi có order-service).

## 3. Schema (V001, theo 05 §9)

- `payments` đúng cột 05 (gồm `refunded_amount`, `refund_reserved_amount`, `next_reconcile_at` để dành PAY-02/04) + thêm
  `snapshot jsonb` (snapshot order đã hash, cần cho tombstone theo 05 "Payment close") và `created_at`/`updated_at`.
  CHECK: method `COD|VNPAY|MOMO`, status theo 05, `currency = 'VND'`, `amount >= 0`, `0 <= refunded + refund_reserved <= amount`.
  UNIQUE `order_id`, `order_no`, `provider_request_id`, `(method, provider_txn_id)` khi có.
- `payment_transactions` theo 05; source `CREATE|WEBHOOK|QUERY|COD|CLOSE|SETTLEMENT` (**thêm CLOSE, SETTLEMENT** để mọi
  thay đổi trạng thái có một dòng bằng chứng); UNIQUE `(payment_id, source, provider_event_key)`.
- `cod_settlements` theo 05 (bảng bổ sung): `gross = fee + net`, amounts ≥ 0, UNIQUE `(payment_id, settlement_ref)`.
- `reconciliation_items` theo 05 (dùng cho lệch số tiền/phương thức từ bằng chứng COD).
- `idempotency_requests`, `outbox_events`, `processed_events` (bản sao DDL platform-durability).
- Runtime: không DELETE bảng nào; không UPDATE `payment_transactions`/`cod_settlements` (append-only).

## 4. State machine trong PAY-01 (tập con của 06 §17.1)

| Từ → đến | Trigger | Event |
|---|---|---|
| (không có) → COD_PENDING | Create COD | `PAYMENT_CREATED` |
| (không có) → FAILED + `closed_at` | Close trước create (tombstone) | `PAYMENT_FAILED` (reason `CLOSED`) |
| COD_PENDING → FAILED + `closed_at` | Close trước thu | `PAYMENT_FAILED` |
| COD_PENDING → COD_COLLECTED | `COD_COLLECTED` đúng order/method/amount | — |
| FAILED (đã close, COD) → COD_COLLECTED, giữ `closed_at` | Bằng chứng thu đến muộn | — |
| COD_COLLECTED → SUCCESS, `paid_at` | Σ settlement gross = amount | `PAYMENT_COMPLETED` (cả khi đã close: order xử lý refund) |
| COD_COLLECTED/SUCCESS → cùng trạng thái + `closed_at` | Close sau khi đã thu | — (response cho order thấy tiền đã thu) |

Không có event riêng khi COD_COLLECTED: order nhận `COD_COLLECTED` trực tiếp từ `shipping.events` (03 §5).

## 5. API

| Method / path | Ai | Hành vi |
|---|---|---|
| `POST /internal/api/v1/payments` | `order-service` | Snapshot `{order_id, order_no, user_id?, method, amount, currency, expires_at}` + `Idempotency-Key` → payment |
| `GET /internal/api/v1/payments/by-order/{order_id}` | `order-service` | `{payment_id, method, amount, currency, status, closed_at, paid_at, version}` |
| `POST /internal/api/v1/payments/by-order/{order_id}/close` | `order-service` | Snapshot gốc + `reason` (1..500) + key → payment đã close hoặc tombstone |
| `GET /admin/api/v1/payments`, `/{id}` | `payment.read` | List (filter status, cursor `(created_at,id)`) / detail + transactions + settlements + reconciliation items |
| `POST /admin/api/v1/payments/{id}/cod-settlements` | `payment.settle` | `{settlement_ref, gross_amount, fee_amount, net_amount, evidence_ref, expected_version}` + key |

- **Create**: `amount` 1..10^12 (O05: total ≤ 0 → 422 `PAYMENT_AMOUNT_UNSUPPORTED`), `currency` chỉ `VND`, method
  `VNPAY|MOMO` → 422 `PAYMENT_METHOD_UNAVAILABLE` tới PAY-02/03. `request_hash` = SHA-256 snapshot chuẩn hóa.
  `provider_request_id = order_no` (cố định qua mọi retry). Một TX dưới advisory lock theo `order_id`: row đã close/tombstone →
  409 `PAYMENT_CLOSED` (kiểm trước hash); cùng hash → trả bản đó (200); khác hash → 409 `PAYMENT_CONFLICT`; chưa có → insert
  COD_PENDING + transaction `CREATE` + outbox. Không có URL cho COD.
- **Close**: path ≠ body `order_id` → 400. Thiếu snapshot → 400 (03: không giả báo đã chặn). Khác hash → 409
  `PAYMENT_CONFLICT`. Theo §4; đã close → trả hiện trạng (idempotent). Transaction `CLOSE` ghi reason.
- **Settlement**: chỉ khi `COD_COLLECTED` (khác → 409 `PAYMENT_NOT_SETTLEABLE`); `gross = fee + net`, mỗi số 0..10^12
  (sai → 400); Σgross (kể cả dòng mới) > amount → 409 `SETTLEMENT_EXCEEDS_AMOUNT`; `settlement_ref` đã dùng cho payment này
  → 409 `SETTLEMENT_REF_REUSED`; version sai → 409 `VERSION_CONFLICT`. Σgross < amount: lưu, giữ COD_COLLECTED (nhiều đợt).
  Σgross = amount → SUCCESS. `confirmed_by` = actor. Transaction `SETTLEMENT`.
- Lỗi 4xx không được lưu: TX rollback cả key, retry được đánh giá lại và nhận cùng lỗi. Create kiểm closed trước các luật 422.
  amount âm là 400 (snapshot sai), amount 0 là 422.

## 6. Bằng chứng COD từ shipping (consumer)

- Inbox dedupe theo `event_id`; ACK sau commit (InboxListenerSupport). Lock payment theo `order_id`.
- Không tìm thấy payment → lỗi retryable; quá `fashion.payment.cod-orphan-grace` (mặc định 10 phút, tính từ `occurred_at` của
  envelope) → `PermanentEventException` vào `shipping.events.DLT` (`messaging_dlt_total`) để một event mồ côi không chặn
  partition (review PR, I1). Không tạo payment từ event.
- `COD_COLLECTED`: insert transaction `COD` (`provider_event_key = event_ref`, amount = gross). Method khác COD hoặc
  `gross_amount ≠ amount` → **không** đổi trạng thái, mở `reconciliation_items` (`kind` `COD_METHOD_MISMATCH`/
  `COD_AMOUNT_MISMATCH`, expected/observed, evidence = event ref) — không tự ép khớp. Khớp → chuyển theo §4; trạng thái
  khác COD_PENDING/FAILED-đã-close → chỉ giữ bằng chứng.
- `COD_REMITTED`: chỉ insert transaction `COD` (status `REMITTED`), không đổi trạng thái (T27, 06 §13).

## 7. Events và contract

`contracts/events/payment-events.schema.json` + registry: `PAYMENT_CREATED`, `PAYMENT_COMPLETED`, `PAYMENT_FAILED`
(topic `payment.events`, key `order_no`, aggregate payment id), payload theo 03 §5.3: order_id, order_no, payment_id, method,
amount, currency, status, paid_at, provider_txn_id (null), reason. OpenAPI `payment.yaml` (admin) + `payment-internal.yaml`,
fixture valid/invalid, Redocly lint, contract test Python.

## 8. Test (PostgreSQL Testcontainers) và tài liệu

| Nhóm | Case |
|---|---|
| Create | COD → COD_PENDING + transaction + outbox; replay cùng key/body; cùng order khác body → 409; song song hai create → một payment; amount 0 → 422; VNPAY → 422; currency khác → 400; caller không phải order-service → 403 trước body |
| Close | close COD_PENDING → FAILED + closed_at + PAYMENT_FAILED; close trước create → tombstone, create sau → 409 PAYMENT_CLOSED; close song song create → một kết quả nhất quán; close lặp idempotent; close sau COD_COLLECTED giữ status |
| COD evidence | collected khớp → COD_COLLECTED; event lặp → một transaction; amount lệch → reconciliation item, status giữ; late collected trên payment đã close → COD_COLLECTED, closed_at giữ; REMITTED không đổi status (**T27**); payment chưa có → retry |
| Settlement | gross ≠ fee+net → 400; hai đợt cộng đủ → SUCCESS + PAYMENT_COMPLETED một lần; vượt amount → 409; ref dùng lại → 409; chưa COD_COLLECTED → 409; song song hai settlement → không vượt amount |
| Quyền/role | `payment.read`/`payment.settle`; runtime không DELETE/UPDATE bảng append-only |
| Gateway | admin route forward, internal 404 |

Smoke local: admin route qua Gateway (403 không quyền, 404 id lạ). Mutation các guard chính. Verify theo module (tắt
compose), contract, docs, CI. Tài liệu: README service, contracts, 03 (§3/§4 payment implemented, path close), 05 (cột
`snapshot`, source CLOSE/SETTLEMENT), 06 §7.2, 08, 10, 12, CLAUDE.md, compose/local-up/.env.example, evidence.

## 9. Lựa chọn mặc định của agent (cần chủ dự án xác nhận)

1. Service mới `payment-service`, port 8086, DB `payment`; PAY-01 **chỉ COD**, VNPAY/MOMO trả 422 tới PAY-02/03.
2. `provider_request_id = order_no` làm business reference cố định (PAY-07).
3. Path close là `/by-order/{order_id}/close` (03 ghi `/payments/{order_id}/close`, trùng dạng với `/payments/{payment_id}/…`
   — đổi như shipping, sửa 03).
4. Thêm cột `snapshot` và source `CLOSE`/`SETTLEMENT` so với 05 (cập nhật 05).
5. Close-before-create phát `PAYMENT_FAILED` reason `CLOSED` để order có một tín hiệu nhất quán.
6. Không phát event khi COD_COLLECTED (order đọc từ `shipping.events`); `PAYMENT_COMPLETED` phát cả khi payment đã close.
7. Settlement nhiều đợt; thiếu thì giữ COD_COLLECTED (không tự mở discrepancy cho phần thiếu — SLA thuộc O04/PAY-05);
   vượt amount thì từ chối.
8. Lệch phương thức/số tiền từ shipping → `reconciliation_items` OPEN, không đổi trạng thái; resolve ở PAY-05.
9. Payment chỉ lưu `user_id`, không PII liên hệ/địa chỉ, nên O06 không chặn PAY-01 (vẫn chặn ORD-01).
