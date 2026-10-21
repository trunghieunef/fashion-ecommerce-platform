# 12 — Hướng dẫn phát triển và onboarding

B1 · 2026-09-19 · Owner TL + DEVOPS.

## 1. Trạng thái thực tế

Repo có docs/CI kiểm tra tài liệu (`.github/workflows/docs-ci.yml`, `scripts/check_docs.py`
cùng test) và nền tảng `TASK:PLT-01` đã được tạo: root Maven reactor/Wrapper,
`services/catalog-service` (Flyway `V001` sample + `V002` CAT-01a admin, role migration/runtime riêng),
`services/gateway`, `web/storefront` và Compose PostgreSQL local. Lệnh chạy/test thực tế
và giới hạn nằm trong README của từng thành phần; không coi các service/đường dẫn khác
trong cấu trúc dự kiến là đã tồn tại.

Phase 1A hiện có [user-service](../../services/user-service/README.md),
[platform-durability](../../services/platform-durability/README.md) và
[platform-security](../../services/platform-security/README.md). Compose thêm Redis;
login/forgot cần Redis để rate limit (USR-01b-ii), readiness user-service gồm DB + Redis.
Proxy allowlist/IP handoff ở 03 §1.2 và README Gateway/user-service; mặc định standalone
không tin header chuyển tiếp. INV-01 thêm [platform-messaging](../../services/platform-messaging/README.md)
và [inventory-service](../../services/inventory-service/README.md), Kafka local và relay catalog/inventory.
User-service chưa bật relay; notification-service/UI auth/admin chưa có.
Catalog admin CAT-01a dùng cùng ES256 verifier, JdbcClient và durability, có catalog.write,
version/key/audit; [evidence local](../evidence/cat-01a-local-2026-10-07.md) ghi số test và mutation.

CART-01: `./mvnw -B -pl services/cart-service -am test` (PostgreSQL Testcontainers, stub catalog/inventory). Smoke tạo giỏ guest qua Gateway, thêm SKU smoke, retry cùng key, đọc preview và mở giỏ member (`CART-01 guest/member cart PASS`); local-up sinh service key cho cart-service. CART-03: route internal snapshot/cleanup chỉ kiểm bằng integration test (`CartSnapshotIntegrationTest`, `CheckoutCleanupIntegrationTest`), chưa smoke vì chưa có order-service; `CART_INTERNAL_CALLERS` trống mặc định.
SHP-01/02: `./mvnw -B -pl services/shipping-service -am test` (PostgreSQL Testcontainers, stub catalog, relay tắt). SHP-02 smoke: self-events qua Gateway với token OPS local (`shipping.self_event` → 404 cho id lạ, thiếu quyền → 403), cancel internal không route (`SHP-02 self-event route PASS`). Smoke estimate qua Gateway bằng variant smoke + variant lạ → 422 (`SHP-01 shipping estimate PASS`); local-up sinh service key `shipping-service` và thêm vào `CATALOG_INTERNAL_CALLERS`; `SHIPPING_INTERNAL_CALLERS` trống mặc định.
ORD-01: `./mvnw -B -pl services/order-service -am test` (PostgreSQL Testcontainers; cart/catalog/shipping là HTTP stub; relay tắt). Smoke guest qua Gateway: checkout token của cart → quote → create 202 → đọc bằng cookie `order_access`, không cookie 404 (`ORD-01 guest quote/create/read PASS`); local-up sinh service key `order-service` (thêm vào `CART_/CATALOG_/SHIPPING_INTERNAL_CALLERS`), cặp khóa checkout token (`CART_CHECKOUT_PRIVATE_KEY` → `ORDER_CHECKOUT_PUBLIC_KEYS`) và `ORDER_QUOTE_SECRET`.
ORD-02: cùng lệnh test (inventory/payment/cart là HTTP stub, worker tắt bằng `fashion.order.saga.enabled=false` và gọi `runOnce()`; consumer `payment.events` có test Kafka Testcontainers). Smoke sau bước ORD-01 poll GET đơn guest tới `CONFIRMED` (≤ 30 × 1 s, `ORD-02 saga confirm PASS`); local-up thêm `order-service` vào `INVENTORY_/PAYMENT_INTERNAL_CALLERS`. Env: `ORDER_SAGA_ENABLED`, `ORDER_SAGA_POLL` (1s), `ORDER_SAGA_MAX_RETRIES` (30), `ORDER_INVENTORY_URL`, `ORDER_PAYMENT_URL`.
ORD-03: cùng lệnh test (inventory commit/shipping create là HTTP stub, `ShiftClock` cho race hạn T12; consumer `shipping.events` có test Kafka Testcontainers). Smoke sau PAY-01 chạy COD end-to-end qua Gateway trên đơn của ORD-02 với token OPS/FINANCE local: FINANCE detail không có PII, FINANCE confirm 403 → OPS `confirm-cod` 202 → poll (≤ 20 × 1 s) tới `PACKING` + saga `DONE` có `shipment_id` → self-events ASSIGN/HANDED_OVER/DELIVERED/COD_COLLECTED → poll `DELIVERED` có `shipped_at` → payment `COD_COLLECTED` → FINANCE `cod-settlements` → `SUCCESS` → poll saga `payment_state = PAID` (cùng TX với `paid_at`) → `ORD-03 COD end-to-end PASS`. Gateway thêm route `order-admin`; không thêm env (dùng `ORDER_INVENTORY_URL`/`ORDER_SHIPPING_URL`, caller đã có).
ORD-04: cùng lệnh test; inventory/shipping/payment là HTTP stub, worker tắt và gọi `runOnce()` (cả `SagaWorker`, `OrderReturnWorker`), test PostgreSQL thật cho khóa/ràng buộc (`OrderReturnsIntegrationTest`, `SagaCancelAfterConfirmIntegrationTest`, `CustomerCancelIntegrationTest`, `ShippingEventsIntegrationTest`); Gateway có `orderCancelAndReturnPathsAreRouted`. Smoke sau `ORD-03 COD end-to-end PASS` (token OPS local có thêm `order.cancel`, `order.return`; tồn kho đọc bằng `inventory.read`): đơn A → `confirm-cod` → `PACKING` → OPS cancel → poll `CANCELLED` với ``inventory_state = RESTORED`, tồn kho SKU bằng trước khi đặt (`ORD-04 cancel after confirm PASS`); đơn B (2 sản phẩm) → ASSIGN/HANDED_OVER/DELIVERY_FAILED/RETURN_STARTED/RETURNED → poll `RETURNING` (kho chưa cộng) → `POST returns` (received 2, restock 1; gửi lại cùng key không thêm phiếu) → poll `RETURNED`, phiếu `COMPLETED`, tồn kho +1 (`ORD-04 return PASS`); đơn C: guest cookie không gửi được cancel của đơn khác (404), `allowed_actions = ["CANCEL"]`, tự hủy → `CANCELLED` `CUSTOMER_CANCELLED`, tồn kho về như trước (`ORD-04 customer cancel PASS`). Không thêm env.
PAY-01: `./mvnw -B -pl services/payment-service -am test` (PostgreSQL Testcontainers; một test Kafka Testcontainers cho consumer `shipping.events`; relay tắt). Smoke: admin payments qua Gateway với token FINANCE local (`payment.read` → list, id lạ 404, thiếu quyền 403), internal không route (`PAY-01 payment admin route PASS`); `PAYMENT_INTERNAL_CALLERS` trống mặc định, internal chỉ kiểm bằng integration test tới khi có order-service.

PLT-05: `local-up` bật thêm Tempo (`127.0.0.1:3200`) và Grafana (`http://127.0.0.1:3000`, anonymous
viewer). Xem trace: lấy `metadata.trace_id` của response → Grafana Explore → Tempo → dán trace id.
Smoke kiểm trace tạo variant đủ span gateway/catalog/inventory (`PLT-05 Kafka trace PASS`); Tempo dừng
thì chỉ bước này fail.

INV-01: `bash scripts/local-up.sh` khởi động PostgreSQL/Kafka, chạy init DB inventory/topic
idempotent rồi build/start stack; giữ volume. `bash scripts/smoke-local.sh` tạo variant,
chờ đăng ký SKU, adjust +5 qua Gateway và kiểm event snapshot. JWT synthetic inventory
chỉ inventory.read/adjust, token catalog vẫn chỉ catalog.write; không chứng minh login OPS.
`./mvnw -B -pl services/inventory-service -am test` cần Docker PostgreSQL17.11/Kafka4.1.2.
Readiness inventory chỉ DB, kafka health riêng. Chi tiết [evidence INV-01](../evidence/inv-01-local-2026-10-15.md).

INV-02 hiện thực local reserve/commit/release/query và expiry sweep; parent ở Review.
Chạy regression PostgreSQL/Kafka thật (Git Bash trên Windows, Java 21, Docker Desktop):

```bash
./mvnw -B -pl services/inventory-service -am test -Dtest='Reserv*Test,Commit*Test,Release*Test' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -pl services/gateway -am test -Dtest=GatewayBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Contract hash ở 03 §1.3; [evidence INV-02](../evidence/inv-02-local-2026-10-16.md)
ghi đủ full verify và sáu mutation guard. Testcontainers dùng PostgreSQL 17.11/Kafka 4.1.2;
không thay bằng H2/mock để nghiệm thu race.

Quota namespace lấy từ `FASHION_ENV` (local mặc định); HMAC identity dùng khóa dẫn xuất từ
master sẵn có. Allowlist rỗng phát WARN để tránh quên config khi chạy sau Gateway; header
không được lookup DNS. Readiness/subnet IPv6 staging còn cần chốt ở 08.

Evidence local hiện có ghi tại [17 §7](17_tech_stack.md): catalog/Gateway, frontend,
Compose smoke chuỗi thật và Nacos proof ngày 2026-09-28. CI remote **PASS** cho commit
`d16151d` ngày 2026-09-30. [Lần kiểm tra ngày 2026-10-02](../evidence/s1-local-2026-10-02.md)
trên clone sạch Windows bị mạng công ty chặn runtime artifacts; chưa đủ fresh-clone
acceptance độc lập. [Fresh-clone review ngày 2026-10-04](../evidence/s1-local-2026-10-04.md)
chạy đủ chuỗi trên Windows sau khi sửa lỗi timezone; Codex (GPT-6) đã duyệt evidence/CI
và nghiệm thu S1-local ngày 2026-10-04 theo chỉ định chủ dự án thay reviewer người/TL.
Không thay bằng H2/mock hoặc bỏ qua test để xác nhận S1-local.

Lưu ý môi trường:

- Windows: dùng Git Bash cho `.sh`, hoặc `./mvnw.cmd test` trong PowerShell với
  `JAVA_HOME` trỏ JDK đã khóa. Wrapper Windows chuẩn hóa project path, hỗ trợ khoảng
  trắng; regression nằm ở `scripts/test_maven_wrapper.py` (Windows + JDK).
- Nếu TLS interception của mạng công ty dùng CA đã được Windows tin cậy, có thể đặt
  `NODE_USE_SYSTEM_CA=1` cho Node và
  `MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'`
  cho Java trên Windows. Không tắt TLS verification. HTTP 403 do policy chặn artifact
  cần môi trường được phép tải hoặc hỗ trợ từ quản trị mạng; không sửa lockfile/version
  để che blocker. `npm ci` có thể exit 0 dù optional native dependency bị chặn: vẫn phải
  chạy unit/build để xác minh.
- JVM trên Windows đặt múi giờ Việt Nam mặc định là alias cũ `Asia/Saigon`; pgjdbc gửi
  zone này và `postgres:17.11` (Debian trixie) từ chối. Surefire của catalog đã đặt
  `-Duser.timezone=UTC`; khi chạy JVM service trên host kết nối PostgreSQL, thêm cùng
  tham số (ví dụ `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`). Container không bị ảnh hưởng.
- Nếu process chưa nạp group `docker`, dùng `sg docker -c '<lệnh>'`; không chmod socket.
- Máy có cả Docker Desktop và Docker Engine thì CLI/Compose theo `docker context`, còn
  Testcontainers mặc định dùng `/var/run/docker.sock`. Kiểm tra `docker context ls` và
  ghi engine/version thực dùng vào evidence; hai lệnh có thể chạy trên hai daemon khác nhau.
- Sandbox chặn kết nối tới port container trên `localhost` làm Testcontainers lỗi
  `Could not connect to Ryuk`; đây là lỗi môi trường, không phải test pass/fail.
- 2026-09-28 trên host Ubuntu kernel 7.0.0-34, Docker Engine gốc (`/var/run/docker.sock`)
  không cho host kết nối port publish/IP bridge (connection reset, container-to-container
  vẫn chạy). Docker Desktop hoạt động. Khi gặp lỗi này, dùng context `desktop-linux` và
  `DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock` cho Testcontainers; không sửa
  firewall host để vượt lỗi.

### Lệnh local thực tế (S1-local)

Chạy từ repo root. Artifact build trên host bằng Maven Wrapper/npm lockfile; Dockerfile
chỉ copy JAR/`dist` vào image runtime pin tag + digest.

| Mục đích | Lệnh |
|---|---|
| Toolchain/lockfile | `bash scripts/verify-toolchain.sh` |
| Docs | `python3 -B -m unittest discover -s scripts -p 'test_*.py' -v` và `python3 -B scripts/check_docs.py` |
| Backend (PostgreSQL thật) | `./mvnw test`; một test: `./mvnw -pl services/catalog-service -Dtest=ProductQueryIntegrationTest test` |
| Service identity (không cần Docker) | `./mvnw -pl services/platform-security test`; xem [README](../../services/platform-security/README.md) |
| Hạ tầng offline (PLT-04) | `bash scripts/validate-infra.sh` (venv từ `tests/infrastructure/requirements.txt`: cfn-lint + policy tests, không gọi AWS); `bash scripts/render-staging.sh [--strict]` |
| Secret scan (Docker) | `bash scripts/scan-secrets.sh` (gitleaks v8.30.1 pin digest, quét git history; finding đã review ghi trong `.gitleaksignore`) |
| User service (PostgreSQL thật) | `./mvnw -pl services/user-service -am test`; xem [README](../../services/user-service/README.md) |
| Durability primitives (PostgreSQL + Kafka thật) | `./mvnw -pl services/platform-durability test`; xem [README](../../services/platform-durability/README.md) |
| Frontend | `npm ci && npm run typecheck && npm test && npm run build` |
| Contract | Cài `python3 -m pip install -r tests/contracts/requirements.txt`; chạy `bash scripts/validate-contracts.sh` (Redocly lint + event envelope tests). Chỉ event: `python3 -B -m unittest discover -s tests/contracts -p 'test_*.py' -v` |
| Khởi động stack | `bash scripts/local-up.sh` (tạo `infra/local/.env` từ example nếu chưa có) |
| Smoke | `bash scripts/smoke-local.sh`; local Redis timeout qua Gateway: `bash scripts/smoke-auth-redis-timeout.sh` (pause/unpause Redis, không chạy khi có traffic người dùng); browser: `npx playwright install chromium && npx playwright test` |
| Nacos proof O02 | `bash scripts/nacos-compat-check.sh` sau `local-up.sh`; tự trả stack về profile mặc định. Mật khẩu admin chỉ được khởi tạo lần đầu và lưu trong volume `nacos-data`: đổi `NACOS_PASSWORD` sau đó thì xóa riêng volume này (`docker volume rm local_nacos-data`) |
| Dừng / reset | `docker compose --env-file infra/local/.env -f infra/local/compose.yaml --profile nacos-compat down`; thêm `-v` chỉ khi chủ động xóa dữ liệu local |

Ports chỉ bind `127.0.0.1`: storefront 4173, Gateway 8080, PostgreSQL 5432 (cho chạy JVM
trên host). Smoke catalog hiện gồm CAT-01a category/product/variant/publish và CAT-01b
collection/size guide create/read/update/replay/stale qua Gateway; giữ401/403 với member thật.
CAT-03: intent → PUT PNG synthetic tới RustFS (`127.0.0.1:19000`, URL ký chỉ trong bộ nhớ) →
complete (route Gateway riêng, timeout 30s) → attach → publish → public GET 200/ETag/304 →
unpublish → 404. Hai route GET ảnh public/admin cũng có response budget Gateway 30s để giữ
503 storage sau timeout S3 10s; các route catalog khác vẫn 2s (regression GatewayBoundaryTest).
OPS smoke dùng JWT synthetic local; phạm vi, giới hạn và dữ liệu tích lũy ghi tại
[catalog README](../../services/catalog-service/README.md). Lệnh trong bảng không đổi.
Catalog và Nacos không publish port. CI tương ứng ở
`.github/workflows/application-ci.yml`, read-only, không có credential AWS.

Theo ADR-18, Sprint 1 chỉ nhắm checkpoint S1-local: development/deployment trên máy local, không AWS/Kubernetes/GitOps. Local workflow phải chạy được trên clean checkout bằng lệnh đã ghi; một smoke path đi qua frontend shell, Gateway, service mẫu và PostgreSQL. Không scaffold toàn bộ service hoặc gọi kết quả là G0/G1/G2.

Danh mục và bộ phiên bản đã chọn sau research R1 được quản lý tại [17 Tech Stack](17_tech_stack.md): Java 21/Boot 4.0 theo BOM; React/Vite SPA, Node 24 LTS/npm. O02 vẫn mở cho runtime compatibility toàn bộ, image digest, license/CVE và xác nhận đánh đổi SEO; lockfile và dependency tree của phần Sprint 1 đã có evidence local.

## 2. Cấu trúc dự kiến

```text
services/
  gateway/
  user-service/ catalog-service/ cart-service/ order-service/
  inventory-service/ payment-service/ promotion-service/
  shipping-service/ notification-service/
web/
  storefront/
  admin/
contracts/
  openapi/       # theo service
  events/        # JSON Schema + examples
infra/
  local/         # Compose, provider stubs, mail sink
  environments/  # manifests dev/staging/prod
tests/
  integration/
  e2e/
  load/
docs/
```

Chỉ tạo module Phase 1 cần dùng, promotion chưa cần deploy. Service chia controller/application/domain/repository/event/client/config khi có trách nhiệm tương ứng; không bắt buộc interface/factory cho mọi class. Common library chỉ chứa primitive dùng thật, không share JPA entity.

## 3. Onboarding checklist có đầu ra

| Bước | Thành viên cần làm | PLT-01/04 phải cung cấp |
|---|---|---|
| 1 | Có Git, JDK 21 distribution đã chốt, container runtime và Node đã chốt | Version matrix + cách kiểm tra version |
| 2 | Clone repo, đọc docs/README và task | Branch policy và ownership |
| 3 | Cấu hình local từ example | Biến môi trường có mô tả/default an toàn, không secret thật |
| 4 | Khởi động local dependencies | Compose command đã test; health wait và ports |
| 5 | Chạy migration/seed cấu hình | Lệnh Maven/Flyway thật, mỗi DB credential riêng |
| 6 | Chạy service/FE và smoke | Lệnh chạy, URLs thật, mock credentials synthetic |
| 7 | Chạy test tối thiểu | Unit + DB integration + contract + browser smoke command |
| 8 | Reset dữ liệu local khi cần | Script chỉ nhắm local env, có bảo vệ không xóa prod |

Fresh-clone proof do người khác tác giả thực hiện, ghi OS/version/lệnh/kết quả. Khi chưa có code, không tự ghi các lệnh npm/maven giả là đã hoạt động.

## 4. Configuration contract

| Nhóm | Cần cấu hình | Quy tắc |
|---|---|---|
| Database | URL/user/password/schema/service ID | Credential riêng, pool bounded, secret injection |
| Kafka | Bootstrap/topic prefix/group/client identity | Env isolation, TLS/SASL tùy platform, retry/DLQ policy |
| Redis | Endpoint/auth/key prefix | Prefix env/service, TTL, không là nguồn stock/quota |
| Gateway/auth | Issuer/audience/JWKS, caller token, allowed origins (`GATEWAY_ALLOWED_ORIGINS`) | Không trust client headers; internal private; service token ADR-19, CSRF ADR-20 |
| Business | Quote 2m, online 15m, COD 24h, fee rules | Version/config audit; thay deadline không đổi snapshot đơn cũ |
| Providers | Base URL, merchant/account IDs, secrets, callbacks | Sandbox/prod tách; không committed secret |
| Workers | Poll interval, lease duration, retry/backoff/max delay | Đo lease theo timeout; token CAS; long tasks renew lease |
| Observability | Exporter/log level/sampling | PII masking, không trace body secret |

Mỗi config ghi owner, required/default, environment và cách đổi/restart; không hot-reload business policy làm thay đổi giá/đơn đã tạo.

## 5. Quy trình code và PR

Main protected, feature branch ngắn theo TASK-ID; PR nhỏ có acceptance, test và doc links. Không cần branch staging lâu dài; artifact/environment ở manifests. Commit conventions do TL khóa PLT-01.

Contract thay đổi trước hoặc cùng implementation: producer và consumer review. API version /v1; additive optional field có compatibility test; breaking change cần migration/dual-read window được chốt trước. Không sửa response enum khiến client cũ coi UNKNOWN là SUCCESS.

Mỗi PR ghi: vấn đề/kết quả; scope; REQ/TASK; schema/API/event impact; validation evidence; config/rollback; open issues. Tác giả không tự coi approval thương mại đã có vì test pass.

## 6. Quy tắc backend

- Dùng Maven Wrapper và tổ hợp BOM tại 17; service nghiệp vụ MVC/JdbcClient, gateway WebFlux riêng. Không pin lại thư viện con do BOM quản lý nếu chưa có lý do/test; không tắt compatibility verifier.
- Transaction chỉ trong DB owner. Không gọi HTTP/provider trong transaction.
- Dùng decimal/integer VND, checked arithmetic; không float/double cho tiền.
- API/event boundary validate type/range/size/enum và ownership. Không lấy user_id/amount từ public input làm authoritative.
- Dedupe và business mutation cùng transaction; offset sau commit.
- Mutation retry cùng operation key; timeout giữ UNKNOWN, không bịa failure.
- Query pagination có limit; tránh N+1 quote/catalog mỗi SKU; index theo query plan.
- Durable steps có next retry, lease reclaim và metrics; không fire-and-forget tiền/kho.
- Secrets dùng injected config; log chỉ identifiers và outcome đã redacted.

## 7. Quy tắc frontend

Theo ADR-17, storefront/admin dùng Vite SPA, npm workspaces và một package-lock tại workspace root khi tạo code; không thêm lockfile package manager khác. Node chỉ phục vụ build/test. Test deep-link reload và SPA fallback tách API/callback. Xác nhận SEO với PO trước WEB-01; version, peer constraints và đường nâng cấp ở 17.

Contract typed từ OpenAPI khi có; không viết amount quyết định thu tiền. UI dùng allowed_actions và vẫn xử lý 403/409 từ server. Giữ Idempotency-Key của submission qua retry, không sinh key mới khi mất response. Token access in-memory, refresh cookie, refresh single-flight; CSRF theo deployment.

Mỗi màn có loading/empty/error/permission/version-conflict; VI/EN, keyboard focus và field labels. Xem 14 để biết copy của processing/refund/COD. Không hiển thị “thành công” chỉ dựa redirect URL.

## 8. Migration và CI

Flyway mỗi service, versioned append-only scripts; sửa migration đã deploy bằng migration mới. Expand → backfill → deploy compatible code → contract → drop sau cửa sổ tương thích. Backfill có batch/checkpoint, không khóa toàn bảng vô hạn.

CI ứng dụng cần hiện thực: formatting/static checks; unit; DB migration/constraint integration khi có schema; OpenAPI/event compatibility; secret/dependency scan; build immutable artifact; staging smoke. Payment/stock PR phải test race/failure path liên quan. Không dùng coverage % thay proof invariant.

CI thực có: Documentation CI (Python stdlib) và Application CI (toolchain, gitleaks,
Maven/Testcontainers, frontend, contract, offline infra, Compose smoke/Redis timeout, Playwright).
Kết quả phải đối chiếu đúng SHA trong evidence; không dùng CI cũ chứng minh patch mới.
Workflow publish-images/OIDC đã chuẩn bị, chưa publish/deploy AWS; dependency/image scan và
CD thực vẫn cần triển khai. Lộ trình và approval ở [16 AWS deployment](16_aws_deployment.md).

Rollback ứng dụng bằng image/manifests đã xác nhận; rollback schema chỉ khi có kế hoạch tested. Không drop dữ liệu tiền để quay lại migration cũ. Release cần biết phiên bản code nào đọc được schema mới.

## 9. Handoff một service

Service README thực tế phải có: responsibility; contract location; env vars; run/test commands; DB migration/version; topics producer/consumer; dependency timeout/ retry; health endpoints; metrics/alerts; seed/bootstrap; sandbox instructions; known limitations/owner. Tạo trong task service, không sao chép toàn PRD vào mỗi README.
