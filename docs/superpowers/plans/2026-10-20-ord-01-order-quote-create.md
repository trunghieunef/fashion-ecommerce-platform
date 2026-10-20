# ORD-01 Order quote/create Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** order-service với quote ký, tạo đơn PENDING idempotent có snapshot bất biến, đọc đơn theo ownership, và checkout
token guest do cart-service cấp.

**Architecture:** Service mới theo mẫu payment-service (JdbcClient, Flyway, `web/` Api + ApiExceptionHandler + NoStoreFilter,
outbox relay platform-messaging) cộng client phụ thuộc theo mẫu cart-service (`deps/ServiceTokens`, `CatalogClient`,
`DependencyUnavailable` → 503). Gọi cart/catalog/shipping ngoài transaction; một transaction order-db cho mọi ghi.
Checkout token: JWT ES256 bằng nimbus (đã có qua platform-security), ký ở cart, xác minh ở order.

**Tech Stack:** Java 21, Spring Boot 4.0, PostgreSQL 17.11, Kafka (relay), Testcontainers, JDK `HttpServer` stub.

**Spec:** [ORD-01 design](../specs/2026-10-20-ord-01-order-quote-create-design.md)

## Global Constraints

- Port 8087, DB `orders` (`order_migration`/`order_runtime`); Gateway `/api/v1/orders/**` public (CSRF Origin như cart); `/internal` không route.
- Actor: Bearer → `user:<uuid>`; không Bearer + `Checkout-Token` → `guest:<sha256 hex cart_token>`; Bearer sai → 401 không fallback.
- Checkout token: ES256, `iss=cart-service`, `aud=order-service`, `sub=guest:<hash>`, `cart_id`, `exp = iat + 15 phút`, `jti`;
  config cart `CART_CHECKOUT_PRIVATE_KEY`/`CART_CHECKOUT_KID`, order `ORDER_CHECKOUT_PUBLIC_KEYS` (`kid:base64 X.509`, nhiều key).
- Quote hạn 2 phút; `quote_token = base64url(payload).base64url(HMAC-SHA256)`, khóa `ORDER_QUOTE_SECRET` + `ORDER_QUOTE_KID`.
- `ORDER_PAYMENT_METHODS` mặc định `COD`; VNPAY/MOMO → 422 `PAYMENT_METHOD_UNAVAILABLE`.
- Tổng: `total = subtotal + shipping_fee + cod_fee` (discount 0), `Math.*Exact`, `total ≤ 0` → 422 `PAYMENT_AMOUNT_UNSUPPORTED`.
- Lỗi: 400 `INVALID_FIELDS`/`INVALID_QUOTE`/`CHECKOUT_TOKEN_GUEST_ONLY`; 401 `UNAUTHORIZED`/`INVALID_CHECKOUT_TOKEN`; 404
  `CART_NOT_FOUND`/`ORDER_NOT_FOUND`; 409 `VERSION_CONFLICT`/`ITEM_UNAVAILABLE`/`PRICE_CHANGED`/`QUOTE_EXPIRED`/
  `IDEMPOTENCY_KEY_REUSED`/`CART_ALREADY_ORDERED`; 422 `CART_EMPTY`/`NO_SHIPPING_RULE`; 503 `TEMPORARILY_UNAVAILABLE`.
- Đơn: `PENDING`, `processing_status = PROCESSING`, create `202` + `Location` + `Retry-After: 3`; COD `expires_at = +24h`;
  `order_no = 'ORD' + yyyyMM (Asia/Ho_Chi_Minh) + lpad(seq, 6)`.
- Cookie guest `order_access`: HttpOnly, Secure, SameSite=Lax, `Path=/api/v1/orders/{order_no}`, Max-Age 90 ngày; DB lưu sha256.
- Không stage 10 file `.sh` đang M và `.gitattributes`; file mới `infra/local/postgres-init/07-order-db.sh` được phép.
- Verify theo module, tắt stack compose khi chạy full verify (máy ít RAM).

## Review Focus

- Retry `POST /orders` cùng key sau khi quote hết hạn và sau khi giá catalog đã đổi → trả cùng đơn (lookup trước mọi kiểm quote) — Task 4.
- Hai `POST /orders` song song cùng key và hai request khác key cùng giỏ/version → đúng một đơn; bên kia nhận đơn cũ / 409 `CART_ALREADY_ORDERED` — Task 4.
- Guest A dùng quote_token của guest B (hoặc đổi `cart_id` trong body) → 400 `INVALID_QUOTE`/404, không lộ giỏ/đơn — Task 4.
- Cart đổi item/quantity sau quote nhưng version bị client giữ cũ → 409 `VERSION_CONFLICT`, không tạo đơn với số lượng cũ — Task 4.
- Cart/catalog/shipping timeout khi quote/create → 503, không row nào được ghi; retry sau đó thành công — Task 3/4.

---

### Task 1: Checkout token ở cart-service

**Files:** Create `services/cart-service/src/main/java/vn/fashion/cart/cart/CheckoutTokens.java`; modify `CartController.java`,
`application.yaml` (`fashion.cart.checkout.private-key`, `.kid`, `.ttl: 15m`), `contracts/openapi/cart.yaml`; test
`CheckoutTokenIntegrationTest`.

**Interfaces — Produces:** `CheckoutTokens.issue(UUID cartId, String guestHash) -> Issued(String token, Instant expiresAt)`;
claim names `cart_id`, `sub`, `aud`, `iss`, `exp`, `iat`, `jti`; header `kid`.

- [ ] Test `CheckoutTokenIntegrationTest`: guest cookie + giỏ của mình → 200, token verify bằng public key test có `aud=order-service`,
  `sub=guest:`+sha256(cookie), `cart_id`, `exp - iat = 900`, response `Cache-Control: no-store`; Bearer member → 400
  `CHECKOUT_TOKEN_GUEST_ONLY`; cookie của guest khác / cart_id lạ → 404; không cookie → 401; không đổi `expires_at` giỏ.
- [ ] Chạy → FAIL (404 route). Implement `POST /api/v1/carts/{cart_id}/checkout-token` dùng `CartIdentity.guestHash` + repo ownership.
- [ ] `./mvnw -B -pl services/cart-service verify` PASS; cart.yaml thêm operation `createCheckoutToken`; commit
  `feat(ORD-01): cart checkout token for guest checkout`.

### Task 2: Khung order-service, schema, hạ tầng local

**Files:** `services/order-service/` (pom copy payment + bỏ consumer, Dockerfile, `application.yaml`, `OrderApplication`,
`OrderBeans`, `web/` copy payment, `deps/ServiceTokens` + `DependencyUnavailable` copy cart), `V001__order.sql`; root pom;
`infra/local/postgres-init/07-order-db.sh`; compose (service `order`, env, gateway `ORDER_BASE_URL`); `scripts/local-up.sh`
(`service_key ORDER order-service`, nạp public key vào `CART_INTERNAL_CALLERS`/`CATALOG_INTERNAL_CALLERS`/
`SHIPPING_INTERNAL_CALLERS` theo biến hiện có, sinh cặp checkout key + quote secret, giữ qua restart); `kafka-init`
topic `order.events` + DLT; `.env.example`; Gateway route `order-public`; test `OrderSchemaIntegrationTest`,
`GatewayBoundaryTest.orderPublicIsRoutedAndInternalIsNot`.

- [ ] `V001__order.sql`: `orders`, `order_items`, `order_status_history`, `order_sagas` đúng 05 §7 + `created_at`/`updated_at`,
  UNIQUE `(actor_key, checkout_key)`, UNIQUE `(cart_id, cart_version)`, CHECK total và discount, CHECK guest ⇒
  `guest_access_hash`, sequence `order_no_seq`, `outbox_events`; runtime REVOKE DELETE/TRUNCATE trên 3 bảng đơn, UPDATE
  `order_status_history`.
- [ ] Test schema: CHECK total sai → lỗi; trùng `(cart_id, cart_version)` → lỗi; guest không hash → lỗi; runtime DELETE `orders` → `permission denied`.
- [ ] Test Gateway: `/api/v1/orders/quote` route tới order; `/internal/api/v1/orders/x` không route.
- [ ] Verify order-service + `GatewayBoundaryTest` PASS; commit `feat(ORD-01): order-service skeleton, schema and local wiring`.

### Task 3: Actor, client phụ thuộc, quote

**Interfaces — Produces:**
- `record OrderActor(UUID userId, String guestHash, UUID tokenCartId)`, `key()`, `member()`; `OrderIdentity.resolve(HttpServletRequest) -> OrderActor` (401 khi không có).
- `CartClient.snapshot(UUID cartId, long expectedVersion, String actorKey) -> CartSnapshot(UUID cartId, long version, List<Item(UUID itemId, UUID variantId, String sku, int quantity, long itemVersion)>)`; 404/409 của cart → `Api.Problem` cùng mã.
- `CatalogClient.quote(List<UUID>) -> Map<UUID, Variant(boolean found, boolean active, String sku, String nameVi, String nameEn, String size, String color, long effectivePrice, long productVersion, int weightGrams, String imageUrl)>`.
- `ShippingClient.quote(String provinceCode, boolean cod, List<Line(UUID variantId, int quantity)>) -> ShippingQuote(UUID quoteId, String carrier, int weightGrams, long fee, long codFee, Instant expiresAt)`; 422 shipping → Problem 422 cùng mã.
- `record Quote(...)` (payload §5 spec) + `QuoteTokens.sign(Quote) -> String`, `QuoteTokens.verify(String) -> Quote` (400 `INVALID_QUOTE`), `Quote.expired(Instant)`.
- `QuoteService.quote(OrderActor, QuoteRequest, Locale) -> Quote`; `QuoteRequest.parse(JsonNode)` strict.

- [ ] Test `QuoteIntegrationTest` (stub cart/catalog/shipping bằng `StubServers` copy cart): member COD 2 item → totals đúng
  (subtotal, fee, cod_fee, total), `expires_at = now + 120s`, token verify được; guest qua checkout token (key test) → OK;
  token cart_id khác body → 404; cart 404 → 404; cart 409 → 409 `VERSION_CONFLICT`; giỏ rỗng → 422 `CART_EMPTY`; variant
  inactive → 409 `ITEM_UNAVAILABLE`; VNPAY → 422; shipping 422 `NO_SHIPPING_RULE` → 422; giá × qty tràn → 422; guest thiếu
  email / field lạ / phone sai → 400; catalog timeout → 503; không ghi row nào.
- [ ] Test `QuoteTokensTest` (unit): sửa 1 byte payload → `INVALID_QUOTE`; kid lạ → `INVALID_QUOTE`; round-trip giữ mọi field.
- [ ] Implement; verify PASS; commit `feat(ORD-01): actor resolution, dependency clients and signed quote`.

### Task 4: Tạo đơn

**Interfaces — Produces:** `OrderService.create(OrderActor, String key, CreateRequest, Locale) -> Created(OrderView view, boolean replay, String guestAccessToken)`;
`OrderView` (field §8 spec); `OrderEvents.created(OrderView)`; `OrderService.byNo(String) -> Optional<OrderRow>`.

- [ ] Test `OrderCreateIntegrationTest`: member create → 202, `Location`, `Retry-After: 3`, row `PENDING`, items snapshot =
  token, history 1, saga (FULFILL, RESERVE, RUNNING), outbox `ORDER_CREATED` payload khớp schema; retry cùng key sau
  `clock` +5 phút (quote hết hạn) và sau khi stub catalog đổi giá → cùng `order_id`, không row mới; cùng key khác body →
  409 `IDEMPOTENCY_KEY_REUSED`; 2 luồng song song cùng key → 1 đơn, cả hai cùng `order_id`; key khác cùng giỏ/version →
  409 `CART_ALREADY_ORDERED` + `order_no`; giá đổi → 409 `PRICE_CHANGED` (errors variant/old/new), 0 row; quote hết hạn → 409
  `QUOTE_EXPIRED`; token của actor khác → 400 `INVALID_QUOTE`; snapshot cart khác quantity token → 409 `VERSION_CONFLICT`;
  guest → `Set-Cookie order_access` đúng thuộc tính, DB chỉ hash; guest retry → cookie mới, hash đổi, `version` không đổi;
  cart timeout → 503, 0 row.
- [ ] Clock: `OrderBeans` cung cấp `Clock` bean để test dịch thời gian.
- [ ] Event: `contracts/events` fixture `order-created.json` valid + 1 invalid (thiếu items); `tests/contracts/test_order.py`.
- [ ] Implement; verify PASS; commit `feat(ORD-01): idempotent order create with immutable snapshot`.

### Task 5: Đọc đơn

**Interfaces — Consumes:** `OrderService.byNo`, `OrderView`. **Produces:** `GET /api/v1/orders/{order_no}`, `GET /api/v1/orders/mine`.

- [ ] Test `OrderReadIntegrationTest`: member đọc đơn mình 200 (`processing_status PROCESSING`, `allowed_actions []`,
  no-store); member khác 404; guest cookie đúng 200; cookie đơn khác hoặc không cookie 404; `order_no` không tồn tại 404 (cùng
  body); sau guest retry cookie cũ vẫn đọc được (sửa theo review nhánh I1); `mine`: 25 đơn → limit 20 + cursor → trang 2 còn 5, filter status, cursor sai 400,
  guest 401.
- [ ] Implement; verify PASS; commit `feat(ORD-01): order read with member and guest ownership`.

### Task 6: Contract, smoke, tài liệu, evidence

- [ ] `contracts/openapi/order.yaml` (quote, create, get, mine; schema strict; ví dụ synthetic); Redocly lint valid; contract
  test Python cập nhật.
- [ ] `scripts/smoke-local.sh`: bước `ORD-01 quote/create PASS` (member synthetic, giỏ của bước CART-01, tỉnh có rule SHP-01:
  quote 200 → create 202 → GET 200 qua Gateway).
- [ ] Docs: 03 §2.3 (implemented + mã lỗi + checkout token), 05 §7, 06 §5.1 (phần ORD-01), 08 (bảng "ORD-01 — quyết định
  2026-10-20" từ spec §11), 10 (In review), 12 (lệnh), README service + root, CLAUDE.md, evidence
  `docs/evidence/ord-01-local-2026-10-20.md`.
- [ ] Mutation: bỏ lookup trước expiry, bỏ so giá, bỏ UNIQUE giỏ/version, bỏ kiểm ownership đọc → test tương ứng fail.
- [ ] Verify order-service, cart-service, Gateway, contracts, docs check, `git diff --check`; commit `docs(ORD-01): contracts, smoke, docs and evidence`.
