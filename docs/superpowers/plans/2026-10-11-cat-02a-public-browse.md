# CAT-02a Public Browse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Public catalog read API (list/search/detail/categories/brands/collections/size guides) và internal variant quote, đọc thẳng PostgreSQL.

**Architecture:** Repository `JdbcClient` sinh SQL động từ mệnh đề hằng, aggregate giá/variant lúc truy vấn; một mảnh SQL "product public" dùng chung cho list, collection và quote. Cursor keyset base64url gắn hash bộ lọc. Quote nội bộ xác thực bằng `ServiceTokenVerifier` như user-service.

**Tech Stack:** Java 21, Spring Boot 4.0 MVC, `JdbcClient`, Flyway, PostgreSQL 17 (`unaccent`), Testcontainers, platform-security.

**Spec:** [CAT-02a design](../specs/2026-10-11-cat-02a-public-browse-design.md) (đã duyệt 2026-10-11).

**Trạng thái local:** Task1–6 đã commit và review; Task7 verification ngày 2026-10-11
có contract43/Redocly, scripts14, smoke và sáu mutation PASS, full reactor453 PASS.
Tài liệu hoàn tất ngày 2026-10-13 với docs58, diff checks và staged secret scan;
Task7/task review và whole-branch review do controller chốt. Parent CAT-02 **In progress**,
02b pending; chưa push/PR/merge/remote CI/staging hoặc nghiệm thu parent.
[Evidence](../../evidence/cat-02a-local-2026-10-11.md) và [handoff](2026-10-11-cat-02a-handoff.md).

## Global Constraints

- `TASK:CAT-02` phần 02a · `REQ:CAT-04`, hỗ trợ `REQ:CAT-03/05`. Làm trên `dev`; commit từng task, chỉ push/mở PR khi chủ dự án yêu cầu.
- Không thêm Redis, Kafka consumer, `CATALOG_CHANGED`, index trigram, rate limit public, dependency mới.
- Migration append-only: chỉ thêm `V007`; không sửa V001–V006.
- Product public = `p.status='ACTIVE'` ∧ category ACTIVE ∧ (`p.brand_id` null ∨ brand ACTIVE) ∧ có variant ACTIVE. Ảnh chỉ asset `availability='AVAILABLE'`.
- Giá đại diện = `min(coalesce(v.price_override, p.base_price))` trên variant ACTIVE; `max_price` tương tự. Money là `long`.
- `limit` mặc định 20, 1–100. `q` trim 1–100; `size`/`color`/`tag` 1–64; `min_price`/`max_price` ≥ 0 và `min_price <= max_price`.
- Sort: `NEWEST` (mặc định) `coalesce(published_at, created_at)` desc, id desc; `BEST_SELLING` `sold_quantity` desc, id desc; `PRICE_ASC` min_price asc, id asc; `PRICE_DESC` min_price desc, id desc.
- Lỗi tham số → `400 VALIDATION_ERROR` có field; không public/không tồn tại → `404 NOT_FOUND`. Mọi giá trị đi qua bind parameter.
- Internal quote: audience `catalog-service`, callers `{cart-service, order-service}`, config `fashion.catalog.internal-callers` = `${CATALOG_INTERNAL_CALLERS:}`; `401 INVALID_SERVICE_TOKEN`, `403 CALLER_NOT_ALLOWED`, `Cache-Control: no-store`; 1–100 UUID không trùng.
- Technical ruling của controller: canonical filter hash escape backslash rồi pipe trong từng field trước join fixed-order; giữ 16-hex SHA-256/wire shape, tránh delimiter collision (không thêm quyết định nghiệp vụ).
- Lệnh Java cần `JAVA_HOME` JDK 21 (xem `docs/engineering/12_engineering_guide.md` §1) và Docker cho Testcontainers.

## Review Focus

- `q` chỉ toàn khoảng trắng hoặc chứa `%`/`_`/`\` phải bị trim/escape, không thành "match all" → test trong Task 3.
- Nhiều product cùng `min_price` và cùng `published_at` phải phân trang không trùng/không sót → Task 3.
- Cursor của sort A dùng cho sort B, hoặc cursor đúng nhưng đổi `size` → 400 field `cursor` → Task 2 (unit) + Task 3.
- Product ACTIVE có variant nhưng mọi variant INACTIVE, hoặc brand bị inactivate sau publish → ẩn ở list, detail, collection; quote trả `active:false` → Task 3, 4, 5, 6.
- Collection đúng biên `end_at = now` theo đồng hồ DB → dùng `now()` của PostgreSQL, test bằng thời điểm DB (`select now()`), không bằng đồng hồ JVM → Task 5.

---

### Task 1: V007 unaccent và hàm tìm kiếm

**Files:**
- Create: `services/catalog-service/src/main/resources/db/migration/V007__public_search.sql`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/product/PublicSearchSchemaIntegrationTest.java`

**Interfaces:**
- Produces: SQL function `f_unaccent(text) returns text` IMMUTABLE, gọi được bởi `catalog_runtime`.

- [x] **Step 1: Write the failing test** (extends `vn.fashion.catalog.admin.CatalogAdminTestSupport`, dùng `jdbc` = runtime role)

```java
@Test void runtimeCanUseUnaccentFunction() {
  assertThat(jdbc.queryForObject("select f_unaccent(lower('Áo Sơ Mi Đỏ'))", String.class)).isEqualTo("ao so mi do");
}
@Test void functionIsImmutableForFutureIndexes() {
  assertThat(jdbc.queryForObject("select provolatile from pg_proc where proname='f_unaccent'", String.class)).isEqualTo("i");
}
```

- [x] **Step 2: Run** `./mvnw -B -pl services/catalog-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PublicSearchSchemaIntegrationTest test` — Expected: FAIL (function does not exist).

- [x] **Step 3: Write V007**

```sql
-- TASK:CAT-02a: accent-insensitive public search (spec §3); index deferred until measured load.
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE FUNCTION f_unaccent(text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;
```

`đ` không thuộc rule mặc định của mọi bản unaccent; nếu assert `do` fail vì `đ`, bọc thêm `translate(..., 'đĐ', 'dD')` trong hàm và giữ test.

- [x] **Step 4: Run** cùng lệnh — Expected: PASS. Chạy thêm `-Dtest=CatalogSchemaIntegrationTest,MediaSchemaIntegrationTest` — PASS (migrate từ đầu vẫn chạy).

- [x] **Step 5: Commit** `git add` hai file; `git commit -m "feat(CAT-02): add accent-insensitive search function V007"`

### Task 2: Bộ lọc và cursor

**Files:**
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/product/BrowseFilters.java`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/product/ProductCursor.java`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/product/BrowseFiltersTest.java`

**Interfaces:**
- Produces:
  - `enum BrowseFilters.Sort { NEWEST, BEST_SELLING, PRICE_ASC, PRICE_DESC }`
  - `record BrowseFilters(String q, UUID categoryId, UUID brandId, String size, String color, String tag, Long minPrice, Long maxPrice, Sort sort)`
  - `static BrowseFilters parse(Map<String,String> params, boolean requireQ)` — gom mọi lỗi rồi ném một `Api.Problem(400, "VALIDATION_ERROR", "INVALID_FILTER", errors)`; field name là tên query param (`q`, `category_id`, `brand_id`, `size`, `color`, `tag`, `min_price`, `max_price`, `sort`). UUID/long nhận dạng String và parse tại đây (tránh `MethodArgumentTypeMismatchException`).
  - `String hash()` — 16 hex đầu SHA-256 của chuỗi `sort|q(lowercase)|category|brand|size(lowercase)|color(lowercase)|tag|min|max` (null → rỗng).
  - `String likePattern()` — `'%' + escape(f_unaccent-ready lowercase q) + '%'`, escape `\`→`\\`, `%`→`\%`, `_`→`\_`; null khi không có q.
  - `record ProductCursor(String sort, String value, UUID id, String filters)`; `String encode()` base64url không padding của JSON `{"s","v","id","f"}`; `static ProductCursor decode(String raw, BrowseFilters f)` ném `Api.Problem(400, "VALIDATION_ERROR", "INVALID_CURSOR", [FieldError("cursor", ...)])` khi base64/JSON hỏng, thiếu field, `s != f.sort().name()` hoặc `f != f.hash()`. Kiểm `value` trước bind SQL: giá/sold parse `long` không âm; `NEWEST`/`COLLECTIONS` parse Instant và năm UTC 1–9999; `COLLECTION:<slug>` giữ `sort_order` 0–2_147_483_647. Ngoài miền trả cùng lỗi cursor (chủ dự án xác nhận 2026-10-11).

- [x] **Step 1: Write the failing tests**

```java
@Test void defaultsAndTrim() {
  var f = BrowseFilters.parse(Map.of("q", "  Áo  "), false);
  assertThat(f.q()).isEqualTo("Áo"); assertThat(f.sort()).isEqualTo(Sort.NEWEST);
}
@Test void reportsEveryInvalidField() {
  var e = assertThrows(Api.Problem.class, () -> BrowseFilters.parse(Map.of("category_id", "x", "min_price", "-1",
      "max_price", "abc", "sort", "CHEAP", "size", "x".repeat(65)), false));
  assertThat(e.errors()).extracting(Api.FieldError::field).containsExactlyInAnyOrder("category_id", "min_price", "max_price", "sort", "size");
}
@Test void minAboveMaxAndBlankQ() {
  assertThat(fields(Map.of("min_price", "10", "max_price", "5"), false)).containsExactly("max_price");
  assertThat(fields(Map.of("q", "   "), true)).containsExactly("q");
  assertThat(fields(Map.of(), true)).containsExactly("q");
  assertThat(fields(Map.of("q", "a".repeat(101)), false)).containsExactly("q");
}
@Test void likePatternEscapesWildcards() {
  assertThat(BrowseFilters.parse(Map.of("q", "50%_off\\"), false).likePattern()).isEqualTo("%50\\%\\_off\\\\%");
}
@Test void cursorRoundTripsAndIsBoundToFiltersAndSort() {
  var f = BrowseFilters.parse(Map.of("size", "M"), false);
  var c = new ProductCursor("NEWEST", "2026-10-11T00:00:00Z", UUID.randomUUID(), f.hash());
  assertThat(ProductCursor.decode(c.encode(), f)).isEqualTo(c);
  assertThat(cursorError(c.encode(), BrowseFilters.parse(Map.of("size", "L"), false))).isEqualTo("cursor");
  assertThat(cursorError(c.encode(), BrowseFilters.parse(Map.of("size", "M", "sort", "PRICE_ASC"), false))).isEqualTo("cursor");
  assertThat(cursorError("%%%", f)).isEqualTo("cursor");
  assertThat(cursorError(Base64.getUrlEncoder().encodeToString("{\"s\":\"NEWEST\"}".getBytes()), f)).isEqualTo("cursor");
}
@Test void hashIgnoresCaseOfTextFilters() {
  assertThat(BrowseFilters.parse(Map.of("q", "ÁO", "color", "Black"), false).hash())
      .isEqualTo(BrowseFilters.parse(Map.of("q", "áo", "color", "black"), false).hash());
}
```

- [x] **Step 2: Run** `-Dtest=BrowseFiltersTest` — Expected: FAIL (classes missing).
- [x] **Step 3: Implement** the two records per the Interfaces block; JSON qua `ObjectMapper` tĩnh `new ObjectMapper()` (Jackson đã có trên classpath).
- [x] **Step 4: Run** — Expected: PASS.
- [x] **Step 5: Commit** `feat(CAT-02): browse filters and filter-bound keyset cursor`

### Task 3: List và search sản phẩm public

**Files:**
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/product/PublicProductSql.java`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/product/ProductBrowseRepository.java`
- Modify: `services/catalog-service/src/main/java/vn/fashion/catalog/product/ProductSummary.java`, `ProductQueryController.java`
- Delete: `services/catalog-service/src/main/java/vn/fashion/catalog/product/ProductQueryRepository.java`
- Modify: `services/catalog-service/src/main/java/vn/fashion/catalog/web/ApiExceptionHandler.java` (basePackages thêm `vn.fashion.catalog.product`, `vn.fashion.catalog.collection`, `vn.fashion.catalog.internal`)
- Create: `services/catalog-service/src/test/java/vn/fashion/catalog/product/PublicCatalogFixtures.java`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/product/ProductBrowseIntegrationTest.java`
- Modify tests: `ProductQueryIntegrationTest.java` (mỗi product ACTIVE seed thêm một variant ACTIVE), `ProductQueryControllerErrorResponseTest.java` (constructor mới)

**Interfaces:**
- Consumes: Task 2 `BrowseFilters`, `ProductCursor`; Task 1 `f_unaccent`.
- Produces:
  - `PublicProductSql.FROM_PUBLIC` — mảnh `from products p join categories c on c.id=p.category_id left join brands b on b.id=p.brand_id cross join lateral (select min(coalesce(v.price_override,p.base_price)) min_price, max(coalesce(v.price_override,p.base_price)) max_price from product_variants v where v.product_id=p.id and v.status='ACTIVE') pr where p.status='ACTIVE' and c.status='ACTIVE' and (p.brand_id is null or b.status='ACTIVE') and pr.min_price is not null`.
  - `PublicProductSql.CARD_COLUMNS` — `p.id, p.slug, p.name_vi, p.name_en, pr.min_price, pr.max_price` + ảnh card (subquery `product_images` join `media_assets` `availability='AVAILABLE'` order by `sort_order` limit 1: `asset_id, alt_vi, alt_en`).
  - `static ProductSummary PublicProductSql.card(ResultSet rs)`.
  - `record ProductSummary(UUID id, String slug, String nameVi, String nameEn, long minPrice, long maxPrice, CardImage image)`; `record CardImage(UUID assetId, String altVi, String altEn)` (nested). JSON snake_case qua cấu hình Jackson hiện có; giữ `@JsonProperty` cũ.
  - `ProductPage ProductBrowseRepository.browse(BrowseFilters f, ProductCursor after, int limit)` — `next_cursor` khác null khi lấy được `limit + 1` dòng.
  - `ProductQueryController`: `GET /api/v1/catalog/products` và `GET /api/v1/catalog/search`, tham số `@RequestParam(defaultValue="20") int limit` (giữ handler `INVALID_LIMIT` cũ) + `@RequestParam Map<String,String> params`; `search` gọi `parse(params, true)`.
  - Test fixture `PublicCatalogFixtures(JdbcTemplate jdbc)`: `UUID category(UUID parent, String slug, String status)`, `UUID brand(String name, String status)`, `UUID product(String slug, String nameVi, UUID category, UUID brand, String status, long basePrice, Instant publishedAt, long sold, String... tags)`, `UUID variant(UUID product, String sku, String size, String color, Long override, String status)`, `UUID image(UUID product, int sort, String availability)`, `UUID collection(String slug, String status, Instant start, Instant end)`, `void item(UUID collection, UUID product, int sort)`, `UUID lookbook(UUID collection, int sort)`, `void sizeGuide(UUID category, String locale)`. Insert SQL thẳng (dùng `postgres` superuser connection như `clearCatalog` nếu runtime role thiếu quyền insert media).

Mệnh đề SQL động (chỉ ghép hằng, bind mọi giá trị):
- `q`: `(f_unaccent(lower(p.name_vi)) ilike f_unaccent(:like) escape '\' or f_unaccent(lower(p.name_en)) ilike f_unaccent(:like) escape '\' or p.slug ilike :like escape '\')`
- `category_id`: `(p.category_id = :cat or c.parent_id = :cat)`; `brand_id`: `p.brand_id = :brand`; `tag`: `p.tags @> array[:tag]::text[]`
- `size`/`color`: một `exists (select 1 from product_variants v2 where v2.product_id=p.id and v2.status='ACTIVE' [and lower(v2.size)=lower(:size)] [and lower(v2.color)=lower(:color)])`
- giá: `pr.min_price >= :min`, `pr.min_price <= :max`
- keyset theo bảng sort ở Global Constraints, so sánh tuple `(key, p.id) < (:v, :id)` (desc) hoặc `>` (asc); `order by key, p.id` cùng chiều; `limit :limit + 1`.
- Ghi chú trong code: `// ponytail: query-time aggregation; denormalize min_price/sizes onto products when measured load needs it (spec §1).`

- [x] **Step 1: Write failing integration tests** (`ProductBrowseIntegrationTest extends CatalogAdminTestSupport`, dữ liệu qua `PublicCatalogFixtures`, gọi `send("GET", path, null, null, null)`):

```java
@Test void hidesNonPublicProducts()        // DRAFT; ACTIVE không variant; mọi variant INACTIVE; category INACTIVE; brand INACTIVE → chỉ product hợp lệ; product brand null vẫn hiện
@Test void cardCarriesMinMaxPriceAndFirstAvailableImage() // base 200000, override 150000 và variant INACTIVE override 1000 → min 150000, max 200000; ảnh sort 1 AVAILABLE, sort 0 DELETING → asset sort 1
@Test void sizeAndColorMustMatchTheSameVariant() // A: (M,Black),(L,White); size=M&color=White → không có A; size=m&color=black → có A
@Test void categoryParentIncludesChildren()
@Test void brandTagAndPriceRangeFilterOnMinPrice() // min_price=150000 khớp product min 150000; max_price=149999 loại
@Test void searchIgnoresAccentsAndEscapesWildcards() // "Áo sơ mi" khớp q="ao so mi"; q="%" không khớp tất cả; /search không q → 400 field q
@Test void everySortPagesWithoutDuplicatesOrGaps()  // 7 product cùng min_price, cùng published_at; mỗi sort duyệt limit=2 tới hết → đủ 7 id, không trùng, đúng thứ tự
@Test void cursorFromOtherFiltersOrSortIsRejected() // 400 errors[0].field = "cursor"
@Test void invalidParametersReturnFieldErrors()    // category_id=x, sort=CHEAP → 400 với hai field; limit=101 vẫn message INVALID_LIMIT field limit
@Test void legacyActiveWithoutPublishedAtSortsByCreatedAt()
```

- [x] **Step 2: Run** `-Dtest=ProductBrowseIntegrationTest` — Expected: FAIL.
- [x] **Step 3: Implement** `PublicProductSql`, `ProductBrowseRepository`, controller, advice basePackages; xóa `ProductQueryRepository`; sửa `ProductQueryIntegrationTest` seed variant ACTIVE cho product ACTIVE (quy tắc mới của spec §3), sửa constructor trong `ProductQueryControllerErrorResponseTest`.
- [x] **Step 4: Run** `-Dtest='ProductBrowseIntegrationTest,ProductQueryIntegrationTest,ProductQueryControllerErrorResponseTest,BrowseFiltersTest'` — Expected: PASS.
- [x] **Step 5: Commit** `feat(CAT-02): public product list and accent-insensitive search`

### Task 4: Detail sản phẩm, categories, brands, size guide public

**Files:**
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/product/PublicCatalogController.java`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/product/PublicCatalogRepository.java`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/product/PublicCatalogReadIntegrationTest.java`

**Interfaces:**
- Consumes: `PublicProductSql.FROM_PUBLIC`, `PublicCatalogFixtures`.
- Produces (records lồng trong `PublicCatalogRepository`):
  - `ProductDetail(UUID id, String slug, String nameVi, String nameEn, String descriptionVi, String descriptionEn, List<String> tags, Ref category, Ref brand, long minPrice, long maxPrice, List<VariantView> variants, List<ImageView> images, SizeGuideView sizeGuide, Boolean available, Instant observedAt)`; `Ref(UUID id, String name)` (category name theo locale); `VariantView(UUID id, String sku, String size, String color, long effectivePrice)`; `ImageView(UUID assetId, String altVi, String altEn, String variantColor, int sortOrder)`; `SizeGuideView(UUID categoryId, String locale, String guidelineHtml, JsonNode table)`.
  - `Optional<ProductDetail> product(UUID id, String locale)` trong `@Transactional(readOnly = true)`; `available`/`observedAt` luôn null (spec §2).
  - `List<CategoryNode> categories()` — `CategoryNode(UUID id, String slug, String nameVi, String nameEn, List<CategoryNode> children)`, chỉ ACTIVE, con của cha INACTIVE bị ẩn.
  - `List<BrandView> brands()` — `BrandView(UUID id, String name)`, ACTIVE, order by name.
  - `Optional<SizeGuideView> sizeGuide(UUID categoryId, String locale)` — category phải ACTIVE.
  - Controller: `GET /api/v1/catalog/products/{id}`, `/categories`, `/brands`, `/size-guides/{categoryId}?locale=`. Locale: `locale` param (size guide) hoặc `Accept-Language` bắt đầu bằng `en` → `en`, còn lại `vi`; `locale` khác vi/en → 400 field `locale`. Id không phải UUID → 404 (path không public). Không có → `Api.Problem(404, "NOT_FOUND", "PRODUCT_NOT_FOUND"|"SIZE_GUIDE_NOT_FOUND", [])`.

- [x] **Step 1: Write failing tests**

```java
@Test void detailShowsOnlyActiveVariantsAndAvailableImages() // variant INACTIVE ẩn; ảnh DELETING ẩn; available và observed_at là null
@Test void detailUsesLocaleForCategoryNameAndSizeGuide()     // Accept-Language: en → category.name "Shirt", size_guide.locale "en"; mặc định vi
@Test void nonPublicProductIs404()                            // DRAFT, không variant ACTIVE, brand INACTIVE, id lạ, id "abc" → 404
@Test void categoriesTreeHidesInactiveBranches()
@Test void brandsListOnlyActive()
@Test void sizeGuidePublicRequiresActiveCategoryAndValidLocale() // category INACTIVE → 404; locale=fr → 400 field locale
```

- [x] **Step 2: Run** `-Dtest=PublicCatalogReadIntegrationTest` — FAIL.
- [x] **Step 3: Implement** per Interfaces.
- [x] **Step 4: Run** — PASS.
- [x] **Step 5: Commit** `feat(CAT-02): public product detail, taxonomy and size guide reads`

### Task 5: Collection public

**Files:**
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/collection/PublicCollectionController.java`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/collection/PublicCollectionRepository.java`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/collection/CollectionPublicIntegrationTest.java`

**Interfaces:**
- Consumes: `PublicProductSql.FROM_PUBLIC`, `CARD_COLUMNS`, `card(rs)`; `ProductSummary`; `PublicCatalogFixtures`.
- Produces:
  - Mảnh SQL visibility collection: `co.status='ACTIVE' and (co.start_at is null or co.start_at <= now()) and (co.end_at is null or co.end_at > now()) and exists (select 1 from collection_items ci join products p on p.id=ci.product_id ... <FROM_PUBLIC điều kiện> where ci.collection_id=co.id)`.
  - `CollectionSummary(UUID id, String slug, String nameVi, String nameEn, UUID coverAssetId, Instant startAt, Instant endAt)`; `CollectionPage(List<CollectionSummary> items, String nextCursor)`.
  - `CollectionDetail(CollectionSummary collection, List<LookbookView> lookbook, List<ProductSummary> products, String nextCursor)`; `LookbookView(UUID assetId, String captionVi, String captionEn, int sortOrder)` (chỉ asset AVAILABLE; frontend dùng caption làm alternate text theo quyết định chủ dự án 2026-10-11, khớp V006). Cover không AVAILABLE trả `coverAssetId=null`.
  - Cursor collection list: keyset `(co.created_at, co.id)` desc; cursor products: `(ci.sort_order, ci.product_id)` asc. Dùng lại `ProductCursor` với `sort` = `"COLLECTIONS"` hoặc `"COLLECTION:" + slug`, `filters` = `""`.
  - Controller: `GET /api/v1/catalog/collections?limit&cursor`, `GET /api/v1/catalog/collections/{slug}?limit&cursor`; limit sai → 400 field `limit`; không public → 404 `COLLECTION_NOT_FOUND`.

- [x] **Step 1: Write failing tests** (thời điểm lấy từ `jdbc.queryForObject("select now()", Instant.class)`):

```java
@Test void emptyCollectionIsHidden()
@Test void collectionWithOnlyUnpublishedProductsIsHidden()
@Test void futureStartIsHidden()
@Test void startBoundaryShowsAndEndBoundaryHides()   // start_at = dbNow - 1s hiện; end_at = dbNow (đặt bằng now() trong SQL update ngay trước request) → ẩn
@Test void detailListsOnlyPublicProductsInSortOrder() // product INACTIVE bị lọc; thứ tự sort_order
@Test void unpublishingLastActiveProductHidesCollection() // update products set status='INACTIVE' → list và detail 404
@Test void lookbookShowsOnlyAvailableAssetsAndPaginatesProducts() // 3 product, limit=2 → next_cursor, trang 2 đủ 1
@Test void listPaginatesAndRejectsForeignCursor()    // cursor từ /products → 400 field cursor
```

- [x] **Step 2: Run** `-Dtest=CollectionPublicIntegrationTest` — FAIL.
- [x] **Step 3: Implement** per Interfaces.
- [x] **Step 4: Run** — PASS.
- [x] **Step 5: Commit** `feat(CAT-02): public collections with time-window and active-product visibility`

### Task 6: Internal variant quote

**Files:**
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/internal/VariantQuoteController.java`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/internal/VariantQuoteRepository.java`
- Modify: `services/catalog-service/src/main/resources/application.yaml` (`fashion.catalog.internal-callers: ${CATALOG_INTERNAL_CALLERS:}`)
- Modify: `infra/local/.env.example`, `infra/local/compose.yaml` (truyền `CATALOG_INTERNAL_CALLERS` rỗng; không sinh key)
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/internal/VariantQuoteIntegrationTest.java`
- Modify: `services/gateway/src/test/java/vn/fashion/gateway/security/GatewayBoundaryTest.java`

**Interfaces:**
- Consumes: `ServiceTokenVerifier(String serviceName, Map<String,ECPublicKey>, Clock)`, `AccessTokenVerifier.parseKeys(String)`, `ServiceTokenIssuer` (test); `PublicProductSql` điều kiện category/brand.
- Produces:
  - `POST /internal/api/v1/catalog/variants/quote`, body `QuoteRequest(List<String> variantIds)` → `List<QuoteItem>` theo thứ tự request.
  - `QuoteItem(UUID variantId, boolean found, Boolean active, String sku, UUID productId, String nameVi, String nameEn, String size, String color, Long basePrice, Long priceOverride, Long effectivePrice, Integer weightGrams, Long productVersion, ProductSummary.CardImage image)`; khi `found=false` các field khác null (Jackson `@JsonInclude(NON_NULL)` chỉ trên record này).
  - `active` = variant ACTIVE ∧ product ACTIVE ∧ category ACTIVE ∧ (brand null ∨ ACTIVE).
  - Lỗi: thiếu/sai token → 401 `UNAUTHORIZED`/`INVALID_SERVICE_TOKEN`; caller ngoài allowlist → 403 `FORBIDDEN`/`CALLER_NOT_ALLOWED`; `variant_ids` null/rỗng/>100/trùng/không UUID → 400 field `variant_ids`. Mọi response `Cache-Control: no-store`.

- [x] **Step 1: Write failing tests** (đăng ký `fashion.catalog.internal-callers` với key cart/order/stranger qua `@DynamicPropertySource`; token bằng `ServiceTokenIssuer(caller, pub, priv, Clock.systemUTC()).issue("catalog-service")`):

```java
@Test void missingOrInvalidTokenIs401()   // không header; token audience "user-service"; token key lạ
@Test void unknownCallerIs403()          // key "notification-service" có trong config nhưng không thuộc allowlist
@Test void cartAndOrderGetItemsInRequestOrder() // [v2, missing, v1] → thứ tự giữ nguyên, missing found=false
@Test void unsellableVariantIsFoundButInactive() // variant INACTIVE; product DRAFT; brand INACTIVE → active=false, giá vẫn có
@Test void effectivePriceUsesOverride()  // base 200000, override 150000 → effective 150000
@Test void invalidVariantIdsAre400()     // [], 101 phần tử, trùng, "x"
@Test void responsesAreNoStore()
```

Gateway: thêm `/internal/api/v1/catalog/variants/quote` (POST) vào test 404 hiện có cho `/internal/**`.

- [x] **Step 2: Run** `-Dtest=VariantQuoteIntegrationTest` (catalog) và `-pl services/gateway -Dtest=GatewayBoundaryTest` — catalog FAIL; gateway PASS ngay (route đã chặn) — ghi nhận là regression guard.
- [x] **Step 3: Implement** controller theo mẫu `services/user-service/src/main/java/vn/fashion/user/auth/NotificationSecretController.java`; một query `where v.id = any(:ids)` rồi sắp lại theo request.
- [x] **Step 4: Run** — PASS.
- [x] **Step 5: Commit** `feat(CAT-02): internal variant quote for cart and order callers`

### Task 7: Contract, smoke, mutation và docs

**Files:**
- Modify: `contracts/openapi/catalog.yaml` (mở rộng `listProducts`; thêm `searchProducts`, `getProduct`, `listCategories`, `listBrands`, `listCollections`, `getCollection`, `getSizeGuide`, `quoteVariants`; ghi "CAT-02a implemented" trong `description` từng operation và thêm một dòng TASK:CAT-02a vào `info.description`, theo quy ước file hiện có)
- Create: `tests/contracts/test_catalog_public.py`
- Modify: `scripts/smoke-local.sh`
- Modify docs: `docs/design/03_interfaces.md` (§2.2 bảng catalog, §4 quote), `docs/design/05_database_design.md` (V007), `docs/design/06_service_flows.md` (dòng "Danh sách/search/detail"), `docs/design/08_decisions.md` (4 quyết định spec §1), `docs/delivery/10_backlog.md` (CAT-02a), `services/catalog-service/README.md`, `docs/superpowers/plans/README.md`
- Create: `docs/evidence/cat-02a-local-2026-10-11.md`, `docs/superpowers/plans/2026-10-11-cat-02a-handoff.md`

- [x] **Step 1: Write failing contract test** `test_catalog_public.py`: mọi operation trên tồn tại; public ops không yêu cầu bearer; `quoteVariants` path bắt đầu `/internal/` và khai báo 401/403; `limit` max 100; `sort` enum đúng 4 giá trị; money fields `type: integer, minimum: 0`; examples validate theo schema (theo mẫu `test_catalog_admin.py`).
- [x] **Step 2: Run** `bash scripts/validate-contracts.sh` — FAIL ở test mới.
- [x] **Step 3: Update** `catalog.yaml`; chạy lại — PASS.
- [x] **Step 4: Smoke** — sau đoạn CAT-03 publish (trước unpublish) thêm: `GET /api/v1/catalog/products?size=<size của variant smoke>&limit=100` chứa `$product_id`; `GET /api/v1/catalog/products/$product_id` 200 có `variants`; `GET /api/v1/catalog/search?q=<tên không dấu>` chứa `$product_id`; echo `CAT-02a browse/detail/search PASS`. Chạy `bash scripts/local-up.sh && bash scripts/smoke-local.sh` — Expected: `S1-local smoke PASS`.
- [x] **Step 5: Mutation thủ công** — mỗi mutant chạy test liên quan, phải FAIL, rồi khôi phục: (1) bỏ `pr.min_price is not null` khỏi `FROM_PUBLIC`; (2) bỏ `p.id` khỏi tuple keyset; (3) bỏ kiểm `f` trong `ProductCursor.decode`; (4) bỏ kiểm allowlist caller; (5) đổi `co.end_at > now()` thành `>=`; (6) bỏ `escape` cho `%`. Ghi kết quả vào evidence.
- [x] **Step 6: Full verification** — `./mvnw -B test`, `bash scripts/validate-contracts.sh`, `python3 -B -m unittest discover -s scripts -p 'test_*.py' -v`, `python3 -B scripts/check_docs.py`, `bash scripts/scan-secrets.sh`, `git diff --check`. Ghi số test từng module vào evidence.
- [x] **Step 7: Docs** — cập nhật các file ở mục Files; backlog ghi CAT-02 **In progress**, 02a đã hiện thực local, 02b còn mở (cache/invalidation, `sold_quantity` từ `ORDER_COMPLETED`, tồn kho, HTTP cache header).
- [x] **Step 8: Commit** `docs(CAT-02): contract, smoke and docs for CAT-02a public browse`
