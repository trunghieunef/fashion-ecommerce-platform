# 08 — Quyết định, giả định và vấn đề còn mở

B1 · 2026-09-19 · Chủ trì: PO + TL · Người phê duyệt cụ thể: chưa gán.

## 1. Cách sử dụng

Baseline hợp nhất dùng để estimate, xây contract/mock và triển khai phần độc lập. Trước khi khóa acceptance hoặc bật tính năng thật, PO/TL ghi quyết định, tên, ngày và bằng chứng vào dòng tương ứng. Không cần chờ mọi vấn đề mở để bắt đầu Phase 0.

## 2. Giả định D01–D12

| ID | Baseline đang dùng | Trạng thái / người xác nhận | Ảnh hưởng nếu đổi | Gate |
|---|---|---|---|---|
| D01 | Một shop, một kho logic | Giả định — PO | Allocation, kho, điều chuyển | Trước INV-01 |
| D02 | PostgreSQL quyết định stock/quota; Redis cache | Thống nhất kỹ thuật — TL review migration | Đổi nguồn sự thật cần ADR mới | Trước INV-02 |
| D03 | Giá server quote, snapshot lúc tạo đơn | Kỹ thuật thống nhất; PO xác nhận UX | Quote, thời hạn và giữ giá | Trước ORD-01 |
| D04 | Order điều phối checkout duy nhất | Thống nhất kỹ thuật — TL | Không thêm handler commit từ payment event | Trước ORD-02 |
| D05 | Online 15 phút; COD 24 giờ chờ OPS | Giả định — PO/OPS | Config deadline, UX, abuse và race | Trước ORD-03 |
| D06 | Một payment/order; một giao dịch chính bên cổng | Giả định — PO/FINANCE | Đổi cổng cần payment attempts | Trước PAY-01 |
| D07 | Tiền đến sau đóng đơn/hết reserve: hoàn toàn bộ | Giả định — PO/FINANCE | Saga và UI | Trước PAY-04 |
| D08 | Một shipment/order, không giao một phần | Giả định — PO/OPS | Split shipment ảnh hưởng kho/tiền/FE | Trước SHP-01 |
| D09 | Khách hủy WAITING_PAYMENT/CONFIRMED; OPS trước bàn giao | Giả định — PO/OPS | Sau bàn giao phải return và kiểm đếm | Trước ORD-04 |
| D10 | Refund toàn phần/một phần; đổi trả sau giao qua OPS | Giả định — PO/FINANCE | Điều kiện hàng, phí, hạn, bằng chứng | Trước G2 |
| D11 | Voucher giới hạn/người cần tài khoản xác minh | Giả định — PO | Cookie guest không đại diện một người | Trước PRO-01 |
| D12 | Một voucher/order; không áp voucher nếu có campaign item | Giả định — PO/MARKETING | Pricing/quota/rounding/FE cùng đổi | Trước PRO-01 |

Gate task là work package trong [backlog](../delivery/10_backlog.md). Mã task có thể trùng requirement ID; ghi rõ “task” hoặc “REQ” khi dẫn.

## 3. ADR kỹ thuật của baseline

| ID | Quyết định | Lý do / giới hạn |
|---|---|---|
| ADR-01 | Giữ 9 boundary service | Theo brief; promotion deploy Phase 2; không sinh service phụ |
| ADR-02 | Java 21, React TypeScript; định hướng Boot 3 ban đầu được thay bởi ADR-16 | Giữ lịch sử lựa chọn; bộ version hiện hành tại 17, runtime compatibility vẫn phải qua PLT-01 |
| ADR-03 | PostgreSQL per service, Flyway | Không cần 9 server; FK nội bộ; credentials riêng |
| ADR-04 | Durable order saga và recovery worker | Phục hồi crash/timeout bằng lease; không chỉ đợi event |
| ADR-05 | Outbox at-least-once và inbox dedupe | Producer idempotence không bảo đảm exactly-once xuyên DB |
| ADR-06 | REST command từ order; Kafka event trạng thái | Không cho REST/event cùng tạo side effect |
| ADR-07 | Cart guest/member lưu DB | Redis mất không mất giỏ; cookie chứa credential ngẫu nhiên |
| ADR-08 | Cart cleanup qua REST duy nhất | Durable cleanup work; giữ item đã sửa; không cleanup thêm từ event |
| ADR-09 | Notification chỉ gửi từ notification.events | Dedupe nghiệp vụ; không gửi lần hai từ domain topic |
| ADR-10 | SELF shipping từ MVP | Có giao hàng, thu/đối soát COD xuyên suốt; carrier ngoài Phase 2 |
| ADR-11 | 202 khi saga chưa chuẩn bị xong | Đo tiếp nhận và thời gian URL riêng |
| ADR-12 | Gateway/Nacos/Kubernetes/GitOps | Giữ stack; internal route private; Nacos metadata riêng |
| ADR-13 | Constraints và row locks cho stock/refund/quota | Load test hot SKU trước khi tối ưu |
| ADR-14 | State machine ở 06, schema ở 05 | PRD/diagram tham chiếu để tránh nhiều bản lệch |
| ADR-15 | AWS theo lựa chọn chủ dự án; credit $200 theo thông tin cung cấp | Đã chọn provider, chưa kiểm chứng Billing/duyệt chi phí; đề xuất EC2 + K3s staging ở 16, không phải quyết định production |
| ADR-16 | R1 chọn Java 21 LTS + Boot 4.0.8 + Cloud 2025.1.3 + Alibaba 2025.1.0.0; Nacos server 3.2.4/client 3.1.1 | Research theo yêu cầu chủ dự án ngày 2026-09-19; thay phần Boot 3 của ADR-02 để tránh train Cloud hết hỗ trợ. Không chọn Boot 4.1 ngoài mapping Alibaba; exact patches chưa test. Nguồn và gate tại 17; TL nghiệm thu PLT-01, chưa duyệt production |
| ADR-17 | React + Vite SPA cho storefront/admin, Node 24 LTS + npm; static hosting | Ưu tiên ít runtime và dễ deploy. Giả định MVP chưa bắt buộc SEO/HTML sản phẩm từ server; **PO xác nhận SPA đủ cho MVP ngày 2026-10-18** (§ quyết định 2026-10-18). Nếu không đạt, mở lại lựa chọn SSR/prerender; không tự thêm Next.js/BFF. Version/peer constraints ở 17 |
| ADR-18 | Sprint 1 chỉ triển khai local; AWS/cloud bắt đầu sau checkpoint S1-local | Chủ dự án chốt 2026-09-19. Sprint 1 hoàn thành nền tảng local chạy từ fresh clone, không đồng nghĩa toàn bộ G2 MVP; không provision AWS/K3s/ECR/ArgoCD hoặc gọi provider thật. Phạm vi và exit ở 09/10 |
| ADR-19 | Service identity cho `/internal`: mỗi service tự ký JWT ES256 bằng khóa riêng (`iss = sub = kid =` tên service, `aud =` service đích, TTL 60s); service đích kiểm tra theo allowlist public key của từng caller, sau đó endpoint kiểm tra caller allowlist 03 §4 | Chủ dự án chọn 2026-10-04 (SEC-01). Chạy được cả Compose local và K3s, không cần CA hay service mesh. Phương án khác: K8s service-account token (local không có), mTLS (nặng vận hành). Private key qua Secret, public key là config. Hiện thực tại `services/platform-security`; chưa có service gọi internal thật |
| ADR-20 | CSRF cho request ghi dùng cookie: kiểm `Origin` theo allowlist, nếu không có Origin thì chỉ nhận `Sec-Fetch-Site: same-origin`, không có cả hai thì từ chối; cookie `Secure`, `HttpOnly`, `SameSite`. Không dùng CSRF token | Chủ dự án chọn 2026-10-04 (SEC-01). Thực thi tại Gateway (`CsrfOriginFilter`, `GATEWAY_ALLOWED_ORIGINS`). FE không cần gửi token. Request chỉ dùng Bearer không bị kiểm. Nếu sau này cần double-submit token thì ghi ADR mới |
| ADR-21 | Access token member/admin: JWT ES256 do user-service ký (`iss=user-service`, `aud=fashion-api`, TTL 900 s, `auth_version`, `permissions` = mảng permission code của các role, `kid`; thêm `permissions` 2026-10-05 ở USR-02b); verifier nhận public key qua config, xoay khóa bằng cách allowlist 2 `kid`. Không có endpoint JWKS | Chủ dự án chọn 2026-10-05 (USR-01). Cùng mô hình ADR-19, không phụ thuộc mạng lúc verify. Phương án khác: ES256/RS256 + JWKS (tự xoay khóa nhưng thêm route/cache). Private key qua Secret; local do `local-up.sh` sinh vào `.env` |

| ADR-22 | INV-01 dùng platform-messaging tối thiểu (Spring Kafka + durability): relay chờ ACK, inbox commit rồi ack, DLT giữ raw bytes/header và chờ ACK; consumer inventory-service đọc earliest khi group mới. Kafka local KRaft combined single-node, 3 partitions/RF 1, PLAINTEXT, chỉ loopback; health kafka riêng, không readiness | Chủ dự án duyệt spec/plan 2026-10-15; ba làm rõ Task1 đã duyệt. Không Kafka transaction/schema registry/replay tool, không bật relay user-service. F8 duyệt 2026-10-16: ledger key user:actor:operation:key, cùng phạm vi actor/operation/header key của idempotency. SASL/TLS/HA thuộc staging, INV-02 reserve/commit/release chưa làm |

Làm rõ ADR-22 bởi chủ dự án 2026-10-16 (F15): envelope hỏng → DLT; event_type
không có handler → ack/no inbox và debug log chỉ event_id, không registry gate runtime
ở consumer; loại có handler sai version/payload → PermanentEventException/DLT.
Không mở rộng DLT cho mọi loại event chưa được service sử dụng.

## 4. Vấn đề mở

| ID | Cần quyết định / đầu ra | Owner | Hạn gate | Có thể làm trước |
|---|---|---|---|---|
| O01 | AWS đã chọn nhưng hoãn khỏi Sprint 1; còn plan/số dư/hạn credit $200, region, ngân sách gộp và tiền tự trả, staging sizing, managed DB/Redis và prod cluster | PO + DEVOPS | Sau S1-local, trước PLT-04 / G2 | Sprint 1 local không bị chặn; đề xuất EC2 + K3s, cost worksheet và subtasks tại 16; chưa tạo tài nguyên |
| O02 | R1 đã chọn bộ version ở 17; còn nghiệm thu runtime compatibility, lockfile/digest, license/CVE (SEO đã đóng: PO chấp nhận SPA 2026-10-18; scan hoãn tới sau G1) | TL + DEVOPS + FE | PLT-01 core; PLT-02/04/05 theo tooling | [S1-local accepted ngày 2026-10-04](../evidence/s1-local-2026-10-04.md) bởi Codex (GPT-6) theo chỉ định chủ dự án, runtime Windows và CI patch `b62f963` PASS; lệch Docker/Compose chấp nhận riêng cho local. Còn Kafka/Redis, image scan/CVE/license, deployment và xác nhận SEO; không tắt verifier/ép peer dependencies để vượt lỗi |
| O03 | Merchant sandbox/production, callback domains, quyền query/refund VNPay/MoMo | PO + FINANCE | PAY-02/03 / G2 | Mock và negative tests |
| O04 | Phí ship/COD, vùng SELF, nguồn địa chỉ, carrier account/SLA | PO + OPS | SHP-01/03 | Fixture giả và adapter |
| O05 | Đổi trả, phí, hàng hỏng, bằng chứng, đơn tổng 0; giá đã gồm thuế hay chưa và yêu cầu hóa đơn | PO + FINANCE | ORD-01/04 / G2 | Baseline không cộng thuế ngoài giá niêm yết; chặn checkout total <= 0; nếu cần thuế tách dòng phải sửa pricing/schema trước code |
| O06 | Retention PII, điều khoản/consent, nghĩa vụ pháp lý hiện hành | PO + phụ trách pháp lý | SEC-01 / G2; ngày hạn hoàn tất: trước G2 | Chủ dự án (PO) duyệt toàn bộ bảng retention và ngoại lệ tại [13 §3](../operations/13_operations_security.md) ngày 2026-10-04, theo xác nhận trong phiên làm việc. Ngày 2026-10-20 agent **đề xuất** giá trị cụ thể cho từng nhóm (gồm traces, outbox/inbox, idempotency, notification, consent, guest credential) tại 13 §3 theo yêu cầu "chốt O06" của chủ dự án; chủ dự án **xác nhận giữ nguyên** cùng ngày (sau review PR #31) — đủ cho ORD-01. Còn mở tới G2: phụ trách pháp lý xác minh áp dụng luật và mốc 10 năm chứng từ, nội dung điều khoản/consent; chưa bật purge tài chính |
| O07 | Email/SMS provider, domain gửi, consent, query/retry hỗ trợ | PO + DEVOPS | NOT-01 / G2; OTP trước G3 | Mail sink và contract |
| O08 | Headcount, capacity, người QA, tên owner/reviewer | PO + TL | Planning đầu | Estimate theo role; chưa cam kết ngày |
| O09 | Bộ tải/dữ liệu/hạ tầng/ngân sách chứng minh SLO | TL + QA + DEVOPS | QA-03 / G2 | Script và synthetic dataset |
| O10 | Nội dung/ảnh/size guide VI/EN, brand assets, điều khoản bán | PO + FE | WEB-01 / G2 | UI với fixture ghi rõ là mẫu |

## 5. Nhật ký hợp nhất B1

- 01: bỏ Approved mâu thuẫn phiếu ký trống; phân biệt MVP/Release 1 và mục tiêu/kết quả đo.
- 02: giữ mã yêu cầu; phase rõ; RBAC/audit/refund/reconcile tối thiểu thuộc MVP; OTP/promotion Phase 2.
- 03: quote/202, UUID, guest ownership, internal mutation, API phục hồi, consumer đúng; bỏ client amount.
- 04: DB authoritative, durable saga, SELF; bỏ mô tả history như event sourcing.
- 05–07: giữ schema/flow, đồng bộ contract references; bổ sung close/cleanup và giới hạn policy.
- 09–15: phân công, dependencies, traceability, test gates, onboarding, runbook, FE và provider.
- Bổ sung AWS/CI: ghi nhận AWS + credit theo chủ dự án; thêm [16](../engineering/16_aws_deployment.md), CI tài liệu và test local; đồng bộ 01/04/09/10/12/13/index. Chưa phê duyệt ngân sách, chưa có run GitHub/deploy hoặc nghiệm thu PLT-04.
- Bổ sung [17 Tech Stack](../engineering/17_tech_stack.md): tập hợp lựa chọn baseline, công cụ chưa chọn, trạng thái/version và bằng chứng cần có cho O02. Nêu rủi ro support Boot/Cloud theo nguồn chính thức; không thay stack, không chốt version chưa test hoặc đánh dấu task Done.
- R1 research 2026-09-19 theo yêu cầu chọn phiên bản: 17 có exact version ứng viên và nguồn upstream; ADR-16 thay Boot 3 bằng Boot 4.0, ADR-17 chọn Vite SPA có gate SEO. Đây là lựa chọn để implementation, không phải compatibility đã pass; không đóng O02, PLT-01 hay cấp quyền provision AWS.
- ADR-18: chủ dự án giới hạn Sprint 1 ở local-first MVP foundation. Thêm checkpoint S1-local; AWS/PLT-04 và toàn bộ G1/G2 không được suy là commitment Sprint 1.
- S1 Task 5 (2026-09-28): contract OpenAPI catalog + Redocly, Compose đủ chuỗi, smoke/Playwright chuỗi thật, Application CI và proof `nacos-compat` local. Đây là evidence tác giả; S1-local chỉ đạt sau fresh-clone review độc lập. O01/AWS vẫn mở.
- S1 verification (2026-10-02): xác minh CI remote PASS trên `d16151d`, sửa quoting project path của `mvnw.cmd` và thêm regression Windows RED → GREEN. Ghi [evidence và blocker mạng](../evidence/s1-local-2026-10-02.md); chủ dự án bỏ qua phần bị chặn trên máy công ty. Không đổi ADR/scope/version, không đánh dấu S1-local hoặc O02 Done.

- S1 verification (2026-10-04): [fresh-clone evidence](../evidence/s1-local-2026-10-04.md)
  ghi runtime trên `a563f05`, lỗi timezone Windows và regression local PASS 10/10.
  Patch UTC đã commit tại `b62f963`; Application CI `37148002874` và Documentation CI
  `37148002811` trên đúng SHA đều PASS (đã đối chiếu GitHub). Chủ dự án chỉ định Codex
  (GPT-6) làm reviewer thay yêu cầu reviewer người/TL; S1-local được nghiệm thu theo
  ngoại lệ này. Chấp nhận lệch Docker Engine/Compose riêng cho checkpoint local dựa
  trên runtime PASS; không đổi R1. O02 vẫn mở cho compatibility ngoài S1 và SEO.
  Không đổi ADR/scope/version hoặc cấp quyền AWS; chưa chuyển PLT-03 sang In progress.
- Phase 0 local (2026-10-04): PLT-02 bắt đầu envelope Draft 2020-12, synthetic fixtures
  và tests theo 03; tooling Python khóa tại `tests/contracts/requirements.txt`,
  Application CI gọi cùng validator. Plan dùng nhầm `event_version/trace_id` được sửa
  thành `version/correlation_id` theo 03; không đổi wire contract B1. Chuẩn bị PLT-03
  theo schema 05, không lấy SQL minh họa lệch schema trong plan làm migration.
  Parent PLT-02 chưa Done; primitive implementation và integration PostgreSQL/Kafka
  còn chờ contract review.
- SEC-01 (2026-10-04): chủ dự án chọn ADR-19 (service JWT ES256 tự ký + allowlist), ADR-20
  (CSRF bằng Origin/Fetch-Metadata + SameSite) và gitleaks v8.30.1 pin digest để quét secret.
  Thêm `services/platform-security`, `CsrfOriginFilter` ở Gateway, `scripts/scan-secrets.sh`
  (Application CI quét toàn bộ history) và data inventory/permission codes ở 13. O06 vẫn mở:
  owner PO + pháp lý, chưa có ngày và thời hạn lưu; không suy luận kết luận pháp lý.
- PLT-04 chuẩn bị (2026-10-04): chủ dự án chọn chuẩn bị hạ tầng trước khi O01 được duyệt, không
  tạo tài nguyên. Thêm CloudFormation identity/ECR và staging, workflow publish khóa bằng biến,
  Kustomize/Argo CD và cfn-lint 1.57.1 (tooling). Storefront chạy root là ngoại lệ có ghi lại,
  namespace dùng Pod Security `baseline`. O01 vẫn mở; không change set, không deploy.
- Phase 1A bắt đầu trước G0 (2026-10-05): chủ dự án chọn làm Phase 1A trên local trong khi G0 chờ
  O01, lệch quy tắc "chỉ bắt đầu sau gate trước" của docs/superpowers/plans. G0 vẫn mở; không
  deploy hay báo 1A đạt checkpoint tích hợp. USR-01 chia 1a (đăng ký/đăng nhập/refresh/logout,
  khóa tài khoản trong PostgreSQL) và 1b (mật khẩu + bàn giao secret); rate limit theo IP và Redis
  8.2.9 hoãn tới 1b. Thêm ADR-21 và cột `users.failed_login_attempts`.
- USR-02 trước USR-01b (2026-10-05, chủ dự án chọn): đổi mật khẩu cần verifier JWT của USR-02. USR-02
  chia 2a (verifier, `/users/me`, địa chỉ) và 2b (RBAC, admin, audit). Ngưỡng rate limit mặc định cho
  1b (config, đổi được): đăng nhập 20 request / 5 phút / IP; quên mật khẩu 5 / giờ / email và
  20 / giờ / IP; vượt ngưỡng trả 429 + `Retry-After`; Redis lỗi thì fail closed.
- USR-02b (2026-10-05, chủ dự án chọn): service kiểm quyền bằng claim `permissions` trong access
  token (ADR-21), không gọi user-service mỗi request; đánh đổi: service khác thấy quyền bị thu hồi
  muộn tối đa 15 phút (TTL token), còn user-service kiểm `auth_version` nên có hiệu lực ngay.
  SUPER_ADMIN đầu tiên do biến môi trường `USER_BOOTSTRAP_ADMIN_EMAIL` cấp khi khởi động (tài khoản
  đã đăng ký, chỉ khi chưa có SUPER_ADMIN), audit actor SYSTEM (`actor_id` NULL). Agent tự chọn,
  cần reviewer xác nhận: admin không được tự đổi role/tự khóa mình; Phase 1 chưa có mở khóa (03 §3
  chỉ có lock); SUPER_ADMIN chỉ có `user.manage`.
- USR-01b (2026-10-05, chủ dự án chọn): dùng Redis 8.2.9 cho local/test trong khi license Redis 8
  (AGPLv3/RSALv2/SSPLv1, 17) vẫn chờ PO/TL chốt trước release; chia 1b-i (mật khẩu + bàn giao secret)
  và 1b-ii (rate limit). Agent tự chọn, cần reviewer xác nhận: token reset mã hóa AES-256-GCM
  (`USER_SECRET_KEY`, challenge id + purpose làm associated data) trong Redis, TTL bằng hạn token;
  `NOTIFY_RESET_PASSWORD` mang `recipient.email` và `challenge_id`, không mang token; service token
  ADR-19 gửi bằng `Authorization: Bearer`; đổi mật khẩu thu hồi cả phiên hiện tại (client đăng nhập
  lại) và sai mật khẩu hiện tại tính vào ngưỡng khóa 5 lần; đặt lại mật khẩu xóa khóa đăng nhập sai.

- USR-01b-ii (2026-10-06, chủ dự án duyệt thiết kế trong phiên): Gateway xác minh IP theo
  proxy allowlist, xóa header client tự gửi; user-service chỉ tin IP từ Gateway được allowlist.
  Local nginx ghi đè X-Forwarded-For bằng socket peer; Compose tin hostname `storefront` ở
  Gateway và `gateway` ở user-service. Standalone mặc định không tin proxy nào. Chọn cửa sổ
  cố định bắt đầu ở lần thử đầu, Lua Redis atomic, quota đã chốt ngày 2026-10-05;
  429 + Retry-After, Redis lỗi fail closed 503 và readiness gồm Redis. Không đổi schema/event;
  chưa triển khai các rate tier còn lại hay staging. USR-01 parent vẫn In progress chờ nghiệm thu.

- Review PR #12 (2026-10-06): sửa parser IP để numeric-looking hostname sai octet không đi DNS,
  chỉ parse header sau trusted peer; zone chỉ xử lý cho socket, thiếu peer không tạo IP giả.
  Thêm HMAC/env namespace quota, dùng khóa dẫn xuất từ master hiện có, WARN allowlist rỗng.
  Không đổi quota/window hoặc chính sách readiness đã được duyệt. Key format/rotation tạo
  quota window mới (ephemeral); reset secret đang chờ còn cần rotation runbook.
- **Mở trước staging user-service, owner PO/TL:** Redis trong readiness rút cả pod khỏi
  Kubernetes Service, làm refresh/register/profile/admin mất route dù không cần Redis.
  Cần chọn giữ readiness chung hay tách Redis health/alert và chỉ đóng login/forgot; chưa có
  manifest user-service và chưa chấp thuận đổi cấu hình local đã duyệt.
- **Mở trước ingress dual-stack, owner PO/TL + QA:** quota IPv6 theo địa chỉ không chặn client
  luân phiên địa chỉ trong /64. Cần chốt /64, /56 hay giữ per-IP theo NAT/fairness và test
  topology thực; gom subnet thay đổi actor/quota nên không tự đưa vào bản sửa PR #12.

- CAT-01a (2026-10-07, chủ dự án giao thực thi spec/plan đã duyệt): dùng jsoup **1.23.2**
  (`Safelist.basic` + h2/h3, chỉ http/https, rel nofollow/noopener); JdbcClient và durability
  primitives hiện có. Các điểm spec §10 được giữ để reviewer đối chiếu: size/color bất biến,
  taxonomy version và list không phân trang, ngưng category không cascade, publish/unpublish
  có key + version, seed uncategorized. Không mở rộng collection/size-guide, điều kiện ảnh,
  CATALOG_CHANGED hoặc Kafka relay trong 1a.
- Chủ dự án chốt trong phiên CAT-01a: record canonical nhận reason null, thiếu version trả 400;
  chỉ publish đặt `published_at = coalesce(published_at, now())`, unpublish không sửa timestamp.
  ACTIVE từ V001 có thể còn null; backfill/xử lý null cho sort/index để CAT-02.
- Chủ dự án duyệt smoke OPS qua Gateway bằng JWT synthetic chỉ trong script local, ký bằng
  khóa `.env` trong memory, ADR-21 ES256/kid/iss/aud, sub UUID mới, auth_version 0,
  permissions chỉ catalog.write và exp ≤ 300 giây. Không sửa user DB; giữ 401/403 member thật.
  Fixture slug/SKU ngẫu nhiên tích lũy vì không DELETE; smoke không chứng minh login → token OPS.
  Chi tiết và giới hạn: [evidence CAT-01a](../evidence/cat-01a-local-2026-10-07.md).
- Review CAT-01a (2026-10-07, chủ dự án duyệt sửa plan Task 2/4): fallback 500 chỉ trong
  admin trả INTERNAL/INTERNAL_ERROR + metadata và X-Correlation-Id, vẫn log exception
  server qua logger redact PII hiện có. Audit dùng metadata.request_id do controller tạo
  một lần theo khuôn user-service; retry metadata mới, không thêm audit hoặc đổi hash.
  Không áp handler cho public ProductQueryController. Không đổi schema hoặc nghiệp vụ.
- Review PR #14 (2026-10-07, chủ dự án duyệt): chỉ sửa Important catch-all đổi lỗi HTTP
  của Spring thành 500. `ErrorResponse` 4xx đến advice admin giữ status/header gốc,
  trả VALIDATION_ERROR/INVALID_HTTP_REQUEST + metadata/X-Correlation-Id bằng JSON,
  không log ERROR; fallback 500 cho lỗi còn lại vẫn log exception qua logger redact PII.
  Giữ scope public và các handler cụ thể. Chủ dự án để các Minor riêng: deadlock mapping,
  scalar coercion; không kéo interceptor/refactor tags vào đợt sửa này.

- PR14 SKU (2026-10-07, chủ dự án duyệt): SKU mới strip → kiểm ASCII 1–64,
  ký tự đầu chữ/số, chỉ `[A-Za-z0-9._-]` → uppercase Locale.ROOT trước hash/DB/audit/outbox.
  SKU unique không phân biệt hoa/thường qua V003, giữ V001/V002 append-only.
  Chọn chỉ chuẩn hóa SKU mới: identity, audit/outbox và key/response cũ giữ nguyên, không backfill.
  Replay key legacy dùng SKU từ response đã lưu để kiểm hash cùng các field request còn lại;
  giữ data/status cũ, đổi field khác vẫn 409. INSERT guard không cản UPDATE trường mutable
  của variant legacy. Collision theo casing làm migration dừng, cần quyết định dữ liệu riêng.
  Spec/plan, 03/05/06 và contracts đồng bộ; CAT-01 vẫn chờ review/acceptance.

- CAT-01b collection activation/visibility — **chủ dự án, 2026-10-07**: chọn PUT collection
  sang ACTIVE không yêu cầu product ACTIVE, cho phép rỗng hoặc product chưa publish.
  Cách này giữ lịch launch start_at tương lai; validation lúc chuyển trạng thái cũng không
  giữ được điều kiện sau khi product bị unpublish, nên không dùng làm invariant liên tục.
  Public list/detail chỉ trả collection ACTIVE, đang trong `[start_at, end_at)` và có ≥ 1
  product ACTIVE; product list bên trong chỉ gồm product ACTIVE. Điều kiện kiểm tại thời điểm đọc.
  Public read thuộc TASK:CAT-02, không triển khai trong CAT-01b; 03 §2.2/§3, 06 §3 và
  backlog CAT-02 đồng bộ acceptance, gồm biên thời gian và unpublish product ACTIVE cuối cùng.
  REQ liên quan CAT-03 (collection), CAT-04 (public query); ảnh/upload giữ ở TASK:CAT-03.

- CAT-01b size guide table — **chủ dự án, 2026-10-07**: chọn bảng linh hoạt thay vì field
  số đo cố định theo category, hỗ trợ khoảng đo và Free size. `table_json` chỉ có đúng
  hai key `columns`/`rows`, không field lạ; 1–20 cột, 1–100 hàng, mỗi hàng đúng số ô
  bằng số cột. Tiêu đề string 1–100 ký tự sau strip, không trùng nhau; ô string 0–100
  ký tự sau strip; JSON sau serialize ≤ 32 KB. Tất cả là plain text, không HTML,
  FE escape; `guideline_html` sanitize bằng jsoup như mô tả sản phẩm.
  Quy ước cột đầu là nhãn size khớp `variant.size`, không ép bằng validation/FK.
  03 §3 và 05 §5 đồng bộ contract/shape; REQ:CAT-06, TASK:CAT-01 phần 1b.
  Quyết định shape này không thay cho duyệt spec/plan.

- CAT-01b size guide PUT/version — **chủ dự án, 2026-10-07**: giữ PUT
  `/catalog/size-guides/{category_id}/{locale}`, không thêm POST tạo. `expected_version=0`
  chỉ tạo khi chưa có bản ghi → 201/version 1; version hiện hành cập nhật → 200/version +1.
  Thiếu version 400; gửi 0 khi đã tồn tại hoặc version lệch → 409 `VERSION_CONFLICT`.
  Tạo độc lập song song (key khác nhau): đúng một 201, còn lại 409 `VERSION_CONFLICT`,
  bảo vệ bởi UNIQUE `(category_id, locale)`. Locale chỉ vi/en (khác 400); category không
  tồn tại chọn 404 `NOT_FOUND`/`CATEGORY_NOT_FOUND` trong hai lựa chọn chủ dự án cho phép,
  theo khuôn catalog hiện có.
  Ngoại lệ CAT-01a: version bắt đầu 1 và PUT bắt buộc `Idempotency-Key` vì có thể tạo.
  Lookup idempotency trước kiểm version; cùng key/body chuẩn hóa replay data/status,
  metadata mới, không thêm audit; cùng key khác body 409 `IDEMPOTENCY_KEY_REUSED`.
  Race cùng key/body tuân theo replay, không coi là hai lệnh tạo độc lập.
  03 §3/05 §5 đồng bộ contract/schema; REQ:CAT-06, TASK:CAT-01 phần 1b.
  Chưa có implementation; tiếp tục thiết kế và duyệt spec/plan theo workflow.

- CAT-01b admin detail GET — **chủ dự án, 2026-10-07**: bổ sung GET
  `/catalog/collections/{id}` (collection + items thứ tự sort_order/product_id + version)
  và GET `/catalog/size-guides/{category_id}/{locale}` (nội dung + version; chưa có
  404 NOT_FOUND, locale ngoài vi/en 400). Quyền `catalog.write` kiểm tại service,
  trả cả dữ liệu DRAFT/INACTIVE, giữ envelope/metadata; version phải khớp nội dung
  và là expected_version cho PUT. Không thêm public read (TASK:CAT-02).
  03 §3/05 §5 và `catalog.yaml` đồng bộ; examples 2xx/4xx và contract regression đã có.
  Test HTTP 401/403/404 và GET → PUT/version conflict phải thực hiện khi triển khai
  CAT-01b theo spec/plan được duyệt; GET đã hiện thực Task 2/4 và kiểm HTTP Testcontainers.

- CAT-01b collection mutation — **chủ dự án, 2026-10-07**: duyệt POST key tạo
  DRAFT/version 0; PUT thay toàn bộ nội dung/items, expected_version, tăng version 1,
  status DRAFT/ACTIVE/INACTIVE; không bắt Idempotency-Key cho PUT, theo CAT-01a.
  Khóa collection/thay items/version/audit cùng transaction. Tối đa 1.000 items, cho phép
  rỗng/product chưa publish, không trùng product_id. Product body không tồn tại chọn
  400 VALIDATION_ERROR tại `items[i].product_id`, thay đề xuất 404; 404 chỉ resource path.
  sort_order integer 0..2147483647, cho phép trùng; đọc sort_order/product_id.
  start_at/end_at ISO-8601 có offset, lưu UTC; null là không giới hạn phía tương ứng,
  có cả hai thì end > start. CAT-01b không nhận cover_url/lookbook (kể cả null trong
  request), cover_url luôn null; cover/lookbook thuộc CAT-03 sau upload kiểm tra.
  03 §3/05 §5/06 §3/catalog.yaml đồng bộ; REQ:CAT-03, TASK:CAT-01 phần 1b.
  Chưa có service/controller/migration; spec/plan cần review trước implementation.

- CAT-01b guideline HTML — **chủ dự án, 2026-10-07**: `guideline_html` không bắt buộc,
  thiếu/null thành chuỗi rỗng; sanitize bằng jsoup như mô tả sản phẩm, tối đa 20.000
  ký tự sau sanitize. Không áp maxLength 20.000 lên raw input trước sanitize; response
  và dữ liệu lưu phải thỏa giới hạn sau sanitize. 03 §3/05 §5/catalog.yaml đồng bộ;
  REQ:CAT-06, TASK:CAT-01 phần 1b. Contract regression đã có; test HTTP/sanitizer của
  size guide đã hiện thực Task 5 với HTTP/PostgreSQL Testcontainers; không xem contract test là runtime proof.

- Duyệt spec CAT-01b — **chủ dự án, 2026-10-07**: duyệt toàn bộ [spec](../superpowers/specs/2026-10-07-cat-01b-design.md)
  gồm §6 mục 1–6 (list/pagination/summary, normalize tên/slug/date/items hash, request đóng,
  32.768 byte UTF-8 compact JSON, guide thiếu với version >0 trả 409, giữ items request order).
  Chốt bổ sung: DTO request dùng @JsonAnySetter gom key lạ → 400 field tên key; không dùng
  ignoreUnknown=false hoặc đổi global fail-on-unknown=false, khác CAT-01a có chủ đích.
  PUT guide auth → validate → category path 404 → idempotency → version guard (kể cả replay).
  Create race INSERT ON CONFLICT(category_id,locale) DO NOTHING RETURNING, rỗng trả
  VERSION_CONFLICT, không bắt unique violation vì transaction idempotency sẽ abort.
  Header trùng strip + toLowerCase(Locale.ROOT), không phân biệt casing; GET collection
  detail một SQL json_agg ORDER BY sort_order/product_id. 03/05/spec/contract đồng bộ;
  implementation plan phải review trước thực thi, không coi duyệt spec là parent CAT-01 Done.

- Duyệt plan CAT-01b — **chủ dự án, 2026-10-07**: thực thi Native tuần tự trên dev,
  commit local từng task; commit spec/plan/docs/contract trước Task 1. NullNode của
  expected_version/sort_order/table_json như thiếu → 400, có regression null tường minh.
  Bỏ test snapshot timing-based; giữ one-statement SQL và mutation json_agg. Toolchain
  trong docs dùng placeholder. Dừng sau whole-branch review và bàn giao; chỉ push dev/
  mở PR main khi chủ dự án yêu cầu riêng theo AGENTS.md, không tự merge.
  Chốt bổ sung runbook: không tạo infra/local/README.md; dùng 12 §1 và catalog README,
  ghi smoke/synthetic JWT ở catalog README; không đổi README gốc khi lệnh không đổi.

- CAT-01b sửa Minor PR15 — **chủ dự án, 2026-10-07**: duyệt start_at/end_at chuyển UTC,
  truncate microsecond trước so sánh/hash/lưu và chỉ nhận năm UTC 1..9999; ngoài phạm vi
  hoặc end <= start sau truncate trả 400 đúng field. Cùng key với date offset tương đương
  hoặc chỉ khác phần dưới microsecond replay, không thêm audit. Duyệt field key lạ lồng nhau
  `items[i].<key>` / `table_json.<key>`; top-level giữ tên key. Đồng bộ 03/spec/plan/OpenAPI
  và HTTP/PostgreSQL regression POST/PUT/retry; không đổi schema hoặc CAT-01a.
  Minor 3 sửa OpenAPI giới hạn tên/slug theo service đã duyệt; Minor 4 chỉ so trùng header
  hợp lệ, tránh lỗi duplicate giả. [Review PR15](https://github.com/trunghieunef/fashion-ecommerce-platform/pull/15),
  `TASK:CAT-01` phần 1b, `REQ:CAT-03/06`; không nghiệm thu parent hoặc tự merge.

- CAT-03 backing store local — **chủ dự án, 2026-10-07**: chọn object storage
  S3-compatible trong Docker local, code dùng S3 API/endpoint cấu hình để staging
  đổi config. Bind host 127.0.0.1, bucket private, không policy public; local-up.sh
  sinh credential vào .env local, không commit/in ra. Không provision AWS/bucket
  thật. Trình 1–2 image kèm license/bảo trì kiểm tại ngày chọn và pin digest;
  SDK S3 là dependency mới phải trình version để duyệt, ghi 17 sau lựa chọn.
  [Research](../evidence/cat-03-stack-research-2026-10-07.md) và
  [spec bản nháp](../superpowers/specs/2026-10-07-cat-03-media-design.md) chưa thay
  contract/schema hoặc là phê duyệt image/SDK/flow upload. REQ:CAT-11, TASK:CAT-03.

- CAT-03 lựa chọn image/SDK có điều kiện — **chủ dự án, 2026-10-07**: RustFS
  1.0.1/digest đã xác minh, Apache-2.0, chỉ local, không staging/prod. Spike
  S3 path-style/endpoint override/presigned PUT/type-length/HEAD/GET/DELETE và
  COPY nếu dùng, anonymous GET phải bị từ chối; lỗi báo chủ dự án và fallback
  Garage 2.4.1 được cho phép. Code chỉ dùng API có trên AWS S3.
  AWS SDK v2 2.55.12/BOM Apache-2.0 chỉ catalog-service, S3 + URLConnection,
  exclude Apache/Netty khỏi S3, không async/native CRT. Dependency tree phải
  chứng minh không đổi các version Boot quản lý hoặc thêm Jackson xung đột.
  StaticCredentialsProvider local/config endpoint/region, không AWS profile/default
  chain. Ghi [17](../engineering/17_tech_stack.md) và
  [spike evidence](../evidence/cat-03-stack-research-2026-10-07.md) trước chốt spec.

- CAT-03 flow/ownership — **chủ dự án, 2026-10-07**: presigned PUT 300s quarantine
  → POST complete HEAD/stream/magic/dimensions/re-encode strip EXIF/GPS → PUT
  images chỉ DB/expected_version; GET upload status để recover. Owner actor JWT
  + target, server sinh key, không nhận URL/key/chuyển owner. Complete idempotent
  theo upload_id, terminal không đọc quarantine, không CopyObject approve; tx
  ngắn PROCESSING claim → S3 ngoài tx → tx ngắn kết quả. ImageIO built-in,
  concurrency giới hạn và cleanup quarantine sau complete/expiry; limits/schedule
  cụ thể cần chốt trước spec toàn bộ và implementation plan.

- CAT-03 approved key/recovery — **chủ dự án, 2026-10-07**: key cố định từ upload_id
  + PutObject If-None-Match:* first-writer-wins, thay chỉ đạo retry overwrite.
  Spike RustFS lần hai phải 412 (đã PASS); nếu không hỗ trợ báo chủ dự án, không
  tự đổi phương án. Recovery lấy size/dimensions/checksum/type từ object approved
  thực tế và kiểm lại hợp lệ, không metadata candidate in-memory. Tx APPROVED
  CAS lease token/attempt còn hiệu lực; attempt hết lease không ghi DB. S3 412
  không lộ client; trùng PROCESSING trả status/409, client GET status. Đồng bộ
  [spec security](../superpowers/specs/2026-10-07-cat-03-media-design.md) và 13;
  chưa duyệt toàn bộ spec/plan hoặc endpoint shapes/errors.

- CAT-03 validation limits — **chủ dự án, 2026-10-07**: JPEG/PNG only;
  raw và từng approved/thumb output <=5 MiB, width/height 1..8192, <=25M pixel.
  Approved cạnh dài <=2560, thumb <=800, giữ tỷ lệ/không upscale. Một re-encode
  /instance, không queue; hết slot429/Retry-After:1 trước đổi upload state.
  Output vượt cap, CMYK JPEG/file hỏng/ảnh ImageIO không xử lý được/decode
  exception → REJECTED có code/test, không500. WebP/format khác duyệt riêng.
  README catalog và 16 ghi ~100MB/buffer decode25MP, chưa là peak heap/sizing
  proof và không tự tăng máy/chi phí. Spec toàn bộ vẫn cần review sau chốt contract.

- CAT-03 read/publish — **chủ dự án, 2026-10-07**: stream ảnh approved qua Gateway,
  kiểm visibility khi request tới service; cache public/admin theo hiệu chỉnh
  review 2026-10-08 bên dưới. Public attach product ACTIVE
  hoặc collection ACTIVE/trong[start,end)/có >=1 ACTIVE product. Admin preview
  catalog.write xem DRAFT/INACTIVE; không download raw/browse CAT-02.
  Publish cần >=1 APPROVED image/alt vi-en hợp lệ, giữ gates CAT-01a. Gỡ ảnh cuối
  ACTIVE400 field images, unpublish trước; ACTIVE legacy V001 giữ status/không
  backfill, được attach. Không gate cover/lookbook collection hoặc ảnh từng màu.

- CAT-03 state/TTL/cleanup — **chủ dự án, 2026-10-07**: PENDING intent15phút,
  presigned300s, PROCESSING lease120s và CAS token/lease khi commit; trùng
  PROCESSING409 CONFLICT/UPLOAD_PROCESSING, REJECTED400 VALIDATION_ERROR/IMAGE_REJECTED
  với reason_code cụ thể, EXPIRED409 CONFLICT/UPLOAD_EXPIRED; S3 lỗi503 retry cùng
  upload_id. Dọn raw sau terminal/sweep expired60s batch100 tránh lease còn hiệu
  lực; lifecycle chỉ quarantine expire1ngày để bắt PUT muộn. Không xóa approved/
  ảnh đang attach. S3 lifecycle config round-trip đã spike PASS; chưa chờ ngày
  để chứng minh lifecycle thực xóa object. Shape contract cuối cần spec review.

- CAT-03 attach/read — **chủ dự án, 2026-10-07**: GET/PUT product/collection images,
  full replacement + expected_version resource, không key;20 product/50 collection,
  alt strip plain1–255 VI/EN, caption0–500, sort0..2147483647 cho trùng, đọc
  (sort_order,asset_id), variant_color đúng product hoặc null. Cover nullable và
  thuộc images request, sai400 cover_asset_id; duplicate400 images[i].asset_id.
  Ownership chỉ mới-gắn; asset đang attach cho actor khác giữ/sửa alt/sort.
  Upload thiếu/foreign owner404. PUT version+1/snapshot/audit diff asset/cover,
  audit chỉ asset_id. ProductACTIVE không0ảnh; collection không cover gate.

- CAT-03 detached/orphan GC — **chủ dự án, 2026-10-07**:7ngày từ gỡ, approved
  chưa attach7ngày từ approve; reattach trong hạn chỉ uploader/target cũ, không
  ngoại lệ OPS. GC mỗi giờ/batch100, single-runner lease platform-durability;
  attach và GC cùng lock asset, DELETING/DELETED400 images[i].asset_id.
  Claim quá hạn/không reference tx ngắn → xóa image/thumb ngoài tx → kết quả
  CAS/retry giới hạn, lỗi lặp log/metric không chặn batch; key mất coi success.
  Không xóa object trong PUT hoặc ảnh đang attach/evidence. Quarantine chưa
  complete dọn sau presigned hết hạn+24h; terminal dọn raw. Lifecycle chốt mới
  ở mục sau thay policy1ngày trước để không dọn sớm.
  Ghi13/spec; chưa thực thi GC hoặc nghiệm thu TASK:CAT-03/REQ:CAT-11.

- CAT-03 deadline/lifecycle superseding — **chủ dự án, 2026-10-07**: PUT300s,
  complete_deadline=put_expires_at+24h (thay TTL intent15phút trước); quá hạn
  EXPIRED/409 UPLOAD_EXPIRED, không đọc S3. Quarantine HEAD404 có lỗi controlled
  UPLOAD_NOT_UPLOADED trước hạn, không500. Sweep sau deadline xóa key mất coi
  success và ghi EXPIRED, bảo vệ valid PROCESSING lease. Lifecycle quarantine
  **2ngày** làm lưới an toàn, thay1ngày; terminal dọn ngay. Config local-up/spike
  đã cập nhật, PUT/GET round-trip PASS, chưa runtime expiry proof. 03/13/spec/
  OpenAPI đồng bộ, chưa triển khai endpoint/sweep hoặc nghiệm thu CAT-03.

- CAT-03 partial objects/recovery — **chủ dự án, 2026-10-07**: approved/thumb keys
  cố định của upload terminal EXPIRED/REJECTED được GC sau7ngày từ terminal,
  chỉ khi không asset/reference/lease; cùng GC job, không xóa trong request,
  keys suy ra upload_id/không list bucket. Recovery khi approved đã tồn tại ưu
  tiên kiểm object/tạo thumb thiếu/commit fenced APPROVED; lỗi tạm thumb/DB
  không tự EXPIRED/REJECTED. Sweep không expire PROCESSING còn lease. Test
  approved-written/commit-fail→retry APPROVED và terminal partial→GC sau hạn.
  Ghi13/spec/schema; không tự vượt deadline đã chốt hoặc triển khai trước duyệt plan.

- CAT-03 spec review — **chủ dự án, 2026-10-08; spec chưa duyệt**: sửa public
  Cache-Control thành public, max-age=300; ETag cố định theo bytes approved,
  If-None-Match trùng trả 304 sau kiểm visibility. Chấp nhận cache fresh hiển thị
  ảnh tối đa 5 phút sau unpublish. Admin giữ private, no-store. S3 không vào
  readiness catalog-service; route media cần storage trả 503 DEPENDENCY_UNAVAILABLE,
  báo S3 qua metric/health group media riêng. Media_uploads thêm UNIQUE(id,
  product_id)/(id, collection_id); association dùng composite FK chặn sai target.
  Kind lạ/rỗng trả 400 field kind ở cả admin/public. Quarantine sweep dùng row
  claim FOR UPDATE SKIP LOCKED và cleanup token/lease/CAS; GC vẫn single-runner
  lease platform riêng. Đồng bộ spec/03/05/06/13/OpenAPI/tests/README/evidence;
  chưa migration, media runtime hoặc viết implementation plan.

- CAT-03 Gateway image response budget — **chủ dự án, 2026-10-08**: tăng riêng
  `GET /api/v1/catalog/images/{asset_id}` và `GET /admin/api/v1/catalog/images/{asset_id}`
  lên 30 giây, như route complete; các route khác giữ 2 giây. S3 mặc định timeout
  10 giây, nên budget 2 giây cũ có thể biến 503 của service thành 504 tại Gateway.
  Thêm regression upstream trả chậm 503, giữ envelope và `Cache-Control: no-store`,
  cùng mutation timeout từng route. Đồng bộ 03/13, spec/plan và README/runbook;
  không đổi quyền/visibility hoặc readiness. Bằng chứng tại [evidence CAT-03](../evidence/cat-03-local-2026-10-08.md).

Mẫu quyết định mới: ID; vấn đề; lựa chọn; phương án khác và lý do; ảnh hưởng PRD/API/schema/test/task; người quyết định; ngày; link bằng chứng. Chưa có chữ ký phê duyệt giả định thương mại.

## CART-01 — quyết định 2026-10-18

`TASK:CART-01` · `REQ:CART-01/02/04`. Chủ dự án giao agent tự chốt và duyệt [spec](../superpowers/specs/2026-10-18-cart-01-carts-design.md):

| Quyết định | Hệ quả |
|---|---|
| cart-service riêng, port 8084, DB `cart` | Không truy vấn chéo DB; gọi catalog/inventory qua internal API với service token |
| Cookie `cart_token` SameSite=Lax, 30 ngày trượt, chỉ lưu SHA-256 | Link ngoài vẫn thấy giỏ; mutation có cookie vẫn qua CSRF origin của Gateway |
| Bearer ưu tiên hơn cookie, Bearer lỗi → 401 không fallback | Gộp giỏ guest→member là CART-02 |
| Không Redis | Giỏ không phụ thuộc cache; thêm khi đo tải |
| DELETE giỏ xóa item, giỏ vẫn ACTIVE; tối đa 100 dòng | Giữ unique ACTIVE của member |
| Kiểm tồn tham khảo khi thêm/tăng; lỗi dependency khi ghi → 503, khi đọc → preview UNAVAILABLE | Không giữ hàng, không chốt giá |
| local-up sinh service key cho cart-service, thêm vào allowlist catalog/inventory | Lần đầu internal API có caller thật |

## SHP-01 — quyết định 2026-10-19

`TASK:SHP-01` · `REQ:SHP-01/04`. Chủ dự án giao agent tự chốt và duyệt [spec](../superpowers/specs/2026-10-19-shp-01-shipping-design.md):

| Quyết định | Hệ quả |
|---|---|
| Service mới shipping-service (8085, DB `shipping`), chỉ carrier SELF | Carrier thật là SHP-03 |
| O04 vẫn mở: bảng phí mẫu V002 "(mẫu)", OPS sửa qua API; mã tỉnh không kiểm danh mục | Phí thật cần PO/OPS chốt trước go-live |
| Rule ACTIVE priority nhỏ nhất khớp thắng; không khớp → 422 (không phí 0) | FLAT là fallback do OPS cấu hình |
| Trọng lượng shipping tự lấy từ catalog quote | Catalog allowlist thêm `shipping-service` |
| Estimate public nhận `items` thay cart/version (khác baseline 03) | Không phụ thuộc giỏ/actor; không lưu |
| Quote internal lưu `shipping_quotes` (bảng mới), hạn 15 phút; shipment lấy fee từ quote, quote phải khớp tỉnh/COD | Order kiểm hạn quote khi tạo đơn |
| SELF tạo PENDING → CREATED trong một TX, tracking `SELF-<order_no>`; advisory lock theo quote (quote insert-only) | Bàn giao/giao/COD/cancel là SHP-02 |
| Phát `SHIPMENT_CREATED` qua outbox từ SHP-01 | Schema + registry mới; chưa có consumer |

## ORD-01 — quyết định 2026-10-20

`TASK:ORD-01` · `REQ:ORD-01/05/07`. Chủ dự án giao agent tự chốt và duyệt [spec](../superpowers/specs/2026-10-20-ord-01-order-quote-create-design.md) (cả các mặc định §11):

| Quyết định | Hệ quả |
|---|---|
| Service mới `order-service` (port 8087, DB `orders` vì `order` là từ khóa SQL); chỉ COD, VNPAY/MOMO 422 | Online thêm ở PAY-02/03 |
| Đơn dừng ở `PENDING`/`PROCESSING`, create luôn 202; saga row RESERVE ghi sẵn | ORD-02 tiếp tục, không phải backfill |
| quote_token tự chứa, ký HMAC, không lưu DB | Không bảng/job dọn quote; token dài và chứa địa chỉ/liên hệ của chính khách (chỉ base64) |
| Quote 2 phút; checkout token guest 15 phút, dùng lại được, header `Checkout-Token`, chỉ guest | Retry sau timeout dùng lại token; member dùng Bearer |
| Giá đổi → 409 `PRICE_CHANGED`, khách quote lại (không kèm quote mới) | FE gọi lại quote và xin xác nhận (T24) |
| UNIQUE `(cart_id, cart_version)` → 409 `CART_ALREADY_ORDERED`; cùng guest thì kèm cookie truy cập đơn đó (review PR #32) | Double-click/tab thứ hai không tạo hai đơn; guest mất key sau reload vẫn xem được đơn. ORD-02 phải bump version giỏ khi cleanup/compensate (hoặc giới hạn UNIQUE cho đơn chưa terminal) để đặt lại được sau hủy |
| Email bắt buộc với guest | Có kênh thông báo đơn (NOT-01) |
| Cookie `order_access` Path theo từng `order_no`, 90 ngày kiểm ở server (`created_at`); retry cấp thêm cookie, cookie cũ vẫn hợp lệ, tối đa 10 mỗi đơn (bảng `order_guest_access`) | Nhiều đơn guest không ghi đè nhau; hai response đua nhau không làm guest mất quyền xem đơn (review I1) |
| Quote không kiểm tồn kho; chưa rate limit checkout | Hết hàng phát hiện ở reserve (ORD-02); limiter chung là việc sau |
| Mã lỗi chi tiết nằm ở `message` (enum `code` chung giữ nguyên); tràn số tiền → 422 `AMOUNT_OUT_OF_RANGE` | Không đổi `common.yaml` |

## ORD-02 — quyết định 2026-10-20

`TASK:ORD-02` · `REQ:ORD-02/09`. Chủ dự án giao agent tự chốt và duyệt [spec](../superpowers/specs/2026-10-20-ord-02-checkout-saga-design.md) (các mặc định §11):

| Quyết định | Hệ quả |
|---|---|
| Saga chạy bằng worker nền (poll 1 s, batch 10, lease 30 s, `SKIP LOCKED`); create không chạy inline, luôn 202 | FE poll (14 §3); không có 201 nhanh. Nhiều instance dựa vào lease, chưa đo tải |
| Thứ tự `RESERVE → CREATE_PAYMENT`; COD xong payment là `CONFIRMED` + `WAIT_OPS` tới `expires_at` (24 h) | OPS xác nhận, commit kho và race commit↔expire ở ORD-03 |
| Hết hạn xử lý trong ORD-02: worker hủy saga quá deadline (kể cả khi bước đang UNKNOWN), release + close | Không xác nhận đơn muộn |
| CANCEL luôn close payment kể cả chưa tạo (tombstone, PAY-08) | Create đến muộn không mở lại payment |
| Key cố định `reserve:`/`payment:`/`release:`/`close:{order_id}`, cleanup = order_id | Retry sau timeout không tạo giữ hàng/payment thứ hai (T03) |
| Lỗi chưa rõ backoff `min(2^n s, 5 phút)` ±20 %, 30 lần → `MANUAL` + metric; từ chối nghiệp vụ khi release (COMMITTED) hoặc close (tiền đã thu → `PAID_NEEDS_REFUND`) → `MANUAL` ngay. Chỉ hủy đơn với reserve `OUT_OF_STOCK`/`RESERVATION_CLOSED` và payment `PAYMENT_CLOSED`/`PAYMENT_METHOD_UNAVAILABLE`/`PAYMENT_AMOUNT_UNSUPPORTED`; mọi mã khác (400, 404, `*_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, 401/403/5xx/timeout) retry rồi MANUAL (review PR #33). Intent trước mỗi lời gọi đã tính một lần thử + backoff; exception trong bước cũng tính. Bù trừ luôn release (trừ RELEASED) để tombstone chặn reserve đến muộn; `COMMITTED`/`RESTORED` → MANUAL. `PAYMENT_COMPLETED` đã ghi khi đang bù trừ → `MANUAL PAID_NEEDS_REFUND` (review ORD-02) | Chưa có UI/alert MANUAL (ADM-02/PLT-05); lỗi contract dừng ở MANUAL sau ngân sách retry thay vì hủy đơn của khách |
| V002 thay UNIQUE `(cart_id, cart_version)` bằng unique index một phần `WHERE status <> 'CANCELLED'` (thay khuyến nghị bump version giỏ ở ORD-01) | Khách đặt lại cùng giỏ sau khi đơn hủy; không thêm API cart |
| Dọn giỏ khi đơn `CONFIRMED` (đã giữ hàng và tạo payment; review ORD-02 dời từ lúc HELD — spec §5 ghi "khi RESERVE thành công"), bằng background task `CART_CLEANUP` độc lập saga; đơn đang/đã hủy bỏ qua task | Đơn hủy trước khi xác nhận (hết hàng, payment từ chối/UNKNOWN rồi hết hạn) giữ nguyên giỏ; đơn `CONFIRMED` rồi hết hạn thì giỏ đã dọn; cart lỗi không đổi trạng thái đơn |
| `PAYMENT_COMPLETED` chỉ ghi `paid_at`/`payment_state = PAID`, không đổi `status`; event cho đơn lạ ACK + log, event hỏng → DLT; không phát `ORDER_PAID` | COD dùng ở ORD-03/04; refund PAY-04 |
| Guest mất response tìm lại đơn: đơn chưa hủy của cùng actor với `(cart_id, cart_version)` → 409 `CART_ALREADY_ORDERED` trước kiểm quote (thay ràng buộc ORD-01) | FE giữ `cart_id`/`cart_version` đã submit; key khác + quote cũ hết hạn nay nhận `CART_ALREADY_ORDERED` thay vì `QUOTE_EXPIRED` |

## ORD-03 — quyết định 2026-10-21

`TASK:ORD-03` · `REQ:ORD-03/06`, `REQ:PAY-03` (COD: thu có bằng chứng → FINANCE xác nhận → `SUCCESS`) kiểm end-to-end qua smoke (sửa khi nghiệm thu: trước ghi nhầm là "phần online hoãn" — online là TASK PAY-02/03). Chủ dự án duyệt
[spec](../superpowers/specs/2026-10-21-ord-03-cod-confirm-fulfil-design.md) ngày 2026-10-21 (cả các mặc định §11); các dòng
"(SDD)" là phán quyết của controller khi triển khai, ghi lại để review PR.

| Quyết định | Hệ quả |
|---|---|
| OPS xác nhận **từng đơn** sau khi gọi khách; ghi chú cuộc gọi tùy chọn (≤ 500), hủy bắt buộc lý do (1..500) | Không có xác nhận hàng loạt; lý do nằm ở history `reason` + `audit_logs.reason` |
| Hủy đơn `CONFIRMED` (saga còn `WAIT_OPS`) kéo từ ORD-04 vào ORD-03 (chủ dự án đồng ý 2026-10-21); hủy từ `PENDING`, sau xác nhận, khách tự hủy, giao thất bại/hoàn hàng vẫn là ORD-04 | Đơn đã xác nhận (saga qua `WAIT_OPS`) hủy → 409 `INVALID_TRANSITION` ở ORD-03; ORD-04 mở rộng (bảng ORD-04 bên dưới) |
| Confirm trả `202`, đơn vẫn `CONFIRMED` (`processing_status = PROCESSING`) tới khi inventory commit xong mới `PACKING` | FE/OPS poll; không bao giờ `PACKING` khi chưa trừ kho (T12) |
| Sau xác nhận, hạn của order không còn hủy đơn: worker chỉ kiểm `expires_at` ở `RESERVE`/`CREATE_PAYMENT`/`WAIT_OPS`; từ `COMMIT_STOCK` inventory là trọng tài (`RESERVATION_EXPIRED` → `CANCELLED EXPIRED`, `RESERVATION_CLOSED` → `CANCELLED RESERVE_FAILED`) | Một kết quả duy nhất do đồng hồ DB của inventory; lệch đồng hồ chỉ làm confirm sát hạn bị từ chối/hủy |
| Ở `COMMIT_STOCK` intent tính lần thử + backoff nhưng giữ `inventory_state = HELD`; key cố định `commit:{order_id}`, `shipment:{order_id}` | T09: worker chết sau commit → retry cùng key, inventory trả `COMMITTED` không trừ lần hai; bù trừ không hiểu nhầm là được release |
| Commit OK kết thúc lượt (`next_retry_at = now`, nhả lease); `CREATE_SHIPMENT` chạy lượt sau (SDD Task 4) | Mỗi lượt một lời gọi HTTP; trễ thêm ~1 s; quan sát được `PACKING` chưa có shipment (`PROCESSING`) |
| Vận đơn SELF tạo tự động sau `PACKING`, body từ snapshot (`quote_id`, `cod_amount = total_amount`, `to_address`); mọi từ chối → `MANUAL`, đơn giữ `PACKING` | Kho đã trừ thì không tự hủy; xử lý tiếp ở ORD-04; chưa có UI/alert MANUAL (ADM-02/PLT-05) |
| Không thêm event `ORDER_PACKING`/`ORDER_SHIPPED`; chỉ phát thêm `ORDER_COMPLETED` khi `DELIVERED` | Chuyển trạng thái vẫn có history; thêm event khi NOT-01/ADM cần |
| Order nghe `shipping.events`: `DELIVERING` có `handed_over_at` → `SHIPPING` (`shipped_at`), `DELIVERED` → `DELIVERED` (`delivered_at`, qua `SHIPPING` nếu còn `PACKING`); `shipment_version` ≤ bản đã áp dụng bị bỏ qua; FAILED/RETURNING/… chỉ ghi version (ORD-04 đổi: `RETURNING`/`RETURNED` → `RETURNING`, xem bảng ORD-04) | Event cũ không hạ trạng thái; giao thất bại giữ `SHIPPING`; `DELIVERED` không có nghĩa đã thu tiền (T27) |
| FINANCE nhận bản redacted bằng cách "không có `order.fulfill`" (không permission riêng): bỏ `contact_phone`/`contact_email`/`shipping_address`/`note`, history `reason` null, `q` theo số điện thoại 403 | Response confirm/cancel cũng bỏ key (không trả null) như GET (SDD Task 6) |
| `q` chỉ khớp chính xác `order_no` (dạng `ORD` + 12 số, không phân biệt hoa thường) hoặc `contact_phone` (so theo chữ số, `84` đầu quy về `0`); `q` rỗng là không lọc (review PR #34 M2/M3); không full-text | Chưa có index `contact_phone` (seq scan, đủ cho MVP) |
| Worker đang giữ lease saga khi OPS bấm → 409 `SAGA_BUSY` thay vì `INVALID_TRANSITION` `current=CONFIRMED` (review PR #34 M4) | FE báo "đang xử lý, tải lại sau"; chỉ gặp khi worker đang hủy đơn đúng lúc hết hạn |
| `cancel_reason = OPS_CANCELLED` (reason_code công khai mới), lý do người nhập vào history `reason` + `audit_logs` (SDD Task 2) | `OrderTransitions.move` nhận actor `ADMIN`/`actor_id`/`request_id` |
| Audit OPS vào `audit_logs` chuẩn 05 §3 (V003, REVOKE UPDATE/DELETE/TRUNCATE); `confirmed_at` giữ nghĩa ORD-02 (shop tiếp nhận) | Thời điểm/người OPS xác nhận đọc từ audit, không thêm cột |
| Confirm/cancel tăng `orders.version`; `order_version` trong event có thể nhảy | Request cầm version cũ nhận `VERSION_CONFLICT` |
| Kiểm version **trước** trạng thái/step saga (plan ghi trạng thái trước; SDD Task 2) | Confirm và cancel đua nhau trên một version: bên thua nhận `VERSION_CONFLICT`; caller cũ thấy `VERSION_CONFLICT` thay vì `INVALID_TRANSITION` |
| Thứ tự khóa toàn service: `order_sagas` rồi `orders` (worker, lệnh OPS, consumer shipping; SDD Task 2/5) | Không deadlock giữa OPS và worker lúc tới hạn |
| Lỗi nghiệp vụ 409 (`VERSION_CONFLICT`/`INVALID_TRANSITION`/`ORDER_EXPIRED`) của confirm/cancel không lưu vào idempotency (transaction rollback); chỉ `202` được lưu | Retry cùng key được đánh giá lại theo trạng thái hiện tại, giống create đơn ORD-01 (4xx không lưu; `Api.body` lưu lỗi chưa dùng ở order-service); 03 §1.3 đã ghi |
| Saga `MANUAL` vẫn hiển thị `PROCESSING` cho khách, không `ACTION_REQUIRED` | FE giới hạn poll theo 14 §3 |

## ORD-04 — quyết định 2026-10-21

`TASK:ORD-04` · `REQ:ORD-04`, `REQ:ORD-10`, `REQ:SHP-06`. Chủ dự án duyệt
[spec](../superpowers/specs/2026-10-21-ord-04-cancel-return-design.md) ngày 2026-10-21 (cả các mặc định §11 và ba quyết định §12);
các dòng "(SDD)" là phán quyết của controller khi triển khai, ghi lại để review PR.

| Quyết định | Hệ quả |
|---|---|
| Chủ dự án (§12.1): trả hàng sau giao (REQ ORD-10) làm luôn phần cơ chế — phiếu + kiểm đếm + cộng kho; **không** enforce chính sách hạn/phí (O05/D10 chưa chốt) | Phiếu trên `DELIVERED`: đơn giữ `DELIVERED`, payment không đổi, không phát lại `ORDER_COMPLETED`; hoàn tiền là PAY-04 |
| Chủ dự án (§12.2): khách chỉ tự hủy khi `CONFIRMED` + saga `FULFILL`/`WAIT_OPS` — hẹp hơn 02 ORD-04 ("`WAITING_PAYMENT` hoặc `CONFIRMED`") | `PENDING` (saga đang giữ hàng) hoặc shop đã xác nhận → 409 `INVALID_TRANSITION`; `WAITING_PAYMENT` online chờ PAY-02/03 |
| Chủ dự án (§12.3): đơn đã thu COD mà phải hủy/hoàn → `MANUAL` `PAID_NEEDS_REFUND` chờ PAY-04, không ghi hoàn tay | Không bao giờ `CANCELLED`/`RETURNED` khi tiền đã thu; chỉ metric/log + API detail (chưa UI) |
| OPS hủy được tới trước bàn giao (`CONFIRMED` `WAIT_OPS`/`COMMIT_STOCK`, `PACKING` `CREATE_SHIPMENT`/`DONE`, mọi trạng thái saga kể cả `MANUAL`); từ `SHIPPING` chỉ còn đường hoàn hàng | Đơn kẹt `MANUAL` có đường thoát; lease còn sống → 409 `SAGA_BUSY` |
| Thứ tự bù trừ cố định vận đơn → kho → payment; chưa chắc vận đơn đã hủy thì không gọi release/returns (T16) | Kho không bao giờ được cộng khi hàng có thể đã rời shop |
| Shipping `409 SHIPMENT_HANDED_OVER` khi hủy → không trả kho, saga `WAITING` step `WAIT_RETURN` (không `MANUAL`), `shipment_state = HANDED_OVER`, metric + log cảnh báo | Event `RETURNING` đưa `RETURNING`; event `DELIVERED` đưa `DELIVERED` qua cạnh mới `CANCELLING → SHIPPING` (06 §5.3) |
| Trả kho khi hủy: luôn release (snapshot + hash gốc) rồi `/returns` khi `restore_required`; không cần biết step trước | Phủ cả commit đang bay (inventory phân xử release↔commit); `RELEASED` không còn được ghi khi `restore_required` |
| Phiếu kiểm đếm hoàn tất trên đơn `RETURNING` đưa `RETURNING → RETURNED` qua bước saga `CLOSE_PAYMENT`; phiếu sau (hàng về muộn) chỉ cộng kho | Tiền và trạng thái đơn do saga giữ (một chủ, một ngân sách retry); task nền `ORDER_RETURN` chỉ cộng kho |
| Hàng hỏng: `restock_quantity < received_quantity`, phần hỏng không vào kho (không có kho "hàng hỏng") | Chỉ chặn vượt số lượng đặt, không chặn nhập thiếu: sai số kiểm đếm là sai kho thật (spec §10b) |
| Key cố định: `shipment-cancel:{order_id}`, `cancel:{order_id}` (trả kho khi hủy; `evidence_ref = order-cancel:{order_id}`), `return:{return_id}` (kiểm đếm), `close:{order_id}`, `release:{order_id}` | Replay cùng key không cộng kho lần hai (T17); 06 §5.4 cũ ghi `cancel:{order_id}:{sku}` đã sửa |
| `reason` gửi shipping/inventory khi hủy là mã `cancel_reason` cố định (hash idempotency của shipping gồm reason); lời người nhập chỉ ở history/audit/phiếu | Replay khớp hash dù OPS sửa lời lẽ |
| `processing_status`: `RETURNING` → `READY` (chờ hàng về, không poll), `RETURNED` → `FINISHED`; `reason_code` mới `CUSTOMER_CANCELLED`, `RETURNED` (admin `cancel_reason`) | FE hiển thị theo 14 §3 |
| Không thêm event mới cho hoàn hàng | Thêm khi NOT-01 cần |
| (SDD) `WAIT_RETURN` đỗ saga: `next_retry_at = now + 30 ngày`, ca `WAIT_RETURN` trong `advance` chỉ đỗ lại (không HTTP); chỉ event shipping/phiếu mới chuyển tiếp (preflight: không có luật cho saga tới hạn ở `WAIT_RETURN` sẽ rơi vào `default` và bị claim lại mỗi lượt) | Chi phí nếu sai: một claim rảnh mỗi 30 ngày; log cảnh báo `WAIT_RETURN` để OPS thấy đơn chờ hàng |
| (SDD) Test ORD-02 `committedStockIsNeverTreatedAsReleased` và `aReleaseThatFindsCommittedStockGoesManual` viết lại: kho đã commit nay được cộng lại qua `/returns` (spec §4 thay nhánh `COMMITTED → MANUAL`) | Không đổi hành vi ngoài spec |
| (SDD) Commit đang bay: release → (`restore_required`) ghi `COMMITTED` → hủy vận đơn → release lại → returns, để không đổi kho trước khi vận đơn chắc chắn hủy | Tốn thêm một lời gọi release |
| (SDD) Release `200` đã ghi `COMMITTED` nhưng không có `restore_required` → `MANUAL` (chặt hơn spec §4 "200 khác → `RELEASED`": mâu thuẫn nghĩa là chưa rõ kho) | Một ca OPS cần xem, không được xảy ra thường |
| (SDD) Event `RETURNED` đến trước `RETURNING` cũng đưa đơn sang `RETURNING` (chỉ tiến lên; đã chứng minh bàn giao) | Không kẹt ở `SHIPPING` khi event đến lộn thứ tự |
| (SDD) Event `RETURNING` trên đơn `CANCELLING` được nhận ở mọi step saga (kho chỉ có thể `RESTORED` sau khi vận đơn `CANCELLED` nên không tác dụng kép) | Không cần ràng step |
| (SDD) Event `RETURNING`/`RETURNED` vượt mặt bàn giao trên đơn `PACKING` → `PACKING → SHIPPING → RETURNING` (`shipped_at` dự phòng như ORD-03 DELIVERED vượt mặt) | Đơn không kẹt `PACKING` (06 §5.3) |
| (SDD) Phiếu trong lúc worker giữ lease saga → 409 `SAGA_BUSY` (lõi lệnh dùng chung); OPS thử lại | Hiếm gặp |
| (SDD) Phiếu gửi inventory đúng `reason`/`evidence_ref` OPS đã lưu (ổn định nên replay khớp); quy tắc "reason = mã `cancel_reason`" chỉ áp cho lời gọi hủy/shipping | Không đổi lời OPS thành mã trên phiếu |
| (SDD) Hash idempotency của phiếu dùng thứ tự cố định (mảng + record), không `Map.of` (thứ tự mỗi JVM khác nhau làm replay sau restart bị `IDEMPOTENCY_KEY_REUSED`) | Test ghim hash (review Task 5) |
| (SDD) Migration V004 không cấp quyền riêng: default privileges như V001–V003 (test chứng minh); Gateway `CsrfOriginFilter` là `GlobalFilter` phủ `/api/v1/orders/**` | Không cần đổi Gateway ngoài test route |
| (SDD) `allowed_actions` luôn là danh sách hành động của **khách** (`["CANCEL"]` hoặc `[]`); view admin của lệnh cũng mang cùng danh sách | Contract ghi audience; FE admin bỏ qua |
| (SDD) Đơn qua `OrderCommands.customerCancel` (idempotency actor = `orders.actor_key`, operation `customer-cancel`, không `audit_logs`, history actor `CUSTOMER`) | Khách và OPS dùng chung lõi khóa + kiểm + chuyển (`execute`); điều kiện nhận (admit) là `Consumer` được phép ném lỗi vì confirm-cod cần phân biệt `INVALID_TRANSITION` với `ORDER_EXPIRED` |
| (review PR #35 I2, thay phán quyết review toàn nhánh) Lỗi hạ tầng khi cộng kho phiếu (UNKNOWN, exception) không bao giờ thành `MANUAL`: thử lại cùng key `return:{return_id}` mãi (backoff tối đa 5 phút ±20 %), quá 30 lần → metric `order.return.stalled` + log; chỉ inventory từ chối (`RETURN_EXCEEDS_COMMITTED`/`RESERVATION_NOT_COMMITTED`, không ghi kho) → `MANUAL` + `order.return.manual`; OPS hủy phiếu `MANUAL` (`POST .../returns/{return_id}/void`, `VOIDED`, migration V005) rồi đếm lại | Không đơn nào kẹt `RETURNING` vì sự cố hạ tầng; hủy phiếu an toàn vì phiếu từ chối không cộng kho; không sửa DB tay |
| (review PR #35 I1) Hủy vận đơn nhận `QUOTE_NOT_FOUND`/`QUOTE_MISMATCH`/`QUOTE_ALREADY_USED` → coi như đã hủy (`shipment_state CANCELLED`), bù trừ đi tiếp | Shipping chỉ trả các mã này khi đơn chưa có shipment; quote bất biến (runtime không UPDATE/DELETE) nên không bao giờ tạo được vận đơn từ snapshot đó; đơn kẹt `MANUAL` ở `CREATE_SHIPMENT` vì quote bị từ chối nay hủy được. Mã từ chối khác vẫn `MANUAL` |
| (review PR #35 M1–M3, M6) `CANCELLING` chờ hàng đã bàn giao (`WAIT_RETURN`) → `processing_status READY`; đơn đã hủy mà vẫn giao → `cancel_reason` null, saga `FULFILL`; OPS khác dùng lại key phiếu trên cùng đơn → 409 `IDEMPOTENCY_KEY_REUSED` (không 500); không `order.fulfill` thì ẩn `evidence_ref` và `condition` của phiếu | Khách không poll nhiều ngày; admin không thấy lý do hủy trên đơn đã giao; text tự do OPS nhập không lộ cho FINANCE |

## NOT-01 — quyết định 2026-10-22

`TASK:NOT-01` · `REQ:NOT-01`, `REQ:NOT-05` (seed), `REQ:NOT-06`. [Spec](../superpowers/specs/2026-10-22-not-01-transactional-email-design.md) và
[plan](../superpowers/plans/2026-10-22-not-01-transactional-email.md); chủ dự án duyệt spec ngày 2026-10-22. O07 (provider email thật) chưa chốt:
nghiệm thu mức local với Mailpit.

| Quyết định | Hệ quả |
|---|---|
| Chủ dự án: 6 email giao dịch (đặt đơn, đang chuẩn bị, giao thành công, hủy, hoàn về shop, reset mật khẩu); không có email "đang giao" | 5 event order + `NOTIFY_RESET_PASSWORD` có sẵn |
| Chủ dự án: kiểu giao diện B (dải tên shop, khối trắng, nút đen, chân email hotline) | Layout chung một file Mustache, không chép khung 12 lần |
| Chủ dự án: câu lý do hủy cố định theo loại, không bao giờ in `reason` OPS nhập | `cancel_category` tính từ mã `orders.cancel_reason`; mã lạ → "Đơn không thể xử lý, vui lòng liên hệ hotline." |
| Chủ dự án (phương án A): email "đang chuẩn bị" gửi khi `CONFIRMED → PACKING`, không phải lúc OPS bấm `confirm-cod` | Email luôn đúng: kho đã trừ, đơn không còn bị hủy do commit thất bại |
| Chủ dự án: tên shop, hotline, địa chỉ gửi, domain storefront là cấu hình, giá trị mẫu | `NOTIFICATION_SHOP_NAME`, `NOTIFICATION_HOTLINE`, `NOTIFICATION_MAIL_FROM`, `NOTIFICATION_STOREFRONT_BASE_URL`, `NOTIFICATION_PUBLIC_ASSET_BASE_URL`, `NOTIFICATION_MESSAGE_ID_DOMAIN`; chủ dự án điền giá trị thật sau |
| (agent) Email "giao thành công" do order phát (`NOTIFY_ORDER_DELIVERED`), không phải shipping như 03 cũ | Shipping không có email/locale khách; `NOTIFY_SHIPMENT_STATUS` để dành cho carrier ngoài (SHP-03) |
| (agent) Event mang dữ liệu render, notification không đọc DB order; order che số điện thoại (`090****123`), chỉ email 1 có địa chỉ; không bao giờ gửi `reason` tự do | Rủi ro PII trên Kafka giảm; giữ retention topic theo 13 |
| (plan) Email 1 chỉ in `address_line`: địa chỉ đơn chỉ có `province_code`/`ward_code`, chưa có danh mục tên hành chính (SHP-01) | Khi có danh mục tên (task sau) thêm vào `ship_to.address` |
| (agent) Link `{storefront}/orders/{order_no}` không token; reset `{storefront}/reset-password#token={secret}` (fragment, không vào log server/Referer) | Trang đích do WEB-04 / WEB-02 làm đúng dạng link; guest xem đơn khi còn cookie |
| (agent) Chỉ HTML, Mustache, Mailpit làm mail sink (SMTP 1025 nội bộ, UI `127.0.0.1:8025`) | Thêm plain-text khi chọn provider O07 nếu cần deliverability |
| (agent) SMTP 5xx → `FAILED` ngay; 4xx, không kết nối và mọi lỗi trước `MAIL FROM` (chào 421, rớt sau EHLO, AUTH) → retry tối đa 5 lần; timeout/đứt kết nối sau khi kết nối (kể cả trước DATA, vì JavaMail không cho biết đang ở bước nào) → `UNKNOWN`, không gửi lại; lease 120 s (timeout 10 s tính theo từng bước socket, một phiên chậm cùng lần lấy secret vẫn dưới 120 s; review PR #36 M3) | An toàn trước gửi trùng, đổi lại thư rớt kết nối ở bước RCPT/DATA bị bỏ thay vì thử lại (review PR #36 M2, giữ có chủ ý); địa chỉ không phải đúng một địa chỉ hợp lệ → DLT ở intake, `FAILED` khi dựng thư (M1) |
| (agent) Chưa phát `notification.results`, chưa job dọn 90 ngày, không email cho đơn online | `notification.results` thêm khi NOT-02 cần; dọn 90 ngày trước G2 (O06); online chờ PAY-02/03 |
| (plan) user-service chưa có relay nên email reset không tới Kafka | Bật `platform-messaging` + relay cho user-service (`USER_RELAY_ENABLED`, mặc định bật), thêm topic `user.events`/`notification.events` và DLT; outbox cũ đang chờ được phát khi bật; tắt trong test user-service bằng surefire env |
| (plan) `local-up.sh` sinh khóa `NOTIFICATION` và thêm vào `USER_INTERNAL_CALLERS` | notification là caller duy nhất của `notification-secrets` |
| (review toàn nhánh) Dòng outbox `NOTIFY_ORDER_*` dùng chung `aggregate_type='order'`/`aggregate_id=order_id` với `order.events`; relay phát theo thứ tự trong aggregate nên nếu `notification.events` chưa có hoặc bị từ chối ở một môi trường, `order.events` sau đó của cùng đơn bị chặn sau dòng NOTIFY | Follow-up PLT-04: danh sách topic staging phải có `notification.events`, `notification.events.DLT`, `user.events`, `user.events.DLT` (hiện chỉ `infra/local/kafka-init/create-topics.sh`) |

## PAY-01 — quyết định 2026-10-20

`TASK:PAY-01` · `REQ:PAY-03/07/08`. Chủ dự án giao agent tự chốt và duyệt [spec](../superpowers/specs/2026-10-20-pay-01-payment-cod-design.md) (cả các mặc định §9):

| Quyết định | Hệ quả |
|---|---|
| Service mới `payment-service` (port 8086, DB `payment`); PAY-01 chỉ COD, VNPAY/MOMO 422 `PAYMENT_METHOD_UNAVAILABLE` | Online thêm ở PAY-02/03, schema đã có cột |
| `provider_request_id = order_no` | Một business reference suốt vòng đời (PAY-07) |
| Path close `/internal/api/v1/payments/by-order/{order_id}/close` (03 cũ: `/payments/{order_id}/close`) | Không trùng dạng `/payments/{payment_id}/…`; 03 đã sửa |
| Thêm `payments.snapshot` và source `CLOSE`/`SETTLEMENT` so với 05 | 05 đã ghi |
| Close-before-create phát `PAYMENT_FAILED` reason `CLOSED`; UNKNOWN không bị close đổi FAILED | Order nhận một tín hiệu nhất quán; UNKNOWN cần bằng chứng |
| Không phát event khi COD_COLLECTED; `PAYMENT_COMPLETED` phát cả khi payment đã close | Order đọc COD từ `shipping.events`; late money báo order refund |
| Settlement nhiều đợt, thiếu giữ COD_COLLECTED, vượt amount 409; gross 0 bị từ chối | SLA/discrepancy phần thiếu thuộc O04/PAY-05 |
| Lệch method/amount từ shipping → `reconciliation_items` OPEN, không đổi trạng thái | Resolve ở PAY-05 |
| COD evidence cho đơn chưa có payment → retry (không ACK) tối đa 10 phút từ lúc event phát (`PAYMENT_COD_ORPHAN_GRACE`), rồi DLT | Review PR I1: event mồ côi không chặn partition vô hạn; FINANCE/BE xem DLT + `messaging_dlt_total` |

## SHP-02 — quyết định 2026-10-20

`TASK:SHP-02` · `REQ:SHP-02/04/06`. Chủ dự án giao agent tự chốt và duyệt [spec](../superpowers/specs/2026-10-20-shp-02-self-events-cancel-design.md) (cả các mặc định §8):

| Quyết định | Hệ quả |
|---|---|
| PICKING = đã gán shipper, chưa lấy hàng; `HANDED_OVER` là sự kiện riêng đặt `handed_over_at` | Order chỉ SHIPPING ở mốc bàn giao (T15) |
| COD thu đủ `cod_amount` một lần (sau DELIVERED), nộp đủ phần còn lại một lần | Nộp nhiều đợt để sau nếu OPS cần |
| `evidence_ref` là mã tham chiếu, chưa upload ảnh | Bằng chứng file làm sau (ADM-02) |
| SELF event sai thứ tự/version → 409, không ghi `applied=false`; riêng shipment CANCELLED: ghi `applied=false` làm bằng chứng, 409 `CANCELLED_EVIDENCE_RECORDED` (review PR #30) | **Câu hỏi mở cho chủ dự án**: có chuyển CANCELLED → MANUAL + incident khi có bằng chứng bàn giao/giao (06 §17.2) không; hiện OPS thấy dòng `applied=false` trong chi tiết shipment, order không được báo |
| Cancel SELF trước bàn giao là tức thì; tombstone cần quote tồn tại và khớp | CANCEL_PENDING dành cho carrier |
| Path cancel `/internal/api/v1/shipping/shipments/by-order/{order_id}/cancel` | Tránh mơ hồ với `GET by-order/{order_id}` |
| Chưa phát `NOTIFY_SHIPMENT_STATUS` | Thêm ở NOT-01 |

## WEB-01 — quyết định 2026-10-19

`TASK:WEB-01`. Chủ dự án duyệt [spec](../superpowers/specs/2026-10-18-web-01-storefront-design.md) và chỉ đạo UI/UX đơn giản trước,
tinh chỉnh thẩm mỹ sau (task riêng):

| Quyết định | Hệ quả |
|---|---|
| Dùng contract đã implement (`catalog.yaml`) thay cho PLT-02 hoàn tất | PLT-02 vẫn In progress, không đánh dấu Done |
| Không thêm thư viện UI/i18n/state/testing | React Router + CSS thuần + từ điển `src/i18n.tsx` |
| Link sản phẩm theo id (`/products/:id`) | API detail nhận UUID; chưa có route theo slug |
| Ngôn ngữ lưu `localStorage`, mặc định VI, không vào URL | Link chia sẻ mở theo ngôn ngữ của người xem |
| "Tải thêm" thay infinite scroll; cursor không lưu URL | Reload về trang đầu của bộ lọc |
| Chưa có "Thêm vào giỏ" | WEB-02 |
| Fixture mock được validate với `catalog.yaml` bằng contract test | Mock không lệch contract |

## CART-02 — quyết định 2026-10-18

`TASK:CART-02` · `REQ:CART-03`. Chủ dự án giao agent tự chốt và duyệt [spec](../superpowers/specs/2026-10-18-cart-02-merge-design.md):

| Quyết định | Hệ quả |
|---|---|
| Merge là lệnh client gọi rõ ràng sau login (`POST /carts/merge`) | Auth không tự gộp; WEB-02 gọi sau login |
| Đã MERGED vào cùng giỏ member → 200 `already_merged`, kể cả key khác | T19: retry/song song chỉ cộng một lần |
| Target = max(qty member, min(member + guest, 99, available quan sát)); mọi dòng bỏ/giảm có adjustment | Không âm thầm bỏ item; không giảm item member |
| Member chưa có giỏ thì tạo trong merge | `member_expected_version` = 0 |
| Giỏ guest giữ items, chỉ MERGED + `merged_into`; response xóa cookie guest | Không migration |
| Tồn kiểm trước transaction, không giữ hàng | Như add item CART-01 |

## CART-03 — quyết định 2026-10-18

`TASK:CART-03` · `REQ:CART-05/06`. Chủ dự án giao agent tự chốt và duyệt [spec](../superpowers/specs/2026-10-18-cart-03-checkout-snapshot-design.md):

| Quyết định | Hệ quả |
|---|---|
| Snapshot/cleanup ở `/internal/api/v1/carts/...`, service token `aud=cart-service`, chỉ caller `order-service` | Gateway không route; shipping tự thêm vào allowlist ở SHP-01 nếu cần |
| Actor qua header `Cart-Actor` (`user:<uuid>`/`guest:<sha256>`), order xác minh trước | Cart không nhận raw cookie qua internal; ownership giữ quy tắc CART-01 |
| Idempotency cleanup theo `order_id` (`Idempotency-Key` phải bằng `order_id`) | Order retry bằng key khác vẫn không trừ hai lần |
| Chỉ xóa item còn khớp cả version và quantity; khác → giữ nguyên dòng (`ITEM_CHANGED`), không "giảm phần còn lại" | T18: item khách sửa/thêm sau snapshot không mất |
| Giỏ vẫn ACTIVE sau cleanup (không `CHECKED_OUT`); version chỉ tăng khi có xóa | Giữ unique ACTIVE member và item còn lại |
| Cart không gọi shipping: `subtotal_preview` (chỉ khi preview OK) + `shipping_fee_status: NOT_CALCULATED` | Fee preview là SHP-01 `POST /shipping/fee/estimate`; tổng chốt chỉ ở order quote |

**Câu hỏi mở cho ORD-01/WEB-03 — đã quyết định 2026-10-20:** cookie `cart_token` có `Path=/api/v1/carts` nên trình
duyệt không gửi nó tới `/api/v1/orders`; order-service cần một cách xác minh guest để điền `Cart-Actor`.

| Quyết định (chủ dự án chọn C, 2026-10-20) | Hệ quả |
|---|---|
| Guest checkout dùng **checkout token ngắn hạn**: cart-service xác minh cookie `cart_token` rồi cấp token ký (JWT ES256 qua `platform-security`) gắn `cart_id` + hash guest, sống ngắn; storefront gửi token trong header khi gọi order; order xác minh chữ ký/hạn/audience rồi điền `Cart-Actor` | Cookie `cart_token` giữ nguyên `Path=/api/v1/carts`; order không phụ thuộc định dạng cookie của cart; dùng được cho client không cookie (mobile) và mở rộng claim xác minh (OTP Phase 2). Chi phí: thêm endpoint cart, cặp khóa ký + xoay khóa, contract và test hết hạn/sai giỏ/dùng lại |
| Loại: mở rộng Path cookie (A), cookie thứ hai cho order (B) | A để cookie guest đi tới mọi service `/api/v1`; B phải đồng bộ hai cookie |

Chi tiết (TTL, tên header, endpoint, có giới hạn dùng một lần hay không, task sở hữu endpoint cart) chốt ở spec ORD-01.
CART-03 không đổi cookie.

## INV-03 — quyết định 2026-10-18

`TASK:INV-03` · `REQ:INV-07`. Chủ dự án giao agent tự chốt và duyệt [spec](../superpowers/specs/2026-10-18-inv-03-returns-design.md):

| Quyết định | Hệ quả |
|---|---|
| Một endpoint `/returns` cho restore sau hủy (release `restore_required`) và hàng hoàn đã kiểm đếm | Reservation vẫn COMMITTED, không thêm trạng thái |
| `stock_returns` thêm `evidence_ref`; UNIQUE `(reservation_id, operation_key, sku)` thay `(operation_key, sku)` | 05 cập nhật; key của order chỉ unique theo order |
| `Idempotency-Key` = `operation_key`; replay so dữ liệu đã lưu | Không thêm cột hash |
| Vượt tổng commit → 409 cả request | Không cộng một phần |
| Không event mới; dùng INVENTORY_UPDATED/RESTOCKED | Catalog projection nhận tồn mới như mọi movement |

## Quyết định chủ dự án 2026-10-18 — SEO và thứ tự gate

| Quyết định | Hệ quả |
|---|---|
| Storefront dạng SPA (không HTML sản phẩm render từ server) được chấp nhận cho MVP | ADR-17 giữ nguyên; phần SEO của O02/PLT-01 đóng; không thêm SSR/prerender/Next.js. Mở lại chỉ khi PO đổi yêu cầu SEO/preview |
| Hoãn G0 (PLT-04 staging, PLT-05 staging/dashboard, O02 scan CVE/license) tới sau G1; staging muộn nhất ngay sau G1, trước G2 | Phase 1B/G1 làm tiếp trên local; G2 vẫn cần mọi P0 Done. Rủi ro deploy (sizing node, secret, ingress, quyền DB) dồn về sau G1 |
| O06 (retention, consent, purge) phải chốt trước ORD-01 | Checkout/order thiết kế theo quyết định retention/consent; không chờ tới G2 |
| Review producer/consumer PLT-02 làm khi order-service có `order.events` | Lúc đó mới đủ producer và consumer để review |

**Cập nhật 2026-10-20:** agent đề xuất giá trị retention O06 (13 §3 "Giá trị cụ thể O06"): giỏ 30 ngày, PII trong đơn
ẩn danh sau 12 tháng từ khi đơn kết thúc, guest credential tối đa 90 ngày, log 30 ngày, traces 7 ngày, backup 30 ngày,
idempotency 30 ngày, tài khoản không hoạt động 24 tháng, chứng từ tài chính 10 năm. Chủ dự án xác nhận giữ nguyên
toàn bộ giá trị cùng ngày (sau review PR #31), nên điều kiện "chốt O06 trước ORD-01" **đã đạt**; xác minh pháp lý còn mở tới G2. Cách xác minh guest checkout đã chốt ngày 2026-10-20: checkout token ngắn hạn (mục CART-03 bên trên).

## CAT-02a — quyết định chủ dự án 2026-10-11

`TASK:CAT-02` phần 02a · `REQ:CAT-04`, hỗ trợ `REQ:CAT-03/05`.
Nguồn: [spec đã duyệt](../superpowers/specs/2026-10-11-cat-02a-public-browse-design.md) §1,
[evidence local](../evidence/cat-02a-local-2026-10-11.md); bốn nhóm quyết định:

| Nhóm | Quyết định đã duyệt | Hệ quả |
|---|---|---|
| Phạm vi và truy vấn | Tách 02a public read/quote PostgreSQL query-time JdbcClient và 02b cache/invalidation/sold consumer/inventory/HTTP cache | Parent CAT-02 Done (local) 2026-10-17 sau 02b; denormalize chỉ khi đo tải cần, không thêm đồng bộ write/projection |
| Search | unaccent + ILIKE bỏ dấu tiếng Việt, bind giá trị và escape %, _, backslash | V007 append-only; literal wildcard không mở rộng query |
| Giá đại diện | min(coalesce(variant.price_override,product.base_price)) trên variant ACTIVE, max tương tự | Dùng cho card/lọc/sort; VND integer long không âm |
| Visibility taxonomy | Product ACTIVE chỉ public nếu category ACTIVE, brand null hoặc ACTIVE, có variant ACTIVE | Kiểm ở thời điểm đọc cho browse/detail/collection/quote active; thiếu/ẩn cùng 404 |

`TASK:PLT-05` (local) · `REQ:NFR-01/03/04`. Chủ dự án chọn xem trace trong Grafana và giao agent tự chốt;
[spec](../superpowers/specs/2026-10-17-plt-05-kafka-trace-design.md) duyệt 2026-10-17:

| Quyết định | Hệ quả |
|---|---|
| Lưu W3C `traceparent` vào cột outbox | Parent/child chính xác qua relay bất đồng bộ; thêm cột ở mọi DB có outbox |
| Supplier truyền vào `OutboxRepository` | platform-durability không phụ thuộc thư viện tracing |
| Relay tự mở span PRODUCER; consumer dùng observation Spring Kafka | Không đổi envelope/contract event |
| OTLP HTTP thẳng tới Tempo, không Collector | Tempo down chỉ mất span; staging cần quyết định lại |
| Endpoint qua `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`, sampling 1.0 chỉ local | Boot 4 coi endpoint rỗng là đã cấu hình nên không khai báo mặc định trong yaml |
| Grafana anonymous Viewer (Explore), loopback, chỉ local | Không dùng cấu hình này ngoài máy dev |

`TASK:CAT-02` phần 02b · `REQ:CAT-04/05/09`. Chủ dự án giao agent tự chốt mặc định và duyệt
[spec](../superpowers/specs/2026-10-17-cat-02b-cache-projections-design.md) ngày 2026-10-17:

| Quyết định | Hệ quả |
|---|---|
| Một generation toàn cục thay xóa từng khóa | Đọc gen trước DB nên reader chậm không ghi đè; mọi thay đổi admin làm toàn bộ cache public miss |
| Invalidation qua `background_tasks` nội bộ, chưa phát CATALOG_CHANGED | Durable cùng transaction admin; Redis lỗi → retry/MANUAL, TTL giới hạn trễ |
| Không cache tồn; projection PostgreSQL theo SKU | Detail đọc observation mỗi request; event cũ không làm lùi |
| Thêm `available`/`observed_at` theo variant, top-level = tổng/cũ nhất | Tương thích thêm field; client không coi tồn là giữ hàng |
| Contract ORDER_COMPLETED định nghĩa trước order-service | Consumer nghiệm thu bằng event tổng hợp; ORD-03 kiểm E2E |
| Redis ACL user riêng cho catalog | Không dùng credential user-service; staging cần ACL tương ứng khi có Redis catalog |
| Header 30/5/60 s, lỗi no-store, Vary detail + size guide | Browser có thể giữ detail ≤ 5 s sau thay đổi |

Quyết định tương thích schema đã duyệt: lookbook V006 chỉ caption VI/EN làm alternate text;
cursor timestamp UTC năm1–9999, numeric long không âm (sort_order int), sai miền →400
INVALID_CURSOR trước SQL binding. Đây là các xác nhận boundary đã có, không mở thêm lựa chọn nghiệp vụ.
