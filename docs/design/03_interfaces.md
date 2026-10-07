# 03 — Interfaces: API, Events & Webhooks

B1 · 2026-09-19 · Chủ trì TL + owners producer/consumer. Hợp đồng mức thiết kế để chia task; OpenAPI/JSON Schema thực thi được tạo và review trong PLT-02 và mỗi service task.

## 1. Quy ước chung

### 1.1 Địa chỉ và danh tính

Public /api/v1, admin /admin/api/v1, internal /internal/api/v1. Các route trong bảng được ghép đúng base của mục đó. Internal chỉ qua Gateway private, service identity + caller allowlist, public ingress không route vào.

Bearer member/admin, cookie credential guest cho cart/order. Actor lấy từ danh tính đã xác minh, không lấy user_id/role do client gửi. Service tự kiểm tra ownership; tài nguyên không thuộc actor trả 404 để không lộ tồn tại. Cookie mutation kiểm tra CSRF/origin. Header danh tính client bị Gateway loại bỏ.

UUID cho resource id; order_no là mã hiển thị unique. ISO-8601 UTC cho timestamp, VND integer cho tiền, quantity integer. Tên JSON snake_case, dùng expires_at thống nhất. Unknown JSON enum không được client diễn giải thành thành công.

### 1.2 Response, lỗi và phân trang

Response thành công gồm code=OK, data, metadata.request_id/trace_id. Lỗi gồm code, message, errors nếu lỗi field và metadata; không gửi stacktrace. HTTP 202 vẫn code=OK, data thể hiện processing_status và status_url.

| HTTP | Code | FE / caller xử lý |
|---|---|---|
| 400 | VALIDATION_ERROR | Gắn lỗi field, không tự retry |
| 406 / 415 | VALIDATION_ERROR | Catalog admin: sửa Accept / Content-Type, không tự retry; lỗi Spring 4xx đến advice admin giữ status gốc |
| 401 | UNAUTHORIZED | Refresh một lần khi phù hợp; guest mất credential không được đoán đơn |
| 403 | FORBIDDEN | Không đủ quyền/CSRF, không retry |
| 404 | NOT_FOUND | Không tồn tại hoặc không thuộc actor |
| 409 | CONFLICT / VERSION_CONFLICT | Khác body cùng key, cạnh sai hoặc version khác; refresh trước xác nhận |
| 409 | PRICE_CHANGED / QUOTE_EXPIRED | Trả quote mới hoặc yêu cầu quote lại, khách xác nhận |
| 409 | OUT_OF_STOCK / PROMOTION_LIMIT | Trả SKU/quota thiếu, không tự tăng tiền |
| 409 | ALREADY_REVIEWED | Không tạo review khác |
| 422 | VOUCHER_INVALID / PAYMENT_AMOUNT_UNSUPPORTED | Nêu reason an toàn; không tạo payment total <= 0 |
| 423 | ACCOUNT_LOCKED | Thông báo khóa, không lộ account khác |
| 429 | RATE_LIMITED | Retry-After giây; backoff |
| 503 | TEMPORARILY_UNAVAILABLE | Dependency/capacity chưa thể nhận; mutation mất response vẫn retry cùng key |
| 500 | INTERNAL | request_id cho hỗ trợ, không kết luận mutation chưa xảy ra |

Public list: limit mặc định 20, tối đa 100; cursor opaque gồm sort value + id, filters gắn cursor. Admin: page từ 1, size mặc định 20 tối đa 100, total. Locale từ profile hoặc Accept-Language vi/en. Trả X-Correlation-Id; correlation chỉ phục vụ trace, không thay idempotency.

Rate-limit khởi điểm: anonymous 60 rpm/IP; member 300 rpm/user; checkout 10 rpm/actor + IP; admin 60 rpm/user; flash-sale 5 rpm/actor. Poll trạng thái dùng tier đọc, không dùng tier checkout. Guest actor theo session đã xác minh; bổ sung phone/IP chống giữ hàng COD. Webhooks có ingress limit riêng không dùng limiter người mua.

`TASK:USR-01b-ii` hiện thực riêng login/forgot (không thay nghiệm thu các tier chung trên): login
20 request/300 giây/IP; forgot 20/3600 giây/IP và 5/3600 giây/email đã trim + lowercase.
Cửa sổ bắt đầu ở request đầu; request bị giới hạn không kéo dài cửa sổ. `429 RATE_LIMITED`
trả `Retry-After` giây làm tròn lên, body chung không nêu email/IP/tier; Redis lỗi →
`503 TEMPORARILY_UNAVAILABLE`, không tạo phiên/token reset. IP quota kiểm trước validation
field của body đã bind; JSON không bind được vẫn trả 400. Chưa có generic ingress limiter.

IP dùng cho quota đi theo hop được xác minh: nginx ghi đè `X-Forwarded-For` bằng socket peer;
Gateway chỉ nhận một IP literal từ header này nếu peer nằm trong `GATEWAY_TRUSTED_PROXIES`,
xóa `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, `X-Client-IP` từ request rồi đặt `X-Client-IP`.
User-service chỉ nhận header đó từ `USER_TRUSTED_GATEWAYS`; peer không được tin, header thiếu,
trùng hoặc không hợp lệ thì dùng socket peer. Allowlist rỗng mặc định; nhận IP literal hoặc
hostname DNS nội bộ, không nhận wildcard/CIDR. Forwarded-header rewriting của server bị tắt
để allowlist luôn xét peer thực. Khi deploy thêm ingress phải cấu hình hop tin cậy và kiểm lại
spoofing; không tự tin mọi địa chỉ private.
Header chỉ được parse sau khi peer tin cậy, IPv4 kiểm đủ octet để không fallback sang DNS.
Zone ID được bỏ khỏi IPv6 socket peer, vẫn từ chối trong header; thiếu peer tại Gateway thì
xóa IP/identity header và để service dùng socket peer Gateway, không tạo IP giả.

### 1.3 Idempotency và version

POST tạo order/payment/refund/reservation/shipment, inventory import/adjust, admin actions và cart merge/cleanup yêu cầu Idempotency-Key. Cart add cũng bắt buộc để retry không cộng quantity. PUT/DELETE có effect idempotent và version guard khi cần.

Scope key = actor_key + operation + key; canonical request hash server tính từ dữ liệu đã normalize, không tin hash do client public gửi. Cùng body/key → cùng resource; đang xử lý 202; đã xong trả 200 hiện trạng. Khác body → 409. Khóa business unique tồn tại dài hơn TTL response.

Idempotency lookup của order chạy sau auth nhưng trước kiểm tra quote hết hạn: retry đơn đã tạo trả đơn cũ, không fail chỉ vì quote hết hạn. Validation lỗi trước tạo resource có thể 4xx; khi order đã durable, client nhận order/status URL và reason nếu saga fail, không khuyến khích tạo key mới vì một timeout.

Mutation cart/admin dùng expected_version; thiếu version bắt buộc trả 400; stale trả 409. Scope hash không chứa transient trace ID, cookie hay secret. Internal operation key ổn định theo order_id:operation; body có request_hash original khi release/close/cancel chống late create.

## 2. API public

### 2.1 User

| Method | Path | Auth / phase | Input → output |
|---|---|---|---|
| POST | /auth/register | Public / 1 | email,password,full_name,locale → user/access token + refresh cookie; email đã có → 409 CONFLICT |
| POST | /auth/login | Public / 1 | email,password → access token 900s + refresh cookie; mọi lỗi đăng nhập (gồm tài khoản đang khóa) trả cùng 401 INVALID_CREDENTIALS |
| POST | /auth/refresh | Refresh cookie / 1 | CSRF/origin → access mới, rotate cookie |
| POST | /auth/logout | Session / 1 | revoke family, clear cookie |
| POST | /auth/password/forgot | Public / 1 | email → thông báo chung 202 |
| POST | /auth/password/reset | Scoped token / 1 | token,new_password → consume token, revoke sessions |
| POST | /auth/password/change | Member / 1 | current/new password → revoke sessions |
| GET, PUT | /users/me | Member / 1 | Profile; PUT không nhận role/auth_version |
| GET, POST | /users/me/addresses | Member / 1 | List/create địa chỉ |
| PUT, DELETE | /users/me/addresses/{id} | Owner / 1 | Address; default invariant tại TX |
| POST | /auth/email/verification | Member / 2 | Send challenge cho email hiện hành |
| POST | /auth/email/verify | Scoped token / 2 | token → email_verified_at |
| POST | /auth/otp/request | Public / 2 | phone → challenge_id,expires_at |
| POST | /auth/otp/verify | Public / 2 | challenge_id,otp_code → session |
| GET | /auth/social/{provider}/start | Public / 2 | state/nonce flow |
| GET | /auth/social/{provider}/callback | Provider flow / 2 | verified identity → session hoặc yêu cầu link |
| POST, DELETE | /users/me/social/{provider} | Member + reauth / 2 | Link có verified provider proof / unlink nếu còn phương thức đăng nhập |

Contract thực thi của register/login/refresh/logout (`TASK:USR-01` phần 1a), đổi/quên/đặt lại mật khẩu (`TASK:USR-01` phần 1b-i; secret chỉ qua [`user-internal.yaml`](../../contracts/openapi/user-internal.yaml)) và `/users/me` + địa chỉ (`TASK:USR-02` phần 2a): [`contracts/openapi/user.yaml`](../../contracts/openapi/user.yaml). `/users/me` cần Bearer; token có `auth_version` cũ hoặc tài khoản không còn ACTIVE trả 401 `INVALID_ACCESS_TOKEN`; địa chỉ của người khác trả 404. Access token là JWT ES256 (`iss=user-service`, `aud=fashion-api`, `sub`, `auth_version`, `permissions`, `kid`), public key cấp cho verifier qua config (ADR-21). `permissions` là mảng permission code (13 §2) của các role tại lúc cấp token; service kiểm code đó cho endpoint admin. Cookie `refresh_token`: HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth, 30 ngày; dùng lại token đã xoay thì thu hồi cả family. Refresh token không nằm JSON response/localStorage. Guest có credential ngẫu nhiên do cart/order cấp bằng Secure HttpOnly cookie; lưu hash ở server.

### 2.2 Catalog, cart và tương tác Phase 2

Collection public list/detail thuộc `TASK:CAT-02`, không triển khai trong CAT-01b.
Theo quyết định chủ dự án ngày 2026-10-07, chỉ trả collection `ACTIVE`, đang trong khoảng
hiệu lực `[start_at, end_at)` và có ít nhất một product `ACTIVE`. Danh sách product của
collection chỉ gồm product `ACTIVE`; điều kiện được kiểm ở thời điểm đọc, kể cả khi product
đã bị unpublish sau khi collection được kích hoạt. Không lọc collection public chỉ bằng status.

| Method | Path | Auth / phase | Input → output |
|---|---|---|---|
| GET | /catalog/products | Public / 1 | `TASK:PLT-01` sample chỉ nhận `limit` (mặc định 20, tối đa 100), trả card ACTIVE `id,slug,name_vi,name_en,next_cursor`; filter/sort/cursor đầy đủ thuộc `CAT-02` |
| GET | /catalog/products/{id} | Public / 1 | variants,images,size_guide,available,observed_at; review summary Phase 2 |
| GET | /catalog/search | Public / 1 | q + filters/cursor; bounded query length |
| GET | /catalog/categories | Public / 1 | Cây tối đa 2 cấp |
| GET | /catalog/brands | Public / 1 | Active brands |
| GET | /catalog/collections | Public / 1 | `TASK:CAT-02`: ACTIVE, trong [start_at, end_at), có ≥ 1 product ACTIVE |
| GET | /catalog/collections/{slug} | Public / 1 | `TASK:CAT-02`: cùng điều kiện list; lookbook + product ACTIVE phân trang |
| GET | /catalog/size-guides/{category_id} | Public / 1 | Locale và hướng dẫn |
| POST | /carts | Public/member / 1 | Guest cấp cookie; member trả ACTIVE cart |
| GET | /carts/mine | Member / 1 | Active cart và version |
| GET | /carts/{cart_id} | Owner / 1 | Items/version và preview có nhãn |
| POST | /carts/{cart_id}/items | Owner / 1 | variant_id,quantity,expected_version + key |
| PUT | /carts/{cart_id}/items/{item_id} | Owner / 1 | Absolute quantity,expected_version |
| DELETE | /carts/{cart_id}/items/{item_id} | Owner / 1 | expected_version qua query |
| DELETE | /carts/{cart_id} | Owner / 1 | Xóa giỏ do khách, expected_version; không dùng cho cleanup order |
| POST | /carts/merge | Member + guest cookie / 1 | guest_cart_id,expected versions + key → target cart,adjustments |
| GET | /catalog/products/{id}/reviews | Public / 2 | Cursor, summary chỉ APPROVED |
| GET | /catalog/review-eligibilities/mine | Member / 2 | order_id,variant_id,eligibility_id,expires_at |
| POST | /catalog/products/{id}/reviews | Member / 2 | eligibility_id,rating,title,content,image object IDs |
| PUT | /catalog/reviews/{id} | Owner / 2 | Nội dung, trong 7 ngày; về PENDING |
| GET | /catalog/wishlist | Member / 2 | Sản phẩm phân trang |
| PUT, DELETE | /catalog/wishlist/{product_id} | Member / 2 | Idempotent |
| PUT, DELETE | /inventory/restock-subscriptions/{sku} | Member / 2 | Subscribe/unsubscribe đúng user |
| POST | /catalog/review-images/uploads | Member eligible / 2 | filename,type,size → scoped upload |
| POST | /notifications/unsubscribe | Scoped token / 2 | token trong body → preference, idempotent |

Catalog images upload admin được mô tả ở §3. Complete kiểm bytes/type thực/size;
attach kiểm APPROVED/ownership/target trong DB; chưa đủ validation không publish ảnh.

### 2.3 Order và payment của khách

| Method | Path | Auth / phase | Input → output |
|---|---|---|---|
| POST | /orders/quote | Cart owner / 1 | cart_id,cart_version,address,contact,method,note,voucher nếu bật → quote |
| POST | /orders | Actor bound quote / 1 | quote_token,cart_id,cart_version + key → order durable |
| GET | /orders/mine | Member / 1 | status/cursor → đơn của mình |
| GET | /orders/{order_no} | Owner/member hoặc guest order cookie / 1 | State/totals/items/tracking/payment/refund và allowed_actions |
| POST | /orders/{order_no}/cancel | Owner / 1 | reason,expected_version,key → 202 CANCELLING hoặc trạng thái hiện hành |
| GET | /orders/{order_no}/payment | Order owner / 1 | Payment status, URL đã có nếu còn mở/hợp lệ; không create/đổi amount |
| POST | /promotions/vouchers/validate | Actor / 2 | cart_id,version,code → server preview |
| GET | /promotions/flash-sales/active | Public / 2 | Server now, start/end, sale price, quota tham khảo |
| POST | /shipping/fee/estimate | Actor / 1 | Cart/version,to,method → preview fee/cod_fee,carrier,expiry |

Không còn POST /payments public nhận amount. Browser redirect/notify provider xem §6. Guest order credential gắn session và order; refresh trang giữ cookie, mất cookie đi hỗ trợ xác minh theo policy, không có API truy cập bằng phone/order_no đơn thuần.

Quote response gồm quote_token,expires_at,cart_version,items snapshots,address/method và totals. Token ký gắn toàn bộ input ảnh hưởng giá/ownership; không chứa secret. Snapshot payload do server kiểm tra lại catalog versions, promotion và quote fee. Quote chưa giữ hàng.

Ví dụ body tạo đơn (UUID minh họa, token không phải token thật):

```json
{
  "cart_id": "11111111-1111-4111-8111-111111111111",
  "cart_version": 4,
  "quote_token": "example-signed-quote"
}
```

Ví dụ response đang chuẩn bị:

```json
{
  "code": "OK",
  "data": {
    "order_id": "22222222-2222-4222-8222-222222222222",
    "order_no": "ORD202609000001",
    "status": "PENDING",
    "processing_status": "PROCESSING",
    "status_url": "/api/v1/orders/ORD202609000001",
    "retry_after_seconds": 3,
    "expires_at": "2026-09-19T03:15:00Z",
    "totals": {
      "subtotal": 558000,
      "discount_amount": 0,
      "shipping_fee": 25000,
      "shipping_discount": 0,
      "cod_fee": 0,
      "total_amount": 583000,
      "currency": "VND"
    }
  },
  "metadata": {"request_id": "example-request", "trace_id": "example-trace"}
}
```

HTTP 202 kèm Location=status_url và Retry-After. HTTP 201 khi đã WAITING_PAYMENT + URL hoặc CONFIRMED COD. GET order trả processing_status=PROCESSING/READY/ACTION_REQUIRED/FINISHED, reason_code nếu cần; không lộ stacktrace/saga internals. Client poll theo Retry-After, pause khi tab hidden, dừng khi terminal; không báo payment success từ query string redirect.

## 3. API admin

Tất cả có Bearer + permission, auth_version hiện hành; mutation có reason/key/version khi áp dụng. Paths CRUD viết {id} là tài nguyên riêng, không có endpoint “...” ngầm.

CAT-01a đã triển khai, kiểm tra local/CI và merge PR #14 vào `main`: GET/POST categories/brands,
PUT theo id; GET/POST products, GET/PUT theo id; POST variant, PUT variant; POST publish/unpublish.
Contract thực thi tại [`catalog.yaml`](../../contracts/openapi/catalog.yaml), Gateway route
`/admin/api/v1/catalog/**` tới catalog-service. Product POST tạo DRAFT; PUT chỉ sửa nội dung,
description lưu HTML đã sanitize, tiền là bigint VND (không nhận số thập phân). Các endpoint
cần `catalog.write` (OPS), mọi POST có `Idempotency-Key`; PUT và publish/unpublish có
`expected_version`. Publish/unpublish nhận reason tùy chọn ≤ 500, cạnh sai trả 409;
publish kiểm taxonomy ACTIVE và ≥ 1 variant ACTIVE (điều kiện ảnh thuộc CAT-03).
Token được kiểm bằng `AccessTokenVerifier`, không gọi user-service; thu hồi quyền trễ tối đa
15 phút theo ADR-21. Retry key/body chuẩn hóa trả cùng kết quả; đổi body trả 409 IDEMPOTENCY_KEY_REUSED.
SKU mới: `String.strip` → kiểm 1–64 ký tự ASCII `^[A-Za-z0-9][A-Za-z0-9._-]*$` →
uppercase `Locale.ROOT` trước hash idempotency/lưu DB/audit/outbox. Trùng SKU không phân biệt
hoa/thường trả 409 `CONFLICT`, field `sku`; retry cùng key đổi casing vẫn replay. SKU cũ bất biến,
response/key/event đã lưu không backfill; replay key cũ giữ data/status, đổi field khác vẫn 409.
Audit của mutation dùng cùng `request_id` với metadata response đầu; retry giữ data/status,
metadata mới và không thêm audit. Lỗi nội bộ admin trả 500 với đúng code `INTERNAL`, message
`INTERNAL_ERROR`, metadata và header `X-Correlation-Id`; exception chỉ log phía server qua
logger redact PII. Handler này không áp cho public ProductQueryController.
Lỗi Spring `ErrorResponse` 4xx đến advice admin giữ status (gồm 415 Content-Type không hỗ trợ
và 406 Accept không hỗ trợ), code `VALIDATION_ERROR`, message chung `INVALID_HTTP_REQUEST`,
metadata/X-Correlation-Id, Content-Type application/json và header lỗi của Spring; không log
ERROR/stacktrace. Các handler 400/401/403/404/409/503 cụ thể vẫn giữ contract hiện có.
Chỉ lỗi còn lại mới dùng fallback 500. Binding/content negotiation có thể xảy ra trước auth
trong method; không đổi thứ tự xác thực hoặc scope advice sang route public/unmapped.
Chưa nghiệm thu CAT-01; collection/size-guide, CATALOG_CHANGED và relay nằm ngoài phần 1a.

CAT-01b collection admin (quyết định chủ dự án ngày 2026-10-07): PUT collection sang
`ACTIVE` không yêu cầu có product `ACTIVE`, cho phép collection rỗng hoặc gồm product chưa
publish để chuẩn bị lịch launch bằng `start_at` tương lai. Điều kiện hiển thị được kiểm ở
public read trong CAT-02 theo §2.2; không dùng điều kiện lúc chuyển trạng thái thay thế kiểm lúc đọc.

Collection mutation (chủ dự án, 2026-10-07): POST cần `Idempotency-Key`, tạo DRAFT,
version 0 và trả 201. PUT chỉ cần `expected_version`, không cần `Idempotency-Key`
như PUT CAT-01a; thay toàn bộ nội dung và danh sách items, tăng version 1, trả 200.
Thiếu version trả 400, stale trả 409 `VERSION_CONFLICT`; PUT cho phép status
DRAFT/ACTIVE/INACTIVE. Nội dung gồm tên VI/EN, slug, start_at/end_at và items.
Mỗi item là `{product_id, sort_order}`; tối đa 1.000 item, cho phép rỗng/product chưa
publish; không trùng product_id. `sort_order` là integer 0..2147483647, cho phép trùng;
thứ tự đọc `(sort_order, product_id)`. Product không tồn tại trong items trả 400
`VALIDATION_ERROR`, field `items[i].product_id` theo vị trí item trong request;
404 chỉ cho resource trên path (collection id), không dùng cho tham chiếu trong body.
Collection row lock, thay items, tăng version và audit cùng transaction.

`start_at`/`end_at` là ISO-8601 có offset, chuyển UTC và truncate xuống microsecond
trước so sánh/hash/lưu; năm UTC phải trong 1..9999, ngoài phạm vi trả 400 đúng field.
Null nghĩa không giới hạn phía tương ứng; có cả hai thì `end_at > start_at` sau truncate.
CAT-01b không nhận `cover_url`,
`lookbook` hoặc `lookbook_images` trong request, kể cả null; response `cover_url`
luôn null. Cover/lookbook thuộc CAT-03, chỉ attach sau upload được kiểm tra;
không mở đường nhận URL chưa được kiểm tra trong CAT-01b. Contract tại `catalog.yaml`
đã có POST/PUT/examples và endpoint collection mutation thực thi tại Task 3.

Collection GET list/detail đã hiện thực và kiểm HTTP Testcontainers tại Task 2;
POST/PUT collection tại Task 3, GET size guide tại Task 4 và PUT tại Task 5.

Spec CAT-01b §6 đã được chủ dự án duyệt ngày 2026-10-07: collection list nhận page
(mặc định 1, ≥ 1), size (20, 1..100), status tùy chọn DRAFT/ACTIVE/INACTIVE, sort
created_at DESC/id DESC; trả `{items, page, size, total}`, summary không kèm product items.
Tên VI/EN strip 1..255, slug strip theo validator CAT-01a (lowercase, tối đa 160).
POST/PUT cần name_vi/name_en/slug/items; date bị bỏ qua thành null theo full replacement.
Collection POST hash giữ thứ tự items request; đổi thứ tự cùng key là đổi body (409),
date offset tương đương chuẩn hóa cùng Instant; response vẫn sort_order/product_id.

Request collection/size-guide từ chối field lạ có chủ đích, khác CAT-01a: DTO dùng
`@JsonAnySetter` gom key lạ, validate trả 400 `VALIDATION_ERROR`; field top-level bằng
tên key, key lạ lồng nhau dùng `items[i].<key>` hoặc `table_json.<key>`.
Giữ global `fail-on-unknown-properties=false`; `ignoreUnknown=false` không đủ override
global nên không dùng thay any-setter. Không đưa unknown field vào hash/audit hoặc đổi
binding của endpoint CAT-01a. GET collection detail dùng một SQL `json_agg` items
ORDER BY sort_order/product_id để nội dung và version cùng snapshot.
Expected_version, sort_order và table_json bị bỏ qua hoặc gửi JSON null đều là thiếu →
400 VALIDATION_ERROR; không để NullNode đi vào numeric/table validation như giá trị hợp lệ.

CAT-01b size guide (quyết định chủ dự án ngày 2026-10-07): `table_json` là object có đúng
hai key `columns` và `rows`, không nhận field lạ. `columns` có 1–20 tiêu đề string,
mỗi tiêu đề 1–100 ký tự sau `strip`, so trùng không phân biệt hoa/thường bằng
`strip + toLowerCase(Locale.ROOT)`, giữ casing hiển thị sau strip. Chỉ header hợp lệ được
kiểm tra trùng; header sai type/rỗng/quá dài chỉ báo lỗi của chính giá trị đó. `rows` có 1–100 hàng; mỗi hàng
là array có đúng số ô bằng số cột; mỗi ô là string 0–100 ký tự sau `strip`.
Toàn bộ `table_json` chuẩn hóa sau serialize compact không vượt 32.768 byte UTF-8 (32 KB).
Giới hạn chuỗi dùng cách đếm hiện có của service. Tiêu đề và ô là plain text,
không HTML; FE phải escape khi render. `guideline_html` không bắt buộc: thiếu/null thành
chuỗi rỗng, sanitize bằng jsoup theo cùng quy tắc mô tả sản phẩm, tối đa 20.000 ký tự
sau sanitize (chủ dự án duyệt ngày 2026-10-07). Quy ước nội dung: cột đầu là nhãn size khớp `variant.size`,
nhưng không ép quy ước này bằng validation/FK. Shape JSON tại 05 §5.

PUT `/catalog/size-guides/{category_id}/{locale}` giữ route hiện có, cần `catalog.write`,
`Idempotency-Key` và `expected_version`. Đây là ngoại lệ so với CAT-01a: PUT có thể tạo,
nên cần key và bản ghi bắt đầu ở version 1. `expected_version=0` chỉ tạo khi chưa có
bản ghi cho category/locale: trả 201, `version=1`. Cập nhật dùng version hiện hành:
trả 200, `version` tăng 1. Thiếu version trả 400; gửi 0 khi đã tồn tại hoặc version
lệch trả 409 `VERSION_CONFLICT`. Hai lệnh tạo song song với key khác nhau: đúng một
201, lệnh còn lại 409 `VERSION_CONFLICT`, dựa trên UNIQUE `(category_id, locale)`.
`locale` chỉ nhận `vi`/`en`, giá trị khác trả 400. Category không tồn tại trả 404,
code `NOT_FOUND`, message `CATEGORY_NOT_FOUND` theo khuôn catalog hiện có.

Thứ tự PUT guide: auth → validate → category path (không tồn tại 404) → idempotency →
version guard. Category được kiểm cả trên replay trước lookup. Nếu category tồn tại nhưng
guide chưa có và expected_version > 0, trả 409 VERSION_CONFLICT, không tự upsert.
Create race dùng `INSERT ... ON CONFLICT (category_id, locale) DO NOTHING RETURNING`;
rỗng trả 409 VERSION_CONFLICT, không bắt unique violation làm abort transaction idempotency.
Cùng key/body
chuẩn hóa replay data/status đã lưu (kể cả 201), metadata mới, không thêm audit;
cùng key khác body trả 409 `CONFLICT`/`IDEMPOTENCY_KEY_REUSED`. Hash phải phân biệt
category_id/locale, expected_version và nội dung đã chuẩn hóa. Race cùng key/body
là retry của một lệnh, tuân theo replay; quy tắc một 201/còn lại 409 áp cho các lệnh
tạo độc lập. Mutation, audit và kết quả idempotency phải commit cùng transaction.
PUT guide đã hiện thực tại Task 5 và kiểm bằng HTTP/PostgreSQL Testcontainers;
Task 6 đã upgrade V004 trên volume local và smoke Gateway PASS; Task 7 full verification281 PASS,
[evidence CAT-01b](../evidence/cat-01b-local-2026-10-07.md) ghi trạng thái whole-branch review.

CAT-01b admin read (chủ dự án, 2026-10-07): bổ sung GET `/catalog/collections/{id}`
trả collection + `items` gồm `product_id`/`sort_order`, thứ tự `(sort_order, product_id)`,
và `version` của collection. GET `/catalog/size-guides/{category_id}/{locale}` trả
nội dung + version; chưa có trả 404 `NOT_FOUND`; locale ngoài vi/en trả 400.
Cả hai kiểm `catalog.write` tại service như GET admin CAT-01a, trả cả dữ liệu
DRAFT/INACTIVE, giữ envelope/metadata và X-Correlation-Id hiện có. Nội dung và version
phải thuộc cùng snapshot; version GET là `expected_version` mà PUT dùng để cập nhật.
Contract/examples 2xx/4xx tại `catalog.yaml` và endpoint GET đã hiện thực ở Task2/4.
HTTP Testcontainers đã kiểm 401/403/404, locale sai400, thứ tự items, đọc DRAFT/INACTIVE,
GET → PUT dùng version nhận được thành công và version cũ sau cập nhật trả 409.
Không bổ sung public read; phần đó giữ tại TASK:CAT-02.

CAT-03 media design (chủ dự án chốt ngày 2026-10-07; chỉnh theo review
2026-10-08; **chưa implementation**): presigned PUT sống 300 giây và ký
Content-Length/Content-Type cho quarantine private. Server sinh key; upload
scope actor JWT và target product/collection, không nhận URL/key hoặc chuyển owner.
Complete HEAD size, bounded stream, magic bytes và dimensions trước decode, rồi
re-encode loại EXIF/GPS thành approved image/thumb. Không CopyObject approve.

Complete dùng transaction ngắn claim PROCESSING, S3 ngoài transaction, rồi
transaction ngắn ghi kết quả với CAS token/attempt và lease còn hiệu lực.
Approved key cố định, PUT If-None-Match:*; 412 recovery từ object thực tế và
không lộ 412 ra client. Metadata durable lấy từ bytes thực tế. Complete idempotent
theo upload_id; terminal không đọc quarantine hoặc thêm audit. Recovery ưu tiên
approved primary đã có và tạo thumb thiếu; lỗi tạm thumb/DB không tự expire/reject.
Shape/error/schema tại [spec để review](../superpowers/specs/2026-10-07-cat-03-media-design.md)
và [catalog.yaml](../../contracts/openapi/catalog.yaml); operations mới gắn planned.

Validation chỉ JPEG/PNG, raw và mỗi output tối đa 5 MiB, width/height 1..8192 và
tối đa 25 triệu pixel. Approved cạnh dài tối đa 2560, thumb tối đa 800, không
upscale. Một re-encode/instance, không queue; hết slot trả 429 và Retry-After: 1
trước đổi state. Decode lỗi hoặc output vượt cap bị REJECTED có code, không 500.
WebP/format mới cần duyệt riêng.

Public image GET dùng **Cache-Control: public, max-age=300** và ETag cố định là
quoted SHA-256 của representation approved. If-None-Match trùng trả **304 không
body**, vẫn có ETag/cache/correlation headers. Khi request tới service, kiểm
visibility và object availability trước conditional response: public chỉ ảnh
đang attach product ACTIVE hoặc collection ACTIVE trong [start_at, end_at) có ít
nhất một product ACTIVE; không visible trả 404 kể cả ETag trùng. Cache fresh có
thể tiếp tục hiển thị ảnh tối đa 5 phút sau unpublish. Admin preview kiểm
catalog.write và dùng **Cache-Control: private, no-store**, được xem DRAFT/INACTIVE.
Không đọc raw hoặc thêm public browse CAT-02.

Query kind chỉ nhận image hoặc thumb, omitted mặc định image. Giá trị lạ hoặc
rỗng trả **400 VALIDATION_ERROR / INVALID_FIELDS, field kind** ở cả public/admin.
Route public planned là GET /api/v1/catalog/images/{asset_id}; thành công là
binary, lỗi dùng JSON envelope.

Publish product cần ít nhất một APPROVED image đã attach và alt VI/EN hợp lệ,
giữ gates CAT-01a. PUT images không làm ACTIVE còn 0 ảnh: 400
ACTIVE_PRODUCT_REQUIRES_IMAGE, field images. ACTIVE legacy V001 giữ status,
được attach, không backfill/unpublish. Collection không có cover/lookbook gate.

URL PUT sống 300 giây; complete_deadline = put_expires_at + 24h; PROCESSING lease
120 giây. Trùng PROCESSING trả 409 CONFLICT/UPLOAD_PROCESSING; REJECTED trả 400
VALIDATION_ERROR/IMAGE_REJECTED với reason_code; quá deadline EXPIRED trả 409
CONFLICT/UPLOAD_EXPIRED, không đọc S3. Raw HEAD 404 trước deadline trả 409
CONFLICT/UPLOAD_NOT_UPLOADED và giữ PENDING cho retry.

Storage outage làm thao tác route ảnh/upload/complete cần S3 trả **503
TEMPORARILY_UNAVAILABLE / DEPENDENCY_UNAVAILABLE**. Không thêm S3 vào readiness
catalog-service; metric/health group media riêng báo storage lỗi. Terminal
complete replay không S3 và vẫn giữ kết quả đã lưu. Policy chi tiết tại 13.

GET/PUT images dùng full replacement và resource expected_version, không
Idempotency-Key. GET snapshot cùng version; PUT tăng version và audit diff chỉ
asset_id thêm/gỡ/cover. Tối đa 20 ảnh product và 50 ảnh collection; alt plain text
1–255 sau strip, caption 0–500, sort_order integer 0..2147483647 cho trùng,
đọc theo (sort_order, asset_id). Variant_color null hoặc đúng variant cùng product.
Cover nullable; khác null phải thuộc images request, sai trả 400 cover_asset_id.
Duplicate asset trả 400 images[i].asset_id.

Asset mới gắn phải uploader/đúng target/APPROVED; retained asset cho OPS khác
giữ hoặc sửa alt/sort. Upload thiếu hoặc khác owner trả 404. Gỡ chỉ cập nhật DB,
không xóa S3; reattach trong retention 7 ngày chỉ uploader/target cũ.
DELETING/DELETED trả 400 images[i].asset_id. GC mỗi giờ/batch 100 dùng single-runner
lease platform; quarantine sweep mỗi 60 giây/batch 100 dùng row claim
FOR UPDATE SKIP LOCKED, cleanup token/lease và CAS, không expire PROCESSING còn
lease. Raw terminal dọn ngay; chưa complete dọn sau deadline, lifecycle quarantine
2 ngày làm lưới an toàn. Partial keys của terminal non-approved chưa asset/
reference/lease được GC sau 7 ngày từ terminal, suy ra upload_id, không list bucket.

| Methods / path | Quyền | Đầu ra / phase |
|---|---|---|
| GET /users; PUT /users/{id}/roles; POST /users/{id}/lock | SUPER_ADMIN (`user.manage`) | Danh sách, phân quyền/khóa audit / 1. Contract: [`user.yaml`](../../contracts/openapi/user.yaml) (`TASK:USR-02` phần 2b); đổi role và khóa cần `reason` + `expected_version` (= `version` của user), khóa cần `Idempotency-Key`; admin không tự đổi/khóa chính mình (403) |
| GET,POST /catalog/products; GET,PUT /catalog/products/{id} | OPS | CRUD/version / 1 |
| POST /catalog/products/{id}/publish; POST /catalog/products/{id}/unpublish | OPS | State validation / 1 |
| POST /catalog/products/{id}/variants; PUT /catalog/variants/{id} | OPS | SKU immutable, price/weight/version / 1 |
| GET,POST /catalog/categories; PUT /catalog/categories/{id} | OPS | Hierarchy/sort/status / 1 |
| GET,POST /catalog/brands; PUT /catalog/brands/{id} | OPS | Brand/status / 1 |
| GET,POST /catalog/collections; GET,PUT /catalog/collections/{id} | OPS (`catalog.write`) | CAT-01b: POST key → DRAFT/version 0; PUT chỉ expected_version, thay toàn bộ items; GET gồm items thứ tự (sort_order, product_id) + version, kể cả DRAFT/INACTIVE; cover_url luôn null, không nhận media/lookbook / 1 |
| GET /catalog/size-guides/{category_id}/{locale} | OPS (`catalog.write`) | CAT-01b: nội dung + version; chưa có 404 NOT_FOUND, locale ngoài vi/en 400 / 1 |
| PUT /catalog/size-guides/{category_id}/{locale} | OPS (`catalog.write`) | CAT-01b: key + expected_version; 0 tạo → 201/version 1, cập nhật → 200/version +1; replay trước version guard / 1 |
| POST /catalog/images/uploads | OPS (`catalog.write`) | CAT-03 planned: key bắt buộc, URL PUT sống 300 giây; complete deadline = PUT expiry +24h; replay sau hạn PUT trả 409 UPLOAD_URL_EXPIRED, client không còn upload_id thì tạo intent mới với key mới / 1 |
| GET,PUT /catalog/products/{id}/images; GET,PUT /catalog/collections/{id}/images | OPS (`catalog.write`) | CAT-03 planned: full replacement/version resource, không key; ownership chỉ mới-gắn, snapshot cùng version / 1 |
| GET /catalog/images/{asset_id}?kind=image hoặc thumb | OPS (`catalog.write`) | CAT-03 planned: admin preview approved, không raw / 1 |
| POST /catalog/images/uploads/{upload_id}/complete | OPS (`catalog.write`) | CAT-03 design: complete idempotent theo upload_id, owner actor+target, validate/re-encode; chưa implementation / 1 |
| GET /catalog/images/uploads/{upload_id} | OPS (`catalog.write`) | CAT-03 design: upload status để phục hồi complete timeout; owner strict, chưa implementation / 1 |
| GET /orders; GET /orders/{order_no} | OPS, FINANCE (PII tối thiểu) | Filter ngày/status/search, page / 1 |
| POST /orders/{order_no}/confirm-cod | OPS | CONFIRMED → durable commit, PACKING khi xong / 1 |
| POST /orders/{order_no}/pack | OPS | PAID → PACKING và create shipment step / 1 |
| POST /orders/{order_no}/cancel | OPS | Reason, key, version → cancellation / 1 |
| POST /orders/{order_no}/returns | OPS | Evidence, items qty/condition, key → kiểm đếm/return work / 1 |
| GET /inventory/skus; GET /inventory/skus/{sku}/transactions | OPS | Stock và ledger / 1 |
| POST /inventory/adjust; POST /inventory/import | OPS | Key, reason; CSV <= 1.000 dòng / 1 |
| GET /payments; GET /payments/{id} | FINANCE | Transaction/refund/discrepancy / 1 |
| POST /payments/{id}/refunds | FINANCE | amount,reason,operation_key,evidence → refund resource / 1 |
| GET /payments/{id}/refunds/{refund_id} | FINANCE | Trạng thái UNKNOWN/SUCCESS/FAILED / 1 |
| POST /payments/{id}/cod-settlements | FINANCE | settlement_ref,gross,fees,net,evidence / 1 |
| GET /payments/reconciliation; POST /payments/reconciliation/{id}/resolve | FINANCE | Giải trình/evidence, không force success không xác minh / 1 |
| GET /shipping/shipments; GET /shipping/shipments/{id} | OPS | Tracking/UNKNOWN/handed_over_at / 1 |
| POST /shipping/shipments/{id}/self-events | OPS | Event/status/evidence/thu COD, không trực tiếp sửa order / 1 |
| GET,POST /shipping/rules; PUT /shipping/rules/{id} | OPS | Fee rule/version/active / 1 |
| GET /catalog/reviews; POST /catalog/reviews/{id}/approve; POST /catalog/reviews/{id}/reject | OPS | Moderation reason / 2 |
| GET,POST /promotions/vouchers; PUT /promotions/vouchers/{id} | MARKETING | Voucher/version / 2 |
| GET,POST /promotions/campaigns; PUT /promotions/campaigns/{id} | MARKETING | Campaign/items/overlap guard / 2 |
| GET,POST /notifications/templates; PUT /notifications/templates/{id}; POST /notifications/templates/preview | MARKETING | Template/version/render / 2 |
| POST /notifications/campaigns | MARKETING | Recipient selection có consent, template/version, idempotent job / 2 |
| GET /reports/orders; GET /reports/cash; GET /reports/stock | OPS / FINANCE / OPS tương ứng | Domain-owned aggregate, ngày/zone, bounded CSV format / 2 |

Report orders do order sở hữu, cash do payment, stock do inventory; dashboard gọi từng API, không join DB. GET order và returns cho FINANCE phải redacted theo nhu cầu. Admin không có route create shipment tách order; retry shipment phải đi recovery của order.

## 4. API internal và caller allowlist

| Methods / path | Caller → owner | Input bắt buộc / kết quả |
|---|---|---|
| GET /carts/{id}/snapshot | order → cart | Verified actor context + expected version → items/versions |
| POST /carts/{id}/checkout-cleanup | order → cart | order_id,actor,items {item_id,version,quantity},key → removed/retained |
| POST /catalog/variants/quote | order,cart → catalog | variant_ids → active/base/override/version/weight/snapshot |
| GET /orders/{order_id}/snapshot | payment,shipping → order | Scoped fields; immutable snapshot/version/hash |
| GET /orders/{order_id}/saga-status | promotion,shipping,payment → order | Intent, readiness,deadline/version; không PII dư |
| POST /inventory/reserve | order → inventory | order_id,order_no,items,expires_at,request_hash,key → reservation |
| POST /inventory/commit | order → inventory | order_id,key → COMMITTED hoặc conflict đã hết hạn |
| POST /inventory/release | order → inventory | order_id,original_reservation {order_no,items,expires_at},original_request_hash,reason,key → RELEASED/EXPIRED/COMMITTED |
| GET /inventory/reservations/{order_id} | order → inventory | Status/items/deadline,404 chưa có |
| GET /inventory/skus/{sku} | catalog,cart → inventory | available,version,observed_at |
| POST /inventory/returns | order → inventory | reservation/order,items,operation_key,evidence_ref → returned quantities |
| POST /promotions/quote | order,cart,catalog → promotion | Server actor/verified flag,items,prices,fee → effective prices/discount/version |
| POST /promotions/reservations/lock | order → promotion | order snapshot/hash,deadline,key → locks/quota |
| POST /promotions/reservations/commit | order → promotion | order_id,key → COMMITTED |
| POST /promotions/reservations/release | order → promotion | order_id,original_snapshot,original_hash,reason,key → RELEASED |
| GET /promotions/reservations/{order_id} | order → promotion | Aggregate/child states |
| POST /payments | order → payment | Authenticated order snapshot/hash,expires_at,key → payment state/URL |
| GET /payments/by-order/{order_id} | order → payment | Verified amount/method/status/closed_at |
| POST /payments/{order_id}/close | order → payment | Original snapshot/hash,key → closed intent/tombstone |
| POST /payments/{payment_id}/refunds | order → payment | operation_key,amount,reason,order context → refund |
| GET /payments/{payment_id}/refunds/{refund_id} | order → payment | Result/cumulative refunded amount |
| POST /shipping/fee | order,cart → shipping | Address + catalog weight/items/method → quote_id,fee,cod_fee,expiry |
| POST /shipping/shipments | order → shipping | Snapshot/hash,ready proof/version,key → shipment state |
| GET /shipping/shipments/by-order/{order_id} | order → shipping | Status,handed_over_at,tracking |
| POST /shipping/shipments/{order_id}/cancel | order → shipping | Original snapshot/hash,key → cancellation/tombstone |
| POST /users/recipients/resolve | notification,inventory → user | Scoped IDs/purpose → minimal recipient/locale/verified |
| GET /users/notification-secrets/{challenge_id} | notification → user | Purpose/expiry/caller check → secret tạm, no-store. Thực thi: [`user-internal.yaml`](../../contracts/openapi/user-internal.yaml) (`TASK:USR-01` phần 1b-i); service token ADR-19 gửi qua `Authorization: Bearer` |
| GET /users/{user_id}/auth-state | gateway, các service có admin action → user | auth_version/status/permissions cho sensitive authorization |

Internal stock return chỉ order; OPS dùng admin order returns để giữ evidence và orchestration. Query pure POST không cần idempotency key; mọi command còn lại cần key + stable business reference. Hash canonical tại service đích phải khớp original payload; không chỉ so chuỗi caller gửi.

Snapshot-based payment/shipment create từ order được xác thực và hạn quyền. Cancel/close có đủ snapshot để tạo tombstone trước create; missing snapshot trả 400, không giả báo đã chặn.

## 5. Kafka contracts

### 5.1 Envelope và xử lý

`TASK:PLT-02` đã bắt đầu schema envelope tại `contracts/events/event-envelope.schema.json`
và fixtures/tests; xem [contract README](../../contracts/README.md). Schema kiểm UUID,
required fields, UTC timestamp và integer versions/sequence. Payload §5.3 của USER_CREATED,
VARIANT_CREATED, CATALOG_CHANGED, INVENTORY_UPDATED/RESTOCKED và ORDER_CREATED/CONFIRMED/
PAID/CANCELLED có schema thực thi qua `contracts/events/registry.json`, kèm topic/partition
key (§5.2) và field làm aggregate_id; test đối chiếu bảng §5.2 nên sửa topic/key phải
sửa cả hai nơi. Các event còn lại thêm trong task producer. Quy ước §1.2/1.3 nằm ở `contracts/openapi/common.yaml`. Chưa có
producer Kafka đang chạy; producer/consumer review pending.

Envelope: event_id UUID, event_type, version (schema integer), occurred_at UTC, aggregate_id text, aggregate_version integer, aggregate_sequence integer, correlation_id, payload. event_id cố định qua retry. aggregate_sequence tăng trong mỗi aggregate cho từng event, giải quyết nhiều event ở cùng business version; unique ở outbox theo aggregate/sequence. Partition key như bảng dưới, consumer không giả định tổng thứ tự giữa topics.

Aggregate IDs: order events là order UUID; payment events là payment UUID; shipment events là shipment UUID; stock snapshot là SKU; reservation lifecycle là reservation UUID. Các payment/shipping event vẫn partition theo order_no để gom giao dịch đơn. Luôn gửi order_id UUID và order_no trong payload liên quan đơn.

Relay giữ thứ tự chưa gửi theo aggregate_sequence. Consumer dedupe trong TX, chỉ bỏ event cũ đối với projection state; event tài chính/ledger phải xử lý theo business ID, không bỏ refund mới vì version nhỏ hơn một event khác. Schema change breaking phải có compatibility/migration plan, không đổi field âm thầm.

### 5.2 Topics và consumer được phép tạo effect

| Topic / key | Producer | Types | Consumers và effect |
|---|---|---|---|
| user.events / user_id | user | USER_CREATED | Chưa có consumer nghiệp vụ bắt buộc; analytics sau |
| catalog.events / product_id | catalog | VARIANT_CREATED, CATALOG_CHANGED | inventory register SKU; catalog invalidation worker |
| order.events / order_no | order | ORDER_CREATED, ORDER_CONFIRMED, ORDER_PAID, ORDER_CANCELLED, ORDER_COMPLETED | catalog sold projection/eligibility, inventory stop restock tại COMPLETED; không create payment/stock/shipment |
| inventory.events / sku cho snapshot; order_no cho reservation | inventory | INVENTORY_UPDATED, INVENTORY_RESTOCKED, INVENTORY_RESERVED, INVENTORY_RELEASED, INVENTORY_DEDUCTED | catalog stock projection; order reconcile reservation; inventory sở hữu restock fan-out |
| payment.events / order_no | payment | PAYMENT_CREATED, PAYMENT_COMPLETED, PAYMENT_FAILED, PAYMENT_REFUNDED | order saga/tài chính COD; không inventory tự deduct |
| promotion.events / variant_id | promotion | PRICE_CHANGED | catalog invalidation |
| shipping.events / order_no | shipping | SHIPMENT_CREATED, SHIPMENT_STATUS_UPDATED, COD_COLLECTED, COD_REMITTED | order giao hàng; payment COD evidence |
| notification.events / dedupe_key | Domain owners | NOTIFY_* (schema thực thi: NOTIFY_RESET_PASSWORD do user, `TASK:USR-01` phần 1b) | Chỉ notification gửi |
| notification.results / dedupe_key | notification | NOTIFICATION_SENT, NOTIFICATION_FAILED | user dọn secret, inventory subscription result |

NOTIFY_ORDER_CONFIRMED do order phát khi online ready hoặc COD tiếp nhận; NOTIFY_PAYMENT_SUCCESS/REFUND_RESULT do payment; NOTIFY_SHIPMENT_STATUS do shipping; NOTIFY_RESTOCK_ALERT/LOW_STOCK do inventory; NOTIFY_RESET_PASSWORD/EMAIL_VERIFY/OTP do user. Một business notification chỉ có một producer/dedupe key.

### 5.3 Payload tối thiểu

| Event family | Required payload / invariant |
|---|---|
| USER_CREATED | user_id,locale; không password/token |
| VARIANT_CREATED | product_id,variant_id,sku,version; duplicate không reset stock |
| CATALOG_CHANGED | product_id,affected_variant_ids,version; invalidate detail/list |
| ORDER_CREATED/CONFIRMED/PAID/CANCELLED | order_id,order_no,user_id nullable,status,order_version,items {variant_id,sku,quantity},total_amount,currency,method; CANCELLED thêm reason |
| ORDER_COMPLETED | order identifiers,user_id nullable,items,delivered_at; sold_count có cho guest, eligibility chỉ member |
| INVENTORY_UPDATED/RESTOCKED | sku,on_hand,reserved,available,stock_version,observed_at; 0→dương để restock |
| INVENTORY_RESERVED/RELEASED/DEDUCTED | order identifiers,reservation_id,status,expires_at,items,reason; version theo reservation |
| PAYMENT_CREATED/COMPLETED/FAILED | order identifiers,payment_id,method,amount,currency,status,paid_at nullable,provider_txn_id nullable,reason nullable |
| PAYMENT_REFUNDED | order identifiers,payment_id,refund_id,operation_key,amount,refunded_total,currency,status; full/partial không suy từ một amount riêng |
| PRICE_CHANGED | variant_id,campaign_item_id nullable,sale_price nullable,start_at,end_at,effective_at,price_version; end xóa campaign price |
| SHIPMENT_CREATED/STATUS_UPDATED | order identifiers,shipment_id,carrier,tracking_code nullable,status,handed_over_at nullable,provider_occurred_at,shipment_version |
| COD_COLLECTED/COD_REMITTED | order identifiers,payment reference nếu biết,shipment_id,event_ref,gross_amount,fee_amount,net_amount,currency,evidence_ref,occurred_at; chưa tự coi REMITTED là tiền thực về |
| NOTIFY_* | recipient,channel,locale,template_key,dedupe_key,user_id nullable,data tối thiểu,expires_at nếu có; OTP/reset chỉ secret reference |
| NOTIFICATION_SENT/FAILED | notification_id,dedupe_key,purpose,subscription_id/generation hoặc challenge_id khi liên quan,provider_message_id nullable,outcome; không secret |

Mẫu naming, required/nullable, enum và ví dụ đầy đủ từng event phải thành JSON Schema trong task của producer, consumer ký review trước integration. Broker retention khởi điểm 7 ngày; processed_events 90 ngày là dự kiến kỹ thuật, phải lớn hơn cửa sổ replay được phép. Replay cũ hơn cần kế hoạch business-key reconciliation ở 13.

## 6. Provider callbacks

Không áp response envelope của shop lên ACK provider. Chi tiết và nguồn chính thức ở [15 Integrations](../engineering/15_integrations.md).

| Endpoint public | Baseline adapter | Hành vi |
|---|---|---|
| GET /payments/vnpay/ipn | VNPay PAY 2.1.0 GET profile | Verify, TX, JSON RspCode/Message theo spec |
| GET /payments/vnpay/return | Browser return | Redirect allowlist tới UI, UI đọc trạng thái server |
| POST /payments/momo/notify | MoMo notification JSON | Verify, TX, HTTP 204 ACK theo spec |
| GET /payments/momo/return | Browser return | Chỉ điều hướng, không xác nhận thu tiền |
| POST /shipping/webhook/{carrier} | GHN/GHTK/VTPL theo account/spec | Adapter auth/event/status mapping, commit trước ACK |

Method/header/carrier mapping thật phải được xác minh trong sandbox. Nếu merchant dùng VNPay profile POST khác, cập nhật contract và fixture trước bật; không triển khai cùng lúc hai giả định ACK/signature. Callback query/body nhạy cảm được che khỏi access log.

## 7. Điều kiện contract-ready

Mỗi endpoint/event khi nhận task phải có: operationId/schema ID, request/response examples hợp lệ, required/nullable/range/enum, permission/ownership, lỗi nghiệp vụ, idempotency/version behavior, producer/consumer và test duplicate/out-of-order nếu liên quan. 03 là danh mục B1; chưa tuyên bố là OpenAPI hoàn chỉnh đã validate. [`catalog.yaml`](../../contracts/openapi/catalog.yaml) gồm public `GET /api/v1/catalog/products` của S1 (200/400), 14 operation admin CAT-01a và 6 operation CAT-01b (collection list/detail/POST/PUT, size guide GET/PUT) đã kiểm HTTP Testcontainers. Bearer/key/version/envelope theo §3, kiểm bằng `scripts/validate-contracts.sh`; contract PASS không thay runtime evidence. Smoke kiểm response live CAT-01a/ProductSummary và CAT-01b collection/guide qua Gateway bằng OPS synthetic ở Task 6; chưa có validator tự sinh từ OpenAPI. Tên đường dẫn thay đổi phải sửa FE/mock/tests cùng PR.
