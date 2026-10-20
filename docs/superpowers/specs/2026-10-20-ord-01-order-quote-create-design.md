# ORD-01 — order-service: quote, tạo đơn, đọc đơn, ownership, guest checkout token

`TASK:ORD-01` · `REQ:ORD-01`, `REQ:ORD-05`, `REQ:ORD-07` · dependency CAT-02, CART-03, SHP-01 (đều Done local); T21, T24.
Trạng thái: **đã duyệt** 2026-10-20. Chủ dự án giao agent tự chốt mặc định và duyệt spec (gồm các lựa chọn §11).
Nguồn: 03 §1.2/§1.3/§2.3/§4, 05 §7, 06 §5.1–§5.3 và §13 (T21/T24), 02 (ORD-01/05/07), 08 CART-03 (guest checkout token,
quyết định 2026-10-20), 13 §3 (O06: guest credential ≤ 90 ngày, PII đơn 12 tháng).

## 1. Mục tiêu và ngoài phạm vi

- Khách (member hoặc guest) lấy **quote** từ server cho giỏ hiện tại + địa chỉ + phương thức: giá catalog, phí ship SELF,
  phí COD, tổng tiền; quote có hạn 2 phút, ký bởi order, không giữ hàng (REQ ORD-01).
- Khách **tạo đơn** từ quote đã xác nhận: đơn `PENDING` với snapshot bất biến (items, giá, địa chỉ, phí), durable trong một
  transaction; trả `202` + `status_url` (REQ ORD-07). Retry cùng key trả cùng đơn, kể cả khi quote đã hết hạn.
- Giá/phí đổi giữa quote và đặt → `409 PRICE_CHANGED`, không tự thu tổng mới (T24).
- Khách **đọc đơn** của mình: member theo Bearer, guest theo cookie truy cập đơn; không đoán được đơn/giỏ người khác (T21,
  REQ ORD-05). Member xem danh sách đơn (cursor).
- Guest checkout theo quyết định 08 CART-03: cart-service cấp **checkout token** ngắn hạn có ký; order xác minh rồi điền
  `Cart-Actor`.

Không làm (task sau): saga reserve/payment/cleanup giỏ và worker phục hồi (ORD-02) — đơn ORD-01 dừng ở `PENDING`,
`processing_status = PROCESSING`; COD confirm/expire/packing/shipment (ORD-03); hủy/trả (ORD-04); `GET
/orders/{order_no}/payment` (ORD-02, khi đã có payment); admin order list/detail (ORD-03/ADM-02); voucher/promotion (Phase
2); VNPay/MoMo (PAY-02/03); rate limit checkout 10 rpm (chưa có limiter chung, ghi rủi ro); UI checkout (WEB-03);
`GET /orders/{order_id}/snapshot` internal (ORD-02/03 khi payment/shipping cần).

Anti-goals: amount/giá/phí từ client làm authority; quote hoặc đơn của actor này đọc/đặt được bằng giỏ/đơn của actor khác;
hai đơn cho một lần submit (retry, double-click); đơn đã durable mà client nhận lỗi khiến tạo key mới; HTTP trong DB
transaction; PII/token trong log, URL hoặc idempotency response.

## 2. Service và hạ tầng

- `services/order-service` (Java 21, Spring Boot 4.0, JdbcClient, Flyway), port **8087**, DB `orders` (role
  `order_migration`/`order_runtime`, init idempotent như payment). platform-durability (outbox), platform-security (access
  token member, service token ADR-19 cho caller), platform-messaging (outbox relay).
- Gọi ra (service token, `iss=order-service`): cart `GET /internal/api/v1/carts/{id}/snapshot` (`Cart-Actor`), catalog
  `POST /internal/api/v1/catalog/variants/quote`, shipping `POST /internal/api/v1/shipping/quotes`. Các allowlist hiện đã có
  caller `order-service`; local-up sinh `service_key ORDER order-service` và nạp public key vào cart/catalog/shipping.
- Gateway: route public `/api/v1/orders/**` → order (CSRF theo Origin như cart); `/internal` không route. Route cart đã có
  phủ endpoint checkout token mới.
- Kafka: phát `order.events` (key `order_no`) — chỉ `ORDER_CREATED` trong ORD-01; local-up tạo topic `order.events` (+ DLT).

## 3. Guest checkout token (cart-service, phần của ORD-01)

- `POST /api/v1/carts/{cart_id}/checkout-token` — chỉ guest: cookie `cart_token` hợp lệ và sở hữu giỏ ACTIVE; có Bearer →
  `400 CHECKOUT_TOKEN_GUEST_ONLY` (member dùng Bearer ở order); giỏ không thuộc cookie/không có → `404` (T21). Không cần
  Idempotency-Key (không đổi dữ liệu), không trượt hạn cookie, response `no-store`.
- Token: JWT ES256 ký bằng khóa riêng của cart (`CART_CHECKOUT_PRIVATE_KEY`, `kid` = `cart-checkout-<n>`), claims `iss=cart-service`,
  `aud=order-service`, `sub=guest:<sha256 hex của cart_token>`, `cart_id`, `iat`, `exp = iat + 15 phút`, `jti`. Không chứa
  cookie, PII hay giá. Response `{checkout_token, expires_at}`.
- Dùng lại được tới khi hết hạn (quote, đặt, retry trong cùng phiên); không one-time vì retry sau timeout phải dùng lại được.
  Order nhận qua header `Checkout-Token`; verifier theo danh sách public key `ORDER_CHECKOUT_PUBLIC_KEYS` (nhiều `kid` để xoay
  khóa). Sai chữ ký/aud/iss/hết hạn → `401 INVALID_CHECKOUT_TOKEN`; `cart_id` trong token khác body → `404 CART_NOT_FOUND`.

## 4. Actor và ownership

- Member: `Authorization: Bearer` (AccessTokenVerifier, như cart) → `actor_key = user:<uuid>`, `user_id`. Bearer sai → 401,
  không fallback sang checkout token.
- Guest: không Bearer, có `Checkout-Token` hợp lệ → `actor_key = guest:<sha256>` (đúng giá trị `Cart-Actor` của CART-03).
- Không actor → `401 UNAUTHORIZED`. Cart snapshot trả 404 khi giỏ không thuộc actor → order trả `404 CART_NOT_FOUND`.
- Đọc đơn: member chỉ đơn có `user_id` của mình; guest chỉ đơn có cookie truy cập khớp `guest_access_hash` (§7). Không thuộc
  actor → `404 ORDER_NOT_FOUND` (không phân biệt với không tồn tại).

## 5. Quote — `POST /api/v1/orders/quote`

Body (strict, field lạ → 400): `cart_id`, `cart_version`, `method` (`COD`|`VNPAY`|`MOMO`), `shipping_address`
{`recipient_name` 1–100, `phone` (VN 9–15 chữ số, cho phép `+`), `province_code`, `ward_code`, `address_line` 1–255} (cùng
shape địa chỉ user-service), `contact` {`phone` bắt buộc, `email` bắt buộc với guest, tùy chọn với member}, `note` ≤ 500.

1. Actor (§4). `method` không bật (`ORDER_PAYMENT_METHODS`, mặc định `COD`) → `422 PAYMENT_METHOD_UNAVAILABLE`.
2. Cart snapshot với `expected_version = cart_version` → version khác `409 VERSION_CONFLICT`; giỏ rỗng → `422 CART_EMPTY`.
3. Catalog quote toàn bộ variant: thiếu/không active → `409 ITEM_UNAVAILABLE` (errors theo `variant_id`). Đơn giá =
   `effective_price` của catalog; `price_snapshot` của cart chỉ tham khảo.
4. Shipping quote (`province_code`, `cod = method == COD`, items) với key mới mỗi lần quote → `fee`, `cod_fee`, `quote_id`,
   `expires_at`. 422 `NO_SHIPPING_RULE` của shipping trả nguyên cho khách.
5. Tổng (long, `Math.*Exact`, tràn → 422): `line_total = quantity × unit_price`; `subtotal = Σ`; `discount = 0`;
   `shipping_discount = 0`; `cod_fee` chỉ COD; `total = subtotal + shipping_fee + cod_fee`; `total ≤ 0` → `422
   PAYMENT_AMOUNT_UNSUPPORTED` (O05 baseline).
6. Response `200`: `quote_token`, `expires_at` (now + 2 phút), `cart_version`, items (variant_id, sku, name theo locale, size,
   color, image, quantity, unit_price, line_total), `shipping_address`, `method`, totals. `no-store`.

Không gọi inventory (quote không giữ hàng; thiếu hàng phát hiện ở reserve ORD-02). Không ghi DB.

**quote_token**: `base64url(payload JSON) + "." + base64url(HMAC-SHA256)` với khóa `ORDER_QUOTE_SECRET` (có `kid` để xoay).
Payload: `kid`, `actor_key`, `cart_id`, `cart_version`, `method`, `shipping_address`, `contact`, `note`, `locale`, items
(variant_id, cart item_id + item_version, quantity, unit_price, catalog `product_version`, sku/name/size/color/image),
shipping quote (`quote_id`, carrier, weight_grams, fee, cod_fee, expires_at), totals, `exp`. Token chỉ chứa dữ liệu khách tự
gửi và giá công khai; không secret; không log.

## 6. Tạo đơn — `POST /api/v1/orders`

Body: `cart_id`, `cart_version`, `quote_token`; header `Idempotency-Key` bắt buộc (1–128). Thứ tự:

1. Actor (§4).
2. **Idempotency lookup trước mọi kiểm tra quote** (03 §1.3): `(actor_key, checkout_key)` đã có → `checkout_hash` khớp → trả
   đơn hiện trạng (`202` khi `processing_status = PROCESSING`, `200` khi khác); khác → `409 IDEMPOTENCY_KEY_REUSED`.
   `checkout_hash = sha256(cart_id | cart_version | sha256(quote_token))`.
3. Token: chữ ký/`kid` sai → `400 INVALID_QUOTE`; `actor_key`/`cart_id`/`cart_version` khác request → `400 INVALID_QUOTE`
   (không lộ đơn khác); hết hạn → `409 QUOTE_EXPIRED`.
4. Kiểm lại ngoài transaction: cart snapshot `expected_version` (khác → `409 VERSION_CONFLICT`); item của snapshot phải đúng
   tập item/quantity của token (khác → `409 VERSION_CONFLICT`); catalog quote: variant mất/không active → `409
   ITEM_UNAVAILABLE`; `effective_price` khác `unit_price` của token → `409 PRICE_CHANGED` (errors: variant_id, giá cũ, giá
   mới) — client quote lại và khách xác nhận (T24). Shipping quote lấy từ token (hạn 15 phút của shipping dài hơn quote).
5. Một transaction order-db: insert `orders` (`PENDING`, `expires_at = created_at + 24h` cho COD — ORD-03 xử lý hạn),
   `order_items`, `order_status_history` (null → PENDING, CUSTOMER), `order_sagas` (intent FULFILL, step RESERVE, RUNNING —
   ORD-02 tiếp tục), outbox `ORDER_CREATED`; với guest sinh credential (§7). `order_no = 'ORD' + yyyyMM (UTC+7) + 6 số từ
   sequence`, chấp nhận nhảy số. Kiểm `subtotal = Σ line_total` trong code trước insert.
6. Đua cùng key: UNIQUE `(actor_key, checkout_key)` → bên thua đọc lại và trả như bước 2.
7. Cùng giỏ + version đã thành đơn bằng key khác: UNIQUE `(cart_id, cart_version)` → `409 CART_ALREADY_ORDERED` kèm
   `order_no` nếu cùng actor (double-click tạo key mới, tab thứ hai).
8. Response `202` (code OK): `order_id`, `order_no`, `status`, `processing_status = PROCESSING`, `status_url`,
   `retry_after_seconds = 3`, `expires_at`, totals; header `Location = status_url`, `Retry-After: 3`. `201` dành cho ORD-02
   khi đơn đã CONFIRMED/WAITING_PAYMENT ngay.

4xx không lưu (không có row); 5xx sau commit không xảy ra vì response dựng từ row đã commit. Client mất response retry cùng
key nhận cùng đơn.

## 7. Credential guest để xem đơn

- Khi tạo đơn guest: token ngẫu nhiên 32 byte (base64url) → cookie `order_access`, `HttpOnly; Secure; SameSite=Lax;
  Path=/api/v1/orders/{order_no}; Max-Age = 90 ngày` (O06 ≤ 90 ngày); DB chỉ lưu `guest_access_hash = sha256`. Path theo
  từng đơn nên nhiều đơn guest trên một trình duyệt không ghi đè nhau.
- Retry tạo đơn của cùng guest actor (bước 2 §6) cấp **thêm** một token mới (bảng `order_guest_access`); token đã cấp
  trước vẫn hợp lệ — hai request đua cùng key có thể về trình duyệt theo thứ tự bất kỳ, và response bị mất chưa tới tay
  ai nên thu hồi không có lợi (review nhánh I1, sửa 2026-10-20). Ghi log audit có cấu trúc (`order_no`, `ACCESS_REISSUED`,
  không token). Không tăng `version` đơn.
- Không có API tra đơn bằng `order_no`/phone đơn thuần (03 §2.3). Mất cookie → hỗ trợ xác minh theo policy (ngoài phạm vi).

## 8. Đọc đơn

- `GET /api/v1/orders/{order_no}`: member owner (Bearer) hoặc guest cookie khớp; còn lại 404. Trả `order_id`, `order_no`,
  `status`, `processing_status` (PENDING → `PROCESSING`; trạng thái khác thuộc task sau), `version`, `method`, items, totals,
  `shipping_address`, `contact`, `note`, `created_at`, `expires_at`, `allowed_actions = []` (ORD-04 thêm cancel). `no-store`.
- `GET /api/v1/orders/mine?status&cursor&limit`: chỉ member (guest → 401); sort `(created_at desc, id desc)`, limit mặc định
  20 tối đa 100, cursor opaque gắn filter; item tóm tắt (order_no, status, total, created_at, số item).

## 9. Schema (V001, theo 05 §7)

`orders`, `order_items`, `order_status_history`, `order_sagas` đúng 05 §7 (cột/CHECK/index), thêm: UNIQUE `(cart_id,
cart_version)`; `order_items.image_url` lấy từ ảnh catalog; `shipping_quote` jsonb = quote shipping trong token; sequence
`order_no_seq`. Bảng kỹ thuật `outbox_events` (platform-durability). Grant runtime: không DELETE/TRUNCATE `orders`,
`order_items`, `order_status_history`; không UPDATE `order_status_history`. Retention: PII đơn ẩn danh 12 tháng sau khi
đơn kết thúc (O06) — job ẩn danh là task riêng, chưa làm.

## 10. Events, contract, test và tài liệu

- `ORDER_CREATED` (`order.events`, key `order_no`, schema `OrderState` đã có): order_id, order_no, user_id, status, order_version,
  items, total_amount, currency, method. Không PII địa chỉ/liên hệ. Fixture valid/invalid + contract test.
- OpenAPI: `contracts/openapi/order.yaml` (quote, create, get, mine), cập nhật `cart.yaml` (checkout-token); mã lỗi mới
  (`ITEM_UNAVAILABLE`, `CART_EMPTY`, `QUOTE_EXPIRED`, `INVALID_QUOTE`, `CART_ALREADY_ORDERED`, `INVALID_CHECKOUT_TOKEN`,
  `CHECKOUT_TOKEN_GUEST_ONLY`) vào enum `common.yaml` nếu chưa có.
- Test (PostgreSQL Testcontainers; cart/catalog/shipping giả bằng HTTP stub trong test, service token thật):
  - quote: member/guest, giỏ người khác 404 (T21), version cũ 409, item không active 409, method chưa bật 422, tổng đúng
    (COD fee), tràn/total 0 → 422, field lạ 400;
  - create: 202 + snapshot đúng; retry cùng key sau khi quote hết hạn trả cùng đơn; khác body 409; song song cùng key một
    đơn; cùng giỏ/version key khác 409 `CART_ALREADY_ORDERED`; giá đổi 409 `PRICE_CHANGED` không tạo đơn (T24); token giả
    mạo/đổi actor 400; quote hết hạn 409; outbox `ORDER_CREATED` một dòng; saga row RESERVE;
  - đọc: member chỉ đơn mình, guest cookie đúng đơn, cookie đơn khác/đoán `order_no` 404 (T21), retry guest cấp lại cookie
    và cookie cũ vẫn đọc được (kể cả hai request guest song song); mine cursor/filter;
  - cart: checkout token cho guest, Bearer 400, giỏ người khác 404, token hết hạn/sai aud bị order từ chối;
  - schema: CHECK tổng, REVOKE runtime; Gateway: route `/api/v1/orders/**`, `/internal` không route.
- Mutation check như PAY-01 (idempotency lookup trước expiry, so giá, unique giỏ/version, ownership đọc).
- Tài liệu: 03 (§2.3 implemented + mã lỗi), 05 (§7 bổ sung UNIQUE/sequence), 06 (§5.1 ghi phần ORD-01), 08 (quyết định
  ORD-01), 10, 12 (lệnh), README service + root, CLAUDE.md, compose/local-up/.env.example, smoke (`ORD-01 quote/create
  PASS` qua Gateway — làm theo luồng guest: checkout token → quote → create → đọc bằng cookie), evidence.

## 11. Lựa chọn mặc định của agent (chủ dự án duyệt cùng spec 2026-10-20)

1. Service mới `order-service`, port 8087, DB `orders` (tên `order` là từ khóa SQL).
2. ORD-01 chỉ bật **COD**; VNPAY/MOMO → 422 tới PAY-02/03 (cấu hình `ORDER_PAYMENT_METHODS`).
3. Đơn dừng ở `PENDING`/`PROCESSING` cho tới ORD-02; create luôn trả `202`. Saga row RESERVE ghi sẵn để ORD-02 không phải
   backfill.
4. **quote_token tự chứa** (HMAC-SHA256, không lưu DB): không bảng quote, không job dọn; đổi lại token dài (tới ~20 KB với 100
   item) và chứa địa chỉ/liên hệ của chính khách (chỉ base64, không mã hóa).
5. Quote hạn **2 phút** (02 ORD-01); checkout token **15 phút**, dùng lại được; header `Checkout-Token`.
6. Endpoint checkout token chỉ cho guest; member dùng Bearer trực tiếp ở order.
7. Giá đổi → 409 `PRICE_CHANGED` yêu cầu quote lại (không trả kèm quote mới trong lỗi).
8. Thêm UNIQUE `(cart_id, cart_version)` chặn hai đơn từ một trạng thái giỏ.
9. Email bắt buộc với guest (nhận thông báo đơn NOT-01), tùy chọn với member.
10. Cookie truy cập đơn guest Path theo từng `order_no`, 90 ngày; retry cấp thêm cookie, cookie cũ vẫn hợp lệ.
11. Đơn COD `expires_at = +24h` lưu ngay; xử lý hết hạn ở ORD-03.
12. Không gọi inventory ở quote; không rate limit checkout trong ORD-01 (ghi rủi ro ở evidence).
