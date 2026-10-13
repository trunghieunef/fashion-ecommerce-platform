# CAT-02a — public catalog browse và internal variant quote

**Trạng thái: đã duyệt ngày 2026-10-11.** Khi lập plan sửa cho khớp schema: brand nullable, cover là
`cover_asset_id`. Plan: [CAT-02a](../plans/2026-10-11-cat-02a-public-browse.md). Đã hiện thực và kiểm local trên `dev` (V007, 8 public reads + internal quote); [evidence](../../evidence/cat-02a-local-2026-10-11.md) và [handoff](../plans/2026-10-11-cat-02a-handoff.md). CAT-02 In progress, 02b pending; chỉ push/mở PR khi chủ dự án yêu cầu.

`TASK:CAT-02` (phần 02a) · `REQ:CAT-04`, hỗ trợ `REQ:CAT-03/05`.
Dependency: CAT-01, CAT-03 (Done local 2026-10-11), SEC-01 phần service token (ADR-19).
Base là `dev/5288765`, schema catalog hiện tại là V006.
Nguồn chủ quản:
[03 Interfaces](../../design/03_interfaces.md) §2.2 và §4,
[04 Architecture](../../design/04_architecture.md) §4,
[05 Database](../../design/05_database_design.md),
[06 Flows](../../design/06_service_flows.md),
[OpenAPI](../../../contracts/openapi/catalog.yaml).

## 1. Phạm vi và cách tiếp cận đã chốt

Chủ dự án chọn chia CAT-02 làm hai phần:

- **02a (spec này):** toàn bộ API public đọc catalog và internal variant quote, đọc thẳng PostgreSQL.
- **02b (sau):** Redis cache-aside + invalidation durable (CAT-09), consumer `ORDER_COMPLETED`
  cập nhật `sold_quantity` chống replay, tồn kho hiển thị từ inventory. Parent CAT-02 chỉ Done khi xong 02b.

Không thêm trong 02a: Redis, Kafka consumer, `CATALOG_CHANGED`, rate limit public chung, index trigram,
storefront UI (WEB-01), promotion/giá campaign (Phase 2).

Ba cách truy vấn đã cân nhắc: tính lúc truy vấn, cột phi chuẩn hóa trên `products`, materialized view.
Chủ dự án chọn **tính lúc truy vấn** bằng `JdbcClient`: khớp code hiện có, không thêm đường đồng bộ
khi ghi variant, luôn đúng theo dữ liệu hiện tại. Nâng lên cột phi chuẩn hóa khi đo tải thấy chậm
(ghi `ponytail:` tại query).

Quyết định của chủ dự án trong phiên:

- Search bỏ qua dấu tiếng Việt bằng extension `unaccent` + ILIKE.
- Giá đại diện của product là giá thấp nhất `coalesce(price_override, base_price)` trên variant ACTIVE;
  dùng cho lọc khoảng giá, sort giá và card ("từ X đ", kèm max).
- Ẩn product có category hoặc brand không ACTIVE (nhất quán với điều kiện publish).

## 2. API public

Prefix `/api/v1/catalog`, không cần đăng nhập; Gateway đã route `/api/v1/catalog/**`.
Envelope và lỗi theo 03 §1.2. Locale từ `Accept-Language` vi/en, mặc định vi.

| API | Tham số | Trả về |
|---|---|---|
| `GET /products` | `q`, `category_id`, `brand_id`, `size`, `color`, `tag`, `min_price`, `max_price`, `sort`, `limit`, `cursor` | Card `id, slug, name_vi, name_en, min_price, max_price, image{asset_id, alt_vi, alt_en}?` và `next_cursor` |
| `GET /search` | Như trên, `q` bắt buộc | Như trên |
| `GET /products/{id}` | — | Product (slug, tên, `description_vi/en` đã sanitize khi ghi, tags, category, brand, min/max price), variants ACTIVE `id, sku, size, color, effective_price`, images `asset_id, alt_vi, alt_en, variant_color, sort_order`, `size_guide` theo locale hoặc null, `available: null`, `observed_at: null` |
| `GET /categories` | — | Cây category ACTIVE tối đa 2 cấp: `id, slug, name_vi, name_en, children[]` |
| `GET /brands` | — | Brand ACTIVE `id, name` |
| `GET /collections` | `limit`, `cursor` | Collection hiển thị: `id, slug, name_vi, name_en, cover_asset_id, start_at, end_at` (cover từ V005, null nếu chưa chọn) |
| `GET /collections/{slug}` | `limit`, `cursor` | Collection + `lookbook[]` (`asset_id, caption_vi, caption_en, sort_order`; chỉ asset AVAILABLE) + `products` (card như list, theo `sort_order`) + `next_cursor` |
| `GET /size-guides/{category_id}` | `locale` | `category_id, locale, guideline_html, table`; 404 nếu không có hoặc category không ACTIVE |

`GET /products` hiện tại (PLT-01 sample) chỉ được **thêm** field; field cũ giữ nguyên.

Lookbook không có `alt_vi`/`alt_en` theo schema V006; frontend dùng caption theo locale làm alternate text
(chủ dự án xác nhận ngày 2026-10-11). Cover cũng chỉ trả `cover_asset_id` khi asset AVAILABLE, nếu không là null.

Tham số và giới hạn:

- `limit` mặc định 20, tối đa 100. `sort` ∈ `NEWEST` (mặc định), `BEST_SELLING`, `PRICE_ASC`, `PRICE_DESC`.
- `q` trim, dài 1–100 ký tự (khi có). `size`, `color`, `tag` dài 1–64. `min_price`, `max_price` là số nguyên
  `0..9_223_372_036_854_775_807`, `min_price <= max_price`. `category_id`, `brand_id` là UUID.
- Vi phạm → `400 VALIDATION_ERROR` kèm field. Product/collection không public hoặc không tồn tại → `404 NOT_FOUND`
  (không phân biệt hai trường hợp).

## 3. Quy tắc hiển thị và truy vấn

Một product **public** khi tất cả đúng tại thời điểm đọc: `status = 'ACTIVE'`; category ACTIVE; brand null hoặc ACTIVE (`brand_id` nullable);
có ít nhất một variant ACTIVE. Ảnh chỉ lấy asset `availability = 'AVAILABLE'`; ảnh card là ảnh `sort_order`
nhỏ nhất.

Collection **public** khi: `status = 'ACTIVE'`, `start_at` null hoặc `<= now()`, `end_at` null hoặc
`> now()`, và có ít nhất một product public trong `collection_items`. Danh sách product của collection chỉ gồm
product public (03 §2.2, quyết định 2026-10-07).

Truy vấn list trong một repository mới `ProductBrowseRepository`, một câu SQL động gồm các mệnh đề cố định:

- `min_price`/`max_price`: aggregate trên variant ACTIVE (lateral subquery).
- `size`/`color`: `EXISTS` variant ACTIVE khớp không phân biệt hoa thường; có cả hai thì cùng một variant
  phải khớp cả hai.
- `category_id`: category đó hoặc category con trực tiếp. `brand_id`: bằng. `tag`: `tags @> ARRAY[:tag]`.
- Khoảng giá: so với `min_price`.
- `q`: bind raw query đã trim; PostgreSQL `f_unaccent(lower(:q))` trước, rồi escape backslash,
  `%`, `_` và thêm `%` hai đầu. Cùng pattern literal so với `f_unaccent(lower(x))` cho
  `name_vi`, `name_en`, `slug` bằng ILIKE/ESCAPE. Unaccent có thể tạo metacharacter từ
  ký tự fullwidth, nên escape phải sau normalization (technical correction F1 ngày 2026-10-13).
  Miền q, hash/cursor wire shape giữ nguyên.

Mọi giá trị đi qua bind parameter; SQL động chỉ ghép các mệnh đề hằng.

Migration **V007** (append-only): `CREATE EXTENSION IF NOT EXISTS unaccent` và hàm
`f_unaccent(text)` IMMUTABLE bọc `unaccent('unaccent', $1)`. Plan phải kiểm role Flyway có quyền tạo
extension trên PostgreSQL local/Testcontainers (unaccent là trusted extension từ PostgreSQL 13).

Sort và keyset:

| Sort | Khóa (giá trị, tie-break) |
|---|---|
| `NEWEST` | `coalesce(published_at, created_at)` desc, `id` desc |
| `BEST_SELLING` | `sold_quantity` desc, `id` desc (bằng 0 tới khi có 02b/ORD) |
| `PRICE_ASC` | `min_price` asc, `id` asc |
| `PRICE_DESC` | `min_price` desc, `id` desc |

Trang sau lọc bằng so sánh tuple `(value, id)` theo chiều sort; lấy `limit + 1` dòng để biết còn trang.
Collection list: `(created_at, id)` desc. Product trong collection: `(sort_order, product_id)` asc.

Cursor: base64url (không padding) của JSON `{"s": sort, "v": value, "id": uuid, "f": filters}`.
`f` là 16 ký tự hex đầu của SHA-256 bộ lọc chuẩn hóa (`q` trim + lowercase, các tham số theo thứ tự cố định).
Technical ruling của controller: escape backslash rồi pipe trong từng giá trị trước khi nối bằng pipe,
để hai bộ filter có delimiter không trùng hash; giữ thứ tự field và wire 16 hex, không đổi miền request.
Giải mã lỗi, sai kiểu, `s` hoặc `f` không khớp request → `400` field `cursor`. Cursor không ký HMAC vì chỉ
dẫn tới dữ liệu public đã lọc.
Giá trị cursor được kiểm trước khi bind SQL: timestamp `NEWEST`/`COLLECTIONS` phải là Instant có năm UTC
1–9999; giá/sold là số nguyên `long` không âm; `COLLECTION:<slug>` có `sort_order` 0–2_147_483_647.
Ngoài các miền này → `400 VALIDATION_ERROR`, message `INVALID_CURSOR`, field `cursor`
(chủ dự án xác nhận ngày 2026-10-11; cùng miền timestamp với CAT-01b).

Detail product đọc product, variants, images, size guide trong một transaction read-only
`REPEATABLE_READ`; collection detail cũng đọc visibility/cover, lookbook và products trong
cùng snapshot. Đây là technical ruling F2 ngày 2026-10-13 để response nội bộ nhất quán
khi writer commit giữa reads; không giữ giá giữa requests và không thêm row lock.

## 4. Internal variant quote

`POST /internal/api/v1/catalog/variants/quote` — Gateway không route `/internal/**`.

- Service token ADR-19, `ServiceTokenVerifier` audience `catalog-service`, key từ
  `fashion.catalog.internal-callers` (`CATALOG_INTERNAL_CALLERS`, định dạng `kid:base64-X.509`, kid là tên caller),
  mặc định rỗng. Cách làm giống `NotificationSecretController` của user-service.
- Thiếu/sai/hết hạn token → `401 INVALID_SERVICE_TOKEN`; caller ngoài `{cart-service, order-service}` →
  `403 CALLER_NOT_ALLOWED`. Response `Cache-Control: no-store`.
- Body `{"variant_ids": [uuid]}`: 1–100 phần tử, không trùng, đúng UUID; sai → `400`.
- Response giữ đúng thứ tự request. Mỗi phần tử: `variant_id, found`; khi `found`: `active, sku, product_id,
  name_vi, name_en, size, color, base_price, price_override, effective_price, weight_grams, product_version,
  image{asset_id, alt_vi, alt_en}?`. `active` theo cùng quy tắc public ở §3 và variant ACTIVE.
- Một query cho toàn bộ id. Đây là snapshot, không giữ giá; kiểm lại khi chốt đơn thuộc ORD-01.

Chưa sinh key cart/order thật và không thêm vào `local-up.sh` vì các service đó chưa tồn tại.

## 5. Kiểm thử

Integration dùng Testcontainers PostgreSQL thật; viết RED trước.

- `ProductBrowseIntegrationTest`: từng filter; size + color cùng variant; category cha gồm con; search không dấu
  và escape `%`/`_`; bốn sort; duyệt hết trang với nhiều product cùng giá không trùng/không sót; cursor đổi filter
  hoặc hỏng → 400; ẩn DRAFT, không variant ACTIVE, category/brand inactive; validation tham số.
- `CollectionPublicIntegrationTest`: collection rỗng; chỉ product chưa publish; `start_at` tương lai; đúng biên
  `start_at` (hiện) và `end_at` (ẩn); lọc product INACTIVE; unpublish product ACTIVE cuối sau khi kích hoạt làm
  collection biến mất; lookbook; phân trang.
- Detail, categories, brands, size guide public: 404 và hai locale.
- `VariantQuoteIntegrationTest`: 401 không token; 403 caller lạ; 200 cart/order; thứ tự; `found: false`;
  `active: false`; validation.
- Gateway: `/internal/api/v1/catalog/variants/quote` → 404.
- Mutation thủ công phải làm test FAIL: bỏ điều kiện ACTIVE/visibility, bỏ `id` khỏi keyset, bỏ kiểm `f` cursor,
  bỏ kiểm caller allowlist.

Lệnh: `./mvnw -B test`, `bash scripts/validate-contracts.sh`, `bash scripts/local-up.sh` +
`bash scripts/smoke-local.sh`, `python3 -B scripts/check_docs.py`, diff check.

## 6. Contract, smoke và tài liệu

- `contracts/openapi/catalog.yaml`: mở rộng `listProducts`, thêm các operation public ở §2 và quote nội bộ;
  contract test trong `tests/contracts/`.
- `scripts/smoke-local.sh`: list có filter → detail product CAT-03 vừa publish → search không dấu.
- Docs cùng thay đổi: 03 (§2.2, §4), 05 (V007), 06 (luồng browse), 08 (bốn quyết định ở §1), 10 (CAT-02a),
  catalog README, evidence CAT-02a, handoff.

## 7. Acceptance CAT-02a và phần còn lại

CAT-02a đạt khi các API ở §2/§4 hoạt động theo §3/§4, test ở §5 pass trên PostgreSQL thật, mutation bị bắt,
full reactor, contract và smoke pass, docs ở §6 đồng bộ.

Còn cho 02b (parent CAT-02 chưa Done): cache Redis + invalidation durable; `sold_quantity` từ
`ORDER_COMPLETED` chống replay; `available`/`observed_at` từ inventory; HTTP cache header cho public read.
