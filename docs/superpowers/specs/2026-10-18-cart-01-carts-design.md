# CART-01 — giỏ hàng guest/member (cart-service)

`TASK:CART-01` · `REQ:CART-01/02/04` · dependency USR-01, CAT-02, INV-01 (đều Done local).
Trạng thái: **bản nháp chờ chủ dự án duyệt**, 2026-10-18. Chủ dự án giao agent tự chốt mặc định;
lựa chọn của agent ghi ở §9. Merge giỏ (CART-02), snapshot/cleanup cho order (CART-03) và UI (WEB-02)
nằm ngoài phần này.

## 1. Mục tiêu

- Khách chưa đăng nhập và thành viên có giỏ hàng lưu trong PostgreSQL; quay lại trong 30 ngày vẫn thấy
  giỏ (guest qua cookie, member qua tài khoản).
- Thêm/sửa/xóa item an toàn khi bấm lại hoặc mở hai thiết bị: retry không cộng hai lần, ghi đè cũ bị 409.
- Biết `cart_id` không đủ để xem hay sửa giỏ của người khác (404).
- Giỏ hiển thị giá và tồn **tham khảo** (preview có nhãn); không giữ hàng, không chốt giá.

Không làm: merge guest→member, snapshot/cleanup cho order, Redis cache, rate limit riêng cho cart,
job dọn giỏ guest hết hạn, event Kafka, UI.

Anti-goals: giỏ mất khi Redis/catalog/inventory lỗi; giá trong giỏ được coi là giá chốt; lộ sự tồn tại
giỏ người khác; gọi HTTP khi đang giữ transaction/row lock; cộng quantity hai lần khi client retry.

## 2. Service và hạ tầng

- Service mới `services/cart-service` (Java 21, Spring Boot 4.0, JdbcClient, Flyway), port `8084`,
  DB riêng `cart` (role `cart_migration`, `cart_runtime`) trên PostgreSQL local, init idempotent như
  inventory. Dùng platform-durability (`IdempotencyStore`) và platform-security
  (`AccessTokenVerifier`, service token client).
- Gateway route public `/api/v1/carts/**` → cart-service; không có route admin/internal cho cart.
  CSRF origin của Gateway đã áp cho mutation có cookie.
- Trace/log/metric như các service khác (PLT-05: OTLP khi đặt endpoint).

## 3. Danh tính và cookie

- **Member**: `Authorization: Bearer` access token (ADR-21), verify tại cart-service bằng
  `AccessTokenVerifier`; actor = `user:<user_id>`.
- **Guest**: cookie `cart_token` = 32 byte ngẫu nhiên base64url; DB chỉ lưu SHA-256 hex
  (`guest_token_hash`). Cookie `HttpOnly; Secure; SameSite=Lax; Path=/api/v1/carts; Max-Age=2592000`.
  Actor = `guest:<hash>`. Token không bao giờ xuất hiện trong log, response body hay metric.
- Có Bearer hợp lệ thì dùng member, bỏ qua cookie (gộp giỏ là CART-02). Bearer sai/hết hạn → 401,
  không fallback sang cookie.
- Giỏ guest có `expires_at = now() + 30 ngày`, gia hạn mỗi lần đọc/ghi thành công; quá hạn coi như
  không tồn tại (404; `POST /carts` cấp giỏ mới và cookie mới). Giỏ member không hết hạn.

## 4. Schema (V001)

Theo 05 §6, chỉ phần CART-01:

- `carts(id uuid PK, user_id uuid?, guest_token_hash char(64)?, status varchar(32) DEFAULT 'ACTIVE',
  version bigint DEFAULT 0, expires_at timestamptz?, merged_into uuid? FK carts, created_at, updated_at)`;
  CHECK đúng một trong `user_id`/`guest_token_hash`; status ∈ ACTIVE/MERGED/CHECKED_OUT/ABANDONED;
  UNIQUE `user_id` WHERE status='ACTIVE' AND user_id IS NOT NULL; UNIQUE `guest_token_hash`; index `expires_at`.
- `cart_items(id uuid PK, cart_id FK, variant_id uuid, sku varchar(64), quantity int CHECK 1..99,
  price_snapshot bigint ≥ 0, catalog_version bigint, version bigint DEFAULT 0, created_at, updated_at)`;
  UNIQUE `(cart_id, variant_id)`.
- `idempotency_requests` (bản sao DDL platform-durability). Runtime không DELETE/TRUNCATE `carts`.
- Tối đa **100** item khác nhau mỗi giỏ (bằng giới hạn batch của catalog quote).

## 5. API (`/api/v1/carts`, contract mới `contracts/openapi/cart.yaml`)

| Method | Path | Hành vi |
|---|---|---|
| POST | `/carts` | Member: trả giỏ ACTIVE (tạo nếu chưa có) 200/201. Guest: cookie hợp lệ → giỏ đó 200; không có/hết hạn → tạo giỏ + Set-Cookie 201 |
| GET | `/carts/mine` | Chỉ member; 401 nếu không Bearer; 404 nếu chưa có giỏ |
| GET | `/carts/{cart_id}` | Owner; items + preview (§6) |
| POST | `/carts/{cart_id}/items` | `variant_id`, `quantity` 1–99, `expected_version`; `Idempotency-Key` bắt buộc. Variant đã có → cộng quantity (tổng > 99 → 400 `QUANTITY_LIMIT`) |
| PUT | `/carts/{cart_id}/items/{item_id}` | `quantity` tuyệt đối 1–99, `expected_version` |
| DELETE | `/carts/{cart_id}/items/{item_id}` | `expected_version` qua query |
| DELETE | `/carts/{cart_id}` | `expected_version` qua query; xóa mọi item, giỏ vẫn ACTIVE |

- Ownership: giỏ phải ACTIVE và thuộc actor (user_id khớp, hoặc hash cookie khớp và chưa hết hạn);
  sai, không tồn tại, UUID sai định dạng → **404 `CART_NOT_FOUND`** giống nhau. Item không thuộc giỏ → 404.
- Version: mỗi mutation thành công tăng `carts.version` đúng 1 và trả giỏ mới. Thiếu
  `expected_version` → 400 `VERSION_REQUIRED`; lệch → 409 `VERSION_CONFLICT` kèm `current_version`.
- Idempotency (03 §1.3): scope `actor + operation + key`, hash body đã chuẩn hóa; cùng key + body → trả
  response đã lưu; khác body → 409 `IDEMPOTENCY_KEY_REUSED`. PUT/DELETE idempotent theo version guard.
- Response giỏ: `cart_id`, `version`, `owner` (`GUEST`/`MEMBER`), `expires_at` (guest), `items[]`
  (`item_id`, `variant_id`, `sku`, `quantity`, `price_snapshot`, `item_version`, preview §6), `preview_status`.
  Mọi response cart `Cache-Control: no-store`.

## 6. Catalog, tồn và preview

- **Thêm/sửa item** (trước transaction): gọi catalog internal quote (service token, caller
  `cart-service`) — variant `found && active` mới được thêm; không → 409 `VARIANT_NOT_AVAILABLE`.
  Gọi inventory `GET /internal/api/v1/inventory/skus/{sku}` — `available < quantity sau thay đổi` →
  409 `OUT_OF_STOCK` kèm `available` (tham khảo, không giữ hàng). Catalog/inventory lỗi/timeout →
  503 `DEPENDENCY_UNAVAILABLE`, không ghi gì. Sau đó một transaction ngắn: lock giỏ, kiểm version,
  ghi item với `price_snapshot`/`catalog_version` từ quote. Giảm quantity hoặc xóa item không cần kiểm tồn.
- **Đọc giỏ**: một quote batch cho mọi variant + tồn theo SKU (song song, timeout 1 s mỗi dependency).
  Mỗi item có `preview`: `effective_price`, `price_changed` (so với snapshot), `active`, `available`,
  `observed_at`. Dependency lỗi → item giữ snapshot, `preview: null`, `preview_status: "UNAVAILABLE"`
  (vẫn 200, giỏ không mất). Preview không ghi DB.
- Giá chốt chỉ ở order quote (06 §5); cart không tính tổng tiền chính thức. Không giữ hàng.

## 7. Service token local

- local-up sinh cặp khóa ES256 cho caller `cart-service` (như USER/INVENTORY keys hiện có), cấp public
  key cho catalog (`CATALOG_INTERNAL_CALLERS`) và inventory (`INVENTORY_INTERNAL_CALLERS`); private key
  chỉ ở cart. Không commit khóa. Đây là lần đầu internal API được gọi bởi caller thật.

## 8. Kiểm thử và acceptance

Integration PostgreSQL thật (Testcontainers); catalog/inventory là HTTP stub trong test (WireMock hoặc
server JDK) với service token thật; smoke dùng service thật.

| Nhóm | Case |
|---|---|
| Guest | POST tạo giỏ + cookie đúng thuộc tính; quay lại với cookie → cùng giỏ; DB chỉ có hash; cookie sai/hết hạn → giỏ mới; quá 30 ngày → 404 |
| Member | POST tạo/trả giỏ ACTIVE duy nhất; `/carts/mine`; Bearer sai → 401 không fallback cookie |
| Ownership | cart_id đoán/người khác/sai định dạng → 404 giống nhau; item giỏ khác → 404 |
| Version | add/put/delete tăng version 1; stale → 409 + current_version; hai request song song cùng version → một 200, một 409 |
| Idempotency | add retry cùng key → không cộng lại; khác body cùng key → 409; thiếu key → 400 |
| Validation | qty 0/100, tổng > 99, item thứ 101, variant inactive/không tồn tại, hết tồn → mã lỗi §5/§6 |
| Dependency | catalog/inventory down khi add → 503 không ghi; khi đọc → 200 `preview_status: UNAVAILABLE` |
| Không lock khi gọi HTTP | test: stub chậm 2 s không giữ row lock (request khác đọc giỏ không bị chặn) |
| Contract | `cart.yaml` Redocly + examples; Gateway route + CSRF cho cookie mutation |

Smoke S1-local: guest tạo giỏ qua Gateway, thêm SKU smoke (tồn 5), đọc preview có `available`,
retry add cùng key không cộng; member tạo giỏ. Mutation các guard chính (ownership, version, idempotency,
hash cookie) phải bị test bắt. Full verify, contract, scan-secrets, CI.

## 9. Quyết định của agent (cần duyệt)

1. Service mới cart-service port 8084, DB `cart` riêng.
2. Cookie `cart_token` SameSite=**Lax** (khác refresh Strict) để link ngoài vẫn thấy giỏ; mutation đã có
   CSRF origin của Gateway.
3. Bearer ưu tiên hơn cookie; Bearer lỗi không fallback.
4. Không dùng Redis trong CART-01: PostgreSQL là nguồn sự thật, "Redis mất không mất giỏ" thỏa vì không
   phụ thuộc Redis; thêm cache khi đo tải cần.
5. `DELETE /carts/{id}` xóa item, giỏ vẫn ACTIVE (tránh đụng unique ACTIVE của member).
6. Tối đa 100 item/giỏ; cộng dồn variant trùng, tổng > 99 → 400.
7. Kiểm tồn tham khảo khi thêm/tăng; dependency lỗi khi ghi → 503, khi đọc → preview UNAVAILABLE.
8. Giỏ guest hết hạn 30 ngày trượt; chưa có job dọn (CART-03 / O06).
9. local-up sinh service key cho cart-service → catalog/inventory.
