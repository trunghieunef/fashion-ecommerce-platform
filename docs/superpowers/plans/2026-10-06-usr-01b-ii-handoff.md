# Handoff USR-01b-ii — 2026-10-06

Đọc [evidence](../../evidence/usr-01b-ii-local-2026-10-06.md), [backlog](../../delivery/10_backlog.md)
và [User service](../../../services/user-service/README.md) trước khi tiếp tục.

## Trạng thái

- PR #11 (USR-01b-i password flows) đã merge. Branch `feat/usr-01b-ii-rate-limit` bổ sung
  quota Redis login/forgot, proxy allowlist/IP verified, 429/Retry-After, Redis fail closed
  và health. Superpowers: thiết kế được duyệt → TDD → review độc lập → verification → PR.
- Phần 1b-ii ở Review, parent USR-01 vẫn In progress. Workflow do chủ dự án yêu cầu:
  tạo PR cho chủ dự án review; chỉ merge sau review/approval. Chưa có approval merge.
- [PR #12](https://github.com/trunghieunef/fashion-ecommerce-platform/pull/12) có review trên
  `96e6ea0`: parser DNS/zone/peer/scheduler đã sửa, bổ sung unit regression, HMAC/env quota
  và startup WARN. CI baseline `96e6ea0` PASS; phải kiểm lại SHA patch mới. Readiness Redis
  và IPv6 subnet policy vẫn giữ hiện trạng đã duyệt, ghi open trước staging/dual-stack ở 08.
- USR-02a/2b có profile/address/RBAC/admin API; UI auth/admin, catalog đầy đủ, inventory,
  cart/checkout chưa có. S1-local accepted, G0 còn O01; chưa đạt G1/G2.
- Handoff Phase 1A ngày 2026-10-06 trên local `dev` tại `df06231` chưa có trên origin/main;
  branch `dev` được giữ nguyên, PR này dùng baseline đã merge và handoff riêng này.

## Tiếp theo

1. Chủ dự án review PR USR-01b-ii; kiểm Application/Documentation CI trên SHA của PR.
   Không lấy local PASS thay acceptance parent hoặc tự merge.
2. Sau review: CAT-01 theo [plan Phase 1A](2026-09-19-phase-1a-commerce-core.md), cần quyền
   `catalog.write` từ USR-02; rồi INV-01/cart. Đọc contract 03/schema 05/flows 06 trước sửa.
3. NOT-01 cần Kafka/outbox relay để tiêu thụ `NOTIFY_RESET_PASSWORD` và lấy secret internal.
   Hiện chưa gửi email thật. O01 và license Redis/SEO/O02 còn cần quyết định tương ứng.

## Kiểm tra

```bash
./mvnw -B test
npm run typecheck && npm test && npm run build
bash scripts/validate-contracts.sh
bash scripts/validate-infra.sh
bash scripts/scan-secrets.sh
bash scripts/local-up.sh && bash scripts/smoke-local.sh
bash scripts/smoke-auth-redis-timeout.sh
npx playwright test
bash scripts/nacos-compat-check.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
git diff --check
```

Redis timeout smoke chỉ dành cho stack local không có traffic người dùng; pause/unpause Redis
và giữ dữ liệu. Khi thay budget timeout, phải kiểm public response qua Gateway. Vite dev mặc
định gom quota theo peer dev proxy; Docker nginx có overwrite header cho trust chain thật.
Ingress staging phải cấu hình allowlist/hop tương ứng rồi kiểm spoofing, không tự tin mọi IP private.
