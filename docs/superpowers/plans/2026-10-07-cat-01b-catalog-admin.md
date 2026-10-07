# CAT-01b Collection/Size Guide Admin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OPS quản lý collection/items và size guide VI/EN qua Gateway, có read/edit version,
validation, idempotency khi tạo và audit atomic theo spec đã duyệt.

**Architecture:** JdbcClient và hai service/controller admin theo khuôn CAT-01a;
response/canonical input dùng record, request schema đóng dùng DTO có @JsonAnySetter.
Tái sử dụng AdminAuth/Api/AdminCommands/AuditLog/HtmlSanitizer và platform-durability;
GET collection detail một SQL json_agg, PUT guide xử lý race bằng ON CONFLICT RETURNING.

**Tech Stack:** Java 21, Spring Boot 4.0 theo BOM repo, PostgreSQL 17.11/Testcontainers,
Jackson 3 có sẵn, jsoup 1.23.2 có sẵn; Maven wrapper trong Git Bash, Docker Desktop.

**Spec:** [2026-10-07-cat-01b-design.md](../specs/2026-10-07-cat-01b-design.md), **đã duyệt**
2026-10-07, gồm §6 mục 1–6 và chốt bổ sung cùng ngày.

**Status:** **Đã duyệt với các sửa đổi ngày 2026-10-07**; Native executing-plans,
tuần tự trên `dev`, commit local từng task; chưa tuyên bố endpoint chạy.
`TASK:CAT-01` phần 1b; `REQ:CAT-03`, `REQ:CAT-06`; dependency CAT-01a/PLT-02/03/USR-02.
Baseline code `8f27663`; checkpoint docs/contract/spec `6ccbbd3`. Làm tuần tự trên `dev`;
không dùng plan Phase 1A Task 2 cũ để đổi scope. Chưa Done parent CAT-01.

## Global Constraints

- Không JPA, dependency/framework mới, DELETE endpoint; không sửa V001/V002/V003.
- Mọi route `/admin/api/v1/catalog/**` kiểm ES256/ADR-21 và catalog.write tại service.
- Collection POST key → DRAFT/version 0/201; PUT chỉ expected_version → version +1/200.
- Tối đa 1.000 items; product_id không trùng; sort_order integer 0..2147483647, được trùng;
  đọc `(sort_order, product_id)`, product body thiếu → 400 field `items[i].product_id`.
- Date ISO-8601 có offset → UTC; null bound không giới hạn; cả hai có giá trị thì end > start.
- Request collection/guide từ chối field lạ qua @JsonAnySetter, 400 field tên key;
  giữ global fail-on-unknown-properties=false và behavior CAT-01a.
- JsonNode expected_version, sort_order, table_json: Java null hoặc NullNode đều là thiếu → 400.
- cover_url luôn null, không nhận cover/lookbook kể cả null; media/upload/publish image gate CAT-03.
- Guide locale vi/en; PUT key + version: 0 tạo 201/version 1, update 200/version +1;
  thiếu version 400, existing-create/stale/absent-positive 409 VERSION_CONFLICT.
- PUT guide: auth → validate → category path 404 → idempotency → version guard.
  Replay giữ data/status, metadata mới, không audit; changed body/key 409.
- Guide create INSERT ON CONFLICT(category_id,locale) DO NOTHING RETURNING, rỗng →
  VERSION_CONFLICT; không catch unique violation trong transaction idempotency.
- Table đúng columns/rows; 1–20 cột, 1–100 hàng, row width bằng column count;
  header strip 1–100, duplicate strip+lowercase(Locale.ROOT), cell strip 0–100;
  compact normalized JSON ≤ 32.768 UTF-8 bytes, plain text/FE escape.
- guideline_html thiếu/null → rỗng; sanitizer product, tối đa 20.000 ký tự sau sanitize.
- Mutation/items/version/audit/idempotency khi có cùng transaction; audit request_id = metadata lần đầu.
- Public read CAT-02, CATALOG_CHANGED/cache/relay/Kafka/inventory/UI/cloud ngoài slice.
- Nếu gặp mâu thuẫn spec/code/plan hoặc quyết định contract mới: dừng phần phụ thuộc và hỏi.

## Review Focus

1. Items request không có thứ tự: field lỗi giữ index gốc, response mới sắp xếp — Task 3.
2. Hai date offset khác nhau nhưng cùng Instant: end bằng start vẫn bị từ chối — Task 3.
3. Global Jackson bỏ field lạ: any-setter vẫn trả đúng key, không bỏ sót null — Task 3/5.
4. Category bị xóa trong DB test sau khi key COMPLETED: PUT replay vẫn 404 trước lookup — Task 5.
5. Unicode/table và audit lỗi cuối transaction: đo UTF-8 bytes, rollback cả data/key — Task 5.

## File map và lệnh dùng chung

Create production chỉ: `services/catalog-service/src/main/resources/db/migration/V004__collections_and_size_guides.sql`,
`services/catalog-service/src/main/java/vn/fashion/catalog/admin/CollectionAdminService.java`,
`CollectionAdminController.java`, `SizeGuideAdminService.java`, `SizeGuideAdminController.java`
(bốn Java file cùng thư mục admin). Chỉ sửa AdminCommands để tách guard key dùng lại khi cần
validate trước category; không thay primitive durability hoặc lỗi admin chung nếu không có test chứng minh.
Tests mới cùng `services/catalog-service/src/test/java/vn/fashion/catalog/admin/`:
`CatalogCollectionsSchemaIntegrationTest`, `CollectionReadIntegrationTest`,
`CollectionAdminIntegrationTest`, `SizeGuideReadIntegrationTest`, `SizeGuideAdminIntegrationTest`.
Sửa test support để cleanup FK đúng thứ tự. Smoke dùng script hiện có, không tạo helper JWT production.

Chạy từ repo root **Git Bash**:

```bash
export JAVA_HOME=/c/Users/<user>/.local/toolchains/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:/c/Users/<user>/.local/toolchains/node-v24.21.0-win-x64:/d/Git/bin:$PATH"
docker info
mkdir -p .superpowers/cat-01b/evidence
```

Target Maven trong mỗi task dùng lệnh dưới, thay TEST bằng tên class/method đã ghi:

```bash
./mvnw -B -pl services/catalog-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TEST test
./mvnw -B -pl services/catalog-service -am test
```

`-am` giúp fresh clone có dependency reactor. RED phải fail đúng assertion/behavior, không
do Docker/toolchain/compile; GREEN module phải 0 failures/errors. Ghi log và tổng tests thực tế,
không dự đoán số test sau implementation. Mutation chạy riêng target, log có `Tests run:` và
không `COMPILATION ERROR`, assertion bị mutation tác động phải FAIL; restore trước module GREEN/commit.
Task 1–5 mỗi task đều RED → implement → module GREEN → mutation thật → restore/GREEN → docs/commit.
Task 6 smoke có RED/GREEN HTTP và mutation script thực chạy; Task 7 chỉ verification/bàn giao,
không chế tạo test đỏ hoặc mutation cho tài liệu.

Trước Task 1: kiểm tra và commit spec/plan/docs/contract đang dở thành một commit docs,
sau các kiểm tra docs/contract; giữ nguyên dữ liệu và thay đổi ngoài phạm vi.

---

### Task 1: Schema V004 và upgrade giữ dữ liệu cũ

**Files:** Create migration V004 và `CatalogCollectionsSchemaIntegrationTest.java` như file map;
Modify `CatalogAdminTestSupport.java:clearCatalog`, `docs/design/05_database_design.md`,
`services/catalog-service/README.md` (schema đã viết/kiểm tra, không nhận endpoint đã có).

**Interfaces:** Produces collections/items/guides theo spec §5; names constraint `collections_slug_key`,
`collection_items_pkey`, `size_guides_category_id_locale_key`; default collection.version 0,
guide.version 1; T timestamps. Test support thêm cleanup collection_items → collections và
size_guides trước products/categories, chỉ trong DB Testcontainers bằng owner connection.

- [ ] **Step 1: Viết schema tests trước DDL.** Dùng Flyway/Testcontainers theo schema test hiện có,
  migrate tới V003, giữ một legacy SKU từ V002 rồi migrate mới; test không tham chiếu class production chưa có.

```java
@Test void newTablesExistAfterV003Upgrade() // to_regclass 3 table != null
@Test void collectionDefaultsConstraintsAndItemForeignKeys() // DRAFT/version0, unique slug, sort=-1 fails; tied sort accepted; unknown product FK fails
@Test void guideLocaleVersionAndCategoryUniqueness() // vi/en, version1; bad locale/version0 fails, duplicate pair fails
@Test void validTimeBoundsAndNullableBounds() // null bounds accepted, end<=start rejected
@Test void legacySkuAndSampleRowsSurviveV004() // original sku/price/status/id remain equal
@Test void runtimeAuditRemainsAppendOnly() // runtime cannot UPDATE/DELETE/TRUNCATE audit
```

- [ ] **Step 2: RED.** Target `CatalogCollectionsSchemaIntegrationTest`; expected assertion table absent,
  log Tests run, no COMPILATION ERROR. Không sửa V001–V003 hoặc reset DB để vượt lỗi.
- [ ] **Step 3: Implement V004.** Chỉ 3 table spec, FK nội bộ/unique/check/index/time/version,
  grant theo default privileges hiện có. Không lookbook/product_images/cache tables. Cập nhật cleanup support.
- [ ] **Step 4: GREEN.** Target rồi module bằng lệnh chung; public/sample/SKU/audit tests cũ vẫn PASS.
- [ ] **Step 5: Mutation.** Bỏ CHECK sort_order >= 0 trong **V004 chưa deploy** → target
  `CatalogCollectionsSchemaIntegrationTest#collectionDefaultsConstraintsAndItemForeignKeys` FAIL khi insert -1;
  restore rồi module GREEN. Mỗi target tạo DB fresh, không đổi schema volume local.
- [ ] **Step 6: Đồng bộ docs và commit** `feat(CAT-01): add collection and size guide schema V004`.

### Task 2: Collection GET list/detail, one-statement snapshot

**Files:** Create `CollectionAdminService.java`, `CollectionAdminController.java`,
`CollectionReadIntegrationTest.java`; Modify `contracts/openapi/catalog.yaml`,
`tests/contracts/test_catalog_admin.py`, `services/catalog-service/README.md`, 03 §3.

**Interfaces:** Service produces nested records `Item(UUID productId, int sortOrder)`,
`Summary(UUID id, String nameVi, String nameEn, String slug, String coverUrl, Instant startAt,
Instant endAt, String status, long version)`, `CollectionData` có cùng field Summary + `List<Item> items`,
`Page(List<Summary> items, int page, int size, long total)`.
Methods `Page list(int page,int size,String status)`, `CollectionData load(UUID id)`.
Controller GET `/collections` (page1/size20/max100/status optional), GET `/collections/{id}`;
AdminAuth → service → Api.ok. Không audit read. Tests seed bằng SQL/owner trên isolated DB.

- [ ] **Step 1: Viết HTTP tests và failing contract cho collection list.** Dùng support send/data,
  seed collection/items trực tiếp; không import service/record chưa tồn tại trong test RED.

```java
@Test void collectionGetsRequireCatalogWrite() // both routes: no token401, member403
@Test void detailIsOrderedAndHasEditVersion() // 3 items, tied sort0 + UUID tie-break; DRAFT/version7; exact fields including cover_url null
@Test void emptyInactiveCollectionReturnsEmptyItems() // [] not null/[null], INACTIVE is visible
@Test void missingCollectionIs404() // NOT_FOUND + metadata/header
@Test void listHasPaginationStatusAndSummaries() // size2/total3, page2; summary no items; created_at DESC/id DESC
@Test void invalidPageSizeAndStatusAre400() // page0,size0/101,statusUNKNOWN
```

- [ ] **Step 2: RED.** Target `CollectionReadIntegrationTest` → 404 instead of expected route response;
  Python contract assertion admin count 20 vs 19/list route missing FAIL, not parsing/tooling failure.
- [ ] **Step 3: Implement interfaces.** load bắt buộc một statement:

```sql
SELECT c.*, COALESCE((SELECT json_agg(json_build_object('product_id',i.product_id,'sort_order',i.sort_order)
  ORDER BY i.sort_order,i.product_id) FROM collection_items i WHERE i.collection_id=c.id),'[]'::json) AS items
FROM collections c WHERE c.id=:id
```

  Parse items JSON qua ObjectMapper hiện có; không query items lần hai. List chỉ summary, SQL params
  status/page/size, offset dùng long như product admin. Bổ sung list OpenAPI/examples; response name
  max255/slug160 theo normalized values, không áp raw max/pattern trước strip cho request.
- [ ] **Step 4: GREEN.** Target + module + `bash scripts/validate-contracts.sh`; cập nhật test admin count 20.
- [ ] **Step 5: Mutation.** Đổi tuple trong json_agg thành product_id/sort_order →
  `CollectionReadIntegrationTest#detailIsOrderedAndHasEditVersion` FAIL. Fixture phải có UUID lớn
  ở sort0 và UUID nhỏ ở sort1 để phân biệt cả sort primary và tie-break; thêm tied UUID
  7fff.../8000... theo thứ tự PostgreSQL UUID, không dùng Java UUID.compareTo signed để tự sort response.
  Restore/module GREEN.
- [ ] **Step 6: Đồng bộ docs và commit** `feat(CAT-01): add collection admin list and detail reads`.

### Task 3: Collection POST/PUT, strict requests và replacement atomic

**Files:** Modify hai CollectionAdmin Java file, `CatalogAdminTestSupport.java` nếu cần fixture,
03/05/06, catalog README và contract; Create `CollectionAdminIntegrationTest.java`.

**Interfaces:** Consumes Task 2 records/load. Service nested mutable `CreateRequest`:
public nameVi/nameEn/slug/startAt/endAt, List<ItemRequest> items, @JsonAnySetter collector key names.
`UpdateRequest extends CreateRequest` thêm status và JsonNode expectedVersion; `ItemRequest` gồm
String productId, JsonNode sortOrder và any-setter. @JsonIgnore unknownFields; không serialize DTO request vào hash.
Normalized record `Input(String nameVi,String nameEn,String slug,Instant startAt,Instant endAt,
String status,Long expectedVersion,List<Item> items)` giữ request item order.
`Input normalize(CreateRequest)`, `Input normalize(UpdateRequest)`,
`CollectionData create(UUID actor,Input input,UUID requestId)` (trong transaction caller),
`CollectionData update(UUID actor,UUID id,Input input,UUID requestId)` (commands.update).
Controller POST dùng commands.create(actor,"catalog.collection.create",key,input,...201);
PUT dùng update, không yêu cầu key, metadata một lần/audit requestId dùng lại.

- [ ] **Step 1: Viết HTTP tests, body JSON literals để RED không phụ thuộc DTO chưa có.**

```java
@Test void createIsDraftVersionZeroAndReplayDoesNotAudit() // 201; same key/body same data/status, metadata differs, audit1
@Test void collectionMutationsRequireCatalogWrite() // POST/PUT: no token401, member403, no mutation/audit/key
@Test void collectionNamesSlugAndDuplicateSlugAreValidated() // strip names/slug; names255 accepted/256 rejected; slug160/161; blank/malformed fields400; duplicate slug409
@Test void equivalentDateOffsetsReplayTheSameKey() // same Instant written with +07:00/Z, same key -> same201/data, one audit
@Test void changedBodyAndReorderedItemsReuseKeyIs409() // changed content/order -> IDEMPOTENCY_KEY_REUSED
@Test void putReplacesItemsWithoutKeyAndRequiresVersion() // send PUT key=null; remove/add/order,200/version+1; missing400,stale409
@Test void unknownFieldsIncludingNullAreRejected() // cover_url/lookbook/x_future/status on POST, field = key; no row/key/audit
@Test void missingProductKeepsOriginalItemIndex() // unsorted request second item missing -> items[1].product_id 400
@Test void duplicateAndInvalidItemValuesAre400() // duplicate product, sort missing/null/-1/2147483648/1.5/string, expected_version:null ->400; >1000; tied max sort allowed
@Test void timeOffsetsNormalizeAndEquivalentBoundsAreRejected() // +07:00->Z; end same Instant400, no offset400, null bounds accepted
@Test void activateEmptyUnpublishedOrFutureCollections() // status ACTIVE with []/DRAFT product/future start succeeds
@Test void twoPutsSameVersionHaveOneWinner() // separate threads/barrier, statuses {200,409}, audit/version/items of winner
@Test void auditFailureRollsBackCollectionItemsVersionAndCreateKey() // owner test constraint rejects audit action; 500 safe envelope; before unchanged/no pending key; finally remove constraint
```

- [ ] **Step 2: RED.** Target CollectionAdminIntegrationTest → missing POST/PUT assertion failures;
  only isolated PG owner may alter audit fixture, never local volume data.
- [ ] **Step 3: Implement normalize/create/update.** Collect unknown keys → field exact key; public DTO
  any-setter must work despite global fail-on-unknown=false. Item UUID parse/validate with indexed
  errors; sort JsonNode integral/canConvertToInt/nonnegative, no global coercion change. Version
  parse integral/nonnegative/canConvertToLong; Java null/NullNode missing400 cho sort/version.
  Names/slug validators reuse taxonomy,
  dates OffsetDateTime.parse → Instant, compare normalized instants; bind JDBC OffsetDateTime UTC
  cho timestamptz, không đưa Instant không được driver hỗ trợ trực tiếp vào param. Duplicate IDs and referenced
  products validate before insert at original index. Mutable CreateRequest lacks status/version so
  POST captures even explicit null as unknown; UpdateRequest knows these fields. Never List.of nullable fields.
  PUT commands.update locks collection row, requireVersion, updates parent and replaces association
  rows, calls Task 2 load inside same transaction, audit before commit. Insert audit failure must rollback.
- [ ] **Step 4: GREEN.** Target + module. CAT-01a remains permissive: add regression old product PUT
  unknown status still ignored; no global mapper/advice changes. Dùng support audited để
  xác nhận audit.request_id bằng metadata.request_id của mutation đầu, replay không thêm audit.
- [ ] **Step 5: Mutations one at a time.** Remove collection requireVersion → twoPutsSameVersion FAIL;
  remove @JsonAnySetter → unknownFieldsIncludingNull FAIL; bỏ wrapper commands.update của
  CollectionAdminService.update, giữ các query → auditFailureRollsBack... FAIL. Each restore/module GREEN before next.
- [ ] **Step 6: Docs/contract and commit** `feat(CAT-01): add atomic collection admin mutations`.

### Task 4: Size guide admin GET và locale

**Files:** Create `SizeGuideAdminService.java`, `SizeGuideAdminController.java`,
`SizeGuideReadIntegrationTest.java`; Modify guide read docs/README và examples nếu cần.

**Interfaces:** Service produces `Table(List<String> columns,List<List<String>> rows)` và
`Guide(UUID id,UUID categoryId,String locale,String guidelineHtml,Table tableJson,long version)`;
`void requireLocale(String locale)` → 400 field locale nếu không vi/en,
`Guide load(UUID categoryId,String locale)` → one-row SELECT, absent404 NOT_FOUND/SIZE_GUIDE_NOT_FOUND.
Controller GET `/size-guides/{category_id}/{locale}` auth → service → Api.ok; read không audit,
category INACTIVE không bị lọc. Dùng ObjectMapper parse JSONB vào Table record.

- [ ] **Step 1: Viết tests seed SQL guide/category, không import class mới khi RED.**

```java
@Test void guideReadRequiresWriterAndSupportsViEn() //401/403; vi/en content/version match SQL seed, no audit
@Test void inactiveCategoryGuideIsReadable() // category INACTIVE still200
@Test void missingGuideIs404AndInvalidLocaleIs400() // NOT_FOUND; fr/VI400 field locale; envelope/header
```

- [ ] **Step 2: RED.** Target SizeGuideReadIntegrationTest → route404 instead of expected response.
- [ ] **Step 3: Implement GET interfaces**, no PUT scaffold or empty normalizer yet.
- [ ] **Step 4: GREEN.** Target + module + existing contract validate.
- [ ] **Step 5: Mutation.** Filter category.status='ACTIVE' in read →
  `SizeGuideReadIntegrationTest#inactiveCategoryGuideIsReadable` FAIL; restore/module GREEN.
- [ ] **Step 6: Docs and commit** `feat(CAT-01): add size guide admin reads`.

### Task 5: Size guide PUT, content validation, replay và create race

**Files:** Modify hai SizeGuideAdmin Java file, `AdminCommands.java` (extract key guard),
03/05/06/08/catalog README/contract; Create `SizeGuideAdminIntegrationTest.java`.

**Interfaces:** `Request` mutable DTO: String guidelineHtml, JsonNode tableJson/expectedVersion,
@JsonAnySetter unknownFields. `PutInput(UUID categoryId,String locale,String guidelineHtml,
Table tableJson,Long expectedVersion)` canonical record, fields order deterministic.
`PutInput normalize(UUID categoryId,String locale,Request r)`, `void requireCategory(UUID categoryId)`,
`AdminCommands.Replay put(UUID actor,String key,PutInput input,UUID requestId)`.
Extract existing key check as `AdminCommands.requireKey(String key)`; create still calls it with
identical CAT-01a errors. New PUT invokes it in validate phase **before requireCategory**.
Controller auth → normalize/key validate → service.put (category → commands.create → version guard).
Operation `catalog.size-guide.put`; audit action create/update with guide UUID as resource_id.

- [ ] **Step 1: Viết HTTP regression tests.** Dùng raw JSON cho null/unknown/type cases và cùng
  body/key cho retries; latches start concurrent requests, assertions DB/audit/key bằng PostgreSQL.

```java
@Test void guideCreateThenGetThenUpdateUsesVersionsOneTwo() // 0->201/1; GET version1 ->PUT200/2
@Test void guidePutRequiresCatalogWrite() // no token401/member403, no mutation/audit/key
@Test void keyVersionLocaleAndCategoryValidationOrder() // missing/blank key; missing/explicit null/negative expected_version, missing/explicit null table_json, invalid locale400; invalid body/key+missingcategory400; valid missingcategory404; absentguide positiveversion409
@Test void missingCategoryIs404BeforeCompletedReplay() // isolated owner deletes guide+synthetic category, retains COMPLETED key; retry404 not201
@Test void replayBeforeVersionAndChangedBodyConflict() // retry create after update replays data/status1; new meta/audit unchanged; changed category/locale/version/content409
@Test void independentConcurrentCreatesYieldOne201AndVersionConflict() // keys different: {201,409 VERSION_CONFLICT}, guide1/audit1/key COMPLETED1/no PENDING
@Test void sameKeyConcurrentCreateReplaysSingleEffect() // same key:201/201 same id, audit1
@Test void concurrentUpdatesHaveOneVersionWinner() // different keys, same version ->200/409, no losing key PENDING
@Test void optionalGuidelineAndSanitizedLength() // omitted/null/empty ->""; scripts/events/unsafe links removed; after-sanitize 20000 accepted/20001 rejected
@Test void tableTypesShapeRowsAndBoundaries() // only2keys; columns1..20/rows1..100; wrong width/type/null/headerblank/101/cell101 rejected; stripped empty cell accepted
@Test void rowWidthMustMatchColumns() // columns1/row2 cells ->400, field table_json.rows[0]; neither cell is otherwise invalid
@Test void headersAreUniqueIgnoringCaseWithRootLocale() // [" Size ","size"]400; Locale Turkish in finally-restored test still ROOT; display casing retained
@Test void serializedUtf8BoundaryIsMeasuredAfterNormalization() // compact32768 allowed/32769 rejected; Unicode <32768 chars but >32768 bytes rejected
@Test void unknownGuideFieldsReportKeyName() // x_future:null400 field x_future, no mutation/key/audit
@Test void guideAuditFailureRollsBackWriteAndKeyWithSafe500() // create/update abort audit; guide before unchanged/no PENDING; INTERNAL metadata/header/no SQL/HTML; finally restore test constraint
```

- [ ] **Step 2: RED.** Target SizeGuideAdminIntegrationTest → PUT404 before implementation.
- [ ] **Step 3: Implement input/content.** Strip strings, case-insensitive duplicate via Locale.ROOT,
  preserve display text; validate before immutable lists to avoid null NPE. Normalize Table record,
  count ObjectMapper compact JSON bytes with StandardCharsets.UTF_8; measure HTML after existing
  HtmlSanitizer.clean. Unknown root any-setter/JSON table keys return400, do not drop field or log value.
- [ ] **Step 4: Implement PUT transaction and approved order.** Validate key before category,
  category lookup even replay, commands.create handles normalized hash/replay. Inside work: version0
  INSERT ON CONFLICT(category_id,locale) DO NOTHING RETURNING, empty→VERSION_CONFLICT; version>0
  SELECT guide FOR UPDATE, absent→VERSION_CONFLICT, requireVersion, UPDATE version+1. Record audit,
  return Result(id,201|200,guide), finish key same transaction. No unique-exception recovery/advisory lock.
- [ ] **Step 5: GREEN.** Target + module + contract validate, existing AdminCommands create key behavior PASS.
  Audit request_id phải bằng metadata.request_id lần đầu cho cả create/update; replay không audit.
- [ ] **Step 6: Mutations one at a time.** Move requireCategory after idempotency → missingCategoryIs404BeforeCompletedReplay FAIL;
  use case-sensitive header set → headersAreUniqueIgnoringCase... FAIL; use JSON string.length instead
  of UTF8 bytes → serializedUtf8Boundary... FAIL; bypass HtmlSanitizer → optionalGuideline... FAIL;
  remove guide requireVersion → concurrentUpdates... FAIL; bỏ row-width guard → rowWidthMustMatchColumns FAIL;
  đổi ON CONFLICT DO NOTHING thành DO UPDATE SET guideline_html=EXCLUDED.guideline_html, giữ RETURNING
  → independentConcurrentCreatesYieldOne201AndVersionConflict FAIL (hai 201/audit2).
  Restore/module GREEN after each.
  Create-race test also guards exact code and no aborted idempotency transaction; do not mutate deployed migration.
- [ ] **Step 7: Docs/contract and commit** `feat(CAT-01): add durable size guide PUT with validated content`.

### Task 6: Gateway smoke đầy đủ cho collection/guide

**Files:** Modify `scripts/smoke-local.sh`, `services/catalog-service/README.md`,
`infra/local/README.md`, `contracts/README.md`; Gateway route hiện có không cần route mới.

**Interfaces:** Reuse local synthetic OPS token/category_id/product_id từ CAT-01a smoke **trước unset catalog_access**;
không helper mới trong production hoặc key/token file. Smoke cần record status/body bằng biến process,
assert envelopes/metadata/versions/replay/stale, không in JWT/private key/member access/refresh cookie.

- [ ] **Step 1: Viết smoke assertions.** GET collection list/guide không token401 và member thật403;
  OPS POST collection key→201/version0/cover null, GET detail/items/version, PUT không key→200/version1;
  PUT guide version0/key→201/version1, GET→PUT version1/newkey→200/version2, retry original key→201/data version1;
  stale collection PUT và guide PUT newkey→409 VERSION_CONFLICT. Slug UUID và current smoke product.
- [ ] **Step 2: RED thực chạy** trước rebuild images local còn CAT-01a: smoke FAIL tại route CAT-01b404.
  Nếu image đã có code mới, không tạo lỗi giả: ghi trạng thái precondition và dùng mutation bước4
  để chứng minh assertion phân biệt regression. Không coi lỗi Redis quota/toolchain là RED hợp lệ.
- [ ] **Step 3: Build/upgrade và GREEN.** `bash scripts/local-up.sh`, Docker 6 healthy/V004 áp trên
  volume V003; `bash scripts/smoke-local.sh` PASS, `npx playwright test` PASS baseline storefront.
  Không down -v/reset dữ liệu/role/quota. Dữ liệu UUID smoke tích lũy; ghi synthetic limitations vào README.
- [ ] **Step 4: Mutation script thực chạy.** Đổi synthetic permissions thành `["user.manage"]`
  (không key/claim khác) → smoke FAIL403 trước OPS mutation; restore script và smoke GREEN.
  Đây là HTTP/script mutation, không có Maven Tests run; báo tách biệt với Java mutations.
- [ ] **Step 5: Commit** `test(CAT-01): smoke collection and size guide admin through Gateway`.

### Task 7: Full verification, evidence, handoff và review branch

**Files:** Create `docs/evidence/cat-01b-local-2026-10-07.md`,
`docs/superpowers/plans/2026-10-07-cat-01b-handoff.md`; Modify root/docs README,
docs delivery backlog, plans index, catalog/contracts/local README và nguồn 03/05/06/08
nếu còn drift. Không đổi CAT-01 parent Done; giữ spec approved/plan progress đúng task đã chạy.

**Interfaces:** Evidence liên kết commit/log đã thực chạy, bảng per-module tests (fail/error/skip),
RED/mutations/restores, schema upgrade, smoke/browser và limits. Handoff có lệnh/toolchain thật,
file chính, commits, scope còn lại, CI local/remote phân biệt, không token/credential/PII.

- [ ] **Step 1: Chạy checks cuối một lượt sau restore hết mutations.**

```bash
./mvnw -B test
bash scripts/validate-contracts.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
bash scripts/scan-secrets.sh
git diff --check
```

  Smoke/browser Task 6 chỉ chạy lại nếu thay code/script/runtime sau đó hoặc evidence thiếu.
  Scan staged diff bằng gitleaks pinned như workflow trước commit; synthetic giống secret có
  gitleaks:allow đúng dòng, không dùng allow để bỏ qua secret thật.
- [ ] **Step 2: Tự review coverage spec/plan.** Mọi constraint/task có bằng chứng; 03/05/06/08/schema/
  OpenAPI/README nhất quán. Không tính contract31 baseline là số sau implementation; lấy reports thực.
- [ ] **Step 3: Ghi evidence/handoff**, chạy docs checker/diff check sau edits; không viết test đỏ/mutation
  giả cho tài liệu. Task 1–6 log RED/GREEN/mutations là bằng chứng chức năng.
- [ ] **Step 4: Commit** `docs(CAT-01): record collection and size guide admin evidence and handoff`.
- [ ] **Step 5: Whole-branch review theo superpowers:requesting-code-review**, fixes đúng findings trong scope,
  nếu phải đổi business/contract chưa duyệt hỏi trước; checks lại khi fix yêu cầu. Không tự merge.
- [ ] **Step 6: Dừng sau whole-branch review và bàn giao.** Chỉ commit local;
  push `dev` và mở PR vào `main` chỉ khi chủ dự án yêu cầu riêng (AGENTS.md).
  Chưa chạy remote CI thì không ghi CI PASS. Ghi parent CAT-01 In progress,
  ảnh/publish gate CAT-03/public CAT-02/relay và nghiệm thu còn mở.

## Self-review và execution gate

Coverage: spec schema §5 → Task1; collection/read/§6.1 → Task2/3; guide §4/§6.4–5 → Task4/5;
closed requests/headers/order/one SQL → Task2/3/5; Gateway/syntheticJWT → Task6; evidence/docs/branch → Task7.
Review Focus 1–5 có named tests tại Task3/5. Interface/type/signature giữa task đã đối chiếu;
không có placeholder hoặc algorithm tự chọn trái các chốt đã duyệt.

Chủ dự án đã duyệt Native ngày 2026-10-07 với 5 sửa đổi: không push/PR tự động,
NullNode như thiếu, bỏ test snapshot timing-based (spec một SQL + mutation json_agg Task 2),
lệnh môi trường dùng placeholder, commit docs trước Task 1. Thực thi tuần tự trên dev
bằng superpowers:executing-plans, review cả branch sau Task 7 rồi bàn giao.
