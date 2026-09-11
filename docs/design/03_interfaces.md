# 03 — Interfaces: API, Events & Webhooks

| | |
|---|---|
| **Ngày** | 2026-09-11 |
| **Trạng thái** | Draft — đồng bộ với PRD v0.2 |
| **Phiên bản** | v0.1 |
| **Tài liệu liên quan** | [01 — Brief](01_brief.md) · [02 — PRD](02_prd.md) |

> Tài liệu này định nghĩa **toàn bộ giao diện của hệ thống**: REST API public/admin/internal, sự kiện Kafka và webhook tích hợp bên ngoài. Đây là "hợp đồng" giữa các service — mọi thay đổi phải qua review.

---

## 1. Quy ước chung (Global Conventions)

### 1.1 Base path & Auth

| Khu vực | Base path | Auth | Ghi chú |
|---|---|---|---|
| Public (khách) | `/api/v1` | Optional/Bearer JWT | Không yêu cầu login trừ có ghi chú |
| Admin | `/admin/api/v1` | Bearer JWT + RBAC | Roles: `SUPER_ADMIN`, `OPS`, `MARKETING`, `FINANCE` |
| Internal (service-to-service) | `/internal/api/v1` | Service token (mTLS) | Chặn truy cập public, chỉ qua Gateway |

### 1.2 Response envelope chuẩn

```json
{
  "code": "OK",
  "data": {},
  "metadata": {
    "request_id": "a1b2c3…",
    "trace_id": "…",
    "pagination": { "next_cursor": "…", "has_more": true }
  }
}
```

- `code == "OK"` cho thành công; lỗi dùng `code` trong bảng mã lỗi + `message` (i18n).
- Mọi response đều kèm `X-Correlation-Id` trả về; client gửi `X-Correlation-Id` hoặc Gateway sinh mới.

### 1.3 Mã lỗi chuẩn

| HTTP | `code` | Ý nghĩa | Chi tiết trả về |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | Sai định dạng input | `errors: [{field, message}]` |
| 401 | `UNAUTHORIZED` | Thiếu/sai token | — |
| 403 | `FORBIDDEN` | Không đủ quyền | — |
| 404 | `NOT_FOUND` | Không tồn tại | — |
| 409 | `CONFLICT` | Chuyển trạng thái không hợp lệ | `from`, `to` |
| 409 | `OUT_OF_STOCK` | Hết hàng | `out_of_stock_skus: [{sku, available}]` |
| 422 | `VOUCHER_INVALID` | Voucher không hợp lệ | `reason` (EXPIRED/USED/LIMIT/…) |
| 429 | `RATE_LIMITED` | Vượt rate limit | `retry_after_seconds` |
| 500 | `INTERNAL` | Lỗi hệ thống | — |

### 1.4 Idempotency

- Header `Idempotency-Key` (UUID) **bắt buộc** cho các POST tạo tài nguyên: `POST /orders`, `POST /payments`, `POST /carts/{id}/items` (tùy chọn), `POST /inventory/reserve`, `POST /inventory/commit` (bắt buộc).
- Trùng key + cùng body → trả tài nguyên đã tạo (HTTP 200), không tạo mới.
- Key khác body khác → 409 `CONFLICT`.

### 1.5 Phân trang

- **Khách (public)**: cursor-based — `?limit=20&cursor=<opaque>`; response trả `metadata.pagination.next_cursor` (`null` nếu hết).
- **Admin**: page-based — `?page=1&size=20`; response: `{page, size, total}`.

### 1.6 Rate limit (tiers)

| Tier | Giới hạn | Áp dụng |
|---|---|---|
| anonymous | 60 rpm/IP | Mọi route public |
| member | 300 rpm/user | Authenticated |
| checkout | 10 rpm/user | `POST /orders`, `POST /payments` |
| flash-sale | 5 rpm/user | Route flash sale |
| admin | 60 rpm/user | `/admin/*` |

---

## 2. REST API — Public (`/api/v1`)

### 2.1 Auth & User — `user-service`

| Method | Path | Mô tả | Auth |
|---|---|---|---|
| POST | `/auth/register` | Đăng ký bằng email + mật khẩu | Public |
| POST | `/auth/login` | Login email-PW → access + refresh token | Public |
| POST | `/auth/otp/request` | Gửi OTP SMS tới SĐT | Public |
| POST | `/auth/otp/verify` | Xác thực OTP → token | Public |
| POST | `/auth/refresh` | Lấy access token mới từ refresh token | Refresh |
| POST | `/auth/logout` | Revoke refresh token | Bearer |
| GET | `/users/me` | Hồ sơ của tôi | Bearer |
| PUT | `/users/me` | Cập nhật hồ sơ | Bearer |
| GET | `/users/me/addresses` | Danh sách địa chỉ | Bearer |
| POST | `/users/me/addresses` | Thêm địa chỉ | Bearer |
| PUT | `/users/me/addresses/{id}` | Sửa địa chỉ | Bearer |
| DELETE | `/users/me/addresses/{id}` | Xóa địa chỉ | Bearer |

**Ví dụ — `POST /auth/otp/verify`**

```json
// Request
{ "phone": "0901234567", "otp_code": "123456" }

// Response 200
{
  "code": "OK",
  "data": {
    "access_token": "eyJhbGciOi…",
    "refresh_token": "…",
    "expires_in": 900,
    "token_type": "Bearer",
    "user": { "id": "u-123", "full_name": "Nguyen Van A", "locale": "vi" }
  }
}
```

**Lỗi đặc thù**: 401 `UNAUTHORIZED` (OTP sai/expired) · 429 `RATE_LIMITED` (quá 5 OTP/ngày) · 423 `ACCOUNT_LOCKED` (sai PW 5 lần).

---

### 2.2 Catalog — `catalog-service`

| Method | Path | Mô tả | Auth |
|---|---|---|---|
| GET | `/catalog/products` | Danh sách + lọc + sort + cursor | Public |
| GET | `/catalog/products/{id}` | Chi tiết: variants, ảnh, reviews tóm tắt | Public |
| GET | `/catalog/products/{id}/reviews` | Danh sách review (phân trang) | Public |
| POST | `/catalog/products/{id}/reviews` | Tạo review (member đã mua) | Bearer |
| GET | `/catalog/collections/{slug}` | Sản phẩm theo collection | Public |
| GET | `/catalog/categories` | Cây danh mục | Public |
| GET | `/catalog/search?q=` | Tìm kiếm (Phase 1: ILIKE; Phase 3: Elasticsearch) | Public |
| GET | `/catalog/size-guides/{category_id}` | Size guide theo loại (ÁO/QUẦN/GIÀY) | Public |

**Query params — `GET /catalog/products`**

```
?category_id=5
&brand_id=3
&size=M
&color=ĐEN|TRẮNG          (pipe = OR)
&price_min=100000&price_max=500000
&tag=hot
&sort=NEWEST|BEST_SELLING|PRICE_ASC|PRICE_DESC
&limit=20&cursor=…
```

**Ví dụ — `GET /catalog/products/{id}`**

```json
// Response 200
{
  "code": "OK",
  "data": {
    "id": "p-001",
    "name": "Áo sơ mi trắng oversize",
    "brand": { "id": "b-1", "name": "FASHION VN" },
    "category": { "id": 5, "name": "Áo sơ mi" },
    "base_price": 350000,
    "sale_price": 279000,
    "images": [{ "color": "TRẮNG", "url": "https://cdn…/a.jpg", "thumb_url": "…" }],
    "variants": [{ "id": "v-01", "sku": "SM-WHITE-M", "size": "M", "color": "TRẮNG", "price_override": null, "available": 12 }],
    "rating_summary": { "average": 4.6, "total": 128, "distribution": { "5": 90, "4": 25, "3": 8, "2": 3, "1": 2 } },
    "size_guide": { "category_id": 5, "table_json": {…} },
    "is_wishlisted": false
  }
}
```

**Ví dụ — `POST /catalog/products/{id}/reviews`**

```json
// Request (multipart: content + up to 5 images)
{ "rating": 5, "title": "Rất đẹp", "content": "Vải dày, mặc vừa", "images": ["file1", "file2"] }

// Response 201
{ "code": "OK", "data": { "id": "r-001", "status": "PENDING" } }

// Lỗi đặc thù
403 FORBIDDEN                // chưa mua sản phẩm này
409 ALREADY_REVIEWED         // đã review đơn x variant này
```

---

### 2.3 Cart — `cart-service`

| Method | Path | Mô tả | Auth |
|---|---|---|---|
| GET | `/carts/{cart_id}` | Xem giỏ + tổng tiền + phí ship ước tính | Public (cookie) |
| POST | `/carts/{cart_id}/items` | Thêm item (kiểm tra tồn kho) | Public |
| PUT | `/carts/{cart_id}/items/{item_id}` | Sửa số lượng (1-99) | Public |
| DELETE | `/carts/{cart_id}/items/{item_id}` | Xóa item | Public |
| POST | `/carts/merge` | Hợp nhất guest → member (sau login) | Bearer |
| DELETE | `/carts/{cart_id}` | Xóa giỏ (sau checkout) | Public |

**Ví dụ — `POST /carts/{cart_id}/items`**

```json
// Request
{ "variant_id": "v-01", "quantity": 2 }

// Response 201 — item mới; FE hiển thị stock còn lại
{
  "code": "OK",
  "data": {
    "item_id": "ci-9", "sku": "SM-WHITE-M", "quantity": 2,
    "price_snapshot": 279000, "available": 12
  }
}

// Lỗi đặc thù
409 OUT_OF_STOCK        // {sku, available}
400 VALIDATION_ERROR    // quantity > 99
```

**Lưu ý**: `cart_id` trả trong cookie `cart_id` (HttpOnly, 30 ngày). Guest giỏ lưu Redis; member lưu DB.

---

### 2.4 Order — `order-service`

| Method | Path | Mô tả | Auth |
|---|---|---|---|
| POST | `/orders` | Tạo đơn (checkout) — **bắt buộc `Idempotency-Key`** | Bearer/Guest |
| GET | `/orders/mine` | Đơn của tôi (filter status, cursor) | Bearer |
| GET | `/orders/{order_no}` | Chi tiết + trạng thái + tracking | Bearer |
| POST | `/orders/{order_no}/cancel` | Khách hủy đơn (chỉ khi WAITING_PAYMENT) | Bearer |

**Ví dụ — `POST /orders`**

```json
// Request (Header: Idempotency-Key: 7c9e…, Authorization: Bearer … (optional))
{
  "cart_id": "cart-abc",
  "items": [{ "variant_id": "v-01", "quantity": 2 }],
  "voucher_code": "SALE10",
  "shipping_address": {
    "recipient_name": "Nguyen Van A", "phone": "0901234567",
    "province_code": "79", "district_code": "760", "ward_code": "26734",
    "address_line": "123 Nguyễn Trãi, Q.1"
  },
  "payment_method": "VNPAY" | "MOMO" | "COD",
  "note": "Giao giờ hành chính"
}

// Response 201
{
  "code": "OK",
  "data": {
    "order_no": "ORD202609110001",
    "status": "WAITING_PAYMENT",
    "payment": { "method": "VNPAY", "payment_url": "https://sandbox.vnpay.vn/pay…" },
    "totals": { "subtotal": 558000, "discount": -27900, "shipping_fee": 25000, "cod_fee": 0, "total": 555100 },
    "expire_at": "2026-09-11T10:20:00Z"
  }
}

// Lỗi đặc thù
409 OUT_OF_STOCK          // kèm skus thiếu
422 VOUCHER_INVALID
400 VALIDATION_ERROR
409 CONFLICT              // trùng Idempotency-Key khác body
```

**Ví dụ — `POST /orders/{order_no}/cancel`**

```json
// Response 200
{ "code": "OK", "data": { "order_no": "ORD…", "status": "CANCELLED", "refund_status": "NONE|REQUESTED" } }

// Lỗi đặc thù
409 CONFLICT    // đơn không ở trạng thái cho phép hủy
```

---

### 2.5 Payment — `payment-service`

| Method | Path | Mô tả | Auth |
|---|---|---|---|
| POST | `/payments` | Tạo giao dịch từ order → trả URL/deep link | Bearer/Guest |
| GET | `/payments/vnpay/return` | VNPay return URL — redirect client | Public |
| POST | `/payments/vnpay/ipn` | VNPay IPN — server-to-server (verify checksum) | Public (verify) |
| POST | `/payments/momo/notify` | MoMo notify (verify signature) | Public (verify) |
| POST | `/payments/{payment_id}/refund` | Refund 1 phần/toàn phần | Admin |

**Ví dụ — `POST /payments`**

```json
// Request (Idempotency-Key bắt buộc)
{ "order_no": "ORD202609110001", "method": "VNPAY", "amount": 555100 }

// Response 201
{
  "code": "OK",
  "data": {
    "payment_id": "pay-01",
    "status": "CREATED",
    "redirect": { "type": "URL", "url": "https://sandbox.vnpay.vn/…", "expires_in": 900 }
  }
}
```

**Ví dụ — `POST /payments/vnpay/ipn` (VD)**

```
Payload form: vnp_TxnRef, vnp_Amount, vnp_ResponseCode, vnp_SecureHash, …
→ HTTP 200 với body "RspCode=00&Message=Confirm Success" (theo spec VNPay)
```

**Ví dụ — `POST /payments/{payment_id}/refund`**

```json
// Request — Admin
{ "amount": 279000, "reason": "Hủy đơn do khách yêu cầu" }

// Response 200
{ "code": "OK", "data": { "refund_id": "rf-01", "status": "PROCESSING" } }
```

---

### 2.6 Promotion — `promotion-service`

| Method | Path | Mô tả | Auth |
|---|---|---|---|
| POST | `/promotions/vouchers/validate` | Validate voucher → trả discount preview (không lock) | Bearer/Guest |
| GET | `/promotions/flash-sales/active` | Flash sale đang chạy + countdown + stock còn | Public |

**Ví dụ — `POST /promotions/vouchers/validate`**

```json
// Request
{ "code": "SALE10", "subtotal": 558000, "user_id": "u-123", "items_sku": ["SM-WHITE-M"] }

// Response 200
{
  "code": "OK",
  "data": { "valid": true, "discount_amount": 27900, "type": "PERCENT", "value": 10, "max_discount": 50000 }
}

// Lỗi đặc thù
422 VOUCHER_INVALID   // reason: EXPIRED / NOT_STARTED / USED / LIMIT / MIN_SUBTOTAL / SCOPE
```

---

### 2.7 Shipping — `shipping-service`

| Method | Path | Mô tả | Auth |
|---|---|---|---|
| POST | `/shipping/fee/estimate` | Ước tính phí ship (cart preview) | Bearer/Guest |

**Ví dụ — `POST /shipping/fee/estimate`**

```json
// Request
{
  "to": { "province_code": "79", "district_code": "760", "ward_code": "26734" },
  "items": [{ "sku": "SM-WHITE-M", "quantity": 2 }],
  "cod": false
}

// Response 200
{
  "code": "OK",
  "data": { "fee": 25000, "currency": "VND", "eta_days": { "min": 1, "max": 2 }, "carrier": "GHN" }
}
```

---

## 3. REST API — Admin (`/admin/api/v1`)

> Tất cả cần Bearer + RBAC; role ghi trong cột **Role**.

| Method | Path | Mô tả | Role |
|---|---|---|---|
| GET | `/users` | Danh sách users (page) | SUPER_ADMIN |
| POST/PUT/DELETE | `/catalog/products…` | CRUD sản phẩm/biến thể/ảnh | OPS |
| POST | `/catalog/reviews/{id}/approve` | Duyệt review | OPS |
| POST | `/catalog/reviews/{id}/reject` | Từ chối review | OPS |
| GET | `/orders` | Danh sách đơn (filter status/ngày/keyword, export CSV) | OPS, FINANCE |
| POST | `/orders/{order_no}/status` | Cập nhật trạng thái đơn (kèm ghi chú) | OPS |
| POST | `/inventory/adjust` | Điều chỉnh tồn kho tay (bắt buộc lý do) | OPS |
| POST | `/inventory/import` | Nhập hàng batch (file CSV) | OPS |
| GET | `/payments` | Tra cứu giao dịch + đối soát | FINANCE |
| POST | `/payments/{id}/refund` | Refund | FINANCE |
| POST/PUT | `/promotions/vouchers` | CRUD voucher | MARKETING |
| POST/PUT | `/promotions/campaigns` | CRUD chiến dịch/flash sale | MARKETING |
| POST | `/shipping/shipments` | Tạo shipment từ order | OPS |
| GET | `/shipping/shipments` | Danh sách vận đơn | OPS |
| GET/POST/PUT | `/notifications/templates` | CRUD template email/SMS | MARKETING |

**Ví dụ — `POST /admin/orders/{order_no}/status`**

```json
// Request
{ "to_status": "PACKING", "note": "Đã đóng gói 2 sản phẩm" }

// Response 200
{ "code": "OK", "data": { "order_no": "ORD…", "status": "PACKING" } }

// Lỗi
409 CONFLICT    // cạnh chuyển trạng thái không hợp lệ
403 FORBIDDEN   // không có quyền OPS
```

---

## 4. REST API — Internal (`/internal/api/v1`)

> Chỉ gọi service-to-service, đi qua Gateway, xác thực service token (mTLS). Không expose ra ngoài.

| Method | Path | Consumer → Service | Mô tả | Idempotent |
|---|---|---|---|---|
| POST | `/inventory/reserve` | order → inventory | Reserve stock theo order; fail nếu thiếu | ✅ bắt buộc key |
| POST | `/inventory/commit` | order → inventory | Deduct stock khi paid | ✅ |
| POST | `/inventory/release` | order → inventory | Release reservation khi cancel/expire | ✅ |
| GET | `/inventory/skus/{sku}` | catalog/cart → inventory | Kiểm tra available | — |
| POST | `/promotions/vouchers/lock` | order → promotion | Lock voucher cho order (chống double-spend) | ✅ |
| POST | `/promotions/vouchers/release` | order → promotion | Release khi hủy | ✅ |
| GET | `/shipping/fee` | order → shipping | Tính fee chính thức (đã cache 1h) | — |

**Ví dụ — `POST /internal/inventory/reserve`**

```json
// Request — từ order-service (Idempotency-Key = order_no)
{ "order_id": "ORD202609110001", "items": [{ "sku": "SM-WHITE-M", "quantity": 2 }] }

// Response 200
{ "code": "OK", "data": { "reserved": true, "reserved_at": "2026-09-11T10:05:00Z" } }

// Response 409 — thiếu hàng
{
  "code": "OUT_OF_STOCK",
  "data": { "out_of_stock_skus": [{ "sku": "SM-WHITE-M", "available": 1 }] }
}
```

**Ví dụ — `POST /internal/promotions/vouchers/lock`**

```json
// Request
{ "order_id": "ORD202609110001", "code": "SALE10", "user_id": "u-123", "subtotal": 558000 }

// Response 200
{ "code": "OK", "data": { "locked": true, "discount_amount": 27900 } }

// Response 422 — hết lượt dùng giữa chừng
{ "code": "VOUCHER_INVALID", "data": { "reason": "USED" } }
```

---

## 5. Kafka Events

### 5.1 Envelope chuẩn

```json
{
  "event_id": "uuid-v4",
  "event_type": "ORDER_CREATED",
  "version": 1,
  "occurred_at": "2026-09-11T10:05:00Z",
  "aggregate_id": "ORD202609110001",
  "payload": {}
}
```

Quy tắc consumer:
- **Idempotency**: track `event_id` đã xử lý (bảng `processed_events`); lặp → bỏ qua.
- **Commit offset** sau khi xử lý thành công + ghi idempotency record.
- **Fail** → thử lại (retry topic) → DLQ `{topic}.dead-letter` + alert.
- Retention 7 ngày; replication 3.

### 5.2 Topics & Event types

| Topic | Key | Producer | Consumers | Event types |
|---|---|---|---|---|
| `order.events` | order_no | order-service | inventory, payment, promotion, catalog, notification | `ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED`, `ORDER_COMPLETED` |
| `inventory.events` | sku | inventory-service | catalog (invalidate cache), notification (restock) | `INVENTORY_RESERVED`, `INVENTORY_RELEASED`, `INVENTORY_DEDUCTED`, `INVENTORY_UPDATED`, `INVENTORY_RESTOCKED` |
| `payment.events` | order_no | payment-service | order-service, notification | `PAYMENT_CREATED`, `PAYMENT_COMPLETED`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED` |
| `shipping.events` | order_no | shipping-service | order-service, notification | `SHIPMENT_CREATED`, `SHIPMENT_STATUS_UPDATED` |
| `promotion.events` | variant_id | promotion-service | catalog | `PRICE_CHANGED` |
| `notification.events` | user_id | mọi service | notification-service | `NOTIFY_ORDER_CONFIRMED`, `NOTIFY_PAYMENT_SUCCESS`, `NOTIFY_SHIPMENT_*`, `NOTIFY_OTP`, `NOTIFY_RESTOCK_ALERT` |
| `user.events` | user_id | user-service | (Phase 2: analytics) | `USER_CREATED` |

### 5.3 Payload chi tiết

**`ORDER_CREATED`** (order → tất cả quan tâm)

```json
{
  "event_id": "a1b2…",
  "event_type": "ORDER_CREATED",
  "version": 1,
  "occurred_at": "2026-09-11T10:05:00Z",
  "aggregate_id": "ORD202609110001",
  "payload": {
    "user_id": "u-123",
    "items": [{ "variant_id": "v-01", "sku": "SM-WHITE-M", "quantity": 2 }],
    "total_amount": 555100,
    "payment_method": "VNPAY",
    "voucher_code": "SALE10"
  }
}
```

**`ORDER_PAID`** (order → inventory, shipping, notification)

```json
{
  "event_id": "b2c3…",
  "event_type": "ORDER_PAID",
  "version": 1,
  "occurred_at": "2026-09-11T10:12:00Z",
  "aggregate_id": "ORD202609110001",
  "payload": {
    "user_id": "u-123",
    "items": [{ "sku": "SM-WHITE-M", "quantity": 2 }],
    "total_amount": 555100,
    "payment_method": "VNPAY",
    "shipping_address": { "province_code": "79", "district_code": "760", "ward_code": "26734", "address_line": "123 Nguyễn Trãi" }
  }
}
```

**`ORDER_CANCELLED`** (order → inventory, promotion, notification)

```json
{
  "event_id": "c3d4…",
  "event_type": "ORDER_CANCELLED",
  "version": 1,
  "occurred_at": "2026-09-11T10:22:00Z",
  "aggregate_id": "ORD202609110001",
  "payload": {
    "user_id": "u-123",
    "items": [{ "sku": "SM-WHITE-M", "quantity": 2 }],
    "reason": "CUSTOMER_REQUEST" | "PAYMENT_TIMEOUT" | "OUT_OF_STOCK" | "ADMIN",
    "refund_required": false
  }
}
```

**`ORDER_COMPLETED`** (khi DELIVERED → catalog: mở quyền review; notification)

```json
{
  "event_id": "d4e5…",
  "event_type": "ORDER_COMPLETED",
  "version": 1,
  "occurred_at": "2026-09-13T02:00:00Z",
  "aggregate_id": "ORD202609110001",
  "payload": {
    "user_id": "u-123",
    "items": [{ "variant_id": "v-01", "sku": "SM-WHITE-M", "quantity": 2 }],
    "delivered_at": "2026-09-13T02:00:00Z"
  }
}
```

**`PAYMENT_COMPLETED`** (payment → order, notification)

```json
{
  "event_id": "e5f6…",
  "event_type": "PAYMENT_COMPLETED",
  "version": 1,
  "occurred_at": "2026-09-11T10:12:00Z",
  "aggregate_id": "ORD202609110001",
  "payload": {
    "order_no": "ORD202609110001",
    "payment_id": "pay-01",
    "provider_txn_id": "VNP2412098",
    "amount": 555100,
    "method": "VNPAY",
    "paid_at": "2026-09-11T10:12:00Z"
  }
}
```

**`PAYMENT_FAILED`** (payment → order)

```json
{
  "event_id": "f6g7…",
  "event_type": "PAYMENT_FAILED",
  "version": 1,
  "occurred_at": "2026-09-11T10:30:00Z",
  "aggregate_id": "ORD202609110001",
  "payload": { "order_no": "ORD…", "payment_id": "pay-01", "reason": "USER_CANCELLED" | "TIMEOUT" | "INSUFFICIENT_FUNDS" }
}
```

**`INVENTORY_UPDATED`** (inventory → catalog: invalidate cache)

```json
{
  "event_id": "g7h8…",
  "event_type": "INVENTORY_UPDATED",
  "version": 1,
  "occurred_at": "2026-09-11T10:05:00Z",
  "aggregate_id": "SM-WHITE-M",
  "payload": { "sku": "SM-WHITE-M", "available": 12, "reserved": 3, "on_hand": 15 }
}
```

**`INVENTORY_RESTOCKED`** (inventory → notification)

```json
{
  "event_id": "h8i9…",
  "event_type": "INVENTORY_RESTOCKED",
  "version": 1,
  "occurred_at": "2026-09-14T08:00:00Z",
  "aggregate_id": "SM-WHITE-M",
  "payload": { "sku": "SM-WHITE-M", "available": 20 }
}
```

**`SHIPMENT_STATUS_UPDATED`** (shipping → order, notification)

```json
{
  "event_id": "i9j0…",
  "event_type": "SHIPMENT_STATUS_UPDATED",
  "version": 1,
  "occurred_at": "2026-09-12T02:30:00Z",
  "aggregate_id": "ORD202609110001",
  "payload": {
    "tracking_code": "GHN-123456",
    "carrier": "GHN",
    "status": "CREATED" | "PICKING" | "DELIVERING" | "DELIVERED" | "FAILED" | "RETURNING" | "RETURNED",
    "note": "Đơn đang trên đường giao"
  }
}
```

**`PRICE_CHANGED`** (promotion → catalog: invalidate cache)

```json
{
  "event_id": "j0k1…",
  "event_type": "PRICE_CHANGED",
  "version": 1,
  "occurred_at": "2026-09-11T00:00:00Z",
  "aggregate_id": "v-01",
  "payload": { "variant_id": "v-01", "sale_price": 279000, "campaign_type": "FLASH_SALE" }
}
```

**`NOTIFY_*`** (mọi service → notification)

```json
{
  "event_id": "k1l2…",
  "event_type": "NOTIFY_ORDER_CONFIRMED",
  "version": 1,
  "occurred_at": "2026-09-11T10:05:00Z",
  "aggregate_id": "ORD202609110001",
  "payload": {
    "user_id": "u-123",
    "channel": "EMAIL" | "SMS",
    "template_key": "order.confirmed",
    "locale": "vi",
    "data": { "order_no": "ORD…", "total": 555100 }
  }
}
```

---

## 6. Webhooks từ bên ngoài (Inbound)

> Các endpoint này là nơi bên thứ 3 gọi **vào** hệ thống. Xác thực bằng checksum/signature, xử lý **idempotent** — webhook có thể gọi lặp/trễ.

### 6.1 VNPay — `POST /api/v1/payments/vnpay/ipn`

- **Content-Type**: `application/x-www-form-urlencoded`
- **Xác thực**: so sánh `vnp_SecureHash` = SHA-256( chuỗi query sort A-Z + secret key )
- **Xử lý**: verify → cập nhật transaction theo `vnp_TxnRef` (bắt buộc trả HTTP 200 kèm body báo nhận)
- **Idempotency**: theo `vnp_TxnRef` + `vnp_TransactionNo`; lặp → không đổi trạng thái, vẫn trả 200.

| Field | Bắt buộc | Ghi chú |
|---|---|---|
| `vnp_TxnRef` | ✅ | `order_no` của hệ thống |
| `vnp_Amount` | ✅ | Số tiền × 100 (VND) |
| `vnp_ResponseCode` | ✅ | `00` = thành công |
| `vnp_TransactionNo` | ✅ | Mã GD bên VNPay |
| `vnp_SecureHash` | ✅ | Checksum |
| `vnp_*` | — | Các field khác theo spec VNPay |

**Return (client redirect)**: `GET /api/v1/payments/vnpay/return` — chỉ hiển thị kết quả cho client; **không** dùng để cập nhật trạng thái (tin cậy IPN).

### 6.2 MoMo — `POST /api/v1/payments/momo/notify`

- **Content-Type**: `application/json`
- **Xác thực**: `signature` = HMAC-SHA256( chuỗi rawsort + secretKey )
- **Idempotency**: theo `orderId` (order_no) + `transId`.

| Field | Bắt buộc | Ghi chú |
|---|---|---|
| `partnerCode` | ✅ | — |
| `orderId` | ✅ | order_no |
| `requestId` | ✅ | — |
| `amount` | ✅ | VND |
| `resultCode` | ✅ | `0` = thành công |
| `transId` | ✅ | Mã GD MoMo |
| `signature` | ✅ | HMAC-SHA256 |

### 6.3 Carrier (GHN / GHTK / Viettel Post) — `POST /api/v1/shipping/webhook/{carrier}`

- **Xác thực**: HMAC header riêng từng carrier (token cấu hình trong Config Center).
- **Xử lý**: cập nhật `shipments.status` theo `tracking_code` + `status` → publish `SHIPMENT_STATUS_UPDATED`.
- **Idempotency**: theo `tracking_code` + trạng thái; trạng thái cũ hơn → bỏ qua (chỉ cho phép chuyển tiến, không lùi).

| Field | Mô tả |
|---|---|
| `tracking_code` | Mã vận đơn carrier |
| `status` | `picking-up` / `delivering` / `delivered` / `failed` / `returning` |
| `timestamp` | Thời điểm sự kiện |
| `hmac` | Chữ ký xác thực |

---

## 7. Ma trận phụ thuộc giữa service (qua Interface)

| Service | Gọi REST tới (sync) | Consume Kafka từ | Publish Kafka tới |
|---|---|---|---|
| `user-service` | — | — | `user.events` |
| `catalog-service` | inventory (stock check) | `inventory.events`, `order.events` (completed → review), `promotion.events` | — |
| `cart-service` | shipping (fee estimate), promotion (validate) | — | — |
| `order-service` | inventory (reserve/commit/release), promotion (lock/release), shipping (fee) | `payment.events` | `order.events` |
| `inventory-service` | — | `order.events`, `payment.events` (paid → deduct) | `inventory.events` |
| `payment-service` | — | `order.events` (created) | `payment.events` |
| `promotion-service` | — | `order.events` (cancelled → release) | `promotion.events` |
| `shipping-service` | carrier external (GHN/GHTK/VTPL) | `order.events` (paid) | `shipping.events` |
| `notification-service` | — | mọi topic | — |

---

## 8. Phiên bản & Tiến hóa hợp đồng

- **Contract versioning**: mỗi event có `version`; service thêm field tương thích ngược (additive) → không tăng version. Thay đổi phá vỡ (rename/remove field) → tăng major + consumer backward compatible ít nhất 1 version.
- **API versioning**: path-based `/api/v1`, `/api/v2`. V1 giữ ít nhất 6 tháng sau khi v2 ra mắt.
- **Schema registry**: Kafka payload validate qua Avro hoặc JSON Schema trên topic `*.events` (Phase 1: JSON Schema; Phase 2+ tùy chọn Avro).
- **Thay đổi contract phải**: (1) cập nhật tài liệu này, (2) mở PR review cross-team, (3) test contract test (Pact/Carrier/Spring Cloud Contract) trước khi merge.