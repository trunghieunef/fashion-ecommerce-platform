# Handoff Phase 0/1A — cuối ngày 2026-10-05

Thay cho [handoff Phase 0 ngày 2026-10-04](2026-10-04-phase-0-handoff.md) (giữ làm lịch sử).
Tác giả: Claude Code (agent). Handoff này không thay trạng thái task trong
[backlog](../../delivery/10_backlog.md) hay acceptance của gate.

## 1. Trạng thái repo

- `main` = `7b375e2`, đã merge tới PR #8 (PR #6 PLT-04 chuẩn bị offline, PR #7 PLT-05 local,
  PR #8 USR-01 phần 1a).
- **PR #9 đang mở** (`dev` → `main`): USR-02 phần 2a, commit `89768d6`.
  https://github.com/trunghieunef/fashion-ecommerce-platform/pull/9 — lúc viết, Documentation CI
  PASS, Application CI đang chạy; chưa có review.
- Commit handoff này nằm trên `dev`, **chưa push** (để không đổi PR #9 khi đang review).
- Stack Compose local vẫn đang chạy (postgres, catalog, user-service, gateway, storefront). Tắt bằng
  `docker compose --env-file infra/local/.env -f infra/local/compose.yaml down` (giữ volume).

## 2. Trạng thái task

| Task | Trạng thái | Đã có | Còn mở |
|---|---|---|---|
| PLT-01/02/03, SEC-01 | In progress | Như handoff 2026-10-04, cộng actuator Gateway tách port 9080 | Như cũ |
| PLT-04 | In progress — chuẩn bị offline | Template, workflow khóa, manifest; sửa review PR #6 (HTTPS, first boot, backup) | Toàn bộ phần cần O01 |
| PLT-05 | In progress — phần local | Trace W3C Gateway → catalog/user, log JSON ECS có redaction (theo tên field + regex), `/actuator/prometheus` | Correlation Kafka, gauge outbox, dashboard/alert/exporter (cần PLT-04) |
| USR-01 | In progress — 1a xong (PR #8) | `services/user-service`: đăng ký, đăng nhập (khóa 15 phút), refresh xoay vòng + thu hồi family có khóa advisory, logout | **1b**: quên/đặt lại/đổi mật khẩu, bàn giao secret, Redis + rate limit IP |
| USR-02 | In progress — 2a (PR #9) | `AccessTokenVerifier`, `/users/me`, sổ địa chỉ một mặc định | **2b**: role/permission, admin đổi role/khóa, audit, admin bootstrap |
| O01 | Mở | — | Region, credit, ngân sách, email alert, domain, thời hạn backup |
| O06 | Mở | PO duyệt bảng retention | Ngày hạn, giá trị cụ thể trong khoảng |

Phase 1A đang chạy **trước G0** theo quyết định chủ dự án (08); G0 vẫn chờ O01.

## 3. Quyết định đã chốt trong ngày (đều ghi ở 08)

- ADR-21: access token member là JWT ES256 do user-service ký, public key qua config (`kid`).
- USR-01 chia 1a/1b; USR-02 chia 2a/2b; làm USR-02 trước USR-01b.
- Rate limit mặc định cho 1b: đăng nhập 20 / 5 phút / IP; quên mật khẩu 5 / giờ / email và
  20 / giờ / IP; 429 + `Retry-After`; Redis lỗi thì fail closed.

## 4. Bước tiếp theo đề xuất

1. Xử lý review PR #9 khi có (kiểm tra từng finding với code, sửa theo TDD, trả lời thread).
2. **USR-02b:** migration role/permission/user_roles/role_permissions/audit_logs; seed role
   SUPER_ADMIN/OPS/MARKETING/FINANCE và permission code theo 13 §2; `audit_logs` chỉ cho
   INSERT/SELECT với role runtime; API admin `/admin/api/v1/users` (danh sách, đổi role, khóa) cần
   permission `user.manage`, tăng `auth_version`, thu hồi refresh family khi khóa, ghi audit có lý do;
   admin bootstrap (đề xuất: biến môi trường gán SUPER_ADMIN một lần cho tài khoản đã có, ghi audit
   actor SYSTEM — **cần chủ dự án xác nhận**); có thể thêm claim `permissions` vào access token để
   service khác kiểm quyền (đổi contract ADR-21, cần ghi lại).
3. **USR-01b:** Redis 8.2.9 vào Compose/Testcontainers (license Redis 8 vẫn chờ PO/TL theo 17), rate
   limit theo ngưỡng ở mục 3, quên/đặt lại mật khẩu (token một lần 30 phút, hash), đổi mật khẩu (tăng
   `auth_version`, thu hồi mọi family), bàn giao secret qua Redis mã hóa + `GET /internal/api/v1/users/notification-secrets/{challenge_id}`
   (service token ADR-19), event `NOTIFY_RESET_PASSWORD` vào registry contract.
4. O01 vẫn cần chủ dự án cung cấp để deploy staging (G0).

## 5. Lệnh kiểm tra (từ repo root)

```bash
./mvnw -B test        # durability 36, security 34, user 28, catalog 12, gateway 19 (cần Docker)
bash scripts/validate-contracts.sh      # venv: tests/contracts/requirements.txt
bash scripts/validate-infra.sh          # venv: tests/infrastructure/requirements.txt
bash scripts/scan-secrets.sh            # gitleaks qua Docker, quét git history
bash scripts/local-up.sh && bash scripts/smoke-local.sh && npx playwright test
bash scripts/nacos-compat-check.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py && git diff --check
```

Lần chạy cuối (2026-10-05, trên `89768d6`): tất cả PASS.

## 6. Lưu ý môi trường và cách làm

- Toolchain R1 và venv như handoff 2026-10-04 (JDK/Node ở thư mục toolchains riêng, đường dẫn venv
  dạng `/c/...` trong Git Bash). Docker Desktop phải đang chạy cho Testcontainers và Compose.
- `local-up.sh` tự sinh `USER_JWT_PRIVATE_KEY` và suy ra `USER_JWT_PUBLIC_KEYS` vào `infra/local/.env`
  (gitignored), bổ sung key mới từ `.env.example` vào `.env` cũ, và chạy lại script tạo DB
  `users` (idempotent) cho volume cũ.
- Dữ liệu test/ví dụ synthetic có dạng giống secret phải đánh dấu `gitleaks:allow` đúng dòng kèm lý do,
  nếu không bước quét secret trên CI sẽ fail.
- Shell heredoc chứa dấu nháy đơn trong nội dung có thể làm Bash tool lỗi parse; ghi script ra file
  tạm rồi chạy sẽ ổn định hơn.
- Lệnh mutation chạy nhiều lượt Maven có thể vượt 10 phút và chuyển nền; không sửa file hay chạy
  Maven song song trong lúc đó.
