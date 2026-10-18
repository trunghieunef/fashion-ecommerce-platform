# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Quy tắc chính nằm ở AGENTS.md (nguồn duy nhất; sửa quy tắc tại đó, không chép sang đây):

@AGENTS.md

## Bổ sung cho Claude Code

- Code hiện có: `services/cart-service` (giỏ guest cookie/member Bearer, gọi catalog quote + inventory stock bằng service token; internal snapshot/checkout-cleanup cho order-service), `services/catalog-service` (admin taxonomy/product/variant/collection/size guide, media S3 private, public browse/search và internal quote), `services/user-service` (auth, địa chỉ, role/permission, admin/audit), `services/inventory-service` (đăng ký SKU từ event, stock/ledger, OPS adjust/import CSV và internal stock read), `services/gateway` (route public/admin, CSRF và ranh giới internal), `web/storefront` (shell/catalog browse). Trạng thái nghiệm thu từng task theo backlog, không suy ra MVP đã hoàn thành.
- Thư viện dùng chung: `services/platform-durability` (outbox/inbox/idempotency/lease), `services/platform-security` (service token ADR-19/JWT), `services/platform-messaging` (Spring Kafka relay, inbox consumer, retry/DLT/metrics). `infra/local/compose.yaml` có PostgreSQL, Redis, RustFS và Kafka KRaft local; catalog relay phát VARIANT_CREATED, inventory tiêu thụ và phát stock snapshot qua outbox. DB/topic init idempotent trong local-up; không reset volume. Contracts OpenAPI/event schemas/registry ở `contracts/`.
- `scripts/scan-secrets.sh` dùng gitleaks; PLT-04 mới chuẩn bị offline (`infra/aws`, `infra/environments`, `infra/argocd`, `publish-images.yml` khóa), chưa provision. Lệnh build/test/smoke thật ở bảng "Lệnh local thực tế" trong `docs/engineering/12_engineering_guide.md` §1.
- Chạy một test Java: `./mvnw -pl services/catalog-service -Dtest=ProductQueryIntegrationTest test` (cần Docker cho Testcontainers). Nếu host không tới được port container (Ryuk/connection reset), đọc lưu ý Docker ở 12 §1; không sửa firewall.
- Chạy một test đơn của docs checker:
  `python3 -B -m unittest discover -s scripts -p 'test_*.py' -k test_invalid_json -v`
  (`scripts/` không phải package nên không gọi được dạng `scripts.test_check_docs...`).
- Kế hoạch triển khai theo thứ tự gate (S1-local → G0 → 1A → G1 → G2) nằm ở `docs/superpowers/plans/`; đọc `README.md` trong đó trước khi thực thi một plan. Trạng thái plan không thay trạng thái task trong backlog (10).
- `.worktrees/` (worktree cô lập theo phase) và `.superpowers/` (scratch của subagent) đã được gitignore; không đưa nội dung của chúng vào tài liệu hay commit.
- `AGENTS.md` nói dùng `apply_patch`; với Claude Code dùng Edit/Write tương đương.
