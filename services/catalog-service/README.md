# Catalog service

`TASK:PLT-01` · partial `TASK:CAT-01` · `REQ:CAT-01`, `REQ:XCT-06`.

Service sở hữu database `catalog`. Phần nền read-only Sprint 1 chạy Flyway `V001`;
CAT-01a bổ sung `V002` (category/brand, product fields, variant identity trigger,
outbox/idempotency/audit). Admin taxonomy đã có GET/POST categories/brands và PUT theo id
tại `/admin/api/v1/catalog`. Mọi endpoint kiểm ES256 và `catalog.write` tại service;
POST cần `Idempotency-Key`, PUT cần `expected_version`; mutation và audit cùng transaction.
Thu hồi quyền ở catalog trễ tối đa TTL access token 15 phút theo ADR-21.
Public API hiện có `GET /api/v1/catalog/products`.
Endpoint trả các product `ACTIVE` theo `created_at DESC, id DESC`; `limit` mặc định
`20`, tối đa `100`. Response thành công theo [contract 03 §1.2](../../docs/design/03_interfaces.md):
`code: "OK"`, `data`, `metadata.request_id`, và `metadata.trace_id`.

`limit` ngoài khoảng `1..100` trả HTTP 400 với `code: "VALIDATION_ERROR"`,
`message: "INVALID_LIMIT"`, một `errors` field-level (`field: "limit"`, `message`,
`rejected_value`) và metadata đầy đủ. Header `X-Correlation-Id` bằng `trace_id` của
response, kể cả lỗi validation.

Product admin đã có GET list (page/size/status), POST draft, GET/PUT theo id; PUT giữ trạng thái,
kiểm version. Description VI/EN qua jsoup 1.23.2 Safelist.basic + h2/h3, link chỉ http/https,
rel nofollow/noopener; giới hạn sau sanitize 20.000 ký tự. Giá VND bigint, từ chối JSON thập phân.

Đây chưa phải acceptance đầy đủ của `TASK:CAT-01`: chưa có publish, variant mutation,
collection/size-guide/media, filter/search public, cursor thật, cache hoặc Kafka. `next_cursor` luôn
`null`; topics producer/consumer: **none trong S1**.

## Chạy local

Yêu cầu JDK 21 và Docker daemon mà user hiện tại được phép truy cập. Từ repo root:

```bash
cp infra/local/.env.example infra/local/.env
docker compose --env-file infra/local/.env -f infra/local/compose.yaml up -d postgres
set -a; source infra/local/.env; set +a
./mvnw -pl services/catalog-service spring-boot:run
```

Service mặc định nghe tại `http://localhost:8081`. Trong Compose đủ chuỗi
(`bash scripts/local-up.sh`), catalog chạy từ `Dockerfile` (JAR build trên host, JRE Temurin
pin digest), không publish port, chỉ Gateway gọi được qua mạng Compose. Dừng dependency bằng
`docker compose --env-file infra/local/.env -f infra/local/compose.yaml down`.
Lệnh này giữ volume local; chỉ dùng `down -v` khi chủ động xóa toàn bộ dữ liệu local.

## Configuration và database roles

| Variable | Default local | Dùng bởi |
|---|---|---|
| `CATALOG_DB_URL` | `jdbc:postgresql://localhost:5432/catalog` | Runtime datasource |
| `CATALOG_DB_USERNAME` / `CATALOG_DB_PASSWORD` | `catalog_runtime` / synthetic local value | Runtime datasource |
| `CATALOG_DB_MAX_POOL_SIZE` | `10` | Hikari runtime pool |
| `CATALOG_JWT_PUBLIC_KEYS` | bắt buộc, không default | `kid:base64-X.509` để kiểm access token ES256; dùng public keys của user-service |
| `CATALOG_MIGRATION_DB_URL` | `jdbc:postgresql://localhost:5432/catalog` | Flyway datasource |
| `CATALOG_MIGRATION_DB_USERNAME` / `CATALOG_MIGRATION_DB_PASSWORD` | `catalog_migration` / synthetic local value | Flyway migration |

`infra/local/postgres-init/01-catalog-roles.sh` tạo roles idempotently. Migration
role có `CREATE` trên database/schema để cài extension và tạo migration; runtime role
chỉ có `USAGE` schema cùng quyền DML mặc định trên tables/sequences do migration role
tạo. Runtime role không có `CREATE` schema và không được dùng làm Flyway credential.

`V001__catalog_baseline.sql` là migration append-only đầu tiên. Migration sau phải
tương thích với dữ liệu cũ; không sửa `V001` sau khi nó đã được áp dụng ở bất kỳ môi
trường nào.

## Test và health

```bash
./mvnw -pl services/catalog-service -Dtest=ProductQueryIntegrationTest test
./mvnw -pl services/catalog-service test
```

Surefire chạy JVM test với `-Duser.timezone=UTC` để pgjdbc không gửi alias zone cũ
(ví dụ `Asia/Saigon` trên Windows) mà PostgreSQL từ chối.
Hai lệnh dùng Testcontainers PostgreSQL `17.11`; chúng cần Docker daemon khả dụng,
không dùng H2 hoặc mock repository thay thế. Test kiểm tra migration, active-only
query, limit, response envelope, readiness và runtime role không thể tạo table.

Sau khi service chạy:

```bash
curl -fsS http://localhost:8081/actuator/health/liveness
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS 'http://localhost:8081/api/v1/catalog/products?limit=20'
curl -fsS http://localhost:8081/actuator/metrics
```

Liveness chỉ gồm `livenessState`, nên outage DB tạm thời không làm probe yêu cầu
restart JVM. Readiness gồm `db` và `catalogMigration`: database trống/chưa có V001
trả HTTP 503. Để kiểm tra case đó trên một database local mới, chạy service với
`SPRING_FLYWAY_ENABLED=false` trước khi chạy migration, rồi gọi readiness; không dùng
profile này cho runtime bình thường.

## Profile `nacos-compat` (O02)

Mặc định Nacos tắt (`spring.cloud.nacos.*.enabled=false`). Profile `nacos-compat`
(`application-nacos-compat.yaml`) import `nacos:catalog-service.yaml` (không optional, fail
fast nếu Nacos down lúc khởi động), đăng ký discovery `catalog-service` và bật
`management.info.env` để proof đọc giá trị import. Biến: `NACOS_SERVER_ADDR`,
`NACOS_USERNAME`, `NACOS_PASSWORD`. Chạy proof: `bash scripts/nacos-compat-check.sh`.

Observability (PLT-05, phần local): `metadata.trace_id` và header `X-Correlation-Id` là trace
W3C của request (tiếp nối `traceparent` từ Gateway; không có span thì sinh 32 hex), còn
`request_id` vẫn là UUID riêng của mỗi response. Log JSON dạng ECS qua
`PiiRedactingJsonCustomizer` ([platform-security](../platform-security/README.md)). Actuator
expose `health`, `info`, `metrics`, `prometheus` trên port 8081; port này không publish ra ngoài,
label metrics dùng URI template. Chưa có tracing exporter, dashboard, alert hay retention policy
(phần staging của PLT-05, sau PLT-04).
