# order-service

`TASK:ORD-01` · `REQ:ORD-01`, `REQ:ORD-05`, `REQ:ORD-07`. Quote từ server (ký, 2 phút), tạo đơn `PENDING` idempotent với
snapshot bất biến, đọc đơn theo ownership; guest checkout bằng checkout token của cart-service.
[Spec](../../docs/superpowers/specs/2026-10-20-ord-01-order-quote-create-design.md) ·
[contract](../../contracts/openapi/order.yaml) · [evidence](../../docs/evidence/ord-01-local-2026-10-20.md).
`TASK:ORD-02` · `REQ:ORD-02`, `REQ:ORD-09`: saga checkout COD bền vững (worker nền, lease + CAS), bù trừ, hết hạn, dọn giỏ,
inbox `payment.events`. [Spec](../../docs/superpowers/specs/2026-10-20-ord-02-checkout-saga-design.md) ·
[evidence](../../docs/evidence/ord-02-local-2026-10-20.md).
`TASK:ORD-03` · `REQ:ORD-03`, `REQ:ORD-06` (COD; `REQ:PAY-03` online chờ PAY-02/03): admin orders cho OPS/FINANCE, OPS xác nhận/hủy
đơn COD chờ OPS, saga commit kho → `PACKING` → vận đơn SELF, consumer `shipping.events` → `SHIPPING`/`DELIVERED` +
`ORDER_COMPLETED`. [Spec](../../docs/superpowers/specs/2026-10-21-ord-03-cod-confirm-fulfil-design.md) ·
[evidence](../../docs/evidence/ord-03-local-2026-10-21.md) (In review).

Chưa có: hủy sau xác nhận/khách tự hủy/giao thất bại/hoàn hàng/shipment `UNKNOWN` (ORD-04); `pack` đơn online (PAY-02/03); màn hình admin (ADM-01/02); VNPay/MoMo (PAY-02/03); rate limit checkout.

## Chạy và test

```bash
./mvnw -pl services/order-service -am test   # Docker: PostgreSQL 17.11 Testcontainers; cart/catalog/shipping/inventory/payment là HTTP stub; relay và worker saga tắt
bash scripts/local-up.sh && bash scripts/smoke-local.sh   # smoke: "ORD-01 guest quote/create/read PASS", "ORD-02 saga confirm PASS", "ORD-03 COD end-to-end PASS"
```

Port 8087 (không publish ra host). Qua Gateway: `/api/v1/orders/**` và `/admin/api/v1/orders/**` (route `order-admin`); không có route `/internal`.

## API

| Method / path | Ai | Ghi chú |
|---|---|---|
| `POST /api/v1/orders/quote` | member (Bearer) hoặc guest (`Checkout-Token`) | cart_id, cart_version, method, shipping_address, contact, note → quote_token + totals, hạn 2 phút |
| `POST /api/v1/orders` | như trên + `Idempotency-Key` | cart_id, cart_version, quote_token → `202` PENDING/PROCESSING, `Location`, `Retry-After: 3`; guest nhận cookie `order_access` |
| `GET /api/v1/orders/{order_no}` | member owner hoặc cookie `order_access` của đơn | Không thuộc actor → 404 `ORDER_NOT_FOUND` (T21); `processing_status` PROCESSING (PENDING/CANCELLING)/READY (CONFIRMED)/FINISHED (CANCELLED), `reason_code` khi hủy |
| `GET /api/v1/orders/mine` | member | `status`, `cursor`, `limit` 1..100 (mặc định 20), mới nhất trước |
| `GET /admin/api/v1/orders` | Bearer `order.read` | `status`, `q` (chính xác `order_no` hoặc `contact_phone`; phone cần `order.fulfill`, else 403), keyset `cursor`, `limit` 1..100 |
| `GET /admin/api/v1/orders/{order_no}` | `order.read` | đơn + items + history (actor, reason) + `expires_at`/`shipped_at` + saga (step, status, last_error) |
| `POST /admin/api/v1/orders/{order_no}/confirm-cod` | `order.fulfill` + `Idempotency-Key` | `{expected_version, note?}` → `202`, đơn vẫn `CONFIRMED` (PROCESSING), saga `COMMIT_STOCK` |
| `POST /admin/api/v1/orders/{order_no}/cancel` | `order.cancel` + `Idempotency-Key` | `{expected_version, reason}` → `202` `CANCELLING`, `cancel_reason = OPS_CANCELLED` |

- **Admin (ORD-03)**: `AdminAuth` kiểm Bearer + permission tại service (cùng `ORDER_JWT_PUBLIC_KEYS`). Không có
  `order.fulfill` (FINANCE) → bỏ key `contact_phone`/`contact_email`/`shipping_address`/`note` (cả response confirm/cancel:
  bỏ `contact`/`shipping_address`/`note`), history `reason` null. Confirm/cancel: auth → key → body, rồi một transaction khóa
  `order_sagas` rồi `orders` (thứ tự chung toàn service): version (409 `VERSION_CONFLICT`) → CONFIRMED + saga `WAIT_OPS` +
  không lease sống (+ COD cho confirm) (409 `INVALID_TRANSITION`, errors `status current=S`) → confirm chưa quá `expires_at`
  (409 `ORDER_EXPIRED`; cancel vẫn được). Cả hai tăng `orders.version`, ghi `audit_logs` (`ORDER_CONFIRM_COD`/`ORDER_CANCEL`)
  và lưu response `202` trong `idempotency_requests` (actor `admin:{user_id}`); 409 nghiệp vụ rollback, không lưu (retry cùng
  key được đánh giá lại); khác body → 409 `IDEMPOTENCY_KEY_REUSED`.

- **Actor**: Bearer quyết định một mình (sai → 401, không fallback). Không Bearer: `Checkout-Token` (ES256 do cart ký,
  `aud=order-service`, `sub=guest:<sha256 cookie giỏ>`, `cart_id`, 15 phút); `cart_id` khác token → 404.
- **Quote**: cart snapshot đúng `cart_version` (khác → 409 `VERSION_CONFLICT`; rỗng → 422 `CART_EMPTY`), giá =
  `effective_price` của catalog (thiếu/không active → 409 `ITEM_UNAVAILABLE`), phí SELF + phí COD từ quote shipping lưu
  sẵn (422 `NO_SHIPPING_RULE`). `total = subtotal + shipping_fee + cod_fee`; tràn → 422 `AMOUNT_OUT_OF_RANGE`; tổng 0 →
  422 `PAYMENT_AMOUNT_UNSUPPORTED`. Guest bắt buộc `contact.email`. Không ghi DB, không gọi inventory.
- **quote_token**: `base64url({kid, quote}).base64url(HMAC-SHA256)`; chứa địa chỉ/liên hệ của chính khách (chỉ base64), không
  log.
- **Create**: lookup `(actor_key, Idempotency-Key)` trước mọi kiểm quote → cùng body trả đơn cũ (kể cả quote hết hạn/giá đã
  đổi), khác body 409 `IDEMPOTENCY_KEY_REUSED`. Sau đó: quote sai actor/cart/version → 400 `INVALID_QUOTE`; hết hạn → 409
  `QUOTE_EXPIRED`; giỏ khác dòng đã quote → 409 `VERSION_CONFLICT`; giá catalog khác → 409 `PRICE_CHANGED` (errors
  `variant_id`, `old=..,new=..`). Một transaction: `orders` + `order_items` + `order_status_history` + `order_sagas`
  (FULFILL/RESERVE/RUNNING cho ORD-02) + outbox `ORDER_CREATED`. Key khác cùng giỏ/version → 409 `CART_ALREADY_ORDERED`
  (order_no chỉ lộ cho cùng actor). COD `expires_at = +24h`. `order_no = ORD + yyyyMM (giờ VN) + 6 số sequence`.
- **Guest credential**: cookie `order_access` (HttpOnly, Secure, SameSite=Lax, `Path=/api/v1/orders/{order_no}`, 90 ngày —
  O06, server kiểm `created_at` của credential, không chỉ `Max-Age`); DB chỉ lưu sha256 (bảng `order_guest_access`). Retry
  create cùng key cấp thêm cookie mới, cookie cũ vẫn hợp lệ (hai response đua nhau không làm guest mất đơn), tối đa 10
  credential mỗi đơn (`ACCESS_CAPPED`); guest cùng actor nhận `409 CART_ALREADY_ORDERED` cũng kèm cookie của đơn đó. Log
  audit `ACCESS_REISSUED`. Dọn credential hết hạn (O06) cần role migration/owner — task retention riêng.
- **Tìm lại đơn (ORD-02)**: trước kiểm quote, đơn chưa hủy của cùng actor với `(cart_id, cart_version)` → 409
  `CART_ALREADY_ORDERED` + order_no (guest kèm cookie), để guest mất response/key vẫn lấy lại được đơn.

## Saga (ORD-02)

`SagaWorker.runOnce()` mỗi `ORDER_SAGA_POLL`: claim ≤ 10 saga đến hạn (`FOR UPDATE SKIP LOCKED`, lease 30 s). Mỗi bước ghi
intent (`*_state = UNKNOWN`) dưới lease, gọi HTTP ngoài transaction, ghi kết quả bằng CAS lease token; mất lease → bỏ kết quả.

| Step | Gọi (key) | OK | Từ chối nghiệp vụ | Còn lại (400/404/`*_CONFLICT`/401/403/5xx/timeout, exception) |
|---|---|---|---|---|
| `RESERVE` | inventory reserve (`reserve:{order_id}`, hash 03 §4) | HELD → `CREATE_PAYMENT` | `OUT_OF_STOCK` → CANCEL `OUT_OF_STOCK`; `RESERVATION_CLOSED` → `RESERVE_FAILED` | retry cùng key |
| `CREATE_PAYMENT` | payment create (`payment:{order_id}`) | đơn `CONFIRMED`, saga `WAIT_OPS` tới `expires_at`, enqueue `CART_CLEANUP` | `PAYMENT_CLOSED`/`PAYMENT_METHOD_UNAVAILABLE`/`PAYMENT_AMOUNT_UNSUPPORTED` → CANCEL `PAYMENT_FAILED` | retry cùng key |
| `COMPENSATE` | release (`release:{order_id}`, snapshot + hash gốc) trừ khi RELEASED (NONE + 400 `INVALID_INPUT` = SKU lạ, không giữ gì), rồi luôn close (`close:{order_id}`) | `CANCELLED` | `MANUAL` (COMMITTED/RESTORED; tiền đã thu hoặc đã ghi `PAYMENT_COMPLETED` → `PAID_NEEDS_REFUND`) | retry |

| `COMMIT_STOCK` (ORD-03) | inventory commit (`commit:{order_id}`; intent giữ `inventory_state = HELD`) | `COMMITTED`, đơn `CONFIRMED → PACKING` (history SYSTEM, không outbox), kết thúc lượt | `RESERVATION_EXPIRED` → `RELEASED` + CANCEL `EXPIRED`; `RESERVATION_CLOSED` → `RELEASED` + CANCEL `RESERVE_FAILED` | retry cùng key |
| `CREATE_SHIPMENT` (ORD-03) | shipping create (`shipment:{order_id}`, body snapshot: `quote_id`, `cod_amount = total_amount`, `to_address`) | lưu `shipment_id`, saga `DONE` | mọi từ chối → `MANUAL`, đơn giữ `PACKING` | retry cùng key/body |

Quá `expires_at` (COD 24 h) ở bước `RESERVE`/`CREATE_PAYMENT`/`WAIT_OPS`, kể cả trong lúc gọi payment → CANCEL `EXPIRED`, không `CONFIRMED` muộn; từ `COMMIT_STOCK` (đã OPS xác nhận) hạn của order không áp dụng, inventory quyết định. Intent tính một lần thử + backoff trước mỗi lời gọi xuôi; claim saga đã hết ngân sách → `MANUAL`. Backoff `min(2^n s, 5 phút)` ±20 %, 30 lần → `MANUAL`
(metric `order_saga_manual_total`, log `last_error`), nghĩa vụ giữ nguyên. Chuyển trạng thái đơn ghi history (`SYSTEM`) +
outbox `ORDER_CONFIRMED`/`ORDER_CANCELLED` cùng transaction.

- **Dọn giỏ** (`CartCleanupWorker`): task `CART_CLEANUP` (business key order_id) gọi cart checkout-cleanup
  (`Idempotency-Key = order_id`); đơn `CANCELLING`/`CANCELLED` → bỏ qua; lỗi chưa rõ retry tối đa 30 lần rồi `MANUAL`, không
  đổi trạng thái đơn.
- **`payment.events`** (group `order-service`, inbox `processed_events`, ACK sau commit, retry/DLT platform-messaging):
  `PAYMENT_COMPLETED` ghi `paid_at` + `payment_state = PAID`, không đổi `status`; đơn lạ ACK + log; event hỏng → DLT.
- **`shipping.events`** (ORD-03, cùng group/inbox; khóa `order_sagas` rồi `orders`): `shipment_version` ≤
  `order_sagas.shipment_version` bỏ qua; `SHIPMENT_STATUS_UPDATED` `DELIVERING` có `handed_over_at` → `SHIPPING`
  (`shipped_at`); `DELIVERED` → (`SHIPPING` nếu còn `PACKING`) `DELIVERED` (`delivered_at`) + outbox `ORDER_COMPLETED`; trạng
  thái khác chỉ ghi version; `SHIPMENT_CREATED` ghi `shipment_id` nếu saga chưa có; `COD_COLLECTED`/`COD_REMITTED` chỉ ACK.
  `processing_status`: CONFIRMED đã qua `WAIT_OPS` và PACKING chưa có shipment → PROCESSING; PACKING có shipment, SHIPPING →
  READY; DELIVERED → FINISHED.

## Cấu hình

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `ORDER_DB_URL`, `ORDER_DB_PASSWORD` | local | runtime role `order_runtime` |
| `ORDER_MIGRATION_DB_URL`, `ORDER_MIGRATION_DB_PASSWORD` | local | Flyway role `order_migration` |
| `ORDER_JWT_PUBLIC_KEYS` | — | verifier access token member |
| `ORDER_SERVICE_PRIVATE_KEY`, `ORDER_SERVICE_PUBLIC_KEY` | trống | ADR-19 caller `order-service` tới cart/catalog/shipping/inventory/payment (local-up sinh, thêm vào `*_INTERNAL_CALLERS`) |
| `ORDER_CART_URL`, `ORDER_CATALOG_URL`, `ORDER_SHIPPING_URL`, `ORDER_DEPENDENCY_TIMEOUT` | localhost, `2s` | phụ thuộc; lỗi/timeout → 503, không ghi gì |
| `ORDER_INVENTORY_URL`, `ORDER_PAYMENT_URL` | `localhost:8083`, `:8086` | saga ORD-02 (cùng timeout) |
| `ORDER_SAGA_ENABLED`, `ORDER_SAGA_POLL`, `ORDER_SAGA_MAX_RETRIES` | `true`, `1s`, `30` | worker saga + dọn giỏ |
| `ORDER_CHECKOUT_PUBLIC_KEYS` | trống | `kid:base64 X.509` của checkout token cart (nhiều key để xoay); cart dùng `CART_CHECKOUT_PRIVATE_KEY`/`CART_CHECKOUT_KID` |
| `ORDER_QUOTE_SECRET`, `ORDER_QUOTE_KID` | trống, `q1` | HMAC quote_token (base64 ≥ 32 byte) |
| `ORDER_PAYMENT_METHODS` | `COD` | phương thức được quote |
| `ORDER_RELAY_ENABLED`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `true`, `localhost:19092` | outbox relay `order.events`; consumer `payment.events`, `shipping.events` (ORD-03) |

## Database

`V001__order.sql`: `orders` (05 §7 + `created_at`/`updated_at`, UNIQUE `(actor_key, checkout_key)` và `(cart_id, cart_version)`,
CHECK tổng tiền, guest ⇔ `guest_access_hash`), `order_guest_access` (mọi access hash đã cấp cho guest), `order_items`, `order_status_history`, `order_sagas`, `outbox_events`,
sequence `order_no_seq`. Runtime không DELETE/TRUNCATE đơn/items/history, không UPDATE history. Local DB/role:
`infra/local/postgres-init/07-order-db.sh`. `V002__order_saga.sql` (ORD-02): `order_sagas.payment_state`, UNIQUE giỏ/version
thành unique index một phần `WHERE status <> 'CANCELLED'`, `processed_events`, `background_tasks`. `V003__order_ops.sql`
(ORD-03): `audit_logs` (REVOKE UPDATE/DELETE/TRUNCATE khỏi runtime), `idempotency_requests`, `orders.shipped_at`,
`order_sagas.shipment_version`. Retention: PII đơn ẩn danh sau 12 tháng từ khi đơn kết thúc (O06) — job chưa làm.

## Health, observability, giới hạn

Readiness `readinessState,db`; log JSON ECS có redaction (token, cookie không được log); trace OTLP khi đặt endpoint;
`/actuator/prometheus`. Event `ORDER_CREATED` không chứa địa chỉ/liên hệ. Giới hạn: chưa có rate limit checkout 10 rpm
(03 §1.2) — chưa có limiter chung; quote_token có thể dài tới ~20 KB với 100 dòng. Saga: `MANUAL` chưa có UI/alert (ADM-02/PLT-05), chỉ metric + log; nhiều
instance dựa vào `SKIP LOCKED` + lease, chưa đo tải; online `WAIT_PAYMENT` chưa đạt được tới PAY-02/03. Admin: list đọc
saga từng dòng CONFIRMED (N+1), chưa index `contact_phone` (seq scan); consumer shipping chưa đối chiếu `shipment_id` với saga
(một shipment/đơn ở shipping hiện tại, cần khi ORD-04 tạo lại vận đơn).
