# Phase 0 local — khởi động ngày 2026-10-04

`TASK:PLT-02` · REQ: ORD-01/07, XCT-02 · dependency: PLT-01 S1-local accepted.
`TASK:PLT-03` · REQ: ORD-02, PAY-07 · dependency: PLT-01, PLT-02.
Assignee PLT-02: Codex (GPT-6); reviewer: chủ dự án. Acceptance theo 09/10,
parent PLT-02 còn In progress, PLT-03 implementation chưa bắt đầu.

## Quyết định thực hiện

- S1-local đã được duyệt tại [evidence](s1-local-2026-10-04.md) theo chỉ định chủ dự án.
- Theo 03, envelope dùng `version/correlation_id`; sửa ví dụ plan dùng nhầm
  `event_version/trace_id`. Không đổi contract B1. Nếu theo tên sai, producer/consumer
  sẽ lệch wire format.
- O01 chỉ chặn cloud theo 08/10; local contracts không cần AWS account/budget.
- Chuẩn bị PLT-03: sửa SQL minh họa trong plan theo nguồn schema 05, gồm
  `aggregate_type`, `id`, `schema_version`, `consumer_name`, `IN_FLIGHT`,
  `next_attempt_at`, `published_at`; claim sequence đầu tiên chưa SENT và CAS lease
  còn hạn. Đây là thiết kế, chưa tạo migration/module hoặc chạy PostgreSQL/Kafka.
- JSON Schema dùng library `jsonschema`, không viết validator subset. Pin dependencies
  đang cài, gồm `rfc3339-validator` vì date-time format checker cần dependency này.
  CI dùng virtualenv, không cài vào Python hệ thống.

## Deliverable và kiểm tra local

[Contracts README](../../contracts/README.md) mô tả envelope Draft 2020-12, fixtures,
tooling và giới hạn. Payload vẫn chỉ là object, chưa phải schema từng event.

| Lệnh | Kết quả trong phiên này |
|---|---|
| `python -B -m unittest discover -s tests/contracts -p 'test_*.py' -v` trước schema | RED: thiếu file schema |
| Cùng lệnh sau schema/fixtures | GREEN: 5 tests PASS, có 11 negative fixtures và kiểm bigint boundary |
| `python -B -m unittest discover -s scripts -p 'test_*.py' -v` | 11 PASS, 1 skip Windows Maven wrapper vì JDK không có trong PATH |
| `python -B scripts/check_docs.py` | PASS |
| `git diff --check` | PASS |

Lúc tác giả chạy, checkout chưa có node_modules, Node trong PATH là 24.14.1 (lệch R1)
và không có Java, nên chưa chạy full `validate-contracts.sh` hoặc Maven. Phần đó được
bổ sung ở review dưới đây. Không commit/push/PR hoặc deploy.

## Review và kiểm tra bổ sung — Claude Code, 2026-10-04

Review diff chưa commit trên `b62f963` (đọc schema/tests/fixtures, đối chiếu 03 §5.1 và 05).
Toolchain đúng R1: Temurin 21.0.12.1+1, Node 24.21.0 / npm 11.19.0; Python 3.11.9.

| Lệnh | Kết quả |
|---|---|
| `npm ci` | PASS, 89 packages |
| venv mới + `pip install -r tests/contracts/requirements.txt`, so `pip freeze` | Lần đầu thiếu pin `typing_extensions` (referencing kéo vào khi Python < 3.13); sau khi thêm pin, freeze trùng khớp requirements |
| `bash scripts/validate-contracts.sh` với venv đã pin đứng đầu PATH | PASS: Redocly `catalog.yaml` valid; envelope 5/5 (`sys.prefix` là venv, jsonschema 4.26.0) |
| Cùng script với venv trống | Exit 1, in `Missing contract tooling: python3 -m pip install -r tests/contracts/requirements.txt` |
| `python3 -B -m unittest discover -s scripts -p 'test_*.py'` có JDK | PASS 12/12 |
| `python3 -B scripts/check_docs.py`; `git diff --check` | PASS 31 Markdown files; PASS |

Lưu ý: trong Git Bash trên Windows, đường dẫn venv dạng `C:/...` trong `PATH` bị tách ở
dấu `:` và lặng lẽ rơi về Python hệ thống; dùng `cygpath -u` (`/c/...`). Python hệ thống
máy này có sẵn `jsonschema`, nên lần chạy đầu của review (trước khi phát hiện) không phải
bằng chứng của venv đã pin; bảng trên là lần chạy lại đúng.

Sửa theo review:

1. Pin `typing_extensions==4.16.0` trong `tests/contracts/requirements.txt`.
2. `validate-contracts.sh` dừng rõ ràng khi thiếu `jsonschema`/`rfc3339_validator`;
   cập nhật bước cài requirements ở [template S1](s1-local-template.md) và
   [handoff S1](../superpowers/plans/2026-10-02-s1-local-machine-handoff.md).
3. PLT-01 parent giữ **In progress** (SEO SPA FE/PO, O02 core ngoài S1); chỉ phần S1
   được chấp nhận ([10](../delivery/10_backlog.md), [docs index](../README.md)).

Chưa có CI remote cho diff này; chưa chạy Maven vì diff không đổi Java. Review của agent
không thay producer/consumer review của parent PLT-02.

CI sau commit `ed4f8f5` (PR #2, đã merge vào `main`):
[Application CI 37149713261](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/37149713261)
và [Documentation CI 37149496139](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/37149496139)
PASS; bước Contract validation cài đủ 8 package đã pin vào venv và chạy envelope 5/5.

## PLT-02 contract core — Claude Code, TDD, 2026-10-04

Theo `superpowers:test-driven-development`: mỗi hành vi có test RED (đúng lý do) trước khi
thêm schema, rồi GREEN; mutation check cho các ràng buộc dễ mất. Toolchain như trên,
venv đã pin (`sys.prefix` kiểm tra mỗi lần chạy).

| Chu trình | RED (lý do đã xác nhận) | GREEN |
|---|---|---|
| Registry payload | Thiếu `contracts/events/registry.json` | `common`, `catalog-events` (CATALOG_CHANGED), registry; `event_type` lạ bị từ chối |
| Tiền VND / ORDER_CREATED | `ORDER_CREATED` chưa đăng ký; 11 negative cases fail | `order-events`; tiền integer 0..bigint, `VND`, method enum, items ≥ 1, user_id nullable UUID |
| Order lifecycle | CONFIRMED/PAID/CANCELLED chưa đăng ký; CANCELLED thiếu reason | `OrderLifecycle` + `unevaluatedProperties`; case camelCase đổi validator mong đợi sang `unevaluatedProperties` (vẫn từ chối) |
| Event Phase 1A | USER/VARIANT/INVENTORY chưa đăng ký; 7 negative cases | `user-events`, VARIANT_CREATED, `stock-events` |
| OpenAPI common | Thiếu `contracts/openapi/common.yaml` | `common.yaml` theo 03 §1.2/1.3; 9 tests |

Mutation check (sửa tạm rồi khôi phục): bỏ `unevaluatedProperties` ở `OrderCancelled`;
fixture `occurred_at` +07:00; `minimum: "6"` sai kiểu trong schema; example 400 của
catalog đổi code `OOPS` — đều làm test/lint FAIL như mong đợi.

Refactor: `catalog.yaml` dùng `Limit`/`Metadata`/`CorrelationId`/`ApiError` của
`common.yaml` (400 thu hẹp `VALIDATION_ERROR`; khớp record `ApiError` của catalog-service).
`validate-contracts.sh` chỉ lint API entrypoint (common lint qua `$ref`, tránh
`no-empty-servers`/unused-components giả) và kiểm thêm `yaml` trong tooling.

| Lệnh | Kết quả |
|---|---|
| `bash scripts/validate-contracts.sh` (venv đã pin) | PASS: Redocly catalog (resolve common), 19 contract tests |
| Python script tests (có JDK) / docs check / `git diff --check` | PASS 12/12 / PASS 31 Markdown files / PASS |

Không đổi Java/React; không chạy Maven/Playwright cho thay đổi chỉ-contract. Chưa có CI
remote cho thay đổi này.

## PLT-02 event routing — Claude Code, TDD, 2026-10-04

Khoảng trống: 03 §5.1/5.2 quy định topic, partition key và aggregate_id cho từng event nhưng
registry chỉ ánh xạ schema; outbox PLT-03 (`topic`, `partition_key`) cần đúng các giá trị này.

| Chu trình | RED (lý do đã xác nhận) | GREEN |
|---|---|---|
| Topic/key khớp 03 §5.2 | 9 failures: entry là string, chưa có topic/key (lần đầu là TypeError, sửa test thành assertion trước khi viết code) | `registry.json` dạng `{schema, topic, partition_key}`; loader payload đọc `schema` |
| aggregate_id theo 03 §5.1 | 9 failures: registry thiếu `aggregate_id` | thêm `aggregate_id` (user_id, product_id, sku, order_id) |
| Partition key bắt buộc | Pass ngay (schema đã bắt buộc) — test guard, chứng minh bằng mutation | — |

Mutation check (sửa tạm rồi khôi phục): topic `orders.events`; bỏ `order_no` khỏi `required`
của order lifecycle; `aggregate_id` fixture bằng `order_no` — đều FAIL như mong đợi.

| Lệnh (Temurin 21.0.12.1+1, Node 24.21.0/npm 11.19.0, venv đã pin, freeze khớp requirements) | Kết quả |
|---|---|
| `bash scripts/validate-contracts.sh` | PASS: Redocly catalog, 22 contract tests |
| `python3 -B -m unittest discover -s scripts -p 'test_*.py'` | PASS 12/12 |
| `python3 -B scripts/check_docs.py`; `git diff --check` | PASS 31 Markdown files; PASS |

Không đổi Java/React nên không chạy Maven/Playwright. Mock API và Mermaid renderer chưa
thêm: 17 §PLT-02 hoãn chọn tới khi có consumer/nhu cầu thật (FE mock khi WEB-01 bắt đầu).
Producer/consumer review vẫn cần người ký trong contracts README.

## Xử lý review PR #3 — Claude Code, TDD, 2026-10-04

Review của Codex (GPT-6) tại `f47bbbc`, gửi dạng COMMENT, gồm 2 finding P2. Cả hai đã được tái hiện trước khi sửa.

| Finding | Tái hiện | RED | GREEN |
|---|---|---|---|
| `order_item.quantity` cho tới 2147483647, 05 quy định 1–99 | quantity 100 và 2147483647: `payload_errors` = [] | negative case quantity=100 FAIL (không có lỗi `maximum`); thêm test biên 99 hợp lệ | `maximum: 99` trong `common.schema.json` |
| `json.loads` làm mất phần thập phân của tiền | `9007199254740992.5` → float `...992.0` → chấp nhận; `398000.0` cũng được chấp nhận | 2 negative case `type` cho `total_amount` FAIL | `load_json` dùng `parse_float=Decimal` cho mọi lần load fixture/case/schema trong tests/contracts |

Kết quả: `bash scripts/validate-contracts.sh` PASS (Redocly + 23 tests). Lưu ý cho PLT-03 và consumer
Java: Jackson mặc định bật `ACCEPT_FLOAT_AS_INT`, sẽ cắt phần thập phân khi đọc vào `long`; consumer
tiền phải tắt tùy chọn này hoặc kiểm tra kiểu token, không chỉ dựa vào schema test.

## PLT-03 durability primitives — Claude Code, TDD, 2026-10-04

`TASK:PLT-03` · REQ: ORD-02, PAY-07 · dependency: PLT-01, PLT-02 (contract core được
Codex (GPT-6) duyệt, PR #3 merge vào `main`). Module: [platform-durability](../../services/platform-durability/README.md).
Môi trường: Temurin 21.0.12.1+1, Maven Wrapper 3.9.16, Docker Desktop Engine 29.2.0 (lệch R1
như S1), Testcontainers 2.0.5, `postgres:17.11`, `apache/kafka:4.1.2`
(`sha256:5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e`).

| Chu trình | RED (lý do đã xác nhận) | GREEN |
|---|---|---|
| Inbox | Thiếu `InboxGuard` (compile) | `processed_events` + effect cùng transaction; duplicate đồng thời chỉ 1 effect; effect lỗi rollback inbox |
| Idempotency | Thiếu `IdempotencyStore` | STARTED / IN_PROGRESS / COMPLETED (trả lại response) / CONFLICT khi khác hash; scope theo actor + operation |
| Outbox | Thiếu `OutboxRepository` | append bắt buộc có transaction; sequence tăng kể cả cùng version; claim chỉ lấy head của aggregate; markSent CAS theo token và lease còn hạn; envelope đúng tên field 03 §5.1 |
| Lease | Thiếu `LeaseRepository` | lease cũ không ghi được kết quả; ghi kết quả lỗi thì giữ nguyên lease; tên bảng phải là identifier |
| Background tasks (05 §15) | Thiếu `BackgroundTaskRepository` | enqueue bắt buộc có transaction và dedupe theo (kind, business_key); claimDue/complete/fail CAS; retry rồi chuyển MANUAL |
| Kafka thật | Pass ngay (chỉ dùng code đã có) — chứng minh bằng mutation M1/M4 | crash sau publish → 2 record cùng `event_id`/value, consumer áp dụng 1 lần; 3 event cùng version đến theo sequence 1, 2, 3 |

Mutation check (sửa tạm rồi khôi phục, mỗi mutation đều làm test FAIL):
- M1 inbox luôn chạy effect → test duplicate đồng thời và test Kafka crash fail.
- M2 markSent bỏ kiểm lease hết hạn; M3 markSent bỏ CAS token.
- M4 claim bỏ chặn sequence trước → 3 test outbox và test thứ tự Kafka fail.
- M5 lease complete bỏ token; M6 idempotency bỏ so hash.
- B1 task complete bỏ token; B2 không bao giờ chuyển MANUAL; B3 enqueue không cần transaction.

| Lệnh | Kết quả |
|---|---|
| `./mvnw -pl services/platform-durability test` | PASS 31/31 |
| `./mvnw -B test` (toàn reactor) | PASS: platform-durability 31, catalog 8, gateway 2 |
| `bash scripts/validate-contracts.sh`; `bash scripts/verify-toolchain.sh` | PASS (23 contract tests); PASS |
| Test `scripts/`; `check_docs.py`; `git diff --check` | PASS; PASS 32 Markdown files; PASS |

Lệch so với plan Task 2: integration test đặt trong module (không tạo module
`tests/integration/platform` riêng); DDL là file tham chiếu để service chép vào migration
riêng, không phải migration dùng chung (tránh trùng version Flyway và DB chung); thêm
`correlation_id` vào outbox theo envelope 03. Chưa có CI remote, chưa có reviewer, chưa
có service nào tích hợp; chưa có backoff relay, metrics (PLT-05) hay retention cleanup.

## Xử lý review PR #4 — Claude Code, TDD, 2026-10-04

Review của Codex (GPT-6) tại `958e78c` gồm 2 finding P1 về ranh giới transaction; CI của PR đều PASS.
Cả hai finding đúng: `TransactionTemplate` mặc định REQUIRED nên join transaction của caller.

| Finding | Nguyên nhân | RED | GREEN |
|---|---|---|---|
| `applyOnce` trả về trước khi commit nếu caller đã có transaction → ACK offset rồi rollback thì mất event | join transaction ngoài | test transaction ngoài + rollback: không có ngoại lệ, ACK xảy ra | `applyOnce` từ chối khi đã có transaction |
| `relay` trong transaction nghiệp vụ publish outbox chưa commit và giữ lock | `claim` join transaction ngoài | test append + relay trong transaction ngoài: publisher bị gọi | `claim` (cả `relay`) từ chối khi đã có transaction |
| Cùng nguyên nhân, review chưa nêu | `BackgroundTaskRepository.claimDue`, `LeaseRepository.claim` | 2 test claim trong transaction | cùng guard |

RED: 5 test FAIL đúng lý do; GREEN: `./mvnw -pl services/platform-durability test` PASS 36/36.
README của module có thêm bảng ranh giới transaction.

## SEC-01 security baseline — Claude Code, TDD, 2026-10-04

`TASK:SEC-01` · REQ: USR-07, USR-08, XCT-02, XCT-03, XCT-06 · dependency: PLT-01.
Chủ dự án chọn trong phiên: ADR-19 (service JWT ES256 tự ký + allowlist), ADR-20 (CSRF bằng
Origin/Fetch-Metadata + SameSite), gitleaks v8.30.1 pin digest.

| Chu trình | RED (lý do đã xác nhận) | GREEN |
|---|---|---|
| Service token | Thiếu `ServiceTokenIssuer/Verifier` (compile). Lần chạy đầu 2 test hợp lệ bị từ chối: debug cho thấy Nimbus không tìm thấy key vì JWK không có `kid` | `platform-security`: ES256, `kid` = caller; 12/12 |
| CSRF Gateway | 4 test từ chối FAIL (request vẫn bị forward); 4 test không chặn nhầm pass sẵn | `CsrfOriginFilter`, `GATEWAY_ALLOWED_ORIGINS`; gateway 10/10 |
| Secret scan | Repo sạch FAIL vì chưa có script; test trên Windows gọi nhầm WSL bash nên đổi sang đường dẫn bash tuyệt đối | `scripts/scan-secrets.sh` + `.gitleaksignore` (1 fingerprint synthetic có lý do) |

Mutation check (sửa tạm rồi khôi phục): bỏ kiểm audience, bỏ giới hạn TTL, bỏ `sub = iss`,
bỏ kiểm thời hạn đều làm đúng test FAIL. Với scan: quét thư mục rỗng làm test token FAIL.
Lúc đầu bỏ `--redact` mà test vẫn PASS: assertion redact là rỗng vì gitleaks không in finding,
nên đã thêm `--verbose`; sau đó bỏ redact làm test FAIL.

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B test` | PASS: platform-durability 36, platform-security 12, catalog 8, gateway 10 |
| `bash scripts/scan-secrets.sh` | `no leaks found`, quét 26 commit (sau khi ghi 1 fingerprint synthetic) |
| Test `scripts/` (có Docker); `check_docs.py`; `validate-contracts.sh`; `verify-toolchain.sh`; `git diff --check` | PASS |
| `local-up.sh` → `smoke-local.sh` → `npx playwright test` | PASS; Playwright 2/2 |
| POST có cookie qua storefront :4173, `Origin: https://evil.example` / `http://localhost:4173` | 403 tại Gateway / qua được tới catalog (405 vì catalog chưa có POST) |

Lệch so với plan Task 3: threat model và data inventory được viết vào 13 (tài liệu chủ quản),
không tạo `docs/security/*` trùng nội dung. Chưa làm `ActorContext` và JWT người dùng vì
USR-01 chưa có issuer. Chưa có module `tests/integration/security`: test nằm trong module.
PII redaction trong log ứng dụng thuộc PLT-05; ở đây chỉ chứng minh verifier không log token.

## Xử lý review PR #5 — Claude Code, TDD, 2026-10-04

Review của Codex (GPT-6) tại `c6aff6a` gồm 2 finding P2 trong `ServiceTokenVerifier`; CI của PR đều PASS. Cả hai đều đúng.

| Finding | RED | GREEN |
|---|---|---|
| Token có `iat` ở tương lai được chấp nhận (issuer lệch đồng hồ +365 ngày thì dùng được cả năm) | `tokenIssuedInTheFutureBeyondSkewIsRejected` FAIL | validator `lifetime`: `iat ≤ now + 5s`, `exp > iat`, `exp ≤ iat + TTL + 5s`; test biên `iat` +3s vẫn hợp lệ |
| JWE gây `NullPointerException` thay vì từ chối | `encryptedJwtIsRejectedWithoutException` ERROR (NPE) | chỉ parse `SignedJWT`, nên JWE và token không ký lỗi ngay ở bước parse |

Mutation: bỏ chặn `iat` tương lai → FAIL; parse lại bằng `JWTParser` → ERROR. Bỏ `exp > iat`
thì test vẫn PASS vì Spring tự từ chối khi dựng `Jwt` có `exp ≤ iat`; giữ kiểm tra tường minh theo
yêu cầu review, test `tokenExpiringBeforeItWasIssuedIsRejected` bảo vệ hành vi này.
`./mvnw -pl services/platform-security test` PASS 16/16.

## SEC-01 — tách actuator Gateway khỏi port public, TDD, 2026-10-04

O06: chủ dự án (PO) ghi bảng retention đã duyệt vào 13 §3 (commit `58e5cf6`). Ngày hạn hoàn
tất và giá trị cụ thể trong các khoảng vẫn chưa chọn, nên chưa có job cleanup/purge.

| Bước | Kết quả |
|---|---|
| RED `actuatorIsNotServedOnThePublicPort` | `/actuator/health` trên port public trả 200 |
| GREEN `management.server.port: ${GATEWAY_MANAGEMENT_PORT:9080}` | Gateway 12/12 (thêm test readiness trên management port) |
| Compose healthcheck → `localhost:9080`; smoke kiểm `/actuator/health` public = 404 | `local-up` PASS, smoke PASS, Playwright 2/2 |
| `bash scripts/nacos-compat-check.sh` | Lần 1 FAIL do lỗi Docker `network ... not found` khi start nacos (lỗi môi trường, không có container cũ để xóa); chạy lại PASS 4/4 (config import, discovery, `lb://`, reconnect) |

## PLT-04 chuẩn bị offline — Claude Code, TDD, 2026-10-04

`TASK:PLT-04` (A, C, D, E) · REQ: NFR-01/06 · dependency: PLT-01, SEC-01; O01 chưa duyệt.
Chủ dự án chọn "chuẩn bị PLT-04, chưa tạo gì". **Không có lệnh AWS nào được chạy**, không đăng nhập,
không tạo change set hay tài nguyên.

| Chu trình | RED (lý do đã xác nhận) | GREEN |
|---|---|---|
| OIDC + ECR | Thiếu `identity-and-ecr.yaml` | Trust policy so khớp chính xác `repo:trunghieunef/fashion-ecommerce-platform:environment:staging-publish` và `aud`; role chỉ push 3 repo; 6 test |
| Workflow publish | Thiếu `publish-images.yml` | Chỉ chạy sau Application CI trên `main` (push), khi `AWS_PUBLISH_ENABLED`; OIDC trong environment; action pin SHA; 5 test. Một assertion ban đầu bắt nhầm chữ "secrets." trong comment, đã sửa test |
| Stack staging | Thiếu `staging.yaml` | 8 test (loại tài nguyên, chỉ 80/443, IMDSv2 hop 1, gp3 mã hóa + Retain, K3s kiểm checksum, role tối thiểu, S3 private/TLS, retention 30–90 không mặc định, budget trước credit). Một assertion ban đầu bắt nhầm `| sha256sum`, đã sửa thành regex |
| Manifest + Argo CD | Kustomize render lỗi, thiếu file | 13 test trên bản render (namespace, ingress, ClusterIP, digest, probes/resources/securityContext, không Secret, credential theo service, default deny, render `--strict`, bản copy init script khớp local, Argo CD scope) |

Mutation check (sửa tạm rồi khôi phục, đều FAIL đúng test): trust policy wildcard, thêm `ec2:RunInstances`,
dùng secret dài hạn, bỏ công tắc bật, cài K3s bằng pipe script, mở SSH, IMDS hop 2, budget tính sau
credit, ingress `/internal` tới catalog, Service lộ port 9080, overlay `newTag: latest`, mật khẩu
plaintext, gateway bỏ non-root. Mutation đầu tiên đổi tag ở base không có tác dụng vì overlay ghi đè digest.

| Lệnh | Kết quả |
|---|---|
| `bash scripts/validate-infra.sh` (venv đã pin, freeze khớp) | cfn-lint sạch; 32 test PASS |
| `bash scripts/render-staging.sh --strict` | exit 1 như mong đợi (digest placeholder) |
| gitleaks `dir` trên thư mục mới | Không có finding mới; chỉ token Nacos synthetic đã biết trong `.env.example`/`.env` local |

Còn mở: số liệu chi phí và phê duyệt O01/A; tạo environment/biến GitHub; AMI/checksum/retention;
cơ chế refresh credential ECR cho K3s; domain/TLS; Secret DB ngoài Git; storefront chạy root.

## Xử lý review PR #6 — Claude Code, TDD, 2026-10-05

Review của Codex (GPT-6) tại `a472fd3` gồm 1 P1 và 2 P2 trong cấu hình staging; CI của PR đều PASS.
Cả 3 finding đều đúng khi đối chiếu template. Chưa tái hiện được trên AWS/K3s vì chưa được phép deploy.

| Finding | Nguyên nhân | RED | GREEN |
|---|---|---|---|
| P1: Ingress chưa ép HTTPS | `spec.tls` chỉ cấp chứng chỉ; Traefik vẫn gắn router vào entrypoint `web`, SG mở port 80 | test yêu cầu annotation `router.entrypoints: websecure` + `router.tls: "true"` FAIL | thêm 2 annotation; HTTP không có router cho app |
| P2: first boot có thể chưa có Internet | subnet không tự cấp public IP, EIP gắn sau khi tạo instance, `curl` chỉ thử một lần với `set -e` | test yêu cầu `DependsOn` route và `curl --retry-all-errors --retry-max-time` FAIL | `DependsOn: [DefaultRoute, PublicSubnetRoutes]`; curl retry tối đa 900 giây |
| P2: backup sống tới 2 lần thời hạn | versioning bật nên Expiration chỉ tạo delete marker, bản noncurrent sống thêm N ngày | test tính thời gian lưu gồm mọi version FAIL | bỏ versioning; dữ liệu bị xóa sau đúng N ngày kể từ khi tạo (lifecycle trễ khoảng một ngày) |

`bash scripts/validate-infra.sh` PASS: cfn-lint sạch, 34 test. Bằng chứng runtime (HTTP không trả app,
K3s cài được ở first boot, thời điểm xóa backup thực tế) phải lưu khi deploy có phê duyệt.

## PLT-05 phần local, Gateway — Claude Code, TDD, 2026-10-05

`TASK:PLT-05` · REQ: NFR-01/03/04, XCT-06 · dependency: PLT-03; phần staging chờ PLT-04.

| Chu trình | RED (lý do đã xác nhận) | GREEN |
|---|---|---|
| Trace W3C | upstream không nhận `traceparent` khi client không gửi (test tiếp nối trace pass sẵn vì Gateway chuyển nguyên header) | `spring-boot-micrometer-tracing-opentelemetry`; lần đầu vẫn FAIL vì module này không kéo bridge, thêm `micrometer-tracing-bridge-otel` |
| Redaction | thiếu `LogRedactor` | 6 test: email, Bearer/JWT, phone VN, `password=`/`otp:`, giữ nguyên ID/số tiền |
| Log JSON | không có dòng log JSON đã che | ECS + `PiiRedactingJsonCustomizer`; assertion ban đầu sai format (ECS của Boot 4 lồng `log.level`), đã sửa test theo output thật |
| Metrics | `/actuator/prometheus` 404 | `micrometer-registry-prometheus`; có `http_server_requests_seconds_count`, không có query string trong label, port public vẫn 404 |

Mutation: bỏ cấu hình customizer làm test log FAIL. `./mvnw -B -pl services/gateway -am test`:
platform-security 22, gateway 16 PASS. Docker Desktop không chạy trong phiên này nên chưa chạy
catalog, Compose, smoke và Playwright.

## PLT-05 phần local, catalog — Claude Code, 2026-10-05

| Bước | Kết quả |
|---|---|
| RED: 2 test trace (tiếp nối `traceparent`; trace mới dạng 32 hex) | FAIL: catalog trả UUID ngẫu nhiên |
| GREEN: `Tracer` vào `ProductQueryController`, tracing OTel + Prometheus + log ECS | catalog 12/12 |
| Log JSON và Prometheus của catalog | Cấu hình được thêm **trước** test (lệch TDD). Bù lại: viết 2 test rồi mutation; bỏ expose `prometheus`, bỏ customizer, bỏ format ECS đều làm đúng test FAIL |
| Smoke | Thêm kiểm tra `traceparent` qua Gateway → catalog quay về đúng trong `metadata.trace_id` |

Lệnh đã chạy (Docker Desktop 29.2.0): `./mvnw -B test` PASS (durability 36, security 22,
catalog 12, gateway 16); `local-up.sh` → `smoke-local.sh` PASS; Playwright 2/2; Nacos proof 4/4;
log container Gateway đã ra JSON ECS.

## Xử lý review PR #7 — Claude Code, TDD, 2026-10-05

Review của Codex (GPT-6) tại `39478d0` gồm 2 finding P2 về redaction; CI của PR đều PASS. Cả hai đều đúng.

| Finding | Nguyên nhân | RED | GREEN |
|---|---|---|---|
| Field MDC/key-value nhạy cảm ghi nguyên văn | customizer chỉ xử lý giá trị chuỗi, không xét tên field | test log JSON thật của Gateway với `MDC password`, key-value `token` và `otp` số FAIL | che theo tên field (`isSensitiveName`) với mọi kiểu giá trị, sau đó regex cho chuỗi |
| Regex bỏ lọt JSON và giá trị có ngoặc kép | key không cho dấu `"`, giá trị dừng ở khoảng trắng | 4 test `LogRedactorTest` FAIL (thêm stub `isSensitiveName` để thấy lỗi hành vi thay vì lỗi compile) | key có ngoặc tùy chọn, tên ghép, giá trị trong `"…"`/`'…'`; giữ nguyên dấu phân cách |

Mutation: bỏ nhánh che theo tên làm test MDC/key-value FAIL. `./mvnw -B test` PASS (durability 36,
security 26, catalog 12, gateway 17).

## Tiếp theo

PLT-03: chạy CI remote, reviewer duyệt, sau đó service producer đầu tiên (CAT-01/USR-01)
chép DDL vào migration và dùng relay/consumer thật. Song song: phần còn lại của SEC-01.
Không đánh dấu PLT-02/03 Done hoặc G0 accepted từ test thư viện.
