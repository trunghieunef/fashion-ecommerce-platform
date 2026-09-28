# Catalog service

`TASK:PLT-01` · partial `TASK:CAT-01` · `REQ:CAT-01`, `REQ:XCT-06`.

Service này là vertical slice catalog read-only của Sprint 1. Nó sở hữu database
`catalog`, chạy Flyway migration `V001`, và chỉ public `GET /api/v1/catalog/products`.
Endpoint trả các product `ACTIVE` theo `created_at DESC, id DESC`; `limit` mặc định
`20`, tối đa `100`. Response thành công theo [contract 03 §1.2](../../docs/design/03_interfaces.md):
`code: "OK"`, `data`, `metadata.request_id`, và `metadata.trace_id`.

`limit` ngoài khoảng `1..100` trả HTTP 400 với `code: "VALIDATION_ERROR"`,
`message: "INVALID_LIMIT"`, một `errors` field-level (`field: "limit"`, `message`,
`rejected_value`) và metadata đầy đủ. Header `X-Correlation-Id` bằng `trace_id` của
response, kể cả lỗi validation.

Đây chưa phải acceptance đầy đủ của `TASK:CAT-01`: không có CRUD/admin, variant,
category, filter/search, cursor thật, cache, auth hoặc Kafka. `next_cursor` luôn
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

Actuator hiện chỉ expose `health`, `info`, `metrics`; chưa có Prometheus registry,
dashboard, alert, tracing exporter hoặc retention policy. Các phần observability đó
thuộc task sau.
