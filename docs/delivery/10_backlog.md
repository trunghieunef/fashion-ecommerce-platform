# 10 — Backlog để chia task

B1 · 2026-09-19 · Work package mặc định: **Planned; Assignee TBD; Reviewer TBD**; ngoại lệ implementation S1 được ghi bên dưới.

## 1. Cách nhận việc

Update 2026-10-20 — `TASK:SHP-02` **Done (local, trước G0) — MANUAL + incident cho bằng chứng sau hủy chờ quyết định (08)**
theo [nghiệm thu](../evidence/acceptance-2026-10-20.md) (PR #30 CI PASS trên `eb0b7c1`, đã merge `main` `03b3aa6`): OPS SELF events có bằng chứng (gán shipper, bàn giao đặt
`handed_over_at`, giao/thất bại/giao lại/hoàn), COD thu/nộp là event tài chính riêng, cancel trước bàn giao + tombstone
hủy-trước-tạo, 409 sau bàn giao; `REQ:SHP-02/04/06`; dependency SHP-01;
[spec](../superpowers/specs/2026-10-20-shp-02-self-events-cancel-design.md), [evidence](../evidence/shp-02-local-2026-10-20.md).

Update 2026-10-19 — `TASK:SHP-01` **Done (local, trước G0) — ngoại lệ O04 (phí mẫu), chuyển trạng thái thuộc SHP-02** theo [nghiệm thu](../evidence/acceptance-2026-10-19.md) (PR #29 CI PASS trên `afea3c6`, đã merge `main` `cb4fe9b`): shipping-service mới — rule phí SELF (tỉnh + trọng lượng từ catalog,
COD), estimate public, quote internal lưu DB, một shipment SELF/đơn (CREATED, SHIPMENT_CREATED outbox), OPS rules/shipments;
bảng phí mẫu vì O04 còn mở; `REQ:SHP-01/04`; dependency PLT-03, USR-02, CAT-02;
[spec](../superpowers/specs/2026-10-19-shp-01-shipping-design.md), [evidence](../evidence/shp-01-local-2026-10-19.md).

Update 2026-10-19 — `TASK:WEB-01` **Done (local, trước G0) — bản thô, chưa hoàn thiện chi tiết UI** theo [nghiệm thu](../evidence/acceptance-2026-10-19.md) (PR #28 CI PASS trên `c024084`, đã merge `main` `ff176ef`): storefront home/collection/listing/search/detail (SF-01/02/03), VI/EN,
360px–desktop, filter trên URL reset cursor, tồn tham khảo ba trạng thái, zoom, size guide; UI đơn giản theo chỉ đạo chủ
dự án (thẩm mỹ làm sau); `REQ:CAT-03/04/05/06`, `REQ:XCT-01/05`; dependency PLT-02 — dùng contract đã implement, PLT-02 vẫn
In progress (08); [spec](../superpowers/specs/2026-10-18-web-01-storefront-design.md),
[evidence](../evidence/web-01-local-2026-10-18.md).

Update 2026-10-18 — `TASK:CART-02` **Done (local, trước G0)** theo [nghiệm thu](../evidence/acceptance-2026-10-18.md) (PR #27 CI PASS trên `21ade69`, merge `main` tại `780b4cb`): cart-service `POST /api/v1/carts/merge` gộp giỏ guest vào giỏ
member (cần cả Bearer và cookie), khóa hai giỏ theo id, đã MERGED → `already_merged` không cộng lại (T19), adjustments
UNAVAILABLE/OUT_OF_STOCK/QUANTITY_CAPPED/CART_ITEM_LIMIT; `REQ:CART-03`; dependency CART-01;
[spec](../superpowers/specs/2026-10-18-cart-02-merge-design.md), [evidence](../evidence/cart-02-local-2026-10-18.md).

Update 2026-10-18 — `TASK:INV-03` **Done (local, trước G0)** theo [nghiệm thu](../evidence/acceptance-2026-10-18.md) (PR #27 CI PASS trên `21ade69`, merge `main` tại `780b4cb`): inventory `POST /internal/api/v1/inventory/returns` (chỉ
`order-service`) cộng lại on_hand cho hàng đã commit, idempotent theo `operation_key` (T17), tổng trả ≤ commit dưới lock
reservation; V004 `stock_returns` (+`evidence_ref`, unique theo reservation); `REQ:INV-07`; dependency INV-02;
[spec](../superpowers/specs/2026-10-18-inv-03-returns-design.md), [evidence](../evidence/inv-03-local-2026-10-18.md).

Update 2026-10-18 — `TASK:CART-03` **Done (local, trước G0)** theo [nghiệm thu](../evidence/acceptance-2026-10-18.md) (PR #26 CI PASS trên `461395e`, merge `main` tại `2f19354`): cart-service internal snapshot/checkout-cleanup cho order-service
(service token, chỉ caller `order-service`, actor `Cart-Actor`, idempotent theo `order_id`, giữ item đã sửa — T18) và
`subtotal_preview`/`shipping_fee_status: NOT_CALCULATED`; `REQ:CART-05/06`; dependency CART-01, PLT-03;
[spec](../superpowers/specs/2026-10-18-cart-03-checkout-snapshot-design.md), [evidence](../evidence/cart-03-local-2026-10-18.md).
Câu hỏi mở cookie guest cho ORD-01 ghi ở 08.

Update 2026-10-18 — `TASK:CART-01` **Done (local, trước G0)** theo [nghiệm thu](../evidence/acceptance-2026-10-18.md) (PR #25 CI PASS, merge `main` tại `b06b27a`): cart-service mới (guest cookie/member Bearer, version +
idempotency, preview giá/tồn tham khảo, không Redis); `REQ:CART-01/02/04`; dependency USR-01, CAT-02,
INV-01; [spec](../superpowers/specs/2026-10-18-cart-01-carts-design.md), [evidence](../evidence/cart-01-local-2026-10-18.md).

Update 2026-10-17 — `TASK:CAT-02` **Done (local, trước G0)** theo
[nghiệm thu 2026-10-17](../evidence/acceptance-2026-10-17.md) (02a PR #19 + 02b `da6d3af`, merge `main` qua PR #23 `0a6928e`;
CI PASS). 02b: (Redis cache-aside +
invalidation bền vững, tồn hiển thị từ inventory, sold từ ORDER_COMPLETED, Cache-Control);
`REQ:CAT-04/05/09`; [spec](../superpowers/specs/2026-10-17-cat-02b-cache-projections-design.md),
[evidence](../evidence/cat-02b-local-2026-10-17.md).

Update 2026-10-17 — `TASK:INV-02` **Done (local, trước G0)** theo
[nghiệm thu 2026-10-17](../evidence/acceptance-2026-10-17.md) trên `dev` `e73ec5d` (merge vào
`main` qua PR #22 `abd5cfb`); review PR #22 đã sửa, verify 587 test, smoke và CI PASS; evidence tại
[evidence INV-02](../evidence/inv-02-local-2026-10-16.md).
`REQ:INV-02`, `REQ:INV-03`, `REQ:INV-06`; dependency INV-01, PLT-02/03, SEC-01.
Acceptance: reserve all-or-nothing, commit/release/expiry, tombstone/query, T01–T05/T09
trên PostgreSQL thật, relay Kafka và 6 guard mutants. Done local không thay gate G0/G1/G2;
ORD-01/02 chưa có caller thật, INV-03 còn sau.

Update 2026-10-16 — `TASK:INV-01` **Done (local, trước G0)**: merge vào `main` qua PR #20/#21
(`a76442e`); chủ dự án chỉ định Claude Code nghiệm thu (không độc lập, cùng tác giả) sau khi chạy
lại verify 540 test và local-up/smoke PASS. Khung stock/ledger, catalog Kafka relay → inventory
inbox, admin read/adjust/import, internal availability và Gateway admin đã có. Follow-up PR20 còn
mở; chưa nghiệm thu G0/G1. Theo [nghiệm thu](../evidence/acceptance-2026-10-16.md),
[evidence](../evidence/inv-01-local-2026-10-15.md) và
[handoff INV-01](../superpowers/plans/2026-10-15-inv-01-handoff.md).

Mã trong cột Task là namespace TASK (ví dụ TASK:PAY-01); mã trong cột REQ là yêu cầu trong 02. Hai namespace độc lập, không coi task và requirement cùng hậu tố là cùng việc. Cột phụ thuộc chứa task ID, không phải REQ. “—” nghĩa không có dependency task; các gate D/O vẫn áp dụng theo 08.

Số ngày là khoảng person-day dự kiến, không phải cam kết deadline. Package > 3 ngày phải tách subtasks khi planning. Mỗi package gồm migration nếu có, contract, xử lý nghiệp vụ, API/event, test và tài liệu liên quan; acceptance dưới đây là điều kiện tối thiểu bổ sung DoD ở 09.

Review mặc định: BE bởi TL/BE khác; FE bởi FE/TL và PO cho UX; platform bởi TL + DEVOPS; tiền bởi BE reviewer + FINANCE UAT; QA report bởi TL + PO. Mỗi task gắn bằng chứng theo 11, không tick Done trước khi có.

### Sprint 1 commitment — local only

Sprint 1 nhận PLT-01, phần core PLT-02 và SEC-01 theo checkpoint S1-local ở 09. Local smoke phải đi qua frontend shell → Gateway → một service mẫu → PostgreSQL, có migration/health/test và lệnh fresh-clone đã review. Phần PLT-03 chỉ kéo vào khi smoke path cần trực tiếp và còn capacity.

PLT-04/05, AWS/K3s/ECR/ArgoCD, provider sandbox và các task business Phase 1 không thuộc commitment Sprint 1. Có thể chuẩn bị tài liệu/mock độc lập nhưng không chuyển In progress hoặc Done thay cho acceptance của task. Sprint 1 không được báo “MVP hoàn tất”; đây là nền tảng local để các sprint sau xây G1/G2.

### Implementation status — 2026-09-28

- `TASK:PLT-01` — **In progress**. Toolchain, catalog read sample/V001, Gateway boundary,
  storefront shell và Compose đủ chuỗi postgres → catalog → Gateway → storefront (nginx) có
  evidence local tác giả: Maven reactor 9 tests (PostgreSQL thật), Vitest, typecheck/build,
  local smoke và Playwright chuỗi thật desktop/360px, proof `nacos-compat` (config import,
  discovery `lb://`, reconnect sau restart Nacos). Evidence tại 17 §7.
- `TASK:PLT-02` core — **In progress**. `contracts/openapi/catalog.yaml` cho operation duy nhất
  đã implement, Redocly lint + validate example. Chưa có event schema (chưa có producer).
- `TASK:SEC-01` phần nền — **In progress**. Strip identity header, `/internal/**` 404, catalog
  không publish port, ports local chỉ `127.0.0.1`. Threat inventory/permission matrix/CSRF chưa làm.
- Chưa đánh dấu `PLT-01`, `CAT-01`, S1-local hay O02 Done: cần fresh-clone review bởi người khác
  tác giả (template `docs/evidence/s1-local-template.md`) và TL xác nhận.

### Verification update — 2026-10-02

- Đã xác minh Application CI và Documentation CI **PASS** trên commit `d16151d` ngày
  2026-09-30; link run và từng kiểm tra ở [evidence](../evidence/s1-local-2026-10-02.md).
- Sửa `mvnw.cmd` để đường dẫn project không kết thúc bằng backslash trong Java argument;
  regression Windows kiểm tra đường dẫn/tham số có khoảng trắng đã chạy RED → GREEN.
- Clone sạch cùng commit: docs tests/checker, frontend typecheck, OpenAPI lint và Compose
  config validation **PASS**. Chưa chạy đủ runtime vì gateway mạng công ty chặn toolchain
  và native dependency bằng HTTP 403; chủ dự án yêu cầu bỏ qua phần bị chặn trên máy này.
- Trạng thái package vẫn **In progress**; yêu cầu bỏ qua kiểm thử không thay acceptance.
  Windows wrapper patch chưa có CI remote; fresh-clone runtime/reviewer/TL acceptance còn mở.

### Fresh-clone review — 2026-10-04

- Clone sạch `a563f05` trên Windows với toolchain đúng R1: toolchain gate, docs, frontend,
  contract, negative/positive smoke, Playwright 2/2 và Nacos proof **PASS**; CI remote trên
  `a563f05` PASS. Chi tiết ở [evidence](../evidence/s1-local-2026-10-04.md).
- `./mvnw test` fail trên host múi giờ Việt Nam (`Asia/Saigon` bị PostgreSQL từ chối); sửa
  bằng surefire `-Duser.timezone=UTC` + regression, PASS 10/10. Patch đã commit tại
  `b62f963`; Application CI `37148002874` và Documentation CI `37148002811`
  trên đúng SHA đều **PASS**, link tại evidence ngày 2026-10-04.
- **S1-local: Accepted** ngày 2026-10-04; reviewer **Codex (GPT-6)** theo chỉ định
  trực tiếp của chủ dự án thay yêu cầu reviewer người/TL cho checkpoint này. Evidence
  ghi rõ review hồ sơ/CI, không phải Codex chạy lại fresh clone. Lệch Docker/Compose
  được chấp nhận riêng cho local; R1 không đổi.
- Phần S1-local của `TASK:PLT-01`, `TASK:PLT-02` core S1 và `TASK:SEC-01` nền S1 được
  chấp nhận. Parent PLT-01 vẫn **In progress**: PO xác nhận SPA đủ SEO cho MVP ngày 2026-10-18
  ([08](../design/08_decisions.md)); còn O02 core ngoài S1. Theo quyết định 2026-10-18, G0
  (PLT-04/05 staging, O02 scan) hoãn tới sau G1; O06 chốt trước ORD-01. Parent PLT-02/SEC-01 vẫn **In progress**, O02 vẫn mở cho Kafka/
  Redis, CVE/license, staging và SEO. Không coi S1-local là G0/G1/G2.
- Phase 0 local tiếp tục: `TASK:PLT-02` — **In progress**, assignee Codex (GPT-6),
  reviewer chủ dự án; envelope, payload schemas §5.3 cho event Phase 1A + order lifecycle,
  registry (schema/topic/partition key/aggregate_id theo §5.1/5.2), `openapi/common.yaml` (§1.2/1.3) và fixtures theo 03 đã có test local,
  [evidence Phase 0](../evidence/phase-0-local-2026-10-04.md). Contract core được Codex
  (GPT-6) duyệt và merge PR #3 ngày 2026-10-04. `TASK:PLT-03` — **In progress**, assignee
  Claude Code (agent), reviewer chủ dự án: `services/platform-durability` có
  outbox/inbox/idempotency/background_tasks/lease với 31 test PostgreSQL + Kafka thật
  (crash sau publish, lease hết hạn, cùng version không đảo sequence). PR #4 đã review/merge
  (sửa 2 P1 về ranh giới transaction); chưa có service nào dùng thư viện.
- `TASK:SEC-01` — **In progress**, assignee Claude Code (agent), reviewer chủ dự án.
  Chủ dự án chốt ADR-19 (service JWT ES256 tự ký), ADR-20 (CSRF Origin/Fetch-Metadata) và
  gitleaks pin digest. Đã có: `services/platform-security` (16 test sau review PR #5: 12 trường hợp phải từ chối, 3 trường hợp hợp lệ, 1 test không log token),
  `CsrfOriginFilter` ở Gateway (8 test), `scripts/scan-secrets.sh` trong Application CI,
  data inventory theo field và permission codes ở 13, cách nạp secret. Còn mở: JWT người dùng,
  `auth_version` và U03/U24 (USR-01/02), service đầu tiên nối service token. Actuator Gateway
  đã tách sang management port 9080 không publish (2026-10-04). Chủ dự án (PO) đã duyệt toàn bộ [bảng retention và ngoại lệ](../operations/13_operations_security.md)
  ngày 2026-10-04; O06 còn ngày hạn, giá trị cụ thể trong các khung thời gian, chi tiết policy,
  nhóm dữ liệu ngoài bảng và xác minh pháp lý; chưa triển khai cleanup/purge.
  O01 chỉ chặn phần cloud, không chặn PLT-02/03 local; chưa provision/deploy.
- `TASK:PLT-05` — **In progress, phần local** (2026-10-05, assignee Claude Code, reviewer chủ dự án):
  Gateway và catalog đã có trace W3C (smoke kiểm trace đi Gateway → catalog và quay về trong
  `metadata.trace_id`), log JSON ECS có redaction và `/actuator/prometheus` (Gateway trên port 9080).
  2026-10-18: correlation HTTP/Kafka xong ở local — outbox lưu `trace_parent`, relay mở span
  PRODUCER, consumer Kafka nối trace; Tempo 3.0.3 + Grafana 13.2.2 trong compose, smoke kiểm một trace
  Gateway → catalog → inventory → catalog ([evidence](../evidence/plt-05-kafka-trace-local-2026-10-17.md)). Merge `main`
  qua PR #24 (`2c9b032`), CI PASS. Gauge `outbox_oldest_pending_age_seconds` đã có từ PLT-03. Còn: dashboard/alert và trace trên staging (cần PLT-04).
- `TASK:USR-01` — **In progress, 1a + 1b-i đã code** (2026-10-05, assignee Claude Code, reviewer chủ
  dự án; Phase 1A bắt đầu trước G0 theo quyết định ở 08). 1a: đăng ký, đăng nhập có khóa 15 phút,
  refresh xoay vòng + thu hồi family khi dùng lại, logout, `USER_CREATED` qua outbox; Gateway route
  `/api/v1/auth/**`. 1b-i: đổi mật khẩu, quên/đặt lại mật khẩu (token một lần 30 phút, chỉ lưu
  hash), bàn giao secret mã hóa qua Redis 8.2.9 + `GET /internal/api/v1/users/notification-secrets/{id}`
  (service token ADR-19, chỉ notification-service), `NOTIFY_RESET_PASSWORD` vào registry.
  (1b-ii) đã code, đang Review: rate limit theo IP/email bằng Redis, 429 + `Retry-After`, fail closed.
  Còn reviewer nghiệm thu và NOT-01 tiêu thụ event.
- `TASK:USR-02` — **In progress, 2a + 2b đã code** (2026-10-05, assignee Claude Code, reviewer chủ dự án).
  2a: `AccessTokenVerifier` (platform-security), `/users/me` GET/PUT, sổ địa chỉ với đúng một mặc
  định (khóa dòng user), kiểm `auth_version`/status hiện tại; Gateway route `/api/v1/users/**`.
  2b: `V003` seed role/permission theo 13 §2, `audit_logs` append-only bằng quyền DB, claim
  `permissions` trong access token, `/admin/api/v1/users` (danh sách, đổi role, khóa) cần
  `user.manage`, admin bootstrap qua `USER_BOOTSTRAP_ADMIN_EMAIL`. Còn: UI admin (ADM-01), endpoint
  admin của service khác kiểm `permissions` khi được tạo; reviewer nghiệm thu.

Update 2026-10-06: `TASK:USR-01b-ii` (thuộc `TASK:USR-01`) — **Review**; REQ: USR-03/05,
XCT-02; dependency: PLT-02/03, SEC-01. Đã code Redis auth quotas, 429 + Retry-After,
fail closed 503, proxy allowlist/IP handoff và readiness Redis. Acceptance của phần này:
quota IP/email, normalization, expiry, concurrency, spoof header, Redis outage và liveness.
Parent chưa Done; NOT-01/relay và reviewer nghiệm thu vẫn còn mở. Kiểm thử và giới hạn ở
[evidence USR-01b-ii](../evidence/usr-01b-ii-local-2026-10-06.md).

Update 2026-10-07: `TASK:CAT-01` — **In progress**, phần 1a catalog admin đã code và kiểm tra
local, đã merge [PR #14](https://github.com/trunghieunef/fashion-ecommerce-platform/pull/14)
vào `main` tại `8f27663`; REQ CAT-01/02, dependency PLT-02/03 và USR-02. Có taxonomy,
product/variant, publish/unpublish, HTML sanitize, SKU immutable, version/idempotency/audit và
VARIANT_CREATED durable outbox. Acceptance phần 1a và bằng chứng tại
[evidence CAT-01a](../evidence/cat-01a-local-2026-10-07.md). CAT-01b collection/size-guide
đã thực hiện Task1–7 trên dev, V004/HTTP Testcontainers/Gateway smoke/full reactor281 PASS;
Task7 whole-branch review0Critical/0Important, Minor docs đã đồng bộ theo
[plan](../superpowers/plans/2026-10-07-cat-01b-catalog-admin.md)
và [evidence](../evidence/cat-01b-local-2026-10-07.md). Đã merge PR15 tại f5c64d9 ngày2026-10-07;
4Minor review đã sửa trong45d7610, full reactor286 PASS, Application/Documentation CI
trên đúng45d7610 đều SUCCESS. **CAT-03 safe media: In progress, đã hiện thực local** theo
[plan](../superpowers/plans/2026-10-08-cat-03-media.md) (spec đã duyệt 2026-10-08): V005/V006,
S3/RustFS, re-encode, upload intent/complete/status, attach + publish gate, public/admin read,
sweep/GC, route Gateway complete và GET ảnh 30s, smoke Gateway→RustFS; đã merge PR16 vào `main`
tại `5dbd088` ngày 2026-10-09, Application/Documentation CI SUCCESS; xem
[evidence](../evidence/cat-03-local-2026-10-08.md) và
[handoff](../superpowers/plans/2026-10-08-cat-03-handoff.md). Chưa deploy/staging và chưa Done
tới khi chủ dự án nghiệm thu CAT-03. Acceptance còn mở (hoãn): health group `media` và metric latency/lỗi S3 theo thao tác.
Chưa Done: còn review/nghiệm thu parent CAT-01 và CAT-03;
public read CAT-02 và relay/Kafka ngoài phạm vi 1a/1b. SKU mới canonical uppercase với V003,
SKU legacy không backfill.

Quyết định CAT-01b ngày 2026-10-07 (chủ dự án): PUT collection sang ACTIVE không yêu cầu
product ACTIVE. Public visibility là acceptance CAT-02 theo 03/06, không làm public read
trong CAT-01b; cần test collection rỗng/chỉ product chưa publish không được trả public,
start_at tương lai, đúng biên start_at/end_at, lọc bỏ product INACTIVE và unpublish product
ACTIVE cuối cùng sau khi collection đã được kích hoạt.

Update 2026-10-11 — nghiệm thu local: chủ dự án chỉ định Claude Code (agent) nghiệm thu thay
reviewer người/TL (tiền lệ S1-local); không độc lập vì cùng là tác giả. **Done (local, trước G0):**
`TASK:PLT-03`, `TASK:USR-01` (gồm 1b-ii), `TASK:USR-02`, `TASK:CAT-01`, `TASK:CAT-03`.
CAT-03 giữ follow-up mở đã hoãn: health group `media`, metric latency/lỗi S3 theo thao tác,
lifecycle 2 ngày, đo heap. Email reset thật thuộc NOT-01, UI admin thuộc ADM-01, public read
thuộc CAT-02; relay Kafka end-to-end/staging chưa kiểm. **Chưa nghiệm thu:** PLT-01 (SEO SPA,
O02), PLT-02 (O02, consumer review), SEC-01 (O06), PLT-05 (staging), PLT-04 (O01).
Bằng chứng và từng tiêu chí: [evidence nghiệm thu](../evidence/acceptance-2026-10-11.md).

## 2. Phase 0 — nền tảng

| Task | Owner role / ngày | Phụ thuộc | REQ | Đầu ra và acceptance |
|---|---|---|---|---|
| PLT-01 | TL+DEVOPS / 2–3 | — | XCT-06 | Kiểm thử bộ version R1 tại 17/ADR-16/17 để nghiệm thu O02 core; FE/PO xác nhận SEO cho SPA; Maven wrapper/BOM, npm lockfile, cấu trúc repo, Compose + env example; fresh clone chạy service mẫu/DB migration và frontend; lưu dependency tree, exact versions và compatibility evidence |
| PLT-02 | TL+BE+FE / 2–4 | PLT-01 | ORD-01/07, XCT-02 | OpenAPI core + event JSON Schema từ 03, examples/negative fixtures và mock; producer/consumer review; schema lint pass; preview Mermaid với renderer/version thống nhất; skeleton không được đánh dấu mọi endpoint đã implement |
| PLT-03 | BE / 2–4 | PLT-01, PLT-02 | ORD-02, PAY-07 | Outbox/inbox/idempotency/lease/background-task primitives tối thiểu; crash sau publish và lease hết hạn test; cùng version nhiều event không đảo sequence |
| PLT-04 | DEVOPS / 2–4 | PLT-01, SEC-01; **sau Sprint 1** | NFR-01/06 | AWS staging theo 16: credit/budget guardrails, namespace/DB/topics/secrets/ingress, OIDC→ECR→GitOps/ArgoCD, smoke/rollback; credentials mỗi DB; public internal route bị chặn; phần CI docs đã có file không thay acceptance deploy |
| PLT-05 | DEVOPS+BE / 1–3 | PLT-03, PLT-04 | NFR-01/03/04 | JSON logs, correlation HTTP/Kafka, RED metrics, dashboard/alerts mẫu; trace checkout giả đi qua 2 service; test redaction |
| SEC-01 | TL+BE / 2–3 | PLT-01 | USR-07/08, XCT-02/03/06 | Threat/data inventory, service identity và permission matrix, CSRF strategy, secret injection; spoof header/cross-service token/PII logs bị chặn; O06 có owner/hạn |

Subtasks PLT-04.A–F, owner theo vai trò, dependencies và acceptance ở [16 AWS deployment](../engineering/16_aws_deployment.md). Không cộng subtasks lần hai vào tổng package; estimate PLT-04 cần review lại sau sizing/compatibility spike. PLT-04 **In progress — chuẩn bị offline** (2026-10-04, chủ dự án chọn): template CloudFormation, workflow publish khóa, manifest Kustomize/Argo CD và policy tests đã có ([16 §5.1](../engineering/16_aws_deployment.md)); chưa provision, chưa có change set, chờ O01/PLT-04.A.

## 3. Phase 1 — identity, catalog, cart, stock

| Task | Owner role / ngày | Phụ thuộc | REQ | Đầu ra và acceptance |
|---|---|---|---|---|
| USR-01 | BE / 2–4 | PLT-02, PLT-03, SEC-01 | USR-01/03/05 | User/token migrations, register/login/refresh/logout/password; reset secret handoff + notification contract; concurrent refresh/replay, email race, revoke family pass |
| USR-02 | BE / 2–3 | USR-01 | USR-06/08, ADM-06 | Profile/address, roles/permissions, bootstrap admin audit; default-address race; OPS không refund, FINANCE không adjust stock; revoked admin bị chặn |
| CAT-01 | BE / 2–4 | PLT-02, PLT-03, USR-02 | CAT-01/02/03/06 | Catalog migration, CRUD/variant/collection/size-guide/admin; VARIANT_CREATED outbox, SKU immutable; publish validation và sanitize; không hard-delete lịch sử |
| CAT-02 | BE / 2–3 | CAT-01 | CAT-03/04/05/09 | Browse/filter/sort/search/cursor, batch quote, durable invalidation; public collection list/detail chỉ ACTIVE, trong [start_at, end_at), có ≥ 1 product ACTIVE; chỉ trả product ACTIVE; test biên thời gian/unpublish product ACTIVE cuối cùng; BEST_SELLING consume completed từ MVP; event replay không cộng lại, cursor ổn định cùng giá  **Done (local)** 2026-10-17: 02a [evidence](../evidence/cat-02a-local-2026-10-11.md), 02b [evidence](../evidence/cat-02b-local-2026-10-17.md), [nghiệm thu](../evidence/acceptance-2026-10-17.md). |
| CAT-03 | BE+DEVOPS / 1–3 | CAT-01, SEC-01 | CAT-11 | Scoped upload/attach + object storage config; validate content/type/size/owner; image alt/thumb; object chưa kiểm tra không public |
| INV-01 | BE / 1–3 | PLT-03, CAT-01, USR-02 | INV-01/08 | Stock schema/register SKU/import/adjust/ledger; duplicate SKU event không reset tồn; CSV lỗi không ghi một phần; adjustment dưới reserved bị từ chối |
| INV-02 | BE / 3–5 | INV-01, PLT-02/03, SEC-01 | INV-02/03/06 | Reserve multi-SKU/commit/release/expire/query, tombstones; T01–T05/T09 pass DB thật; lock ordering; hash sai snapshot 400 REQUEST_HASH_MISMATCH, khác hash đã lưu 409 RESERVATION_CONFLICT; evidence/status tại update đầu tài liệu |
| INV-03 | BE / 1–3 | INV-02 | INV-07 | Return/restore và cumulative guard dưới lock; operation key + evidence; T17 và return đồng thời không vượt commit |
| CART-01 | BE / 2–3 | USR-01, CAT-02, INV-01 | CART-01/02/04 | DB guest/member carts, cookie credential, versions, item APIs; Redis loss không mất giỏ; guessed cart_id bị 404 |
| CART-02 | BE / 1–2 | CART-01 | CART-03 | Merge locks/version và adjustments UI contract; retry/parallel chỉ cộng một lần; T19 |
| CART-03 | BE / 1–2 | CART-01, PLT-03 | CART-05/06 | Snapshot/cleanup APIs + fee-preview integration contract; T18, giữ item mới/sửa; cleanup retry không trừ lại |

## 4. Phase 1 — transaction và fulfillment

| Task | Owner role / ngày | Phụ thuộc | REQ | Đầu ra và acceptance |
|---|---|---|---|---|
| SHP-01 | BE / 2–3 | PLT-03, USR-02, CAT-02 | SHP-01/04 | Shipping rules/SELF fee, mapping địa chỉ O04, create/query/state/handed-over APIs; quote có expiry và phí COD rõ |
| SHP-02 | BE / 2–3 | SHP-01 | SHP-02/04/06 | SELF evidence events, cancel tombstone, COD collected/remitted payload; T15–T17; không SHIPPING ngay khi CREATED |
| PAY-01 | BE / 2–3 | PLT-03, USR-02, SHP-02 | PAY-03/07/08 | Payment/COD/close/query schema, closed_at, settlement gross-fee-net; T27; close-before-create ngăn URL mới, late success vẫn ghi tiền |
| ORD-01 | BE / 2–4 | CAT-02, CART-03, SHP-01 | ORD-01/05/07 | Order quote/create/read/ownership, immutable snapshots, idempotency lookup trước quote expiry; PRICE_CHANGED/202 contract; T21/T24 |
| ORD-02 | BE / 3–5 | ORD-01, INV-02, PAY-01, PLT-03 | ORD-02/09 | Durable saga steps/recovery, WAITING deadlines, lease token; background cart cleanup; T03/T07/T09/T23; no HTTP trong DB TX |
| ORD-03 | BE / 2–3 | ORD-02, SHP-02 | ORD-03/06, PAY-03 | COD confirm/expire/packing, shipment create, delivered event; T12; end-to-end qua SELF + FINANCE settlement |
| ORD-04 | BE / 2–4 | ORD-03, INV-03 | ORD-04/10, SHP-06 | Cancel/return intent, evidence rows, shipment UNKNOWN handling; T04/T16/T17; không báo CANCELLED trước giải phóng xong |
| PAY-02 | BE / 2–4 | PAY-01, ORD-02 | PAY-01/04/07 | VNPay adapter + profile fixture/source ở 15; create/query/IPN/return sandbox; amount/merchant/signature invalid không SUCCESS; T06/T07; O03 gate |
| PAY-03 | BE / 2–4 | PAY-01, ORD-02 | PAY-02/04/07 | MoMo adapter + callback/query fixtures; sandbox create/notify/return, pending/result-code mapping; duplicate callback một effect; O03 gate |
| PAY-04 | BE / 3–5 | PAY-02, PAY-03, ORD-04 | PAY-05/06, ORD-08 | Refund full/partial cả cổng, UNKNOWN worker/manual COD refund, reconciliation rows; T08/T10/T11/T26; FINANCE evidence; không retry reference mới |
| ORD-05 | BE / 2–3 | PAY-04, ORD-04 | ORD-02/08 | Online commit/late-payment/cancel completion integration; PAID sau commit; refund result query; T05–T09; event FAILED trễ không hạ paid |
| NOT-01 | BE / 2–3 | PLT-03, USR-01 | NOT-01/05/06 | Email sender + VI/EN seed templates, recipient snapshot, secret handoff reset password, leases/results; T22, expired secret không gửi; O07 gate |

## 5. Phase 1 — giao diện, chất lượng, vận hành

| Task | Owner role / ngày | Phụ thuộc | REQ | Đầu ra và acceptance |
|---|---|---|---|---|
| WEB-01 | FE / 3–5 | PLT-02 | CAT-03/04/05/06, XCT-01/05 | Storefront shell/list/detail/collection/zoom/size guide, responsive VI/EN, loading/empty/error; mock có contract, keyboard và mobile 360px |
| WEB-02 | FE / 2–4 | WEB-01, USR-01, CART-02 | USR-05/06/07, CART-01–04 | Login/profile/address/guest cart/merge, access token in-memory, refresh single-flight; version conflict và mất credential không làm mất item |
| WEB-03 | FE / 3–5 | WEB-02, ORD-01 | ORD-01/05/07, CART-05 | Quote confirmation/price changed/submit same key/202 polling/order history; không success từ redirect; browser interruption/resume test với mock |
| WEB-04 | FE / 2–3 | WEB-03, ORD-05, NOT-01 | ORD-04/08, PAY-03/05 | Tích hợp thật online/COD/cancel/refund status; email link không lộ credential; UAT VNPay/MoMo/COD; poll dừng terminal |
| ADM-01 | FE / 3–5 | USR-02, CAT-03, INV-01, PLT-02 | ADM-01/03/06/08 | Admin catalog/media/stock/users roles, validation/reason/version; không hiển thị hoặc gọi action trái quyền; audit link |
| ADM-02 | FE / 3–5 | ORD-05, SHP-02, PAY-04, ADM-01 | ADM-02/08, ORD-06/10, PAY-05/06 | OPS queue/packing/returns; FINANCE refund/settlement/discrepancy; 202/UNKNOWN không giả thành công; UAT một đơn < 1 phút thao tác |
| QA-01 | QA+BE / 2–3 | PLT-02, INV-02 | NFR-07 | Harness DB thật/provider stub, dataset/clock/fault injection; T01–T05/T09; report có invariants DB và seed |
| QA-02 | QA+FE+BE / 3–5 | WEB-04, ADM-02, QA-01 | NFR-07, XCT-02/05 | E2E + security/ownership + T01–T27 phần MVP; tách T20 social/T25 OTP và promotion Phase 2; regression hợp đồng producer/consumer |
| QA-03 | QA+DEVOPS / 2–3 | QA-02, PLT-05 | NFR-01–10 | Profile 100 concurrent, hot SKU, acceptance/readiness/error ratio; report p50/p95/p99, CPU/DB/lag và exclusions; O09 gate |
| OPS-01 | DEVOPS+BE / 2–3 | PLT-04, ORD-05, PAY-04 | NFR-06, XCT-06 | Backup/restore drill, replay bounded, incident runbooks; chứng minh RPO/RTO hoặc ghi gap chặn G2; không chỉ DB boot |
| OPS-02 | TL+PO+OPS / 1–2 | QA-03, OPS-01, NOT-01, ADM-02 | P0 toàn bộ | UAT/training, secrets/domain/provider/PII checks, release record G2; O01–O10 liên quan có kết luận; go/no-go có tên và evidence |

G1 có thể demo sớm từ ORD-03 + NOT-01 + UI mock đã tích hợp COD, trước khi toàn bộ PAY-02–04 hoàn tất. G1 không thay gate G2.

## 6. Phase 2 — hoàn thiện Release 1

| Task | Owner role / ngày | Phụ thuộc | REQ | Đầu ra và acceptance |
|---|---|---|---|---|
| USR-03 | BE+FE / 2–4 | USR-02, NOT-01 | USR-02/09, NOT-02 | OTP + email verify, secret TTL/code hash, rate limits, UI; T20/T25 phần auth; callback không log secret; SMS sandbox pass |
| USR-04 | BE+FE / 2–3 | USR-03 | USR-04 | Google/Facebook start/callback/link/unlink; email trùng yêu cầu proof; state/nonce invalid bị chặn |
| PRO-01 | BE / 3–5 | ORD-05, USR-03, PLT-03 | PRO-01/02/05, D11/D12 | Voucher quote/reservations/counters/per-user, tombstone, eligibility; T13/T14/T24; transaction all-or-nothing |
| PRO-02 | BE+FE / 3–5 | PRO-01, CAT-02, WEB-04 | PRO-03/04/06, CAT-09 | Campaign/flash quota/price display, overlap lock, end invalidation; giá effective tại checkout; T13/T14 với inventory và quota riêng |
| SHP-03 | BE / 4–6 | SHP-02, ORD-04, PAY-01 | SHP-02/03/06 | Tách 3 adapter subtasks GHN/GHTK/VTPL; fee/create/query/cancel/webhook/return/COD mapping có nguồn và sandbox; T11/T15–17; không giả HMAC chung |
| CAT-04 | BE+FE / 3–4 | CAT-03, ORD-03, WEB-02 | CAT-07/10 | Eligibility/review/edit/moderation/wishlist, upload ownership, 30 ngày/7 ngày/5 ảnh; replay không cấp trùng; guest không có eligibility |
| NOT-02 | BE+FE / 2–3 | NOT-01, INV-03, USR-03 | INV-04/05, CAT-08 | Restock subscribe/QUEUED/result/generation/daily dedupe và low-stock; ngưng khi completed; notification không ghi inventory DB |
| NOT-03 | BE+FE / 2–4 | NOT-01, USR-03 | NOT-03/05 | Template editor/preview/version, marketing campaign durable fan-out/consent/unsubscribe; opt-out trước send được tôn trọng |
| PAY-05 | BE+FINANCE / 2–3 | PAY-04, SHP-03 | PAY-06 | Batch online/COD reconciliation, gross/fee/net statements, idempotent import/source_ref; sai lệch có queue/audit |
| ADM-03 | BE+FE / 3–4 | PRO-02, CAT-04, NOT-03, PAY-05, ADM-02 | ADM-04/05/07, XCT-01 | UI marketing/moderation/reports/domain CSV; GMV/gross/net/refund/COD outstanding tách rõ; timezone VN; CSV chống formula injection |
| QA-04 | QA+DEVOPS / 3–4 | PRO-02, SHP-03, CAT-04, NOT-02, NOT-03, USR-04, PAY-05, ADM-03 | P1, NFR-07 | T01–T27 toàn bộ, 1.000 concurrent, privacy/consent/provider regressions, UAT G3 và report budget |

## 7. Phase 3 — chưa kéo vào MVP

Search nâng cao, PWA, in-app, analytics/A/B, tối ưu hot SKU/sharding, scale 10k concurrent và throughput Kafka là candidate epics. Chỉ tạo implementation tasks khi có metric/bottleneck và PO chọn ưu tiên; không pre-build payment attempts/multi-warehouse/split shipment.

## 8. Mẫu issue để copy

```text
TASK:<id> — kết quả mong muốn
Phase / priority:
Assignee / reviewer:
Estimate / capacity:
REQ IDs:
Docs: 03 operation; 05 schema; 06 flow; 14 screen
Scope / deliverables:
Dependencies (task IDs) / decisions Dxx/Oxx:
Acceptance: Given ... When ... Then ...
Tests: Txx/Uxx + happy/negative/race khi có
Observability / security / migration / rollback:
Evidence: PR, commit/image, report, staging demo
Status / blocker / updated_at:
```

Ví dụ tách INV-02: schema+constraints → reserve/key/hash → commit/release/tombstone → expiry/recovery → integration/race proof. Parent chỉ Done khi tất cả invariants T01–T05 đạt, không lấy từng subtask unit pass thay acceptance cha.

## 9. Quy tắc traceability

REQ → task → test ID → evidence nằm trong issue/test report, truy vết tổng hợp ở 11. Range như CART-01–04 nghĩa đủ từng yêu cầu 01,02,03,04. Gate “P0 toàn bộ” yêu cầu kiểm tra coverage từng requirement, không chỉ tổng số task Done. Nếu thiếu coverage, bổ sung task trước go-live và ghi thay đổi ở 08.
