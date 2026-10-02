# Handoff Sprint 1 local foundation — 2026-09-22

Handoff này giữ snapshot lịch sử. Agent tiếp tục trên máy mới đọc
[handoff ngày 2026-10-02](2026-10-02-s1-local-machine-handoff.md): Task 5 đã có code,
CI baseline đã PASS; còn fresh-clone runtime/reviewer/TL acceptance.

## Mục đích

Đây là handoff cho agent tiếp theo của branch Sprint 1. Tài liệu ghi trạng thái đã kiểm chứng, evidence và thứ tự tiếp tục.

Nó không thay status backlog, không khẳng định S1-local/G0/G1/G2/MVP hoàn thành và không cho phép deploy, commit, push hay gọi AWS/provider.

Đọc cùng [Sprint 1 plan](2026-09-19-sprint-1-local-foundation.md), [03 Interfaces](../../design/03_interfaces.md), [09 Delivery](../../delivery/09_delivery_plan.md), [10 Backlog](../../delivery/10_backlog.md), [12 Engineering guide](../../engineering/12_engineering_guide.md), [13 Operations & security](../../operations/13_operations_security.md), [17 Tech Stack](../../engineering/17_tech_stack.md) và [08 Decisions](../../design/08_decisions.md).

## Cập nhật 2026-09-28

Task 5 đã được hiện thực trên working tree (chưa commit): OpenAPI catalog + Redocly, Dockerfile
ba thành phần, Compose đủ chuỗi có healthcheck, `scripts/local-up.sh`, `smoke-local.sh`,
`validate-contracts.sh`, `nacos-compat-check.sh`, Application CI và Playwright chuỗi thật.
Evidence tác giả ở [17 §7](../../engineering/17_tech_stack.md); trạng thái task ở
[10](../../delivery/10_backlog.md). Còn lại: fresh-clone review độc lập theo
[template](../../evidence/s1-local-template.md) và run Application CI trên remote. Các mục
"Việc kế tiếp" và "Gate/rủi ro" bên dưới là snapshot 2026-09-22.

Lệch so với plan Task 5, cần TL xác nhận khi review:

- JAR/`dist` build trên host bằng Maven Wrapper/npm lockfile; Dockerfile chỉ copy artifact (không
  build trong image). `docker compose up --build` trần sẽ đóng gói artifact cũ; dùng
  `scripts/local-up.sh`.
- Example OpenAPI viết inline trong `catalog.yaml`, không có `contracts/openapi/examples/catalog-empty.json`.
- Smoke không bắt buộc `items` rỗng để không vỡ khi DB local có dữ liệu; mỗi item có mặt được kiểm
  đúng shape `ProductSummary` của contract. DB fresh-clone rỗng nên shape item live chỉ được kiểm khi
  có dữ liệu; integration test catalog vẫn kiểm với dữ liệu thật.
- Storefront tĩnh dùng nginx 1.30.5 (17 §4.1 cho phép static container pin).

## Checkout snapshot

| Trường | Giá trị |
|---|---|
| Directory | `/home/tts/code/fashion-ecommerce-platform/.worktrees/s1-local-foundation` |
| Branch | `feature/s1-local-foundation` |
| HEAD | `cc18a00` — `feat(PLT-01): add local storefront smoke shell` |
| Git status trước khi tạo handoff | Clean |
| Compose lần kiểm tra cuối | Không có container Compose đang chạy; không dùng `down -v` và không xóa volume local |
| Docker đã xác minh | Engine/Compose `29.8.1`; Testcontainers 2.0.5 chạy PostgreSQL 17.11 thật |

Các commit gần nhất liên quan:

- `cc18a00` — storefront shell, UI/E2E skeleton và tài liệu.
- `acd2763` — Gateway catalog-only, strip spoofed identity headers.
- `896162c` — giữ parameter metadata cho Spring MVC.
- `d04896a` — test catalog chuyển sang Jackson 3.
- `146747b` — catalog readiness/validation contracts và PostgreSQL tests.

Trước khi sửa, chạy `git status --short` và đọc diff liên quan. Không reset, checkout, rebase hoặc dọn thay đổi của người khác.

## Phần đã có

### Catalog sample — `TASK:PLT-01`, partial `TASK:CAT-01`

- Root Maven reactor có catalog và Gateway; Java 21, Spring Boot 4.0.8, Testcontainers 2.0.5.
- Catalog dùng Spring MVC + Jdbc/JdbcClient, Flyway `V001`, PostgreSQL 17.11 và role migration/runtime tách riêng; không dùng JPA, H2 hoặc mock thay DB thật.
- `GET /api/v1/catalog/products` chỉ có `limit` default 20/max 100, chỉ trả ACTIVE products trong envelope `OK/data/metadata`; limit sai trả HTTP 400 + correlation header.
- Readiness gồm DB + `catalogMigration`; liveness không phụ thuộc outage DB tạm thời.
- Lỗi MVC đã fix bằng `parameters=true` tại [catalog pom](../../../services/catalog-service/pom.xml); sau đổi compiler option phải `clean` một lần để xóa bytecode cũ.
- Spring Boot 4 auto-configure Jackson 3 `tools.jackson.databind.ObjectMapper`; không đổi test về `com.fasterxml.jackson.databind.ObjectMapper`.

### Gateway — `TASK:SEC-01`, `REQ:USR-07`, `REQ:XCT-03`

- Chỉ route `/api/v1/catalog/**` tới `CATALOG_BASE_URL` (default `http://localhost:8081`).
- Không catch-all route; `/internal/**` phải 404.
- Filter precedence cao strip `X-User-Id`, `X-User-Roles`, `X-Actor-Id`, `X-Service-Name`.
- Xem lệnh/config thực tại [Gateway README](../../../services/gateway/README.md); không bỏ filter hay public internal path để làm smoke pass.

### Storefront — `TASK:PLT-01`, `REQ:XCT-01`, `REQ:XCT-05`

- Vite/React có `/` và `/products`, state loading/empty/error/retry cho catalog GET.
- Browser gọi same-origin `/api`; Vite proxy tới Gateway để deep link fallback không biến API 404 thành `index.html`.
- Đây chưa phải `WEB-01`: chưa có catalog đầy đủ, auth, cart, checkout hay token persistence.
- Xem lệnh/config thực tại [Storefront README](../../../web/storefront/README.md).

## Evidence đã chạy

| Lệnh | Kết quả |
|---|---|
| `sg docker -c './mvnw -pl services/catalog-service clean test'` | **PASS** 2026-09-20: 7 tests, 0 failures/errors. PostgreSQL 17.11 qua Testcontainers; migration, readiness có/không migration, active-only query, limit, envelope và runtime không DDL. |
| `python3 -B scripts/check_docs.py` | **PASS**: 25 Markdown files tại thời điểm chạy. Checker không kiểm anchor, external URL, Mermaid, application/deployment. |
| `git diff --check` | **PASS** sau bản sửa catalog. |
| Docker/Testcontainers | Docker server `29.8.1`, API `1.56`, Ubuntu 24.04.5 LTS; Ryuk và `postgres:17.11` đã chạy thành công. |

Warning không chặn: Testcontainers báo thiếu `~/.docker/config.json` rồi fallback Docker default; Mockito/JDK báo dynamic agent. Không thêm Docker credential hoặc suppress warning chỉ để log sạch.

### Quyền Docker cho agent

Process agent có thể chưa nạp group `docker`. Nếu `docker version` permission denied, không chmod socket và không lưu mật khẩu sudo. Dùng subshell tạm:

```bash
sg docker -c 'docker version'
sg docker -c './mvnw -pl services/catalog-service clean test'
sg docker -c 'docker compose --env-file infra/local/.env.example -f infra/local/compose.yaml up -d postgres'
```

Chạy catalog JVM theo README:

```bash
cp infra/local/.env.example infra/local/.env
set -a; source infra/local/.env; set +a
./mvnw -pl services/catalog-service spring-boot:run
```

Không commit `.env`/credential. `down -v` chỉ dùng khi chủ động xóa dữ liệu local.

## Việc kế tiếp — Task 5

Bắt đầu **Task 5** trong [Sprint 1 plan](2026-09-19-sprint-1-local-foundation.md#task-5-add-contract-orchestration-and-acceptance): `TASK:PLT-02` core và phần còn lại `TASK:SEC-01`.

Giữ luồng: `storefront /api/* -> Gateway localhost:8080 -> catalog localhost:8081 -> PostgreSQL localhost:5432`.

Thứ tự:

1. Đọc Task 5, 03/12/13/17/08 và README catalog/Gateway/storefront.
2. Viết executable OpenAPI catalog, empty fixture và validator; đối chiếu envelope/error đang chạy, không mở rộng thành catalog MVP.
3. Viết `smoke-local.sh`, chứng minh fail khi stack dừng rồi mới nối full path.
4. Hoàn tất Compose health ordering và CI chỉ cho artifact có thật. CI dùng full SHA action pin, `permissions: contents: read`, không AWS credential.
5. Sau default smoke mới làm `nacos-compat`; không tắt compatibility verification hoặc thêm fallback che reconnect lỗi.
6. Đồng bộ README, 03/08/12/evidence template; chạy complete gate và yêu cầu fresh-checkout review độc lập.

### Cần xác minh trước khi tự thiết kế

- Compose hiện chỉ có PostgreSQL nhưng Task 5 yêu cầu PostgreSQL → catalog → Gateway → static storefront bằng healthcheck. Checkout chưa có Dockerfile ba component; đối chiếu plan/12/17 và xác nhận phạm vi artifact với TL trước khi tự thêm pipeline build/serve.
- Plan yêu cầu Nacos compatibility nhưng catalog/Gateway chưa có implementation Nacos. BOM import không phải evidence; giữ default explicit route hoạt động.
- Playwright cần Vite/Gateway chạy và intercept catalog cho deep-link. Trong CI phải giữ API miss đi Gateway thật, không biến smoke thành mock-only.
- Không dùng `latest`, không provision AWS/K3s/ECR/ArgoCD, không gọi payment/provider, không báo G0/G1/G2/MVP done.

## Gate/rủi ro còn mở

- Chưa có fresh-clone review, full Compose path, OpenAPI validator, application CI, default smoke, Nacos reconnect proof hoặc complete-gate evidence.
- Catalog integration pass không chứng minh Gateway/storefront end-to-end; code hai phần tồn tại nhưng chưa thay acceptance smoke.
- `TASK:PLT-01` và `TASK:CAT-01` vẫn **In progress**. Catalog chỉ read sample, chưa có CRUD/variant/admin/event acceptance.
- O02/runtime compatibility toàn bộ vẫn mở; local pass không thay reviewer hay production evidence.

## Trước handoff kế tiếp

Chỉ báo PASS cho lệnh đã chạy:

```bash
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
git diff --check
```

Khi Task 5 xong, chạy thêm Maven reactor, `npm ci`, typecheck/unit/build, contract validation, browser smoke và local smoke theo plan. Ghi OS/CPU, JDK/Node/npm/Docker version, command, result, commit, ngày và reviewer vào evidence. Không commit/push/PR nếu không có user authorization.
