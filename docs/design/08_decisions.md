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
| ADR-17 | React + Vite SPA cho storefront/admin, Node 24 LTS + npm; static hosting | Ưu tiên ít runtime và dễ deploy. Giả định MVP chưa bắt buộc SEO/HTML sản phẩm từ server; PO/FE xác nhận trước WEB-01. Nếu không đạt, mở lại lựa chọn SSR/prerender; không tự thêm Next.js/BFF. Version/peer constraints ở 17 |
| ADR-18 | Sprint 1 chỉ triển khai local; AWS/cloud bắt đầu sau checkpoint S1-local | Chủ dự án chốt 2026-09-19. Sprint 1 hoàn thành nền tảng local chạy từ fresh clone, không đồng nghĩa toàn bộ G2 MVP; không provision AWS/K3s/ECR/ArgoCD hoặc gọi provider thật. Phạm vi và exit ở 09/10 |
| ADR-19 | Service identity cho `/internal`: mỗi service tự ký JWT ES256 bằng khóa riêng (`iss = sub = kid =` tên service, `aud =` service đích, TTL 60s); service đích kiểm tra theo allowlist public key của từng caller, sau đó endpoint kiểm tra caller allowlist 03 §4 | Chủ dự án chọn 2026-10-04 (SEC-01). Chạy được cả Compose local và K3s, không cần CA hay service mesh. Phương án khác: K8s service-account token (local không có), mTLS (nặng vận hành). Private key qua Secret, public key là config. Hiện thực tại `services/platform-security`; chưa có service gọi internal thật |
| ADR-20 | CSRF cho request ghi dùng cookie: kiểm `Origin` theo allowlist, nếu không có Origin thì chỉ nhận `Sec-Fetch-Site: same-origin`, không có cả hai thì từ chối; cookie `Secure`, `HttpOnly`, `SameSite`. Không dùng CSRF token | Chủ dự án chọn 2026-10-04 (SEC-01). Thực thi tại Gateway (`CsrfOriginFilter`, `GATEWAY_ALLOWED_ORIGINS`). FE không cần gửi token. Request chỉ dùng Bearer không bị kiểm. Nếu sau này cần double-submit token thì ghi ADR mới |
| ADR-21 | Access token member/admin: JWT ES256 do user-service ký (`iss=user-service`, `aud=fashion-api`, TTL 900 s, `auth_version`, `permissions` = mảng permission code của các role, `kid`; thêm `permissions` 2026-10-05 ở USR-02b); verifier nhận public key qua config, xoay khóa bằng cách allowlist 2 `kid`. Không có endpoint JWKS | Chủ dự án chọn 2026-10-05 (USR-01). Cùng mô hình ADR-19, không phụ thuộc mạng lúc verify. Phương án khác: ES256/RS256 + JWKS (tự xoay khóa nhưng thêm route/cache). Private key qua Secret; local do `local-up.sh` sinh vào `.env` |

## 4. Vấn đề mở

| ID | Cần quyết định / đầu ra | Owner | Hạn gate | Có thể làm trước |
|---|---|---|---|---|
| O01 | AWS đã chọn nhưng hoãn khỏi Sprint 1; còn plan/số dư/hạn credit $200, region, ngân sách gộp và tiền tự trả, staging sizing, managed DB/Redis và prod cluster | PO + DEVOPS | Sau S1-local, trước PLT-04 / G2 | Sprint 1 local không bị chặn; đề xuất EC2 + K3s, cost worksheet và subtasks tại 16; chưa tạo tài nguyên |
| O02 | R1 đã chọn bộ version ở 17; còn nghiệm thu runtime compatibility, lockfile/digest, license/CVE và yêu cầu SEO trước WEB-01 | TL + DEVOPS + FE | PLT-01 core; PLT-02/04/05 theo tooling | [S1-local accepted ngày 2026-10-04](../evidence/s1-local-2026-10-04.md) bởi Codex (GPT-6) theo chỉ định chủ dự án, runtime Windows và CI patch `b62f963` PASS; lệch Docker/Compose chấp nhận riêng cho local. Còn Kafka/Redis, image scan/CVE/license, deployment và xác nhận SEO; không tắt verifier/ép peer dependencies để vượt lỗi |
| O03 | Merchant sandbox/production, callback domains, quyền query/refund VNPay/MoMo | PO + FINANCE | PAY-02/03 / G2 | Mock và negative tests |
| O04 | Phí ship/COD, vùng SELF, nguồn địa chỉ, carrier account/SLA | PO + OPS | SHP-01/03 | Fixture giả và adapter |
| O05 | Đổi trả, phí, hàng hỏng, bằng chứng, đơn tổng 0; giá đã gồm thuế hay chưa và yêu cầu hóa đơn | PO + FINANCE | ORD-01/04 / G2 | Baseline không cộng thuế ngoài giá niêm yết; chặn checkout total <= 0; nếu cần thuế tách dòng phải sửa pricing/schema trước code |
| O06 | Retention PII, điều khoản/consent, nghĩa vụ pháp lý hiện hành | PO + phụ trách pháp lý | SEC-01 / G2; ngày hạn hoàn tất: chưa được chỉ định | Chủ dự án (PO) duyệt toàn bộ bảng retention và ngoại lệ tại [13 §3](../operations/13_operations_security.md) ngày 2026-10-04, theo xác nhận trong phiên làm việc. Còn giá trị cụ thể trong các khung thời gian, chi tiết policy, nhóm dữ liệu ngoài bảng và xác minh pháp lý; O06 vẫn mở cho các phần này; chưa bật purge tài chính |
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

Mẫu quyết định mới: ID; vấn đề; lựa chọn; phương án khác và lý do; ảnh hưởng PRD/API/schema/test/task; người quyết định; ngày; link bằng chứng. Chưa có chữ ký phê duyệt giả định thương mại.
