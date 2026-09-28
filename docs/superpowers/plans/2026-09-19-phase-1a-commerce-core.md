# Phase 1A Commerce Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Xây identity/RBAC, catalog/media, inventory reservation, durable cart và storefront/admin cơ bản trên contract thật để chuẩn bị checkout COD.

**Architecture:** Mỗi service sở hữu database, migration, outbox/inbox and authorization. Catalog emits immutable-SKU lifecycle events to Inventory; Cart stores durable guest/member state in PostgreSQL and treats Redis only as cache. Storefront/admin consume Gateway contracts and retain optimistic versions/idempotency keys.

**Tech Stack:** R1 Java/Spring MVC/JdbcClient/Flyway/PostgreSQL/Kafka/Redis stack; React/Vite npm workspace; OpenAPI 3.1/JSON Schema; Testcontainers PostgreSQL/Kafka/Redis; Vitest/Playwright.

**Spec:** `docs/delivery/10_backlog.md` USR-01/02, CAT-01/02/03, INV-01/02/03, CART-01/02/03, WEB-01/02, ADM-01, QA-01; `docs/design/03_interfaces.md`, `05_database_design.md`, `06_service_flows.md`, `14_frontend_behavior.md`.

## Global Constraints

- G0 must be accepted. PO/FE must record the ADR-17 SEO decision before WEB-01; O10 must identify which VI/EN content and images are approved versus synthetic fixtures.
- Use database/credential per service; no cross-DB joins, shared domain entities or public internal routes.
- VND uses integer/bigint with checked arithmetic. Client price, role, user ID and available stock are never authoritative.
- Catalog SKU is immutable after publication. Historical order-facing data will use snapshots; never hard-delete referenced product/variant rows.
- Inventory locks SKU rows in canonical SKU order; reservation is all-or-nothing and maintains `0 <= reserved <= on_hand`.
- Cart persists in PostgreSQL. Redis loss may reduce performance but cannot lose authoritative cart items or permit stale writes.
- All mutations use idempotency scope + canonical request hash and optimistic version where defined; same key/different body returns 409.
- Commit steps require explicit user permission; otherwise hand off verified changes without committing.

## Review Focus

- Registering the same email concurrently with different casing must create one account and reveal no enumeration detail; Task 1 tests U01.
- Catalog publish with missing image, invalid variant or unsafe rich content must fail atomically; Task 2 tests U05/U24.
- Two checkouts competing for one unit or a multi-SKU request with one empty SKU must never oversell/partially reserve; Task 4 tests T01/T02.
- Retried guest-to-member merge must not duplicate items or overwrite newer member edits; Task 5 tests T19/version conflicts.
- Redis flush/outage must preserve cart and stock authority while applying bounded DB fallback; Tasks 4/5 test T25.

---

## File Map

| Path | Responsibility |
|---|---|
| `services/user-service/` | Account, credential/token family, admin roles/auth version and address ownership |
| `services/catalog-service/` | Product/variant/collection/media/price query and admin mutation |
| `services/inventory-service/` | SKU stock ledger and reservation lifecycle |
| `services/cart-service/` | Guest/member cart, version, merge and cleanup |
| `web/storefront/src/auth|catalog|cart/` | SF-01–05 flows and explicit UI states |
| `web/admin/src/catalog|stock|users/` | AD-01/02/07 permission-scoped screens |
| `contracts/openapi/`, `contracts/events/` | Reviewed interface for each slice |
| `tests/integration/commerce-core/`, `tests/e2e/commerce-core.spec.ts` | Race, outage, ownership and browser acceptance |

### Task 1: Implement account, token family and RBAC (`TASK:USR-01`, `TASK:USR-02`; `REQ:USR-01/03/05/06/07/08`)

**Files:**
- Create: `services/user-service/` application, auth/address/admin packages and README
- Create: `services/user-service/src/main/resources/db/migration/V001__users.sql`
- Create: `services/user-service/src/test/java/vn/fashion/user/AuthIntegrationTest.java`
- Create: `contracts/openapi/user.yaml`, `contracts/events/user-events.schema.json`
- Modify: `services/gateway/`, `docs/design/03_interfaces.md`, `docs/design/05_database_design.md`

**Interfaces:**
- Consumes: normalized email/password, refresh cookie, CSRF token and admin service identity.
- Produces: `register`, `login`, `refresh`, `logout`, password-reset intent, profile/address CRUD, admin role/lock; events `USER_AUTH_VERSION_CHANGED` and sanitized reset notification intent.

- [ ] **Step 1: Write concurrency, replay and permission tests**

```java
@Test void concurrentCaseInsensitiveRegistrationCreatesOneAccount() {
  var results = concurrently(() -> register("Person@Example.test"), () -> register("person@example.test"));
  assertThat(results).filteredOn(HttpResult::isCreated).hasSize(1);
  assertThat(countUsersByNormalizedEmail("person@example.test")).isEqualTo(1);
}

@Test void rotatedRefreshTokenReplayRevokesFamily() {
  var first = loginAndReadRefreshCookie();
  var second = refresh(first);
  assertThat(refresh(first).status()).isEqualTo(401);
  assertThat(refresh(second).status()).isEqualTo(401);
}

@Test void opsCannotGrantFinancePermission() {
  callGrantRole(opsToken(), "FINANCE").expectStatus().isForbidden();
  assertThat(auditFor("ROLE_GRANTED")).isEmpty();
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/user-service -Dtest=AuthIntegrationTest test`

Expected: FAIL because service/schema do not exist.

- [ ] **Step 3: Add schema uniqueness and hashed credentials**

```sql
CREATE UNIQUE INDEX users_email_normalized_uq ON users (lower(email));
CREATE TABLE refresh_tokens (
  id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), family_id uuid NOT NULL,
  token_hash bytea NOT NULL UNIQUE, status varchar(16) NOT NULL,
  expires_at timestamptz NOT NULL, rotated_to uuid, created_at timestamptz NOT NULL DEFAULT now()
);
```

Password/refresh/reset values are hashed; raw secrets never enter events/logs. Role changes increment `auth_version` transactionally and write an audit row/outbox event.

- [ ] **Step 4: Implement rotate-on-use and server authorization**

```java
public record AccessClaims(UUID userId, long authVersion, Set<String> permissions) {}

@Transactional
RefreshPair rotate(String rawToken) {
  var current = tokens.lockByHash(hasher.hash(rawToken)).orElseThrow(Unauthorized::new);
  if (!current.isActive(clock.instant())) {
    tokens.revokeFamily(current.familyId());
    throw new Unauthorized();
  }
  return tokens.rotate(current.id(), secrets.newRefreshToken());
}
```

Use secure HttpOnly SameSite cookie, access token in response memory flow and CSRF/origin checks on cookie-authenticated POST.

- [ ] **Step 5: Run U01/U02/U03/T20/T21 subset**

Run: `./mvnw -pl services/user-service,tests/integration/security test`

Expected: one normalized account/default address, refresh replay family revoke, ownership denial, revoked admin denial and redacted logs all PASS.

- [ ] **Step 6: Sync contract/docs and checkpoint**

Validate contracts and write service README. If commit permission exists:

```bash
git add services/user-service services/gateway contracts docs/design/03_interfaces.md docs/design/05_database_design.md
git commit -m "feat(USR-01): add account authentication and RBAC"
```

### Task 2: Complete catalog, collections and safe media (`TASK:CAT-01`, `TASK:CAT-02`, `TASK:CAT-03`; `REQ:CAT-01–06/09/11`)

**Files:**
- Create/modify: `services/catalog-service/src/main/java/vn/fashion/catalog/product/`, `services/catalog-service/src/main/java/vn/fashion/catalog/collection/`, `services/catalog-service/src/main/java/vn/fashion/catalog/media/`, `services/catalog-service/src/main/java/vn/fashion/catalog/price/`
- Create: `services/catalog-service/src/main/resources/db/migration/V002__variants_and_collections.sql`, `services/catalog-service/src/main/resources/db/migration/V003__media.sql`
- Create: `services/catalog-service/src/test/java/vn/fashion/catalog/CatalogAdminIntegrationTest.java`
- Create: `contracts/events/variant-created.schema.json`
- Modify: `contracts/openapi/catalog.yaml`, `docs/design/03_interfaces.md`, `docs/design/05_database_design.md`

**Interfaces:**
- Consumes: OPS actor, expected version, scoped upload reference and VI/EN content.
- Produces: cursor list/detail/search, admin CRUD/publish, collection/size guide/media attach, price query; `VARIANT_CREATED` with immutable SKU and dimensions/weight.

- [ ] **Step 1: Write publish/version/media tests**

```java
@Test void publishRequiresImageAndSellableVariant() {
  var draft = createDraftWithoutImage();
  publish(draft.id(), draft.version()).expectStatus().isBadRequest();
  assertThat(productStatus(draft.id())).isEqualTo("DRAFT");
}

@Test void staleAdminMutationReturnsConflict() {
  updateProduct(PRODUCT_ID, 3, "Tên A").expectStatus().isOk();
  updateProduct(PRODUCT_ID, 3, "Tên B").expectStatus().isEqualTo(409);
}

@Test void fakeImageContentIsRejectedBeforePublicAttach() {
  attach(bytes("<script>"), "image/png").expectStatus().isBadRequest();
  assertThat(publicObjects()).isEmpty();
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/catalog-service -Dtest=CatalogAdminIntegrationTest test`

Expected: FAIL for absent migrations/use cases.

- [ ] **Step 3: Add constraints and outbox emission**

Variants require unique immutable SKU, non-negative VND list price, positive weight where shipping requires it and explicit status. Publish locks product/version, validates content/media/active variant, changes status and inserts `VARIANT_CREATED` outbox in one transaction.

```java
@Transactional
Product publish(UUID id, long expectedVersion) {
  var product = products.lock(id, expectedVersion);
  publishPolicy.validate(product, variants.find(id), media.find(id));
  variants.newlyPublished(id).forEach(v -> outbox.append(VariantCreated.from(v)));
  return products.markActive(id, expectedVersion);
}
```

- [ ] **Step 4: Implement stable cursor and snapshot-friendly reads**

Cursor signs/encodes `(created_at,id,filters_hash)`; changing filters rejects the cursor. Public query returns ACTIVE only, effective integer price, media alt, availability as observation rather than promise.

- [ ] **Step 5: Run U05/U06/U24 and contract tests**

Run: `./mvnw -pl services/catalog-service test && bash scripts/validate-contracts.sh`

Expected: publish/media/version/cursor/SKU immutability and duplicate event tests PASS.

- [ ] **Step 6: Update README/docs and checkpoint**

If commit permission exists:

```bash
git add services/catalog-service contracts docs/design/03_interfaces.md docs/design/05_database_design.md
git commit -m "feat(CAT-01): complete catalog and safe media workflows"
```

### Task 3: Add stock authority and immutable ledger (`TASK:INV-01`; `REQ:INV-01/08`)

**Files:**
- Create: `services/inventory-service/` and README
- Create: `services/inventory-service/src/main/resources/db/migration/V001__stock_and_ledger.sql`
- Create: `services/inventory-service/src/test/java/vn/fashion/inventory/StockAdjustmentIntegrationTest.java`, `services/inventory-service/src/test/java/vn/fashion/inventory/VariantCreatedConsumerTest.java`
- Create: `contracts/openapi/inventory.yaml`, `contracts/events/stock-events.schema.json`
- Modify: `docs/design/05_database_design.md`

**Interfaces:**
- Consumes: `VARIANT_CREATED`, OPS import/adjust with operation key/reason.
- Produces: stock query/import/adjust/ledger; idempotent SKU registration; no reservation API until Task 4.

- [ ] **Step 1: Write adjustment/import/event tests**

```java
@Test void adjustmentCannotMoveOnHandBelowReserved() {
  seedStock("SKU-1", 5, 4);
  adjust("SKU-1", -2, "damage", "op-1").expectStatus().isEqualTo(409);
  assertStock("SKU-1", 5, 4);
}

@Test void invalidCsvRollsBackEveryRow() {
  importCsv("SKU-1,5\nUNKNOWN,4\n", "import-1").expectStatus().isBadRequest();
  assertLedgerEmpty("import-1");
}

@Test void duplicateVariantEventDoesNotResetStock() {
  consumeVariantCreatedTwice();
  assertThat(stockRows("SKU-1")).hasSize(1);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/inventory-service test`

Expected: FAIL before schema/service exists.

- [ ] **Step 3: Implement constrained counters and append-only ledger**

```sql
CREATE TABLE stock (
  sku varchar(96) PRIMARY KEY, on_hand bigint NOT NULL CHECK (on_hand >= 0),
  reserved bigint NOT NULL CHECK (reserved >= 0 AND reserved <= on_hand), version bigint NOT NULL DEFAULT 0
);
CREATE TABLE stock_ledger (
  id uuid PRIMARY KEY, sku varchar(96) NOT NULL REFERENCES stock(sku), operation_key varchar(128) NOT NULL,
  kind varchar(24) NOT NULL, quantity bigint NOT NULL, reason varchar(255) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (sku,operation_key,kind)
);
```

- [ ] **Step 4: Run DB authority tests with Redis disabled**

Run: `REDIS_ENABLED=false ./mvnw -pl services/inventory-service test`

Expected: stock/event/import behavior remains correct and all ledger effects occur once.

- [ ] **Step 5: Sync docs and checkpoint**

If commit permission exists:

```bash
git add services/inventory-service contracts docs/design/05_database_design.md
git commit -m "feat(INV-01): add stock authority and ledger"
```

### Task 4: Implement reservation/commit/release/return (`TASK:INV-02`, `TASK:INV-03`; `REQ:INV-02/03/06/07`)

**Files:**
- Create: `services/inventory-service/src/main/resources/db/migration/V002__reservations.sql`, `services/inventory-service/src/main/java/vn/fashion/inventory/reservation/ReservationService.java`, `services/inventory-service/src/main/java/vn/fashion/inventory/reservation/ReservationRepository.java`
- Create: `services/inventory-service/src/test/java/vn/fashion/inventory/reservation/InventoryRaceIntegrationTest.java`, `services/inventory-service/src/test/java/vn/fashion/inventory/reservation/ReservationExpiryIntegrationTest.java`
- Modify: `contracts/openapi/inventory.yaml`, `contracts/events/stock-events.schema.json`, `docs/design/06_service_flows.md`

**Interfaces:**
- Consumes: internal commands with `reservation_id`, sorted lines, request hash, deadline and operation key.
- Produces: `reserve`, `commit`, `release`, `returnStock`, `getReservation`; events with stable aggregate sequence.

- [ ] **Step 1: Write T01–T05/T09/T17 tests**

```java
@Test void oneUnitHasExactlyOneWinningReservation() {
  seedStock("HOT", 1, 0);
  var results = concurrently(() -> reserve("r1", line("HOT",1)), () -> reserve("r2", line("HOT",1)));
  assertThat(results).filteredOn(ReserveResult::accepted).hasSize(1);
  assertStock("HOT", 1, 1);
}

@Test void multiSkuReservationIsAllOrNothing() {
  seedStock("A", 3, 0); seedStock("B", 0, 0);
  reserve("r3", line("A",2), line("B",1)).expectRejected();
  assertStock("A", 3, 0); assertStock("B", 0, 0);
}

@Test void releaseBeforeReserveCreatesTombstone() {
  release("future", "close-1");
  reserve("future", line("A",1)).expectClosed();
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/inventory-service -Dtest='*RaceIntegrationTest,*ExpiryIntegrationTest' test`

Expected: FAIL until reservation schema/handlers exist.

- [ ] **Step 3: Implement canonical locking and state guards**

```java
@Transactional
ReserveResult reserve(ReserveCommand command) {
  var lines = command.lines().stream().sorted(comparing(ReserveLine::sku)).toList();
  var existing = reservations.lock(command.reservationId());
  if (existing.isPresent()) return existing.get().sameHashOrConflict(command.requestHash());
  var stocks = stock.lockAll(lines.stream().map(ReserveLine::sku).toList());
  if (!policy.available(stocks, lines)) return reservations.reject(command);
  stock.incrementReserved(lines);
  return reservations.activate(command);
}
```

Commit decreases both `on_hand` and `reserved`; release decreases only `reserved`; return increases `on_hand` with evidence. Expiry locks ACTIVE reservation and cannot beat a concurrent valid commit.

- [ ] **Step 4: Run races repeatedly on PostgreSQL 17.11**

Run: `./mvnw -pl services/inventory-service -Dtest='*RaceIntegrationTest,*ExpiryIntegrationTest' -Dsurefire.rerunFailingTestsCount=0 test`

Expected: PASS with invariant query returning zero violations after each repeated case; H2/mock results are not accepted.

- [ ] **Step 5: Sync contract/flow and checkpoint**

If commit permission exists:

```bash
git add services/inventory-service contracts docs/design/06_service_flows.md
git commit -m "feat(INV-02): implement race-safe inventory reservations"
```

### Task 5: Implement durable guest/member cart (`TASK:CART-01`, `TASK:CART-02`, `TASK:CART-03`; `REQ:CART-01–06`)

**Files:**
- Create: `services/cart-service/` and README
- Create: `services/cart-service/src/main/resources/db/migration/V001__carts.sql`
- Create: `services/cart-service/src/test/java/vn/fashion/cart/CartVersionIntegrationTest.java`, `services/cart-service/src/test/java/vn/fashion/cart/CartMergeIntegrationTest.java`, `services/cart-service/src/test/java/vn/fashion/cart/CartCleanupIntegrationTest.java`
- Create: `contracts/openapi/cart.yaml`
- Modify: `docs/design/03_interfaces.md`, `docs/design/05_database_design.md`, `docs/design/06_service_flows.md`

**Interfaces:**
- Consumes: guest credential hash or authenticated user, catalog quote read, expected cart version.
- Produces: cart CRUD, merge, snapshot/cleanup and fee-preview input; guest credential only in secure cookie.

- [ ] **Step 1: Write version/merge/cleanup/outage tests**

```java
@Test void staleQuantityUpdateReturnsCurrentVersionConflict() {
  updateQty(CART, ITEM, 2, 4).expectStatus().isOk();
  updateQty(CART, ITEM, 3, 4).expectStatus().isEqualTo(409);
  assertQuantity(CART, ITEM, 2);
}

@Test void retriedMergeIsIdempotentAndKeepsNewerMemberEdit() {
  var key = "merge-1";
  merge(GUEST, MEMBER, key); updateQty(MEMBER, ITEM, 5, currentVersion()); merge(GUEST, MEMBER, key);
  assertQuantity(MEMBER, ITEM, 5);
}

@Test void cleanupDoesNotDeleteItemChangedAfterSnapshot() {
  var snapshot = snapshot(CART); updateQty(CART, ITEM, 4, snapshot.version()); cleanup(snapshot);
  assertQuantity(CART, ITEM, 4);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/cart-service test`

Expected: FAIL before service/schema exists.

- [ ] **Step 3: Implement ownership, optimistic version and canonical merge**

```java
@Transactional
Cart updateQuantity(CartOwner owner, UUID itemId, long quantity, long expectedVersion) {
  if (quantity < 1 || quantity > 99) throw new InvalidQuantity();
  int changed = carts.updateItem(owner, itemId, quantity, expectedVersion);
  if (changed == 0) throw new VersionConflict(carts.current(owner));
  return carts.current(owner);
}
```

Merge records operation key/hash before combining SKU quantities with a documented cap; cleanup compares snapshot item versions. Redis caches reads only and uses environment/service prefixes.

- [ ] **Step 4: Run T18/T19/T21/T25/U10**

Run: `./mvnw -pl services/cart-service test && REDIS_URL=unreachable ./mvnw -pl services/cart-service -Dtest=CartOutageIntegrationTest test`

Expected: durable cart, ownership, retry and bounded DB fallback tests PASS.

- [ ] **Step 5: Sync docs and checkpoint**

If commit permission exists:

```bash
git add services/cart-service contracts docs/design/03_interfaces.md docs/design/05_database_design.md docs/design/06_service_flows.md
git commit -m "feat(CART-01): add durable versioned carts"
```

### Task 6: Deliver storefront and permission-scoped admin (`TASK:WEB-01`, `TASK:WEB-02`, `TASK:ADM-01`)

**Files:**
- Modify/create: `web/storefront/src/auth/`, `web/storefront/src/catalog/`, `web/storefront/src/cart/`, `web/storefront/src/i18n/`
- Create: `web/admin/package.json`, `web/admin/src/catalog/`, `web/admin/src/stock/`, `web/admin/src/users/`
- Create: `tests/e2e/commerce-core.spec.ts`
- Modify: `package.json`, `package-lock.json`, `docs/design/14_frontend_behavior.md`

**Interfaces:**
- Consumes: generated/validated user/catalog/cart/inventory API types and `allowed_actions`/permissions.
- Produces: SF-01–05 and AD-01/02/07 with loading/empty/error/stale/forbidden states, VI/EN and responsive keyboard navigation.

- [ ] **Step 1: Write browser acceptance first**

```ts
test('guest cart survives login merge and a stale tab refreshes', async ({browser}) => {
  const first = await browser.newPage();
  await first.goto('/products/red-shirt');
  await first.getByRole('button', {name:'Thêm vào giỏ'}).click();
  const second = await browser.newPage();
  await second.goto('/cart');
  await first.getByLabel('Số lượng').fill('2');
  await second.getByLabel('Số lượng').fill('3');
  await expect(second.getByText('Giỏ hàng đã thay đổi')).toBeVisible();
});

test('ops cannot see or call finance actions', async ({page}) => {
  await loginAs(page, 'OPS');
  await page.goto('/admin');
  await expect(page.getByRole('link', {name:'Hoàn tiền'})).toHaveCount(0);
  expect((await page.request.post('/admin/api/v1/payments/x/refunds')).status()).toBe(403);
});
```

- [ ] **Step 2: Run and verify failure**

Run: `npx playwright test tests/e2e/commerce-core.spec.ts`

Expected: FAIL because routes/components/admin workspace do not exist.

- [ ] **Step 3: Implement route-owned components and session single-flight**

```ts
let refreshInFlight: Promise<AccessToken> | undefined;
export function refreshOnce(): Promise<AccessToken> {
  refreshInFlight ??= refresh().finally(() => { refreshInFlight = undefined; });
  return refreshInFlight;
}
```

Keep access token in memory; cookie carries refresh only. UI hides disallowed action but always renders 403/409 from server honestly. Product/cart pages implement loading/empty/error/retry and 360px no-horizontal-scroll acceptance.

- [ ] **Step 4: Run component/accessibility/browser checks**

Run: `npm run typecheck && npm test && npx playwright test tests/e2e/commerce-core.spec.ts`

Expected: U03/U05/U07/U10 paths PASS in VI and EN; deep-link reload remains valid.

- [ ] **Step 5: Sync frontend behavior and checkpoint**

If commit permission exists:

```bash
git add package.json package-lock.json web tests/e2e/commerce-core.spec.ts docs/design/14_frontend_behavior.md
git commit -m "feat(WEB-01): integrate commerce core storefront and admin"
```

### Task 7: Build commerce-core integration evidence (`TASK:QA-01`)

**Files:**
- Create: `tests/integration/commerce-core/`, `tests/fixtures/commerce-core/`
- Create: `scripts/test-commerce-core.sh`, `docs/evidence/commerce-core-template.md`
- Modify: `.github/workflows/application-ci.yml`, service READMEs, `docs/quality/11_test_strategy.md`

**Interfaces:**
- Consumes: pinned PostgreSQL/Kafka/Redis containers, injectable clock, synthetic users/SKUs.
- Produces: repeatable report for T01–T05/T09/T18/T19/T21/T24/T25 and U01/U03/U05/U06/U07/U10/U14.

- [ ] **Step 1: Add the invariant query to the harness**

```sql
SELECT sku, on_hand, reserved FROM stock
WHERE on_hand < 0 OR reserved < 0 OR reserved > on_hand;
```

The test fails if any row is returned and records reservation/ledger/outbox rows, not only HTTP status.

- [ ] **Step 2: Add deterministic fault/clock controls**

Expose test-only controls only inside the integration harness network: pause after local commit, stop Kafka, flush Redis and advance application clock. Production profiles must fail startup if test controls are enabled.

- [ ] **Step 3: Run the integrated suite**

Run: `bash scripts/test-commerce-core.sh`

Expected: all named tests PASS on PostgreSQL 17.11/Kafka 4.1.2/Redis 8.2.9; container digests and seed are recorded.

- [ ] **Step 4: Run full CI and docs checks**

```bash
./mvnw test
npm ci && npm run typecheck && npm test && npm run build
bash scripts/validate-contracts.sh
npx playwright test tests/e2e/commerce-core.spec.ts
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
git diff --check
```

Expected: all PASS.

- [ ] **Step 5: Review checkpoint**

Record build/commit, environment, tests, invariant outputs, defects and reviewer. This checkpoint does not claim checkout/G1. If commit permission exists:

```bash
git add tests scripts/test-commerce-core.sh docs/evidence/commerce-core-template.md .github/workflows/application-ci.yml docs/quality/11_test_strategy.md services/*/README.md
git commit -m "test(QA-01): prove integrated commerce core"
```

## Phase 1A Exit Gate

- Identity/RBAC, catalog/media, stock/reservations and cart run through reviewed executable contracts.
- PostgreSQL race/invariant, Kafka duplicate, Redis outage and ownership tests pass with synthetic data.
- Storefront SF-01–05 and admin AD-01/02/07 pass responsive/keyboard/VI/EN/error-state evidence.
- Every service README has actual commands/config/migrations/topics/health/limits.
- Checkout, payment, shipping, notification, G1 and G2 remain unclaimed.
