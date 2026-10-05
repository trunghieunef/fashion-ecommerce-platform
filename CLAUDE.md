# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Quy tắc chính nằm ở AGENTS.md (nguồn duy nhất; sửa quy tắc tại đó, không chép sang đây):

@AGENTS.md

## Bổ sung cho Claude Code

- Code hiện có chỉ là nền tảng S1-local: `services/catalog-service` (read sample), `services/gateway`, `web/storefront` shell, `contracts/` (OpenAPI catalog/common, event schemas + registry), `infra/local/compose.yaml`, thư viện `services/platform-durability` (PLT-03: outbox/inbox/idempotency/lease) và `services/platform-security` (SEC-01: service token ADR-19); Gateway có CSRF Origin filter (ADR-20); `scripts/scan-secrets.sh` (gitleaks); hạ tầng PLT-04 đã chuẩn bị offline (`infra/aws`, `infra/environments`, `infra/argocd`, `publish-images.yml` khóa), chưa provision. `services/user-service` (USR-01 phần 1a: đăng ký/đăng nhập/refresh/logout; USR-02: `/users/me`, địa chỉ, role/permission, `/admin/api/v1/users`, audit, admin bootstrap). Các service nghiệp vụ khác chưa tồn tại. Lệnh build/test/smoke thật ở bảng "Lệnh local thực tế" trong `docs/engineering/12_engineering_guide.md` §1.
- Chạy một test Java: `./mvnw -pl services/catalog-service -Dtest=ProductQueryIntegrationTest test` (cần Docker cho Testcontainers). Nếu host không tới được port container (Ryuk/connection reset), đọc lưu ý Docker ở 12 §1; không sửa firewall.
- Chạy một test đơn của docs checker:
  `python3 -B -m unittest discover -s scripts -p 'test_*.py' -k test_invalid_json -v`
  (`scripts/` không phải package nên không gọi được dạng `scripts.test_check_docs...`).
- Kế hoạch triển khai theo thứ tự gate (S1-local → G0 → 1A → G1 → G2) nằm ở `docs/superpowers/plans/`; đọc `README.md` trong đó trước khi thực thi một plan. Trạng thái plan không thay trạng thái task trong backlog (10).
- `.worktrees/` (worktree cô lập theo phase) và `.superpowers/` (scratch của subagent) đã được gitignore; không đưa nội dung của chúng vào tài liệu hay commit.
- `AGENTS.md` nói dùng `apply_patch`; với Claude Code dùng Edit/Write tương đương.
