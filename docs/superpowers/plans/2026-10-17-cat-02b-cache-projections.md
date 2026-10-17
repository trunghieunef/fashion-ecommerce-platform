# CAT-02b — cache, tồn hiển thị và sold_quantity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hoàn tất phần 02b của `TASK:CAT-02`: Redis cache-aside cho public read với invalidation bền vững, tồn hiển thị theo variant từ `inventory.events`, `sold_quantity` từ `ORDER_COMPLETED`, và `Cache-Control` cho public response.

**Architecture:** Mọi thay đổi admin enqueue `background_tasks` trong cùng transaction; worker tăng một generation Redis làm mọi khóa cache cũ hết hiệu lực. Hai consumer Kafka (inbox + PostgreSQL) ghi projection tồn và sold; tồn được ghép vào detail mỗi request, không cache. Redis lỗi thì đọc PostgreSQL như 02a.

**Tech Stack:** Java 21, Spring Boot 4.0, Spring Data Redis (Lettuce, Boot BOM như user-service), Jackson 3 (`tools.jackson`), JdbcClient, Flyway, platform-durability (`BackgroundTaskRepository`, `InboxGuard`), platform-messaging (`InboxListenerSupport`, `PermanentEventException`, DLT `<topic>.DLT`), Testcontainers PostgreSQL 17.11 / Kafka / `redis:8.2.9`.

**Spec:** [`docs/superpowers/specs/2026-10-17-cat-02b-cache-projections-design.md`](../specs/2026-10-17-cat-02b-cache-projections-design.md) (đã duyệt 2026-10-17).

## Global Constraints

- Khóa cache: `<FASHION_ENV>:catalog:v1:g<gen>:<route>:<sha256 hex tham số chuẩn hóa>`; generation `<FASHION_ENV>:catalog:gen`, thiếu = 0; đọc `gen` **trước** query DB.
- Redis TTL: product detail, collection detail, size guide, categories, brands 300 s; products/search/collection list 30 s.
- Redis timeout connect/command 200 ms; lỗi Redis → bỏ qua cache, query PostgreSQL, không 5xx, không retry trong request; readiness **không** gồm Redis.
- Chỉ cache `data` của `200`; metadata/trace sinh mới; 4xx/5xx không cache; internal quote không cache.
- Invalidation: `background_tasks` kind `catalog.cache.invalidate`, `business_key` = UUID mới mỗi transaction; worker 1 s, `claimDue` tối đa 100, một `INCR`, `finish` tất cả.
- Tồn: chỉ `INVENTORY_UPDATED`/`INVENTORY_RESTOCKED`; upsert khi `stock_version` mới **lớn hơn**; không cache tồn.
- Sold: chỉ `ORDER_COMPLETED`; dedupe `event_id` (inbox) + `order_id` (`product_sales_applied`); variant lạ → `PermanentEventException` → DLT, không cộng phần nào; không đổi `version`/`updated_at` product.
- Header 200: list/search/collection list/categories/brands `public, max-age=30`; product detail `public, max-age=5`; collection detail, size guide `public, max-age=60`; mọi lỗi public `no-store`. Product detail và size guide thêm `Vary: Accept-Language` (detail resolve locale size guide từ header — technical ruling khi lập plan, mở rộng spec §6).
- Redis ACL user `catalog`, password `CATALOG_REDIS_PASSWORD`, chỉ khóa `<FASHION_ENV>:catalog:*`; không dùng credential user-service.
- Không phát `CATALOG_CHANGED` ra Kafka; không ETag/CDN; không giảm sold khi trả hàng.
- Migration append-only (V008); không sửa V001–V007. Tests integration dùng PostgreSQL/Redis/Kafka thật, không mock Redis/DB.

## Review Focus

1. Redis treo (không phải từ chối kết nối) giữa request → request vẫn trả 200 trong ngân sách ~200 ms + DB, không giữ thread lâu. Test: `PublicCacheIntegrationTest.pausedRedisFallsBackWithinBudget` (Task 5).
2. Giá trị cache hỏng/khác schema sau khi đổi record (vd VariantView thêm field) → coi như miss, không 500. Test: `PublicCacheIntegrationTest.undecodableValueIsMiss` (Task 5).
3. `ORDER_COMPLETED` có cùng variant lặp ở hai item → cộng tổng đúng một lần cho product. Test: `SoldQuantitiesIntegrationTest.duplicateVariantLinesSum` (Task 3).
4. Event tồn cho SKU catalog không có variant (SKU lạ hoặc variant bị xóa dữ liệu test) → vẫn lưu projection, không lỗi, không DLT; detail chỉ ghép theo SKU của variant. Test: `StockObservationsIntegrationTest.unknownSkuIsStored` (Task 2).
5. Writer ngoài `AdminCommands` làm đổi public read (job media) → vẫn invalidate. Test: `CacheInvalidationIntegrationTest.mediaWriterOutsideAdminCommandsInvalidates` nếu Task 6 Step 1 tìm thấy writer như vậy; nếu không, ghi kết quả rà soát vào evidence.

---

## File Structure

| File | Trách nhiệm |
|---|---|
| `contracts/events/registry.json`, `order-events.schema.json`, `tests/contracts/fixtures/…` | `ORDER_COMPLETED` |
| `contracts/openapi/catalog.yaml` | tồn theo variant, `Cache-Control`/`Vary` |
| `services/catalog-service/src/main/resources/db/migration/V008__catalog_projections.sql` | 2 bảng projection + grants |
| `catalog/stock/StockObservations.java`, `InventoryEventsListener.java` | projection tồn + ghép vào detail |
| `catalog/sales/SoldQuantities.java`, `OrderEventsListener.java` | projection sold |
| `catalog/web/PublicCacheHeaders.java` | filter `no-store` mặc định + helper `allow` |
| `catalog/cache/PublicCache.java`, `CacheConfig.java` | Redis cache-aside, generation, metric |
| `catalog/cache/CacheInvalidation.java` | enqueue + worker |
| `catalog/admin/AdminCommands.java` | gọi `CacheInvalidation.enqueue()` |
| `infra/local/compose.yaml`, `.env.example`, `kafka-init/create-topics.sh`, `scripts/local-up.sh`, `scripts/smoke-local.sh` | ACL Redis, topics, secret, smoke |

---

### Task 1: Contracts

**Files:**
- Modify: `contracts/events/registry.json`, `contracts/events/order-events.schema.json`, `contracts/openapi/catalog.yaml`
- Create: fixture `ORDER_COMPLETED` hợp lệ + 2 không hợp lệ theo cách các fixture event hiện có được tổ chức trong `tests/contracts/` (đọc `tests/contracts/test_*.py` để theo đúng thư mục/tên)
- Test: `tests/contracts/test_*.py` hiện có (thêm case nếu test không tự quét fixture)

**Interfaces:**
- Produces: registry `"ORDER_COMPLETED": {"schema": "order-events.schema.json#/$defs/OrderCompleted", "topic": "order.events", "partition_key": "order_no", "aggregate_id": "order_id"}`; `$defs/OrderCompleted` required `order_id, order_no, user_id, order_version, items, delivered_at`, `items` minItems 1 dùng `common.schema.json#/$defs/order_item`, `delivered_at` date-time, `additionalProperties: false`.
- Produces (catalog.yaml): `PublicVariant` thêm `available` (`integer ≥ 0 | null`), `observed_at` (`date-time | null`); `PublicProductDetail.available` đổi `null` → `integer ≥ 0 | null`, `observed_at` → `date-time | null`, mô tả: tổng variant có observation / observation cũ nhất. Mọi public 200 khai báo header `Cache-Control` (const theo Global Constraints), product detail và size guide khai báo `Vary`. Response lỗi public tham chiếu header `Cache-Control: no-store` (dùng anchor/`$ref` header chung trong `common.yaml` nếu có, giống INV-02). Sửa mô tả "deferred to CAT-02b".

- [ ] **Step 1:** Thêm fixtures (hợp lệ: 2 item, `user_id: null`; không hợp lệ: thiếu `delivered_at`; `quantity: 0`).
- [ ] **Step 2:** Run `bash scripts/validate-contracts.sh` — Expected: FAIL (registry/schema chưa có `ORDER_COMPLETED`).
- [ ] **Step 3:** Sửa registry, schema, `catalog.yaml` như Interfaces; cập nhật example detail: một variant có `available: 3, observed_at: '2026-10-17T03:00:00.000Z'`, top-level `available: 3`, cùng `observed_at`.
- [ ] **Step 4:** Run `bash scripts/validate-contracts.sh` — Expected: Redocly hợp lệ, unittest contract PASS. (Python 3.15 không cài được tooling: dùng Python 3.12/3.13 trong venv ngoài repo; nếu không có, ghi rõ và để CI chạy.)
- [ ] **Step 5:** Commit `feat(CAT-02b): ORDER_COMPLETED contract and stock/cache fields`

### Task 2: Schema V008 và projection tồn

**Files:**
- Create: `services/catalog-service/src/main/resources/db/migration/V008__catalog_projections.sql`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/stock/StockObservations.java`, `InventoryEventsListener.java`
- Modify: `product/PublicCatalogRepository.java` (record), `product/PublicCatalogController.java` (ghép tồn), `infra/local/kafka-init/create-topics.sh`
- Modify: `src/test/java/vn/fashion/catalog/admin/CatalogAdminTestSupport.java` (xóa 2 bảng mới trong `clearCatalog`; property `spring.kafka.listener.auto-startup=false` mặc định)
- Test: `src/test/java/vn/fashion/catalog/stock/StockObservationsIntegrationTest.java`, `CatalogProjectionSchemaIntegrationTest.java`

**Interfaces:**
- V008: `variant_stock_observations(sku varchar PK, available int not null check ≥0, stock_version bigint not null check ≥1, observed_at timestamptz not null, updated_at timestamptz not null default now())`; `product_sales_applied(order_id uuid PK, applied_at timestamptz not null default now())`. Grant runtime `SELECT, INSERT, UPDATE` cho bảng đầu, `SELECT, INSERT` cho bảng sau (theo cách V00x trước cấp quyền `catalog_runtime`).
- `public record Observation(int available, Instant observedAt)`
- `StockObservations.apply(EventEnvelope env) -> Boolean` (true khi đã ghi)
- `StockObservations.forSkus(List<String> skus) -> Map<String, Observation>`
- `StockObservations.attach(ProductDetail detail) -> ProductDetail`
- Record đổi: `VariantView(UUID id, String sku, String size, String color, long effectivePrice, Integer available, Instant observedAt)`; `ProductDetail.available` từ `Boolean` thành `Integer`. Repository vẫn trả `null` cho các field này; chỉ `attach` điền.
- Listener: `@KafkaListener(topics="inventory.events", groupId="catalog-service", autoStartup="${spring.kafka.listener.auto-startup:true}")`, `support.handle(record, ack, Map.of("INVENTORY_UPDATED", stock::apply, "INVENTORY_RESTOCKED", stock::apply))`, consumer name `catalog-service`.
- Topics thêm: `inventory.events.DLT order.events order.events.DLT` (3 partitions, như hiện có).

- [ ] **Step 1: Test schema** `CatalogProjectionSchemaIntegrationTest`: `stockVersionMustBePositive` (insert `stock_version=0` → `DataIntegrityViolationException`), `availableCannotBeNegative`, `salesAppliedOrderIdIsUnique`.
- [ ] **Step 2: Test projection** (`StockObservationsIntegrationTest`, gọi `apply` trực tiếp với `EventEnvelope` dựng từ JSON envelope đúng `event-envelope.schema.json`):
  - `newerVersionUpdatesAndOlderIsIgnored`: apply v2 (available 5) → apply v1 (available 9) → `forSkus` = 5, v2; apply v2 lại → trả `false`.
  - `invalidPayloadIsPermanent`: thiếu `stock_version` hoặc `available` âm → `PermanentEventException`.
  - `unknownSkuIsStored` (Review Focus 4).
  - `detailShowsPerVariantStockAndOldestObservedAt`: product publish 2 variant; observation A=2 lúc t1, B=3 lúc t2>t1 → `GET /api/v1/catalog/products/{id}`: variant A `available=2`, B `3`, top-level `available=5`, `observed_at=t1`; variant chưa có observation → `available` null và top-level chỉ tính variant có observation; không variant nào có → cả hai null.
  - `listenerConsumesFromKafka` (Kafka Testcontainer, listener bật bằng `@DynamicPropertySource` cho class này): publish envelope `INVENTORY_UPDATED` lên `inventory.events` → trong 20 s `forSkus` thấy giá trị; `INVENTORY_RESERVED` được ACK, không ghi.
- [ ] **Step 3:** Run `./mvnw -q -pl services/catalog-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='CatalogProjectionSchemaIntegrationTest,StockObservationsIntegrationTest' test` — Expected: FAIL (bảng/lớp chưa có).
- [ ] **Step 4:** Viết V008, `StockObservations` (validate payload như `SkuRegistration` của inventory: version envelope 1, field kiểu đúng, SKU canonical hợp lệ; upsert `on conflict (sku) do update … where variant_stock_observations.stock_version < excluded.stock_version`), listener, đổi record, controller gọi `stock.attach(...)` sau repository. Cập nhật topic script.
- [ ] **Step 5:** Run lại lệnh Step 3 — Expected: PASS; chạy cả `PublicCatalogReadIntegrationTest`, `PublicDetailSnapshotIntegrationTest` — PASS (record đổi không phá test cũ; sửa assertion `available` null nếu test cũ so kiểu Boolean).
- [ ] **Step 6:** Commit `feat(CAT-02b): inventory stock observations on product detail`

### Task 3: sold_quantity từ ORDER_COMPLETED

**Files:**
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/sales/SoldQuantities.java`, `OrderEventsListener.java`
- Test: `src/test/java/vn/fashion/catalog/sales/SoldQuantitiesIntegrationTest.java`

**Interfaces:**
- Consumes: bảng `product_sales_applied` (Task 2); schema `OrderCompleted` (Task 1).
- `SoldQuantities.apply(EventEnvelope env) -> Boolean` (false khi order đã áp dụng).
- Listener: topic `order.events`, group `catalog-service`, handlers `Map.of("ORDER_COMPLETED", sold::apply)`.

- [ ] **Step 1: Tests**
  - `completedOrderAddsQuantityPerProduct`: product P1 (variant a, b), P2 (variant c); items a×2, b×1, c×4 → P1 `sold_quantity`=3, P2=4; `products.version`, `updated_at` không đổi.
  - `replaySameOrderDoesNotAddTwice`: apply cùng envelope (cùng `event_id`) qua `InboxGuard.applyOnce` hai lần, và envelope khác `event_id` cùng `order_id` → vẫn 3/4; lần hai `apply` trả `false`.
  - `duplicateVariantLinesSum` (Review Focus 3): items a×2, a×3 → P1 = 5.
  - `unknownVariantIsPermanentAndAddsNothing`: items a×1, (variant ngẫu nhiên)×1 → `PermanentEventException`; P1 = 0; không có dòng `product_sales_applied`.
  - `guestOrderCounts`: `user_id: null` vẫn cộng.
  - `bestSellingOrdersBySoldQuantity`: sau apply, `GET /api/v1/catalog/products?sort=BEST_SELLING` (cache tắt trong test support) → P2 trước P1.
  - `invalidEventGoesToDlt` (Kafka thật): publish `ORDER_COMPLETED` với variant lạ lên `order.events` → bản ghi xuất hiện ở `order.events.DLT` trong 20 s.
- [ ] **Step 2:** Run `-Dtest=SoldQuantitiesIntegrationTest` — Expected: FAIL.
- [ ] **Step 3:** Implement: validate payload (envelope version 1, UUID chuẩn, `items` 1..100, quantity 1..99); `insert into product_sales_applied … on conflict do nothing`, 0 dòng → `false`; một query lấy `product_id` theo `variant_id = any(:ids)`, thiếu bất kỳ id nào → `PermanentEventException`; một `update products … from (values/unnest) …` cộng tổng theo product. Tất cả trong transaction của `InboxGuard.applyOnce`.
- [ ] **Step 4:** Run lại — Expected: PASS.
- [ ] **Step 5:** Commit `feat(CAT-02b): sold_quantity projection from ORDER_COMPLETED`

### Task 4: Cache-Control headers

**Files:**
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/web/PublicCacheHeaders.java`
- Modify: `product/PublicCatalogController.java`, `product/ProductQueryController.java`, `collection/PublicCollectionController.java` (thêm tham số `HttpServletResponse response`, gọi `allow` trước khi trả)
- Test: `src/test/java/vn/fashion/catalog/product/PublicCacheHeadersIntegrationTest.java`

**Interfaces:**
- `PublicCacheHeaders extends OncePerRequestFilter`: với request `GET /api/v1/catalog/**` trừ `/api/v1/catalog/images/**` (media tự đặt header), đặt `Cache-Control: no-store` **trước** chain (mẫu `VariantQuoteNoStoreFilter`). Lỗi binding/security/404 giữ `no-store`.
- `static void allow(HttpServletResponse response, int maxAgeSeconds)` → `setHeader("Cache-Control", "public, max-age=" + maxAgeSeconds)`; `static void varyLanguage(HttpServletResponse response)` → `setHeader("Vary", "Accept-Language")`.
- Hằng: `LIST = 30`, `DETAIL = 5`, `STATIC = 60` (collection detail, size guide).

- [ ] **Step 1: Test** `everyPublicRouteSendsItsCacheControl` (bảng route → giá trị trong Global Constraints, gồm `/products`, `/search?q=a`, `/products/{id}`, `/categories`, `/brands`, `/collections`, `/collections/{slug}`, `/size-guides/{category_id}`); `errorsAreNoStore` (`/products?limit=0` 400, `/products/{random}` 404, `/collections/missing` 404, `/size-guides/{id}?locale=fr` 400); `detailAndSizeGuideVaryOnLanguage`; `imagesKeepTheirOwnHeader` (một assertion hiện có của `MediaReadIntegrationTest` vẫn pass).
- [ ] **Step 2:** Run `-Dtest=PublicCacheHeadersIntegrationTest` — Expected: FAIL.
- [ ] **Step 3:** Implement filter + gọi `allow`/`varyLanguage` trong 8 handler.
- [ ] **Step 4:** Run test mới + `MediaReadIntegrationTest` — Expected: PASS.
- [ ] **Step 5:** Commit `feat(CAT-02b): public Cache-Control headers`

### Task 5: Redis cache-aside

**Files:**
- Modify: `services/catalog-service/pom.xml` (`spring-boot-starter-data-redis`, cùng cách user-service khai báo; test `testcontainers` GenericContainer đã có qua BOM)
- Modify: `src/main/resources/application.yaml`
- Create: `catalog/cache/PublicCache.java`, `catalog/cache/CacheConfig.java`
- Modify: 3 public controller (bọc lời gọi repository)
- Modify: `CatalogAdminTestSupport` (`fashion.catalog.cache.enabled=false` mặc định)
- Modify: `infra/local/compose.yaml` (ACL + env catalog), `infra/local/.env.example`, `scripts/local-up.sh` (sinh `CATALOG_REDIS_PASSWORD` như các secret `openssl rand -hex 32`)
- Test: `src/test/java/vn/fashion/catalog/cache/PublicCacheIntegrationTest.java`

**Interfaces:**
- Config: `spring.data.redis.host=${CATALOG_REDIS_HOST:localhost}`, `port=${CATALOG_REDIS_PORT:6379}`, `username=${CATALOG_REDIS_USERNAME:catalog}`, `password=${CATALOG_REDIS_PASSWORD:}`, `timeout=200ms`, `connect-timeout=200ms`; `fashion.catalog.cache.enabled=${CATALOG_CACHE_ENABLED:true}`, `fashion.catalog.cache.environment=${FASHION_ENV:local}`. Readiness group giữ `readinessState,db,catalogMigration`; tắt `management.health.redis.enabled=false` để Redis không làm health DOWN.
- `PublicCache`:
  - `<T> Optional<T> read(String route, Duration ttl, TypeReference<T> type, Supplier<Optional<T>> load, String... params)` — key = `env + ":catalog:v1:g" + gen + ":" + route + ":" + sha256(params đã escape backslash rồi pipe, nối bằng "|")`. Đọc `gen` (thiếu = 0) → GET → hit thì decode; decode lỗi = miss; miss → `load`, nếu có giá trị thì `SET key value EX ttl`. Mọi `RuntimeException` từ Redis (kể cả `gen`) → `load` trực tiếp, không ghi.
  - `long bumpGeneration()` — `INCR`; ném lỗi Redis cho caller (worker cần biết thất bại).
  - Disabled → luôn `load`.
  - Metric counter `catalog.cache` tag `route`, `outcome` ∈ `hit|miss|error`.
- Route và params (dùng giá trị **sau** validate):
  - `products` / `search`: `filters.hash()`-đầu vào chuẩn hóa — dùng chuỗi canonical đầy đủ của `BrowseFilters` (thêm method `String canonical()` trả chuỗi đã nối mà `hash()` đang băm), cursor nguyên văn hoặc rỗng, `limit`. TTL 30 s.
  - `product`: id, locale. TTL 300 s. Chỉ phần catalog; `StockObservations.attach` chạy **sau** cache.
  - `categories`, `brands`: không params. TTL 300 s.
  - `collections`: cursor, limit. TTL 30 s. `collection`: slug, cursor, limit. TTL 300 s.
  - `size-guide`: category id, locale đã chọn. TTL 300 s.
- Compose `redis` command thêm ACL user (kiểm cú pháp với redis 8.2.9): `--user catalog on ">$$CATALOG_REDIS_PASSWORD" "~$${FASHION_ENV:-local}:catalog:*" "+@connection" "+get" "+set" "+incr"`; service `catalog` nhận `CATALOG_REDIS_HOST=redis`, `CATALOG_REDIS_PASSWORD`, `FASHION_ENV`, `depends_on` redis **không** bắt buộc healthy (catalog chạy được khi Redis down).

- [ ] **Step 1: Tests** (`redis:8.2.9` GenericContainer, cache bật cho class này):
  - `secondReadIsHitWithFreshMetadata`: GET detail hai lần → `data` bằng nhau, `metadata.request_id` khác, counter `hit` = 1.
  - `cacheDoesNotStoreErrors`: 404 detail → không khóa nào có `:product:` trong Redis (`SCAN`).
  - `stockIsNotCached`: GET detail (hit), apply observation mới → GET lại thấy `available` mới dù catalog part là hit.
  - `staleReaderWritesOnlyOldGeneration` (race): đọc `gen`=g → admin sửa tên + `bumpGeneration()` → gọi `read` với supplier trả dữ liệu cũ nhưng đã đọc gen g trước (mô phỏng bằng cách gọi trực tiếp `PublicCache.read` trong test với supplier chặn trên latch trong khi thread khác bump) → GET kế tiếp trả tên mới.
  - `redisDownFallsBackToDatabase`: dừng container → mọi route public trả 200 đúng dữ liệu; `/actuator/health/readiness` UP.
  - `pausedRedisFallsBackWithinBudget` (Review Focus 1): `docker pause` container (`redis.getDockerClient().pauseContainerCmd(id)`) → GET detail 200 trong < 1 s; unpause sau test.
  - `undecodableValueIsMiss` (Review Focus 2): SET khóa detail hiện hành thành `"{bad"` → GET 200 từ DB, counter `miss`/`error` tăng, không 500.
  - `aclUserCannotTouchOtherPrefixes`: container khởi động với ACL như compose; `catalog` user `GET local:user:x` → lỗi NOPERM (chứng minh credential riêng).
- [ ] **Step 2:** Run `-Dtest=PublicCacheIntegrationTest` — Expected: FAIL.
- [ ] **Step 3:** Implement `PublicCache`, `CacheConfig` (bean `StringRedisTemplate` dùng sẵn của Boot nếu đủ; không tự tạo connection factory khi auto-config làm được), `BrowseFilters.canonical()`, bọc 8 handler, compose/env/local-up.
- [ ] **Step 4:** Run test mới + toàn bộ test `product`, `collection` package — Expected: PASS.
- [ ] **Step 5:** `bash scripts/local-up.sh` rồi `docker compose -f infra/local/compose.yaml exec redis redis-cli --user catalog --pass "$CATALOG_REDIS_PASSWORD" ACL WHOAMI` — Expected `catalog`; `curl` hai lần `/api/v1/catalog/categories` qua Gateway → lần hai metric `catalog_cache_total{outcome="hit"}` ≥ 1 trên `/actuator/prometheus`.
- [ ] **Step 6:** Commit `feat(CAT-02b): Redis cache-aside for public catalog reads`

### Task 6: Invalidation bền vững

**Files:**
- Create: `catalog/cache/CacheInvalidation.java`
- Modify: `catalog/admin/AdminCommands.java` (gọi `enqueue()` sau `work.get()` trong nhánh STARTED của `create`, và trong `update`)
- Modify: `CatalogBeans.java` hoặc `CacheConfig` (bean `BackgroundTaskRepository` nếu catalog chưa có; bean `@Scheduled` có điều kiện `fashion.catalog.cache.invalidation-enabled` mặc định `true`, tắt trong test support — mẫu `MediaConfig`)
- Test: `src/test/java/vn/fashion/catalog/cache/CacheInvalidationIntegrationTest.java`

**Interfaces:**
- Consumes: `PublicCache.bumpGeneration()` (Task 5), `BackgroundTaskRepository.enqueue/claimDue/finish` (platform-durability; đọc chữ ký `finish`/fail thật trong file trước khi dùng).
- `CacheInvalidation.enqueue()` — `tasks.enqueue("catalog.cache.invalidate", UUID.randomUUID().toString(), "{}")`; phải trong transaction (repository tự kiểm).
- `CacheInvalidation.runOnce() -> int` — `claimDue(kind, 100, lease 30 s)`; rỗng → 0; một `bumpGeneration()`; thành công → `finish` từng task; lỗi Redis → không finish (để lease hết hạn/backoff theo repository), tăng counter `catalog.cache.invalidation{outcome=error}`, trả 0.
- Scheduled `fixedDelay = 1 s` gọi `runOnce()`.

- [ ] **Step 1: Rà writer** ngoài `AdminCommands` làm đổi public read: `rg -n "update |insert into|delete from" services/catalog-service/src/main/java --glob '!**/admin/**'` và đọc `media/MediaJobs.java`, `media/UploadService.java`. Với mỗi writer đổi bảng mà public read đọc (products, product_variants, product_images, media_assets đã attach, collections, collection_items, lookbook_images, size_guides, categories, brands), thêm `enqueue()` trong transaction của nó. Ghi danh sách vào evidence. Nếu có, thêm test Review Focus 5.
- [ ] **Step 2: Tests**
  - `adminChangesBecomeVisibleAfterWorker`: bảng các thao tác — đổi tên product, unpublish, đổi giá variant, thêm product vào collection, PUT size guide, đổi tên category — mỗi thao tác: GET (miss → cache) → thao tác admin → `runOnce()` → GET thấy giá trị mới.
  - `replayDoesNotEnqueue`: cùng `Idempotency-Key` + body gửi lại → số dòng `background_tasks` kind `catalog.cache.invalidate` không tăng.
  - `failedAdminCommandEnqueuesNothing`: lệnh admin 409 `STALE_VERSION` → 0 task mới (rollback).
  - `redisDownKeepsTaskThenRecovers`: dừng Redis → admin sửa → `runOnce()` = 0, task còn chưa finish; khởi động lại Redis → sau khi lease/backoff đến hạn (đặt `next_attempt_at=now()` bằng SQL trong test) `runOnce()` > 0, task finished, GET thấy mới.
  - `oneBumpForManyTasks`: 3 thao tác admin → `runOnce()` = 3, `gen` tăng đúng 1.
- [ ] **Step 3:** Run `-Dtest=CacheInvalidationIntegrationTest` — Expected: FAIL.
- [ ] **Step 4:** Implement.
- [ ] **Step 5:** Run lại + toàn bộ test `admin` package — Expected: PASS.
- [ ] **Step 6:** Commit `feat(CAT-02b): durable cache invalidation after admin changes`

### Task 7: Smoke, mutation, tài liệu, verify

**Files:**
- Modify: `scripts/smoke-local.sh` (sau bước CAT-01a publish hiện có: GET detail qua Gateway → kiểm `Cache-Control: public, max-age=5`; admin đổi tên product → poll tối đa 10 s tới khi public detail thấy tên mới; in `CAT-02b cache invalidation PASS`)
- Modify docs theo spec §9: `docs/design/03_interfaces.md`, `04_architecture.md` §4, `05_database_design.md`, `06_service_flows.md`, `08_decisions.md` (7 quyết định spec §8 + Vary detail), `docs/delivery/10_backlog.md` (CAT-02 trạng thái Review, không Done), `services/catalog-service/README.md` (Redis/ACL, topics consume, metric `catalog_cache_total`, `catalog_cache_invalidation_total`, giới hạn), `docs/superpowers/plans/README.md`
- Create: `docs/evidence/cat-02b-local-2026-10-17.md`, `docs/superpowers/plans/2026-10-17-cat-02b-handoff.md`

- [ ] **Step 1: Mutants** — chạy tuần tự, một mutant mỗi lần trên source thật, khôi phục sau mỗi lần, mỗi mutant phải làm test chỉ định FAIL:

| # | Mutant | Test phải bắt |
|---|---|---|
| 1 | Upsert tồn bỏ điều kiện `stock_version <` | `StockObservationsIntegrationTest#newerVersionUpdatesAndOlderIsIgnored` |
| 2 | Bỏ insert `product_sales_applied` (chỉ dựa inbox) | `SoldQuantitiesIntegrationTest#replaySameOrderDoesNotAddTwice` |
| 3 | Đọc `gen` **sau** `load` | `PublicCacheIntegrationTest#staleReaderWritesOnlyOldGeneration` |
| 4 | Bỏ `enqueue()` trong `AdminCommands.update` | `CacheInvalidationIntegrationTest#adminChangesBecomeVisibleAfterWorker` |
| 5 | `finish` task cả khi `bumpGeneration` lỗi | `CacheInvalidationIntegrationTest#redisDownKeepsTaskThenRecovers` |
| 6 | Filter không đặt `no-store` trước chain | `PublicCacheHeadersIntegrationTest#errorsAreNoStore` |

Lệnh: `./mvnw -q -pl services/catalog-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='<Class#method>' test` — Expected exit 1, 1 failure.

- [ ] **Step 2: Verify** (Docker Desktop bật, Git Bash; `python3` trỏ tới Python thật nếu là alias Store):
  - `./mvnw -B verify` — BUILD SUCCESS, 0 failure.
  - `bash scripts/validate-contracts.sh` — PASS (hoặc ghi lý do không chạy được unittest Python, để CI).
  - `bash scripts/local-up.sh` && `bash scripts/smoke-local.sh` — exit 0, có dòng `CAT-02b cache invalidation PASS`.
  - `python3 -B scripts/check_docs.py`, `python3 -B -m unittest discover -s scripts -p 'test_*.py'`, `git diff --check`, `bash scripts/scan-secrets.sh` — PASS.
- [ ] **Step 3:** Ghi evidence (lệnh, số test, mutant 6/6, chỗ lệch khỏi plan, kết quả rà writer Task 6 Step 1) và handoff; CAT-02 để **Review**, không đánh dấu Done.
- [ ] **Step 4:** Commit `docs(CAT-02b): contracts, evidence and docs for cache and projections`
