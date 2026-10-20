# payment-service

`TASK:PAY-01` · `REQ:PAY-03`, `REQ:PAY-07`, `REQ:PAY-08`. Payment COD một-đơn-một-payment, close/tombstone, bằng chứng thu
COD từ `shipping.events`, FINANCE settlement gross-fee-net. [Spec](../../docs/superpowers/specs/2026-10-20-pay-01-payment-cod-design.md) ·
[contract](../../contracts/openapi/payment.yaml) · [internal](../../contracts/openapi/payment-internal.yaml) ·
[evidence](../../docs/evidence/pay-01-local-2026-10-20.md).

Chỉ COD trong PAY-01: VNPAY/MOMO trả 422 `PAYMENT_METHOD_UNAVAILABLE` tới PAY-02/03 (schema đã có `provider_txn_id`,
`payment_url`, `next_reconcile_at`). Chưa có refund (PAY-04), resolve đối soát/batch (PAY-05), route public cho khách (ORD).

## Chạy và test

```bash
./mvnw -pl services/payment-service -am test   # Docker: PostgreSQL 17.11 + Kafka (một test) Testcontainers; relay tắt
bash scripts/local-up.sh && bash scripts/smoke-local.sh   # smoke: "PAY-01 payment admin route PASS"
```

Port 8086 (không publish ra host). Qua Gateway chỉ `/admin/api/v1/payments/**`; `/internal` không route.

## API

| Method / path | Ai | Ghi chú |
|---|---|---|
| `POST /internal/api/v1/payments` | `order-service` | Snapshot `{order_id, order_no, user_id, method, amount, currency, expires_at}` + `Idempotency-Key` → COD_PENDING |
| `GET /internal/api/v1/payments/by-order/{order_id}` | `order-service` | `payment_id, method, amount, currency, status, closed_at, paid_at, version` |
| `POST /internal/api/v1/payments/by-order/{order_id}/close` | `order-service` | Snapshot gốc + `reason` + key → close hoặc tombstone |
| `GET /admin/api/v1/payments`, `/{id}` | `payment.read` | Danh sách (status, cursor) / chi tiết + transactions, settlements, reconciliation items |
| `POST /admin/api/v1/payments/{id}/cod-settlements` | `payment.settle` | `settlement_ref, gross_amount, fee_amount, net_amount, evidence_ref, expected_version` + key |

- **Create**: một TX dưới advisory lock theo `order_id`; `provider_request_id = order_no` (PAY-07); transaction `CREATE`, outbox
  `PAYMENT_CREATED` (`payment.events`, key `order_no`). Cùng order + cùng snapshot → payment cũ (mọi key); khác → 409
  `PAYMENT_CONFLICT`; đã close/tombstone → 409 `PAYMENT_CLOSED` (kiểm trước snapshot, cả khi replay cùng key). amount ≤ 0 → 422
  `PAYMENT_AMOUNT_UNSUPPORTED` (kiểm sau closed); field lạ/thiếu, currency khác VND, amount âm hoặc > 10^12 → 400. Trùng
  `order_no` với order khác → 409 `DUPLICATE`.
- **Close (PAY-08)**: cùng lock. Chưa có payment → tombstone FAILED + `closed_at` + snapshot; COD_PENDING → FAILED + `closed_at`;
  hai trường hợp này phát `PAYMENT_FAILED` (reason `CLOSED`). Tiền đã thu (COD_COLLECTED/SUCCESS) → chỉ đặt `closed_at`, giữ
  trạng thái tiền (order xử lý refund). Đã close, hoặc retry cùng key → trả hiện trạng (COD thu sau close vẫn hiện ra). UNKNOWN không bị close đổi thành FAILED.
- **Bằng chứng COD** (consumer `shipping.events`, group `payment-service`, inbox `processed_events`, ACK sau commit):
  `COD_COLLECTED` khớp method COD và `gross_amount = amount` → COD_COLLECTED (cả payment FAILED đã close; `closed_at` giữ);
  lệch → `reconciliation_items` OPEN (`COD_AMOUNT_MISMATCH`/`COD_METHOD_MISMATCH`), trạng thái giữ; `COD_REMITTED` chỉ ghi
  transaction (T27: chưa SUCCESS). Mỗi `event_ref` một dòng. Chưa có payment → retry (backoff tới 30 s, không ACK); quá `PAYMENT_COD_ORPHAN_GRACE`
  (mặc định 10 phút từ lúc event phát) → `shipping.events.DLT`, đếm `messaging_dlt_total`.
- **Settlement (PAY-03)**: chỉ khi COD_COLLECTED; `gross = fee + net`, > 0; `settlement_ref` một lần mỗi payment; Σgross ≤ amount.
  Đủ amount → SUCCESS + `paid_at` + `PAYMENT_COMPLETED` (cả khi đã close). Thiếu → giữ COD_COLLECTED, đợt sau cộng tiếp.

## Cấu hình

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `PAYMENT_DB_URL`, `PAYMENT_DB_PASSWORD` | local | runtime role `payment_runtime` |
| `PAYMENT_MIGRATION_DB_URL`, `PAYMENT_MIGRATION_DB_PASSWORD` | local | Flyway role `payment_migration` |
| `PAYMENT_JWT_PUBLIC_KEYS` | — | verifier access token FINANCE |
| `PAYMENT_INTERNAL_CALLERS` | trống | `order-service:<base64 X.509>`; trống → mọi call internal 401 (order-service chưa có) |
| `PAYMENT_COD_ORPHAN_GRACE` | `10m` | COD evidence không có payment: retry trong khoảng này rồi vào DLT |
| `PAYMENT_RELAY_ENABLED`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `true`, `localhost:19092` | outbox relay và consumer (platform-messaging) |

## Database

`V001__payment.sql`: `payments` (05 + `snapshot`), `payment_transactions` (source thêm CLOSE/SETTLEMENT), `cod_settlements`
(CHECK `gross = fee + net`), `reconciliation_items`, `outbox_events`, `processed_events`, `idempotency_requests`. Runtime không
DELETE/TRUNCATE bảng tiền, không UPDATE `payment_transactions`/`cod_settlements`. Local DB/role: `infra/local/postgres-init/06-payment-db.sh`.
Retention: chứng từ tài chính 10 năm, không purge (13 §3, O06).

## Health, observability, giới hạn

Readiness `readinessState,db`; log JSON ECS có redaction; trace OTLP khi đặt endpoint; `/actuator/prometheus`; DLT
`shipping.events.DLT` cho event hỏng. Payment chỉ lưu `user_id`, không liên hệ/địa chỉ.
Chưa có: smoke internal (cần order-service), consumer `payment.events` (ORD-02/03), alert COD outstanding/discrepancy (PAY-05),
`NOTIFY_PAYMENT_SUCCESS` (NOT-01). Một `COD_COLLECTED` của đơn không có payment chặn **cả partition** (mọi shipping event sau nó,
gồm COD của đơn khác; FINANCE settle nhận 409 trong lúc đó) tối đa thời gian grace rồi vào DLT; grace tính từ `occurred_at`
của shipping (đồng hồ khác). Replay: sau khi tạo payment, publish lại bản ghi từ `shipping.events.DLT` vào `shipping.events`
(inbox + `event_ref` chặn trùng). Alert trên `messaging_dlt_total` thuộc PLT-05 dashboard/alert.
