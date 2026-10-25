# payment-service

`TASK:PAY-01` · `REQ:PAY-03`, `REQ:PAY-07`, `REQ:PAY-08`. Payment COD một-đơn-một-payment, close/tombstone, bằng chứng thu
COD từ `shipping.events`, FINANCE settlement gross-fee-net. [Spec](../../docs/superpowers/specs/2026-10-20-pay-01-payment-cod-design.md) ·
[contract](../../contracts/openapi/payment.yaml) · [internal](../../contracts/openapi/payment-internal.yaml) ·
[evidence](../../docs/evidence/pay-01-local-2026-10-20.md).

`TASK:PAY-02` (đang làm: code local xong, chờ sandbox thật + nghiệm thu): VNPay PAY 2.1.0 GET — link ký, IPN, return, QueryDR
([spec](../../docs/superpowers/specs/2026-10-25-pay-02-vnpay-design.md) · [evidence](../../docs/evidence/pay-02-local-2026-10-25.md)).
VNPAY tắt (thiếu `PAYMENT_VNPAY_TMN_CODE`/`HASH_SECRET`) và MOMO trả 422 `PAYMENT_METHOD_UNAVAILABLE`. Chưa có refund (PAY-04),
resolve đối soát/batch (PAY-05); order chưa cho chọn VNPAY khi checkout (ORD-05/WEB-04).

## Chạy và test

```bash
./mvnw -pl services/payment-service -am test   # Docker: PostgreSQL 17.11 + Kafka (một test) Testcontainers; relay tắt
bash scripts/local-up.sh && bash scripts/smoke-local.sh   # smoke: "PAY-01 payment admin route PASS"
```

Port 8086 (compose chỉ publish `127.0.0.1:${PAYMENT_PORT:-8086}` cho `scripts/vnpay-sandbox.sh`). Qua Gateway chỉ `/admin/api/v1/payments/**` và (PAY-02) `/api/v1/payments/vnpay/ipn`, `/api/v1/payments/vnpay/return`; `/api/v1/payments` khác và `/internal` không route.

## API

| Method / path | Ai | Ghi chú |
|---|---|---|
| `POST /internal/api/v1/payments` | `order-service` | Snapshot `{order_id, order_no, user_id, method, amount, currency, expires_at}` + `Idempotency-Key` → COD_PENDING |
| `GET /internal/api/v1/payments/by-order/{order_id}` | `order-service` | `payment_id, method, amount, currency, status, closed_at, paid_at, version` |
| `POST /internal/api/v1/payments/by-order/{order_id}/close` | `order-service` | Snapshot gốc + `reason` + key → close hoặc tombstone |
| `GET /admin/api/v1/payments`, `/{id}` | `payment.read` | Danh sách (status, cursor) / chi tiết + transactions, settlements, reconciliation items |
| `POST /admin/api/v1/payments/{id}/cod-settlements` | `payment.settle` | `settlement_ref, gross_amount, fee_amount, net_amount, evidence_ref, expected_version` + key |
| `POST /internal/api/v1/payments` (VNPAY) | `order-service` | Thêm `client_ip` (bắt buộc) và `locale` (`vi`/`en`), không vào hash; trả `payment_url` + `payment_url_expires_at`, không gọi mạng, `vnp_TxnRef = order_no`; replay cùng link |
| `GET /api/v1/payments/vnpay/ipn` | chữ ký HMAC-SHA512 + `vnp_TmnCode` | Luôn 200 `{RspCode, Message}` (00/01/02/04/97/99); SUCCESS chỉ khi `00`+`00` đúng số tiền ×100, ACK sau commit |
| `GET /api/v1/payments/vnpay/return` | public | Không đọc/ghi DB; 302 tới `{PAYMENT_STOREFRONT_URL}/orders/{vnp_TxnRef}` hoặc `/` |

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
  lệch → `reconciliation_items` OPEN (`COD_AMOUNT_MISMATCH`/`COD_METHOD_MISMATCH`), trạng thái giữ; thu lần hai (khác
  `event_ref`) trên payment đã COD_COLLECTED/SUCCESS → `COD_DUPLICATE_COLLECTION`; thiếu/sai `gross`/`fee`/`net` hoặc currency khác
  VND → DLT; `COD_REMITTED` chỉ ghi
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
| `PAYMENT_VNPAY_TMN_CODE`, `PAYMENT_VNPAY_HASH_SECRET` | trống | Merchant sandbox; trống = VNPAY tắt (create 422, worker không chạy, IPN `99`). Chỉ từ env, không log |
| `PAYMENT_VNPAY_PAY_URL`, `PAYMENT_VNPAY_API_URL` | URL sandbox | Trang thanh toán và QueryDR |
| `PAYMENT_VNPAY_RETURN_URL`, `PAYMENT_STOREFRONT_URL` | Gateway `:8080`, storefront `:5173` (compose: `:4173`) | Return qua Gateway; storefront là đích redirect duy nhất |
| `PAYMENT_VNPAY_LINK_TTL`, `PAYMENT_VNPAY_SERVER_IP` | `15m`, `127.0.0.1` | Hạn link (không vượt `expires_at` order); `vnp_IpAddr` của QueryDR |
| `PAYMENT_VNPAY_QUERY_TIMEOUT` | `10s` | Timeout mỗi lần gọi QueryDR |
| `fashion.payment.vnpay.query-poll` (property, chưa có env) | `30s` | Chu kỳ worker QueryDR chọn payment tới hạn |
| `PAYMENT_RELAY_ENABLED`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `true`, `localhost:19092` | outbox relay và consumer (platform-messaging) |

## VNPay sandbox

VNPay chỉ gọi IPN qua HTTPS và máy local không nhận request từ Internet, nên local xác nhận bằng **QueryDR**; IPN qua tunnel là tùy chọn.

1. Điền `PAYMENT_VNPAY_TMN_CODE` và `PAYMENT_VNPAY_HASH_SECRET` (merchant sandbox) vào `infra/local/.env` (gitignore; `scripts/local-up.sh` thêm
   hai khóa rỗng nếu `.env` cũ chưa có). Không commit, không dán vào chat/log.
2. `docker compose --env-file infra/local/.env -f infra/local/compose.yaml up -d postgres kafka payment gateway`
   (máy 7.4 GB: chỉ các service này; `PAYMENT_INTERNAL_CALLERS` có `order-service` do `local-up.sh` sinh).
3. `bash scripts/vnpay-sandbox.sh [amount]` (mặc định 10000): tạo payment VNPAY cho order giả lập (`SBX<yyyyMMddHHmmss>`), in `payment_url`,
   poll trạng thái mỗi 15 s tối đa 20 phút; thoát 0 khi `SUCCESS`, 1 khi `FAILED`/hết giờ. Chỉ local, không chạy trong CI.
4. Mở link, trả bằng thẻ test trong [tài liệu VNPay](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html). QueryDR phát hiện kết quả
   trong vài phút.
5. Tùy chọn IPN: `cloudflared tunnel --url http://localhost:8080`, rồi đặt IPN URL `https://<tunnel>/api/v1/payments/vnpay/ipn` ở trang merchant sandbox
   và trả đơn thứ hai; `payment_transactions.source = WEBHOOK`.

Lịch QueryDR: +1, +2, +5, +10, +30 phút sau create, rồi mỗi 30 phút tới hạn link + 24 giờ. Lỗi mạng/timeout chỉ dời lịch. `91` (không có giao dịch)
chỉ thành `FAILED` `EXPIRED` sau hạn link + 15 phút. Return không đổi trạng thái tiền.

## Database

`V002__vnpay.sql` thêm `payments.provider_created_at` (`vnp_CreateDate`, dùng lại cho `vnp_TransactionDate` của QueryDR) và `payment_url_expires_at`. `V001__payment.sql`: `payments` (05 + `snapshot`), `payment_transactions` (source thêm CLOSE/SETTLEMENT), `cod_settlements`
(CHECK `gross = fee + net`), `reconciliation_items`, `outbox_events`, `processed_events`, `idempotency_requests`. Runtime không
DELETE/TRUNCATE bảng tiền, không UPDATE `payment_transactions`/`cod_settlements`. Local DB/role: `infra/local/postgres-init/06-payment-db.sh`.
Retention: chứng từ tài chính 10 năm, không purge (13 §3, O06).

## Health, observability, giới hạn

Readiness `readinessState,db`; log JSON ECS có redaction; trace OTLP khi đặt endpoint; `/actuator/prometheus`; DLT
`shipping.events.DLT` cho event hỏng. Payment chỉ lưu `user_id`, không liên hệ/địa chỉ.
VNPay: trace span có thể giữ query string của IPN (không có secret, nhưng có amount/bank/txn); không mở Tempo/Grafana ra ngoài local.
Mới kiểm bằng test + fixture tự ký; chưa có kết quả sandbox thật. Chưa có: smoke internal (cần order-service), consumer `payment.events` (ORD-02/03), alert COD outstanding/discrepancy (PAY-05),
`NOTIFY_PAYMENT_SUCCESS` (NOT-01). Một `COD_COLLECTED` của đơn không có payment chặn **cả partition** (mọi shipping event sau nó,
gồm COD của đơn khác; FINANCE settle nhận 409 trong lúc đó) tối đa thời gian grace rồi vào DLT; grace tính từ `occurred_at`
của shipping (đồng hồ khác). Replay: sau khi tạo payment, publish lại bản ghi từ `shipping.events.DLT` vào `shipping.events`
(inbox + `event_ref` chặn trùng). Alert trên `messaging_dlt_total` thuộc PLT-05 dashboard/alert.
