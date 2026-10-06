# CAT-01a Catalog Admin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OPS quản lý category, brand, product, variant qua `/admin/api/v1/catalog/**`, publish có kiểm tra, SKU bất biến, mỗi variant mới sinh đúng một `VARIANT_CREATED` trong outbox.

**Architecture:** Thêm package `vn.fashion.catalog.web` (envelope/lỗi/auth) và `vn.fashion.catalog.admin` (controller + service JdbcClient) vào catalog-service theo khuôn user-service; migration `V002` thêm schema 05 §5 + bảng kỹ thuật; mutation chạy trong `TransactionTemplate` cùng idempotency/audit/outbox của platform-durability. Gateway thêm một route.

**Tech Stack:** Java 21, Spring Boot 4.0.8 webmvc + JdbcClient, Flyway, PostgreSQL 17.11 (Testcontainers), platform-durability, platform-security (`AccessTokenVerifier`), jsoup 1.23.2.

**Spec:** [`docs/superpowers/specs/2026-10-07-cat-01a-design.md`](../specs/2026-10-07-cat-01a-design.md). `TASK:CAT-01` phần 1a; `REQ:CAT-01`, `REQ:CAT-02` (SKU/variant/event).

## Global Constraints

- Chạy từ Git Bash, repo root: `export JAVA_HOME=/c/Users/<user>/.local/toolchains/jdk-21.0.12.1+1 PATH="$JAVA_HOME/bin:$PATH"`; Docker Desktop chạy.
- Không sửa `V001__catalog_baseline.sql`; mọi schema mới ở `V002__catalog_admin.sql`.
- JDBC (`JdbcClient`), không JPA; JSON snake_case; tiền `long`/`bigint` VND, không float/double.
- Mọi endpoint admin cần permission `catalog.write` trong claim `permissions`; không gọi user-service.
- Mọi POST cần header `Idempotency-Key` (1–128); mọi PUT và publish/unpublish cần `expected_version` (thiếu → 400 `VALIDATION_ERROR`, lệch → 409 `VERSION_CONFLICT`).
- Lỗi theo envelope 03 §1.2: `{code, message, errors[{field,message}], metadata{request_id,trace_id}}`, header `X-Correlation-Id`.
- Không có endpoint DELETE. Không dữ liệu thật/secret trong fixture; chuỗi giống secret cần `gitleaks:allow`.
- Mutation test: chạy `./mvnw` trong bash, log phải có `Tests run:` và không có `COMPILATION ERROR`.
- Commit trên `dev` sau mỗi task; không push/PR khi chưa được bảo.

## Review Focus

1. Hai request song song tạo variant cùng SKU (khác product) → một 201, một 409, đúng một dòng outbox — test ở Task 4.
2. Retry `POST .../variants` cùng key sau khi đã thành công → cùng body/status, không thêm outbox — Task 4.
3. Publish khi variant ACTIVE duy nhất vừa bị chuyển INACTIVE (dữ liệu đổi giữa lúc OPS xem và bấm) → 400, vẫn DRAFT — Task 5.
4. Body có field lạ như `sku` trong PUT variant hoặc `status` trong PUT product → bị bỏ qua, không đổi gì — Task 3 và 4.
5. Mô tả chứa `<a href="javascript:...">`, `<img onerror>`, `<script>` → bị loại, `<strong>` giữ — Task 3.

---

### Task 1: Migration V002 và bảng kỹ thuật

**Files:**
- Create: `services/catalog-service/src/main/resources/db/migration/V002__catalog_admin.sql`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/admin/CatalogSchemaIntegrationTest.java`

**Interfaces:**
- Produces: bảng `categories(id, parent_id, name_vi, name_en, slug, sort_order, status, version, created_at, updated_at)`, `brands(id, name, logo_url, status, version, created_at, updated_at)`, cột mới ở `products`, `product_variants(id, product_id, sku, size, color, price_override, weight_grams, status, version, created_at, updated_at)`, `outbox_events`, `idempotency_requests`, `audit_logs`. Tên constraint cố định: `categories_slug_key`, `products_slug_key` (đã có từ V001), `product_variants_sku_key`, `product_variants_product_size_color_key`. Category seed `uncategorized` id `00000000-0000-4000-8000-000000000001`.

- [ ] **Step 1: Viết test schema (đỏ)**

Dùng Testcontainers giống `ProductQueryIntegrationTest` (`postgres:17.11`, `catalog-test-init.sql`, runtime `catalog_runtime`). Các test:

```java
@Test void sampleRowFromV001SurvivesWithSeedCategory()   // insert trước V002 không làm được qua Flyway → dùng Flyway target: chạy migrate tới "1", insert product, rồi migrate; assert category_id = seed, base_price = 0, status giữ ACTIVE
@Test void skuSizeColorAndProductAreImmutable()          // UPDATE product_variants SET sku='X' → DataIntegrityViolation/SQLException message chứa "immutable"; tương tự size, color, product_id; UPDATE price_override thì OK
@Test void duplicateSkuViolatesNamedConstraint()          // message chứa "product_variants_sku_key"
@Test void duplicateSizeColorPerProductViolatesNamedConstraint() // "product_variants_product_size_color_key"
@Test void weightMustBePositiveAndPricesNonNegative()     // weight_grams=0, price_override=-1, base_price=-1 → lỗi CHECK
@Test void runtimeRoleCannotChangeAuditLogs()             // với catalog_runtime: insert audit OK; UPDATE/DELETE → "permission denied"
@Test void categoryCannotBeItsOwnParent()
```

Class này không dùng `@SpringBootTest`. `@BeforeAll`: `Flyway.configure().dataSource(url, "catalog_migration", "catalog_migration_test").target("1").load().migrate()`, insert một product ACTIVE bằng superuser, rồi migrate không target; các test sau chạy trên schema đầy đủ đó (JDBC `catalog_runtime` cho test quyền, superuser cho phần còn lại).

- [ ] **Step 2: Chạy và xác nhận đỏ**

Run: `./mvnw -B -pl services/catalog-service -Dtest=CatalogSchemaIntegrationTest test`
Expected: FAIL (bảng/cột chưa có).

- [ ] **Step 3: Viết `V002__catalog_admin.sql`**

Thứ tự: `categories` (+ CHECK status ACTIVE/INACTIVE, `parent_id <> id`), insert seed `uncategorized` (`Chưa phân loại`/`Uncategorized`); `brands`; `ALTER TABLE products ADD` các cột 05 (`category_id` nullable → `UPDATE ... SET category_id = seed` → `SET NOT NULL`; `base_price bigint NOT NULL DEFAULT 0 CHECK (base_price >= 0)`, rồi `DROP DEFAULT` để ứng dụng phải cấp); `product_variants` với constraint đặt tên như Interfaces; function + trigger `product_variants_immutable` `BEFORE UPDATE` raise `'variant identity is immutable'` khi `NEW.sku/size/color/product_id IS DISTINCT FROM OLD`; index `products(category_id, status, published_at, id)`; chép nguyên DDL `outbox_events`, `audit_logs`, `idempotency_requests` từ `services/user-service/src/main/resources/db/migration/V001__users.sql` và `V003__roles_permissions_audit.sql`, đổi `REVOKE ... FROM catalog_runtime`.

- [ ] **Step 4: Chạy lại, xanh; chạy cả module để chắc `ProductQueryIntegrationTest` vẫn PASS**

Run: `./mvnw -B -pl services/catalog-service test`
Expected: PASS toàn bộ (12 test cũ + test mới).

Lưu ý: `ProductQueryIntegrationTest` insert product không có `category_id`/`base_price` — sửa helper insert của test đó thêm hai cột (category seed, `0`), không đổi assertion.

- [ ] **Step 5: Mutation thật** — bỏ dòng `RAISE` trong trigger → `skuSizeColorAndProductAreImmutable` FAIL; khôi phục.

- [ ] **Step 6: Commit** `feat(CAT-01): add catalog admin schema V002`

---

### Task 2: Nền web/auth + category và brand

**Files:**
- Modify: `pom.xml` (property `jsoup.version` 1.23.2 + dependencyManagement), `services/catalog-service/pom.xml` (platform-durability, jsoup), `services/catalog-service/src/main/resources/application.yaml`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/web/{Api.java,ApiExceptionHandler.java,AdminAuth.java}`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/admin/{AdminCommands.java,AuditLog.java,CatalogBeans.java,TaxonomyController.java,TaxonomyService.java}`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/admin/{CatalogAdminTestSupport.java,TaxonomyIntegrationTest.java}`

**Interfaces:**
- Produces:
  - `web.Api` — chép `vn.fashion.user.web.Api` (đổi package); `Api.Problem(HttpStatus, String code, String message, List<FieldError>)`.
  - `web.ApiExceptionHandler` — như user-service (bỏ phần rate limit) + `DuplicateKeyException` → 409 `CONFLICT`, message `DUPLICATE`, field suy từ tên constraint: `*_slug_key`→`slug`, `product_variants_sku_key`→`sku`, `product_variants_product_size_color_key`→`size`; + `MissingRequestHeaderException` cho `Idempotency-Key` → 400 field `Idempotency-Key`. Phải scope `@RestControllerAdvice(basePackages = "vn.fashion.catalog.admin")` để không đổi lỗi của `ProductQueryController`.
  - `web.AdminAuth.requireCatalogWriter(String authorization): UUID` — verify bằng `AccessTokenVerifier(parseKeys(fashion.catalog.jwt.public-keys))`; không token/sai → 401 `UNAUTHORIZED` `INVALID_ACCESS_TOKEN`; thiếu `catalog.write` → 403 `FORBIDDEN` `PERMISSION_REQUIRED`. Không truy vấn DB.
  - `admin.AdminCommands`:
    - `record Result(UUID resourceId, int status, Object data)`
    - `Replay create(UUID actor, String operation, String key, Object canonicalRequest, Supplier<Result> work)` — trong một `TransactionTemplate`: key rỗng/>128 → 400; `request_hash` = SHA-256 hex của `objectMapper.writeValueAsString(canonicalRequest)`; `IdempotencyStore.begin(actorKey="user:"+actor, ...)`: `CONFLICT` → 409 `CONFLICT` `IDEMPOTENCY_KEY_REUSED`; `COMPLETED` → trả body đã lưu; `STARTED` → chạy `work`, `finish(...)`.
    - `record Replay(int status, JsonNode data)`; controller trả `Api.ok(HttpStatus.valueOf(status), data, meta)`.
    - `<T> T update(Supplier<T> work)` — chỉ bọc transaction.
    - `static void requireVersion(Long expected, long actual)` — null → 400 field `expected_version`; khác → 409 `VERSION_CONFLICT` `STALE_VERSION`.
  - `admin.AuditLog.record(UUID actor, String action, String resourceType, UUID resourceId, String reason, Object before, Object after)` — insert `audit_logs`, `request_id` = UUID mới, before/after serialize JSON.
  - `admin.CatalogBeans` — `@Bean IdempotencyStore`, `@Bean OutboxRepository` từ `JdbcClient`.
  - DTO JSON (snake_case toàn cục qua `spring.jackson.property-naming-strategy: SNAKE_CASE`):
    `Category(UUID id, UUID parentId, String nameVi, String nameEn, String slug, int sortOrder, String status, long version)`,
    `Brand(UUID id, String name, String logoUrl, String status, long version)`.
  - Routes (base `/admin/api/v1/catalog`): `GET /categories` (list phẳng, sort `parent_id nulls first, sort_order, slug`), `POST /categories` (201), `PUT /categories/{id}`, `GET /brands`, `POST /brands` (201), `PUT /brands/{id}`. Audit action `catalog.category.create|update`, `catalog.brand.create|update`.
  - Config: `fashion.catalog.jwt.public-keys: ${CATALOG_JWT_PUBLIC_KEYS}` (không default).
- Test support: `CatalogAdminTestSupport` (abstract, `@Testcontainers @SpringBootTest(RANDOM_PORT)`): container + properties như `ProductQueryIntegrationTest` + `fashion.catalog.jwt.public-keys = "test:" + base64(public)`; `String token(String... permissions)` ký ES256 (nimbus, `kid=test`, `iss=user-service`, `aud=fashion-api`, `exp=+900s`, `auth_version=0`); `HttpResponse<String> send(String method, String path, String token, String idempotencyKey, String jsonBody)`; `JsonNode json(HttpResponse<String>)`; `@BeforeEach` xóa dữ liệu catalog (giữ category seed, `audit_logs` dùng superuser connection).

- [ ] **Step 1: Viết `TaxonomyIntegrationTest` (đỏ)**

```java
@Test void missingOrInvalidTokenIs401()          // no header, "Bearer x", token ký key khác → 401 code UNAUTHORIZED
@Test void tokenWithoutCatalogWriteIs403()       // token("user.manage") → 403 FORBIDDEN
@Test void createCategoryReturns201AndAudits()   // POST {name_vi,name_en,slug:"ao",sort_order:1} → 201, data.version 0, status ACTIVE; audit_logs có 1 dòng catalog.category.create
@Test void sameKeySameBodyReplaysWithoutSecondRow()  // 2 lần cùng key → cùng data.id, count(categories where slug='ao') = 1
@Test void sameKeyDifferentBodyIs409()           // code CONFLICT, message IDEMPOTENCY_KEY_REUSED
@Test void missingIdempotencyKeyIs400()
@Test void duplicateSlugIs409WithField()         // errors[0].field = "slug"
@Test void invalidFieldsAre400()                 // slug "Áo Đẹp", name_vi "", name_en 256 ký tự → mỗi field có trong errors
@Test void categoryDepthIsAtMostTwo()            // tạo root A, con B(parent A), C(parent B) → 400 field parent_id; unknown parent → 400 parent_id
@Test void categoryWithChildrenCannotBecomeChild()   // PUT A với parent_id = D (root khác) khi A có con → 400 parent_id
@Test void updateNeedsCurrentVersion()           // PUT thiếu expected_version → 400; expected_version 5 → 409 VERSION_CONFLICT; đúng 0 → 200 version 1
@Test void concurrentUpdatesWithSameVersionOnlyOneWins() // 2 thread PUT expected_version 0 → statuses {200, 409}
@Test void brandCreateUpdateAndInactivate()      // POST brand → PUT status INACTIVE expected_version 0 → 200, version 1
```

- [ ] **Step 2: Chạy, xác nhận đỏ** — `./mvnw -B -pl services/catalog-service -Dtest=TaxonomyIntegrationTest test` → FAIL (404/compile).

- [ ] **Step 3: Implement theo Interfaces.** Validation gom thành list `FieldError` rồi ném một `Problem` 400 `VALIDATION_ERROR`. Slug `^[a-z0-9]+(-[a-z0-9]+)*$` ≤ 160; tên 1–255 sau `strip()`; brand `logo_url` null hoặc `https://`/`http://` ≤ 500; `status` ∈ {ACTIVE, INACTIVE}. PUT khóa dòng `FOR UPDATE` trước `requireVersion`, rồi `version = version + 1, updated_at = now()`. Kiểm độ sâu category trong transaction: parent phải tồn tại và có `parent_id IS NULL`; category có con không được nhận parent; `parent_id = id` → 400.

- [ ] **Step 4: Chạy module, xanh** — `./mvnw -B -pl services/catalog-service test` → PASS (kể cả test public cũ và `ProductQueryControllerErrorResponseTest`).

- [ ] **Step 5: Mutation thật** — bỏ `requireVersion` trong PUT category → `updateNeedsCurrentVersion` FAIL; đổi permission thành `user.manage` → `tokenWithoutCatalogWriteIs403` FAIL. Khôi phục.

- [ ] **Step 6: Commit** `feat(CAT-01): add catalog admin auth, categories and brands`

---

### Task 3: Product CRUD + sanitize HTML

**Files:**
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/admin/{ProductAdminController.java,ProductAdminService.java,HtmlSanitizer.java}`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/admin/{ProductAdminIntegrationTest.java,HtmlSanitizerTest.java}`

**Interfaces:**
- Consumes: Task 2 `AdminAuth`, `AdminCommands`, `AuditLog`, `Api`.
- Produces:
  - `HtmlSanitizer.clean(String html): String` — `Jsoup.clean(html, Safelist.basic().addTags("h2","h3").removeProtocols("a","href","ftp","mailto").addEnforcedAttribute("a","rel","nofollow noopener"))` (basic chỉ còn http/https); null → `""`.
  - DTO `Product(UUID id, UUID categoryId, UUID brandId, String nameVi, String nameEn, String slug, String descriptionVi, String descriptionEn, long basePrice, List<String> tags, String status, Instant publishedAt, long version, List<Variant> variants)`; `ProductPage(List<Product> items, int page, int size, long total)` (items không kèm variants → `variants` = `[]`).
  - `ProductAdminService.load(UUID id): Product` (gồm variants, sort `created_at, id`) — dùng lại ở Task 4/5; `ProductAdminService.lockForUpdate(UUID id): Product` (`select ... for update`, 404 `NOT_FOUND` `PRODUCT_NOT_FOUND`).
  - Routes: `GET /products?page&size&status` (page ≥ 1, size 1–100 mặc định 20, status optional ∈ DRAFT/ACTIVE/INACTIVE, sort `created_at desc, id desc`), `POST /products` (201, DRAFT), `GET /products/{id}`, `PUT /products/{id}`. Audit `catalog.product.create|update`.
  - Record `Variant(UUID id, UUID productId, String sku, String size, String color, Long priceOverride, int weightGrams, String status, long version)` trong package admin; `load` dùng ở task này, Task 4 dùng lại.

- [ ] **Step 1: Viết test (đỏ)**

```java
// HtmlSanitizerTest (unit)
@Test void stripsScriptsHandlersAndJavascriptLinks() // "<p onclick=x>a<script>b</script><img src=x onerror=y><a href=\"javascript:z\">c</a></p>" → không chứa script/onclick/onerror/javascript/img
@Test void keepsAllowedFormattingAndForcesRel()      // "<h2>T</h2><strong>s</strong><a href=\"https://e.test\">l</a>" → giữ h2/strong; a có rel="nofollow noopener"

// ProductAdminIntegrationTest
@Test void createProductIsDraftWithSanitizedDescription() // 201, status DRAFT, version 0, description_vi không chứa <script>
@Test void createRequiresExistingCategoryAndBrand()       // category_id ngẫu nhiên → 400 field category_id; brand_id ngẫu nhiên → 400 brand_id
@Test void createValidatesPriceTagsAndLengths()           // base_price -1, thiếu base_price, 21 tags, tag 51 ký tự, description > 20000 → 400 đủ field
@Test void basePriceAcceptsLargeIntegerWithoutPrecisionLoss() // 9007199254740993 lưu và đọc lại đúng
@Test void updateKeepsStatusAndBumpsVersion()             // PUT với "status":"ACTIVE" trong body → status vẫn DRAFT, version 1
@Test void staleUpdateIs409()
@Test void listPagesWithTotalAndStatusFilter()            // 3 product, size=2 → items 2, total 3; status=ACTIVE → total 0; size=101 → 400; page=0 → 400
@Test void unknownProductIs404()
```

- [ ] **Step 2: Chạy, đỏ** — `-Dtest='ProductAdminIntegrationTest,HtmlSanitizerTest'`.

- [ ] **Step 3: Implement.** Field PUT giống POST (category_id, brand_id, name_vi/en, slug, description_vi/en, base_price, tags) + `expected_version`; request record không có `status` nên field lạ bị Jackson bỏ qua (kiểm `spring.jackson.deserialization.fail-on-unknown-properties` mặc định false). FK category/brand kiểm bằng `select exists` trước insert để trả 400 field thay vì lỗi FK. Tags ghi bằng `java.sql.Array` (`connection.createArrayOf("text", ...)`) hoặc `cast(:tags as text[])` từ literal an toàn — chọn `createArrayOf` qua `JdbcClient.param(name, array)`. Description đo độ dài **sau** sanitize.

- [ ] **Step 4: Xanh module.** `./mvnw -B -pl services/catalog-service test`

- [ ] **Step 5: Mutation** — gỡ `HtmlSanitizer.clean` khỏi create → test sanitize FAIL; khôi phục.

- [ ] **Step 6: Commit** `feat(CAT-01): add admin product CRUD with HTML sanitizing`

---

### Task 4: Variant + outbox `VARIANT_CREATED`

**Files:**
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/admin/{VariantAdminController.java,VariantAdminService.java}`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/admin/VariantAdminIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 `ProductAdminService.lockForUpdate`, `Variant`; Task 2 `AdminCommands`, `AuditLog`; `OutboxRepository.append(OutboxEvent)`.
- Produces: `POST /products/{id}/variants` (201 → `Variant`), `PUT /variants/{id}` (body `price_override?`, `weight_grams`, `status`, `expected_version` → `Variant`). Audit `catalog.variant.create|update`.
- Outbox: `new OutboxEvent(UUID.randomUUID(), "product", productId.toString(), newProductVersion, "VARIANT_CREATED", 1, "catalog.events", productId.toString(), traceId, payload)`; payload `{"product_id","variant_id","sku","version":0}`. `correlationId` = trace id hiện tại (`Api.metadata(tracer).traceId()`).

- [ ] **Step 1: Viết test (đỏ)**

```java
@Test void createVariantWritesExactlyOneOutboxEvent() // 201; outbox_events where event_type='VARIANT_CREATED' = 1; topic catalog.events; partition_key = product_id; aggregate_sequence 1; payload khớp và hợp lệ theo contracts/events/catalog-events.schema.json#/$defs/VariantCreated (so required keys + additionalProperties bằng tay: đúng 4 key)
@Test void productVersionBumpsAndSequenceIncrements()  // 2 variant → product version 2, sequences {1,2}
@Test void retrySameKeyAddsNoVariantOrEvent()           // cùng key/body 2 lần → cùng variant id, count outbox = 1
@Test void duplicateSkuIs409AndLeavesNoEvent()          // sku trùng ở product khác → 409 field sku; outbox vẫn 1
@Test void duplicateSizeColorIs409()                    // field size
@Test void concurrentSameSkuOnTwoProducts()             // 2 thread → {201, 409}; outbox count 1
@Test void failureAfterInsertRollsBackVariantAndEvent() // revoke INSERT on audit_logs from catalog_runtime (superuser) → POST trả 5xx; count variants=0, outbox=0; grant lại trong finally
@Test void validationOfSkuSizeColorWeightPrice()        // sku "a b", sku 65 ký tự, size "", weight 0, price_override -1 → 400 từng field
@Test void updateChangesOnlyMutableFields()             // PUT kèm "sku":"NEW","size":"XL" → sku/size giữ nguyên; price_override, weight, status đổi; version 1; outbox vẫn 1
@Test void variantOfUnknownProductIs404()
```

- [ ] **Step 2: Chạy, đỏ.**

- [ ] **Step 3: Implement.** Trong `AdminCommands.create`: `lockForUpdate(productId)` → insert variant (id mới, version 0) → `update products set version = version + 1, updated_at = now()` returning version → `outbox.append(...)` → `audit.record(...)` → `Result(variantId, 201, variant)`. SKU `^[A-Za-z0-9][A-Za-z0-9._-]*$` 1–64; size/color 1–50 sau strip; `weight_grams` 1..`Integer.MAX_VALUE`; `price_override` null hoặc ≥ 0. PUT: `select ... for update` variant, `requireVersion`, update ba field mutable + `version+1`.

- [ ] **Step 4: Xanh module.**

- [ ] **Step 5: Mutation** — gọi `outbox.append` sau khi `AdminCommands.create` trả về (ngoài transaction) → `retrySameKeyAddsNoVariantOrEvent` FAIL (append ngoài TX ném `IllegalStateException`); gỡ `audit.record` khỏi transaction tương tự → `failureAfterInsertRollsBackVariantAndEvent` FAIL. Khôi phục.

- [ ] **Step 6: Commit** `feat(CAT-01): add variants with VARIANT_CREATED outbox`

---

### Task 5: Publish / unpublish

**Files:**
- Modify: `ProductAdminController.java`, `ProductAdminService.java`
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/admin/PublishIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 `lockForUpdate`, `load`; Task 2 `AdminCommands.create`, `requireVersion`.
- Produces: `POST /products/{id}/publish`, `POST /products/{id}/unpublish`; body `{expected_version, reason?}`; header `Idempotency-Key`; 200 → `Product`. Audit `catalog.product.publish|unpublish` có `reason`.

- [ ] **Step 1: Viết test (đỏ)**

```java
@Test void publishWithoutActiveVariantIs400AndStaysDraft()   // errors chứa field "variants"; status DRAFT; version 0
@Test void publishWithInactiveCategoryOrBrandIs400()         // field "category_id" / "brand_id"
@Test void publishSetsActiveAndPublishedAtOnce()             // 200 ACTIVE, published_at != null; unpublish → INACTIVE; publish lại → published_at không đổi
@Test void legacyActiveWithoutPublishedAtKeepsNullUntilRepublished() // ACTIVE legacy/null → unpublish INACTIVE/null → publish ACTIVE/timestamp được đặt
@Test void publishAfterLastVariantDeactivatedIs400()         // variant ACTIVE → PUT INACTIVE → publish 400
@Test void invalidTransitionIs409()                          // unpublish DRAFT → 409 CONFLICT INVALID_TRANSITION; publish ACTIVE (key mới) → 409
@Test void publishNeedsVersionAndKey()                       // thiếu expected_version 400 field expected_version (không 500); stale 409 VERSION_CONFLICT; thiếu key 400
@Test void publishWithoutReasonSucceeds()                    // body chỉ {expected_version:0} → 200; audit reason null
@Test void retryPublishSameKeyReturnsSameResult()            // lần 2 cùng key/body → 200 cùng body, không thêm audit
@Test void publicListShowsProductOnlyWhileActive()           // GET /api/v1/catalog/products có sản phẩm sau publish, mất sau unpublish
```

- [ ] **Step 2: Chạy, đỏ.**

- [ ] **Step 3: Implement.** Canonical request = record `StatusChange(UUID productId, String operation, Long expectedVersion, String reason)` (nhận null; không dùng `List.of`/`Map.of` vì ném NPE với null → 500). Trước `AdminCommands.create`: `expected_version` null → 400 field `expected_version`, `reason` > 500 → 400 (không chạm idempotency). `reason` null hợp lệ. Thứ tự trong transaction: `lockForUpdate` → `requireVersion` → kiểm cạnh (409 `INVALID_TRANSITION`) → (publish) kiểm điều kiện: `name_vi/name_en` không rỗng, category ACTIVE, brand null hoặc ACTIVE, `exists(variant ACTIVE)` → 400 gom lý do → update `status`, `version+1`; chỉ publish đặt `published_at = coalesce(published_at, now())`, unpublish không sửa `published_at` → audit. `reason` ≤ 500.

- [ ] **Step 4: Xanh module.**

- [ ] **Step 5: Mutation** — bỏ kiểm variant ACTIVE → `publishWithoutActiveVariantIs400AndStaysDraft` FAIL. Khôi phục.

- [ ] **Step 6: Commit** `feat(CAT-01): add product publish and unpublish`

---

### Task 6: Gateway route, compose, contract, smoke

**Files:**
- Modify: `services/gateway/src/main/resources/application.yaml` (route `catalog-admin`, `Path=/admin/api/v1/catalog/**`, uri `${CATALOG_BASE_URL:...}`)
- Modify: `services/gateway/src/test/java/vn/fashion/gateway/security/GatewayBoundaryTest.java`
- Modify: `infra/local/compose.yaml` (catalog-service env `CATALOG_JWT_PUBLIC_KEYS: ${USER_JWT_PUBLIC_KEYS:?run scripts/local-up.sh ...}`)
- Modify: `contracts/openapi/catalog.yaml`, `tests/contracts/` nếu cần test mới
- Modify: `scripts/smoke-local.sh`

**Interfaces:**
- Consumes: routes Task 2–5.
- Produces: contract OpenAPI các path admin với `security: [bearerAuth]`, `$ref` `IdempotencyKey`, `ExpectedVersion`, envelope/error từ `common.yaml`; schema `AdminCategory`, `AdminBrand`, `AdminProduct`, `AdminVariant`, `AdminProductPage` (`additionalProperties: false`, tiền `integer` `format: int64` `minimum: 0`); mỗi operation có ít nhất một example 2xx và một 4xx.

- [ ] **Step 1: Test Gateway (đỏ)** — thêm `routesAdminCatalogRequestsToCatalogService`: GET `/admin/api/v1/catalog/products?page=1` → stub catalog nhận path đúng; header `X-User-Roles` client bị loại (khuôn `routesAdminUserRequestsToUserService`).
- [ ] **Step 2:** `./mvnw -B -pl services/gateway -Dtest=GatewayBoundaryTest test` → FAIL; thêm route → PASS.
- [ ] **Step 3: Contract** — viết paths/schemas; `bash scripts/validate-contracts.sh` → PASS.
- [ ] **Step 4: Smoke** — theo chủ dự án chốt trong phiên thực thi 2026-10-07: giữ member thật gọi `GET /admin/api/v1/catalog/products` → 403 và không token → 401; bổ sung luồng category → product → variant → publish qua Gateway bằng token synthetic. Chỉ ký trong `scripts/smoke-local.sh`, Node crypto có sẵn đọc `USER_JWT_PRIVATE_KEY`/`USER_JWT_KEY_ID` từ `.env` trong memory, không log hoặc ghi file secret/token, không sửa user DB. Claim ADR-21: ES256/kid, iss=user-service, aud=fashion-api, sub UUID ngẫu nhiên, auth_version 0, permissions chỉ catalog.write, exp ≤ 300 giây. Slug/SKU có hậu tố ngẫu nhiên; dữ liệu tích lũy vì không có DELETE. README/evidence phải ghi smoke này không chứng minh chuỗi login → token OPS.
- [ ] **Step 5:** `bash scripts/local-up.sh && bash scripts/smoke-local.sh && npx playwright test` → PASS (nếu 504 lần đầu sau tạo lại container, chạy lại smoke).
- [ ] **Step 6: Commit** `feat(CAT-01): route catalog admin through Gateway and publish contract`

---

### Task 7: Đồng bộ tài liệu, kiểm tra toàn bộ, evidence

**Files:**
- Modify: `docs/design/03_interfaces.md` §3 (dòng catalog trỏ `catalog.yaml`, ghi quy ước key/version, `catalog.write`), `docs/design/05_database_design.md` §5 (V002, `version` ở categories/brands, trigger, seed `uncategorized`), `docs/design/06_service_flows.md` §3 (VARIANT_CREATED khi tạo variant; điều kiện ảnh hoãn CAT-03), `docs/design/08_decisions.md` (jsoup; điểm agent tự chọn spec §10 chờ reviewer), `docs/delivery/10_backlog.md` (CAT-01: 1a xong local, còn 1b + ảnh CAT-03), `docs/engineering/17_tech_stack.md` (jsoup 1.23.2), `services/catalog-service/README.md` (endpoint admin, config `CATALOG_JWT_PUBLIC_KEYS`, outbox chưa có relay, giới hạn), `docs/engineering/12_engineering_guide.md` §1 nếu số test thay đổi được nêu ở đó
- Create: `docs/evidence/cat-01a-local-2026-10-07.md`, handoff mới trong `docs/superpowers/plans/` + dòng trong `docs/superpowers/plans/README.md`

- [ ] **Step 1:** Cập nhật các file trên; không đánh dấu CAT-01 Done.
- [ ] **Step 2: Chạy toàn bộ kiểm tra**

```bash
./mvnw -B test
bash scripts/validate-contracts.sh
bash scripts/scan-secrets.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py && git diff --check
```

Expected: tất cả PASS; ghi số test từng module vào evidence.
- [ ] **Step 3: Commit** `docs(CAT-01): sync docs and evidence for CAT-01a`
