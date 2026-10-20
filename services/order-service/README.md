# order-service

`TASK:ORD-01` · `REQ:ORD-01`, `REQ:ORD-05`, `REQ:ORD-07`. Quote từ server (ký, 2 phút), tạo đơn `PENDING` idempotent với
snapshot bất biến, đọc đơn theo ownership; guest checkout bằng checkout token của cart-service.
[Spec](../../docs/superpowers/specs/2026-10-20-ord-01-order-quote-create-design.md) ·
[contract](../../contracts/openapi/order.yaml) · [evidence](../../docs/evidence/ord-01-local-2026-10-20.md).

Chưa có: saga reserve/payment/cleanup giỏ và worker phục hồi (ORD-02) — đơn ORD-01 dừng ở `PENDING`/`PROCESSING`; COD
confirm/expire/packing (ORD-03); hủy/trả (ORD-04); admin order (ORD-03/ADM-02); VNPay/MoMo (PAY-02/03); rate limit checkout.

## Chạy và test

```bash
./mvnw -pl services/order-service -am test   # Docker: PostgreSQL 17.11 Testcontainers; cart/catalog/shipping là HTTP stub; relay tắt
bash scripts/local-up.sh && bash scripts/smoke-local.sh   # smoke: "ORD-01 guest quote/create/read PASS"
```

Port 8087 (không publish ra host). Qua Gateway: `/api/v1/orders/**`; không có route `/internal`.

## API

| Method / path | Ai | Ghi chú |
|---|---|---|
| `POST /api/v1/orders/quote` | member (Bearer) hoặc guest (`Checkout-Token`) | cart_id, cart_version, method, shipping_address, contact, note → quote_token + totals, hạn 2 phút |
| `POST /api/v1/orders` | như trên + `Idempotency-Key` | cart_id, cart_version, quote_token → `202` PENDING/PROCESSING, `Location`, `Retry-After: 3`; guest nhận cookie `order_access` |
| `GET /api/v1/orders/{order_no}` | member owner hoặc cookie `order_access` của đơn | Không thuộc actor → 404 `ORDER_NOT_FOUND` (T21) |
| `GET /api/v1/orders/mine` | member | `status`, `cursor`, `limit` 1..100 (mặc định 20), mới nhất trước |

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
  O06); DB chỉ lưu sha256 (bảng `order_guest_access`). Retry create cùng key cấp thêm cookie mới, cookie cũ vẫn hợp lệ (hai
  response đua nhau không làm guest mất đơn); log audit `ACCESS_REISSUED`.

## Cấu hình

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `ORDER_DB_URL`, `ORDER_DB_PASSWORD` | local | runtime role `order_runtime` |
| `ORDER_MIGRATION_DB_URL`, `ORDER_MIGRATION_DB_PASSWORD` | local | Flyway role `order_migration` |
| `ORDER_JWT_PUBLIC_KEYS` | — | verifier access token member |
| `ORDER_SERVICE_PRIVATE_KEY`, `ORDER_SERVICE_PUBLIC_KEY` | trống | ADR-19 caller `order-service` tới cart/catalog/shipping (local-up sinh, thêm vào `*_INTERNAL_CALLERS`) |
| `ORDER_CART_URL`, `ORDER_CATALOG_URL`, `ORDER_SHIPPING_URL`, `ORDER_DEPENDENCY_TIMEOUT` | localhost, `2s` | phụ thuộc; lỗi/timeout → 503, không ghi gì |
| `ORDER_CHECKOUT_PUBLIC_KEYS` | trống | `kid:base64 X.509` của checkout token cart (nhiều key để xoay); cart dùng `CART_CHECKOUT_PRIVATE_KEY`/`CART_CHECKOUT_KID` |
| `ORDER_QUOTE_SECRET`, `ORDER_QUOTE_KID` | trống, `q1` | HMAC quote_token (base64 ≥ 32 byte) |
| `ORDER_PAYMENT_METHODS` | `COD` | phương thức được quote |
| `ORDER_RELAY_ENABLED`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `true`, `localhost:19092` | outbox relay `order.events` |

## Database

`V001__order.sql`: `orders` (05 §7 + `created_at`/`updated_at`, UNIQUE `(actor_key, checkout_key)` và `(cart_id, cart_version)`,
CHECK tổng tiền, guest ⇔ `guest_access_hash`), `order_guest_access` (mọi access hash đã cấp cho guest), `order_items`, `order_status_history`, `order_sagas`, `outbox_events`,
sequence `order_no_seq`. Runtime không DELETE/TRUNCATE đơn/items/history, không UPDATE history. Local DB/role:
`infra/local/postgres-init/07-order-db.sh`. Retention: PII đơn ẩn danh sau 12 tháng từ khi đơn kết thúc (O06) — job chưa làm.

## Health, observability, giới hạn

Readiness `readinessState,db`; log JSON ECS có redaction (token, cookie không được log); trace OTLP khi đặt endpoint;
`/actuator/prometheus`. Event `ORDER_CREATED` không chứa địa chỉ/liên hệ. Giới hạn: chưa có rate limit checkout 10 rpm
(03 §1.2) — chưa có limiter chung; quote_token có thể dài tới ~20 KB với 100 dòng; đơn ORD-01 không tự tiến trạng thái.
