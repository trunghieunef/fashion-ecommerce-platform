# Sprint 1 Local Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hoàn thành checkpoint S1-local: fresh clone chạy storefront shell → Gateway → catalog service mẫu → PostgreSQL, có migration, contract, security guard và bằng chứng compatibility R1.

**Architecture:** Chỉ tạo một business service thật là `catalog-service`, một Gateway WebFlux và một storefront Vite. PostgreSQL là dependency durable; Nacos được bật trong compatibility profile để kiểm tra config/discovery nhưng smoke mặc định dùng route URI cấu hình tường minh nhằm cô lập lỗi. Không tạo AWS, Kubernetes, provider adapter hoặc các service MVP còn lại.

**Tech Stack:** Temurin 21.0.12.1+1, Maven 3.9.16 Wrapper, Spring Boot 4.0.8, Spring Cloud 2025.1.3, Spring Cloud Alibaba 2025.1.0.0, Gateway 5.0.3, Nacos server 3.2.4/client 3.1.1, PostgreSQL 17.11, Node 24.21.0, npm 11.19.0, React 19.2.8, TypeScript 6.0.3, Vite 8.3.0, Vitest 5.0.1, Playwright 1.63.0, Docker Engine 29.8.1/Compose 5.5.1.

**Spec:** `docs/delivery/09_delivery_plan.md` § Sprint 1/S1-local; `docs/delivery/10_backlog.md` PLT-01, PLT-02 core, SEC-01 core; ADR-16/17/18 in `docs/design/08_decisions.md`.

## Global Constraints

- Sprint 1 is local-only: do not create or call AWS, K3s, ECR, ArgoCD, VNPay, MoMo, email or production resources.
- Use one root Maven BOM/aggregator, Maven Wrapper 3.9.16, one root npm workspace and one `package-lock.json`; do not add another package manager lockfile.
- Business services use MVC + JdbcClient; Gateway alone uses WebFlux. Do not add JPA, OpenFeign, saga frameworks, Tailwind, Redux or Axios.
- PostgreSQL uses a migration credential and a separate runtime credential; the runtime credential cannot create or alter schema.
- Never trust browser-supplied identity/role headers; Gateway strips them and `/internal/**` is not publicly routed.
- Every changed implementation/config file must update its owning README or design document in the same task.
- A completed plan proves S1-local only; it does not prove G0, G1, G2 or MVP completion.
- Commit steps require explicit user authorization under repository policy; without it, preserve the tested diff and report the proposed commit message.

## Review Focus

- A clean machine with only the documented JDK/Node/container prerequisites must complete bootstrap without globally installed Maven; Task 1 pins this with wrapper and version tests.
- A database that is reachable but has not been migrated must fail readiness rather than return an empty catalog; Task 2 tests this.
- Browser-supplied `X-User-Id` and `X-User-Roles` must never reach the service; Task 3 tests header stripping.
- Reloading `/products` must return the SPA while `/api/not-found` remains a real 404; Task 4 tests fallback separation.
- Nacos unavailable in the default smoke profile must not hide a broken Gateway-to-service path, while the compatibility profile must prove reconnect; Task 5 runs both profiles.

---

## File Map

| Path | Responsibility |
|---|---|
| `pom.xml`, `.mvn/wrapper/*`, `mvnw`, `mvnw.cmd` | Backend BOM, modules and reproducible Maven entry point |
| `services/catalog-service/` | One sample business service, catalog query and Flyway migration |
| `services/gateway/` | Public route, header stripping and local upstream routing |
| `package.json`, `package-lock.json`, `web/storefront/` | One npm workspace and browser shell |
| `contracts/openapi/catalog.yaml` | Executable contract for the one implemented public endpoint |
| `infra/local/compose.yaml`, `infra/local/.env.example` | PostgreSQL and Nacos local dependencies with safe synthetic values |
| `tests/e2e/s1-local.spec.ts`, `scripts/smoke-local.sh` | Browser and command-line proof of the complete path |
| `.github/workflows/application-ci.yml` | Build/test contract for Sprint 1 code |
| `docs/engineering/12_engineering_guide.md`, `docs/engineering/17_tech_stack.md` | Actual commands and compatibility evidence |

### Task 1: Bootstrap reproducible workspaces (`TASK:PLT-01`, `REQ:XCT-06`)

**Files:**
- Create: `pom.xml`, `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, `mvnw.cmd`
- Create: `package.json`, `web/storefront/package.json`, `web/storefront/tsconfig.json`, `web/storefront/vite.config.ts`
- Create: `.tool-versions`, `.gitignore`, `scripts/verify-toolchain.sh`
- Modify: `docs/engineering/17_tech_stack.md`

**Interfaces:**
- Consumes: exact R1 versions from `docs/engineering/17_tech_stack.md`.
- Produces: commands `./mvnw`, `npm ci`; module names `services/catalog-service`, `services/gateway`; npm workspace `@fashion/storefront`.

- [ ] **Step 1: Write the failing toolchain check**

```bash
#!/usr/bin/env bash
set -euo pipefail
./mvnw -version | grep -F 'Apache Maven 3.9.16'
test "$(node --version)" = "v24.21.0"
test "$(npm --version)" = "11.19.0"
./mvnw -q help:effective-pom -Doutput=target/effective-pom.xml
npm ci --ignore-scripts
npm ls --all
```

- [ ] **Step 2: Run it and verify the expected failure**

Run: `bash scripts/verify-toolchain.sh`

Expected: FAIL because `mvnw`, root `pom.xml` and npm lockfile do not exist.

- [ ] **Step 3: Add the Maven aggregator and wrapper**

Use this dependency-management core in `pom.xml`; add module entries when Tasks 2 and 3 create their directories:

```xml
<properties>
  <java.version>21</java.version>
  <spring-boot.version>4.0.8</spring-boot.version>
  <spring-cloud.version>2025.1.3</spring-cloud.version>
  <spring-cloud-alibaba.version>2025.1.0.0</spring-cloud-alibaba.version>
</properties>
<dependencyManagement><dependencies>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>${spring-boot.version}</version><type>pom</type><scope>import</scope></dependency>
  <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-dependencies</artifactId><version>${spring-cloud.version}</version><type>pom</type><scope>import</scope></dependency>
  <dependency><groupId>com.alibaba.cloud</groupId><artifactId>spring-cloud-alibaba-dependencies</artifactId><version>${spring-cloud-alibaba.version}</version><type>pom</type><scope>import</scope></dependency>
</dependencies></dependencyManagement>
```

Generate Maven Wrapper for 3.9.16, retain its checksum-bearing properties, and make `mvnw` executable.

- [ ] **Step 4: Add the root npm workspace**

```json
{
  "name": "fashion-ecommerce-platform",
  "private": true,
  "packageManager": "npm@11.19.0",
  "workspaces": ["web/storefront"],
  "scripts": {
    "build": "npm run build --workspaces --if-present",
    "test": "npm run test --workspaces --if-present",
    "typecheck": "npm run typecheck --workspaces --if-present"
  }
}
```

Pin direct dependencies in `web/storefront/package.json` without caret ranges: React/react-dom 19.2.8, React Router 8.4.0, Vite 8.3.0, plugin-react 6.1.1, TypeScript 6.0.3, Vitest 5.0.1 and `@playwright/test` 1.63.0. Generate the root lock with `npm install --package-lock-only`; never use `--force` or `--legacy-peer-deps`.

- [ ] **Step 5: Run and record dependency evidence**

Run: `bash scripts/verify-toolchain.sh && ./mvnw -q dependency:tree -DoutputFile=target/dependency-tree.txt`

Expected: PASS; effective POM, dependency tree and root lock exist; `rg --files -g 'yarn.lock' -g 'pnpm-lock.yaml'` returns no path.

- [ ] **Step 6: Synchronize docs and checkpoint**

Update `docs/engineering/17_tech_stack.md` §7 with OS/CPU, commands, resolved versions, date and reviewer field; do not mark runtime compatibility complete. Run `python3 -B scripts/check_docs.py`.

If commit permission exists:

```bash
git add pom.xml .mvn mvnw mvnw.cmd package.json package-lock.json web/storefront .tool-versions .gitignore scripts/verify-toolchain.sh docs/engineering/17_tech_stack.md
git commit -m "build(PLT-01): bootstrap pinned local toolchains"
```

### Task 2: Build the PostgreSQL-backed catalog sample (`TASK:PLT-01`, partial `TASK:CAT-01`)

**Files:**
- Create: `services/catalog-service/pom.xml`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/CatalogApplication.java`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/product/ProductQueryController.java`
- Create: `services/catalog-service/src/main/java/vn/fashion/catalog/product/ProductQueryRepository.java`
- Create: `services/catalog-service/src/main/resources/application.yaml`
- Create: `services/catalog-service/src/main/resources/db/migration/V001__catalog_baseline.sql`
- Create: `services/catalog-service/src/test/java/vn/fashion/catalog/product/ProductQueryIntegrationTest.java`
- Create: `services/catalog-service/README.md`, `infra/local/compose.yaml`, `infra/local/.env.example`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: `CATALOG_DB_*` for runtime and `CATALOG_MIGRATION_DB_*` for Flyway.
- Produces: `GET /api/v1/catalog/products?limit=1` with the standard envelope and Actuator liveness/readiness.

- [ ] **Step 1: Write the failing PostgreSQL integration tests**

```java
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductQueryIntegrationTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");

  @Test void returnsOnlyActiveProducts() {
    jdbc.update("insert into products(id,slug,status,name_vi,name_en,version) values (gen_random_uuid(),'hidden','DRAFT','Ẩn','Hidden',0)");
    var response = rest.get().uri("/api/v1/catalog/products?limit=1").retrieve().body(ProductPageResponse.class);
    assertThat(response.data().items()).isEmpty();
    assertThat(response.data().nextCursor()).isNull();
  }

  @Test void runtimeRoleCannotCreateTables() {
    assertThatThrownBy(() -> jdbc.execute("create table forbidden(id bigint)"))
        .hasMessageContaining("permission denied");
  }
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/catalog-service -Dtest=ProductQueryIntegrationTest test`

Expected: FAIL because the module/application/migration do not exist.

- [ ] **Step 3: Add the minimal catalog schema**

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE products (
  id uuid PRIMARY KEY,
  slug varchar(160) NOT NULL UNIQUE,
  status varchar(16) NOT NULL CHECK (status IN ('DRAFT','ACTIVE','INACTIVE')),
  name_vi varchar(255) NOT NULL,
  name_en varchar(255) NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX products_public_order_idx ON products(created_at DESC, id DESC) WHERE status = 'ACTIVE';
```

Compose creates separate migration/runtime roles with a mounted idempotent init script. Do not grant runtime `CREATE` on the schema.

- [ ] **Step 4: Implement the bounded query**

```java
public record ProductSummary(UUID id, String slug, String nameVi, String nameEn) {}
public record ProductPage(List<ProductSummary> items, String nextCursor) {}

ProductPage findActive(int limit) {
  if (limit < 1 || limit > 100) throw new ResponseStatusException(BAD_REQUEST, "INVALID_LIMIT");
  var items = jdbc.sql("select id,slug,name_vi,name_en from products where status='ACTIVE' order by created_at desc,id desc limit :limit")
      .param("limit", limit).query(ProductSummary.class).list();
  return new ProductPage(items, null);
}
```

Readiness includes DB and migration state; liveness does not restart-loop the JVM during a temporary DB outage.

- [ ] **Step 5: Prove migration, query and readiness**

Run: `docker compose --env-file infra/local/.env.example -f infra/local/compose.yaml up -d postgres && ./mvnw -pl services/catalog-service test`

Expected: PASS. Against an empty/unmigrated database, `curl -fsS localhost:8081/actuator/health/readiness` must return non-zero/HTTP 503.

- [ ] **Step 6: Document and checkpoint**

Write `services/catalog-service/README.md` with responsibility, actual commands, config, migration/runtime roles, topics (`none` in S1), health, metrics and limitations. If commit permission exists:

```bash
git add pom.xml services/catalog-service infra/local
git commit -m "feat(PLT-01): add PostgreSQL catalog sample service"
```

### Task 3: Add Gateway trust boundaries (`TASK:SEC-01`, `REQ:USR-07`, `REQ:XCT-03`)

**Files:**
- Create: `services/gateway/pom.xml`
- Create: `services/gateway/src/main/java/vn/fashion/gateway/GatewayApplication.java`
- Create: `services/gateway/src/main/java/vn/fashion/gateway/security/ClientIdentityHeaderFilter.java`
- Create: `services/gateway/src/main/resources/application.yaml`
- Create: `services/gateway/src/test/java/vn/fashion/gateway/security/GatewayBoundaryTest.java`
- Create: `services/gateway/README.md`
- Modify: `pom.xml`, `infra/local/compose.yaml`, `docs/operations/13_operations_security.md`

**Interfaces:**
- Consumes: `CATALOG_BASE_URL`; optional Nacos import/discovery in profile `nacos-compat`.
- Produces: route `/api/v1/catalog/**`; no `/internal/**` route; strips `X-User-Id`, `X-User-Roles`, `X-Actor-Id`, `X-Service-Name`.

- [ ] **Step 1: Write failing boundary tests**

```java
@Test void removesBrowserIdentityHeaders() {
  client.get().uri("/api/v1/catalog/products?limit=1")
      .header("X-User-Id", "attacker").header("X-User-Roles", "SUPER_ADMIN")
      .exchange().expectStatus().isOk();
  assertThat(upstream.takeRequest().getHeader("X-User-Id")).isNull();
}

@Test void neverRoutesInternalPath() {
  client.get().uri("/internal/api/v1/platform/ping").exchange().expectStatus().isNotFound();
  assertThat(upstream.getRequestCount()).isZero();
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl services/gateway -Dtest=GatewayBoundaryTest test`

Expected: FAIL because the Gateway/filter do not exist.

- [ ] **Step 3: Implement highest-precedence header stripping**

```java
private static final Set<String> UNTRUSTED = Set.of("X-User-Id", "X-User-Roles", "X-Actor-Id", "X-Service-Name");
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
  var request = exchange.getRequest().mutate().headers(h -> UNTRUSTED.forEach(h::remove)).build();
  return chain.filter(exchange.mutate().request(request).build());
}
```

Configure only the catalog public path. Do not add a catch-all route.

- [ ] **Step 4: Run focused and aggregate tests**

Run: `./mvnw -pl services/gateway test && ./mvnw test`

Expected: PASS; `rg 'spring-boot-starter-webmvc' services/gateway` returns no match.

- [ ] **Step 5: Document and checkpoint**

Document routes, timeout and trust boundary; sync implemented controls in 13. If commit permission exists:

```bash
git add pom.xml services/gateway infra/local/compose.yaml docs/operations/13_operations_security.md
git commit -m "feat(SEC-01): enforce local gateway trust boundary"
```

### Task 4: Build the storefront shell (`TASK:PLT-01`, `REQ:XCT-01`, `REQ:XCT-05`)

**Files:**
- Create: `web/storefront/src/main.tsx`, `web/storefront/src/app/App.tsx`
- Create: `web/storefront/src/catalog/CatalogPage.tsx`, `web/storefront/src/catalog/catalogClient.ts`
- Create: `web/storefront/src/catalog/CatalogPage.test.tsx`, `web/storefront/src/app/styles.css`
- Create: `web/storefront/index.html`, `playwright.config.ts`, `tests/e2e/s1-local.spec.ts`
- Create: `web/storefront/README.md`

**Interfaces:**
- Consumes: `GET /api/v1/catalog/products?limit=1` through same-origin Gateway.
- Produces: routes `/` and `/products` with loading, empty, error and retry states.

- [ ] **Step 1: Write failing component tests**

```tsx
import {act} from 'react';
import {createRoot} from 'react-dom/client';
import {vi} from 'vitest';

it('shows empty state after an empty response', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({code:'OK',data:{items:[],next_cursor:null},metadata:{request_id:'req-1',trace_id:'trace-1'}}), {status:200})));
  const container = document.createElement('div');
  await act(async () => createRoot(container).render(<CatalogPage />));
  expect(container.textContent).toContain('Chưa có sản phẩm');
});

it('offers retry after a network error', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')));
  const container = document.createElement('div');
  await act(async () => createRoot(container).render(<CatalogPage />));
  expect(container.querySelector('button')?.textContent).toBe('Thử lại');
});
```

- [ ] **Step 2: Run and verify failure**

Run: `npm test --workspace @fashion/storefront -- --run`

Expected: FAIL because components and test setup do not exist.

- [ ] **Step 3: Implement typed fetch and explicit states**

```ts
export type ProductPage = {code:'OK';data:{items:Array<{id:string;slug:string;name_vi:string;name_en:string}>;next_cursor:string|null};metadata:{request_id:string;trace_id:string}};
export async function getProducts(signal: AbortSignal): Promise<ProductPage> {
  const response = await fetch('/api/v1/catalog/products?limit=1', {signal});
  if (!response.ok) throw new Error(`catalog:${response.status}`);
  return response.json() as Promise<ProductPage>;
}
```

Use the discriminated state `idle | loading | ready | error`; retry invokes the same GET and has visible focus. Persist no token, amount or secret.

- [ ] **Step 4: Test deep-link and API boundaries**

```ts
test('deep link works while API misses stay 404', async ({page, request}) => {
  await page.goto('/products');
  await expect(page.getByRole('heading', {name:'Sản phẩm'})).toBeVisible();
  expect((await request.get('/api/not-found')).status()).toBe(404);
});
```

Run: `npm run typecheck && npm test && npx playwright test tests/e2e/s1-local.spec.ts`

Expected: PASS at 360px and desktop; `/products` reload succeeds; API miss is 404.

- [ ] **Step 5: Document and checkpoint**

Document actual commands and SPA fallback; state explicitly that this shell does not complete WEB-01. If commit permission exists:

```bash
git add package.json package-lock.json web/storefront tests/e2e
git commit -m "feat(PLT-01): add local storefront smoke shell"
```

### Task 5: Add contract, orchestration and acceptance (`TASK:PLT-02`, `TASK:SEC-01`)

**Files:**
- Create: `contracts/openapi/catalog.yaml`, `contracts/openapi/examples/catalog-empty.json`
- Create: `scripts/validate-contracts.sh`, `scripts/smoke-local.sh`
- Create: `.github/workflows/application-ci.yml`, `docs/evidence/s1-local-template.md`
- Modify: `infra/local/compose.yaml`, `README.md`, `docs/README.md`, `docs/engineering/12_engineering_guide.md`, `docs/design/03_interfaces.md`, `docs/design/08_decisions.md`

**Interfaces:**
- Consumes: Gateway `localhost:8080`, private catalog container and storefront `localhost:4173`.
- Produces: one-command local start/stop, OpenAPI validation, smoke and read-only CI.

- [ ] **Step 1: Add the executable OpenAPI operation**

```yaml
openapi: 3.1.0
info: {title: Catalog API, version: 0.1.0}
paths:
  /api/v1/catalog/products:
    get:
      operationId: listProducts
      parameters:
        - {in: query, name: limit, schema: {type: integer, minimum: 1, maximum: 100, default: 20}}
      responses:
        '200':
          description: Stable public product page
          content:
            application/json:
              schema: {$ref: '#/components/schemas/ProductPageResponse'}
components:
  schemas:
    ProductPageResponse:
      type: object
      required: [code, data, metadata]
      properties:
        code: {type: string, const: OK}
        data:
          type: object
          required: [items, next_cursor]
          properties:
            items:
              type: array
              items: {$ref: '#/components/schemas/ProductSummary'}
            next_cursor: {type: [string, 'null']}
        metadata:
          type: object
          required: [request_id, trace_id]
          properties: {request_id: {type: string, minLength: 1}, trace_id: {type: string, minLength: 1}}
    ProductSummary:
      type: object
      required: [id, slug, name_vi, name_en]
      additionalProperties: false
      properties:
        id: {type: string, format: uuid}
        slug: {type: string, minLength: 1, maxLength: 160}
        name_vi: {type: string, minLength: 1, maxLength: 255}
        name_en: {type: string, minLength: 1, maxLength: 255}
```

- [ ] **Step 2: Write the smoke and prove it fails while stopped**

```bash
#!/usr/bin/env bash
set -euo pipefail
curl --fail --silent --show-error http://localhost:8080/actuator/health/readiness
body="$(curl --fail --silent --show-error 'http://localhost:8080/api/v1/catalog/products?limit=1')"
python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["data"]["items"] == []' <<<"$body"
code="$(curl -sS -o /dev/null -w '%{http_code}' -H 'X-User-Roles: SUPER_ADMIN' http://localhost:8080/internal/api/v1/platform/ping)"
test "$code" = "404"
```

Run: `bash scripts/smoke-local.sh`

Expected: FAIL with connection refused while Compose is stopped.

- [ ] **Step 3: Complete Compose health ordering and CI**

Compose starts PostgreSQL → catalog → Gateway → static storefront using healthchecks. `application-ci.yml` runs docs, Maven tests, `npm ci`, typecheck/unit/build, contract validation and browser smoke without AWS credentials; actions use full commit SHAs and `permissions: contents: read`.

- [ ] **Step 4: Prove default and Nacos profiles**

```bash
docker compose --env-file infra/local/.env.example -f infra/local/compose.yaml up --build -d
bash scripts/smoke-local.sh
docker compose --env-file infra/local/.env.example -f infra/local/compose.yaml --profile nacos-compat up -d nacos
SPRING_PROFILES_ACTIVE=nacos-compat ./mvnw -pl services/catalog-service spring-boot:run
```

Expected: default smoke PASS. Compatibility profile imports config/discovers, survives one Nacos restart and reconnects without disabling Spring compatibility verification.

- [ ] **Step 5: Perform independent fresh-clone review**

Reviewer fills `docs/evidence/s1-local-template.md` with commit, OS/CPU, JDK/Node/Docker versions, commands, timing, dependency evidence, result and reviewer/date. Use synthetic values only.

- [ ] **Step 6: Run the complete gate**

```bash
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
./mvnw test
npm ci && npm run typecheck && npm test && npm run build
bash scripts/validate-contracts.sh
bash scripts/smoke-local.sh
git diff --check
```

Expected: all PASS. Any unrun check remains a gate failure.

- [ ] **Step 7: Synchronize status and checkpoint**

Update actual commands in README/12, implemented contract in 03, and O02 evidence in 08 while leaving AWS/O01 open. Mark only S1-local after reviewer acceptance. If commit permission exists:

```bash
git add contracts scripts .github/workflows/application-ci.yml infra/local README.md docs
git commit -m "test(PLT-01): prove S1 local foundation"
```

## S1-local Exit Gate

- Fresh-checkout proof is independently reviewed.
- Default smoke and Nacos compatibility profile pass with no forced dependency resolution.
- Migration/runtime roles are separated; readiness fails on an unmigrated database.
- Gateway strips spoofed identity headers and does not route `/internal/**`.
- Storefront deep-link and API 404 behavior pass at 360px and desktop.
- No AWS/provider resource or credential was used; no G0/G1/G2 claim is made.
