# 12 — Hướng dẫn phát triển và onboarding

B1 · 2026-09-19 · Owner TL + DEVOPS.

## 1. Trạng thái thực tế

Repo hiện có docs và CI kiểm tra tài liệu (`.github/workflows/docs-ci.yml`, `scripts/check_docs.py` cùng test). Chưa có pom.xml, package.json, Compose, migration hay lệnh chạy ứng dụng. PLT-01 phải hiện thực cấu trúc và ghi lệnh thực tế được kiểm chứng bên dưới trước G0. Không coi cấu trúc dự kiến là file đã tồn tại.

Theo ADR-18, Sprint 1 chỉ nhắm checkpoint S1-local: development/deployment trên máy local, không AWS/Kubernetes/GitOps. Local workflow phải chạy được trên clean checkout bằng lệnh đã ghi; một smoke path đi qua frontend shell, Gateway, service mẫu và PostgreSQL. Không scaffold toàn bộ service hoặc gọi kết quả là G0/G1/G2.

Danh mục và bộ phiên bản đã chọn sau research R1 được quản lý tại [17 Tech Stack](17_tech_stack.md): Java 21/Boot 4.0 theo BOM; React/Vite SPA, Node 24 LTS/npm. O02 vẫn mở cho runtime compatibility, lockfile/digest, license/CVE và xác nhận đánh đổi SEO; chưa có bộ dependency được kiểm thử trong repo.

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
| Gateway/auth | Issuer/audience/JWKS, caller token, allowed origins | Không trust client headers; internal private |
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

CI thực có hiện tại chỉ kiểm tra tài liệu bằng Python stdlib; lệnh chạy, phạm vi/giới hạn, cách bật required check và lộ trình GitHub OIDC → ECR → GitOps ở [16 AWS deployment](16_aws_deployment.md). Chưa có CD hoặc quyền AWS trong workflow này.

Rollback ứng dụng bằng image/manifests đã xác nhận; rollback schema chỉ khi có kế hoạch tested. Không drop dữ liệu tiền để quay lại migration cũ. Release cần biết phiên bản code nào đọc được schema mới.

## 9. Handoff một service

Service README thực tế phải có: responsibility; contract location; env vars; run/test commands; DB migration/version; topics producer/consumer; dependency timeout/ retry; health endpoints; metrics/alerts; seed/bootstrap; sandbox instructions; known limitations/owner. Tạo trong task service, không sao chép toàn PRD vào mỗi README.
