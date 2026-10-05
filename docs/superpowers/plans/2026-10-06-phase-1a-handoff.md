# Handoff Phase 1A — 2026-10-06

> **Đã được thay** bởi [handoff USR-01b-ii — 2026-10-06](2026-10-06-usr-01b-ii-handoff.md) (USR-01b-ii đã làm ở PR #12). Giữ làm lịch sử.

Thay cho [handoff Phase 0/1A ngày 2026-10-05](2026-10-05-phase-1a-handoff.md) (giữ làm lịch sử).
Tác giả: Claude Code (agent). Handoff này không thay trạng thái task trong
[backlog](../../delivery/10_backlog.md) hay acceptance của gate.

## 1. Trạng thái repo

- `main` = `dc24935`, đã merge tới PR #11. `dev` không có commit nào chưa merge; không có PR mở.
- PR đã merge từ handoff trước: #9 (USR-02a + sửa lỗi binding), #10 (USR-02b: RBAC, admin, audit,
  bootstrap), #11 (USR-01b-i: đổi/quên/đặt lại mật khẩu, bàn giao secret qua Redis).
- Stack Compose local đang chạy (postgres, redis, catalog, user-service, gateway, storefront). Tắt:
  `docker compose --env-file infra/local/.env -f infra/local/compose.yaml down` (giữ volume).
- Volume local có một tài khoản SUPER_ADMIN synthetic (từ lần thử bootstrap), nên bootstrap trên
  volume này sẽ bỏ qua.

## 2. Trạng thái task

| Task | Trạng thái | Đã có | Còn mở |
|---|---|---|---|
| PLT-01/02/03, SEC-01 | In progress | Như handoff 2026-10-04; `ServiceTokenVerifier` đã nối vào user-service (endpoint internal đầu tiên) | Như cũ |
| PLT-04 | In progress — chuẩn bị offline | Template, workflow khóa, manifest | Toàn bộ phần cần O01 |
| PLT-05 | In progress — phần local | Trace, log ECS có redaction, Prometheus | Correlation Kafka, gauge outbox, dashboard/alert (cần PLT-04) |
| USR-01 | In progress — 1a + 1b-i | Đăng ký/đăng nhập/refresh/logout; đổi/quên/đặt lại mật khẩu; `user_action_tokens` (V004); token reset mã hóa AES-256-GCM trong Redis; `GET /internal/api/v1/users/notification-secrets/{id}` (chỉ notification-service); `NOTIFY_RESET_PASSWORD` trong registry | **1b-ii**: rate limit (xem §4) |
| USR-02 | In progress — 2a + 2b đã code | `/users/me`, địa chỉ; V003 role/permission theo 13 §2; claim `permissions` (ADR-21); `/admin/api/v1/users` (danh sách, đổi role, khóa); `audit_logs` append-only; bootstrap `USER_BOOTSTRAP_ADMIN_EMAIL` | Reviewer nghiệm thu; mở khóa tài khoản, UI admin (ADM-01) |
| O01 | Mở | — | Region, credit, ngân sách, email alert, domain, thời hạn backup |
| O06 | Mở | PO duyệt bảng retention | Ngày hạn, giá trị cụ thể |
| License Redis 8 | Mở | Chủ dự án cho dùng 8.2.9 ở local/test | PO/TL chọn license trước release (17) |

## 3. Quyết định trong ngày (đều ghi ở 08)

- Bootstrap admin bằng biến môi trường; service kiểm quyền bằng claim `permissions` (thu hồi ở service
  khác trễ tối đa 15 phút).
- Redis 8.2.9 cho local/test; USR-01b chia 1b-i và 1b-ii.
- Agent tự chọn, **chờ reviewer xác nhận**: admin không tự sửa/khóa mình; chưa có mở khóa; SUPER_ADMIN
  chỉ có `user.manage`; event reset mang `recipient.email`; service token qua `Authorization: Bearer`;
  đổi mật khẩu thu hồi cả phiên hiện tại; sai mật khẩu hiện tại tính vào khóa; reset xóa khóa đăng nhập sai.

## 4. Bước tiếp theo đề xuất

1. **USR-01b-ii — rate limit** (ngưỡng đã chốt 2026-10-05, config đổi được): đăng nhập 20 request / 5
   phút / IP; quên mật khẩu 5 / giờ / email và 20 / giờ / IP; vượt ngưỡng 429 + `Retry-After`
   (`RATE_LIMITED`); Redis lỗi thì fail closed (503). Cần quyết định cách lấy IP client: Gateway là
   proxy duy nhất nên phải chọn header tin cậy (vd. Gateway ghi đè `X-Forwarded-For`, service chỉ tin
   hop từ Gateway) — **hỏi chủ dự án** nếu chưa rõ. Thêm Redis vào readiness của user-service khi
   login phụ thuộc Redis. Test: vượt ngưỡng, reset cửa sổ, Redis down → 503, không lộ email.
2. Sau USR-01: tuyến Phase 1A còn lại theo [plan 1A](2026-09-19-phase-1a-commerce-core.md): CAT-01
   (catalog admin, cần USR-02 permission `catalog.write`), INV-01, cart. Đọc 10 để chọn task kế; endpoint
   admin của service mới kiểm `permissions` bằng `AccessTokenVerifier`.
3. NOT-01 (notification) sẽ tiêu thụ `NOTIFY_RESET_PASSWORD` và gọi endpoint internal; cần relay outbox
   + Kafka trong stack local.
4. O01 vẫn cần chủ dự án để deploy staging (G0).

## 5. Lệnh kiểm tra (từ repo root)

```bash
./mvnw -B test        # durability 36, security 35, user 54, catalog 12, gateway 20 (cần Docker)
bash scripts/validate-contracts.sh      # venv: tests/contracts/requirements.txt
bash scripts/validate-infra.sh          # venv: tests/infrastructure/requirements.txt
bash scripts/scan-secrets.sh            # gitleaks qua Docker, quét git history
bash scripts/local-up.sh && bash scripts/smoke-local.sh && npx playwright test
bash scripts/nacos-compat-check.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py && git diff --check
```

## 6. Lưu ý môi trường và cách làm

- JDK/Node ở `C:\Users\<user>\.local\toolchains\` (Temurin 21.0.12.1+1, Node 24.21.0); trong Git Bash
  cần `export JAVA_HOME=/c/Users/<user>/.local/toolchains/jdk-21.0.12.1+1` và thêm `bin` vào `PATH`
  trước `./mvnw`. Docker Desktop phải chạy (Testcontainers, Compose).
- **Mutation test:** chạy bằng `./mvnw` trong bash, kiểm log có đúng `Tests run: 1,` và không có
  `COMPILATION ERROR`. Không gọi `mvnw.cmd` qua `cmd /c` từ Python: lệnh này không chạy được và exit
  code khác 0 từng bị hiểu nhầm là test FAIL (đã đính chính ở evidence 2026-10-05).
- Race test tất định: một kết nối superuser giữ `FOR SHARE` trên dòng user, đợi `pg_locks` có đủ
  phiên chờ rồi commit; thứ tự khóa chuẩn là **dòng user trước token** (login `FOR NO KEY UPDATE`,
  refresh `FOR SHARE`, admin `FOR UPDATE`).
- Sau khi `local-up.sh` tạo lại container user-service, smoke lần đầu có thể gặp 504 ở Gateway (kết
  nối cũ); chạy lại thì PASS.
- Dữ liệu synthetic giống secret phải có `gitleaks:allow` đúng dòng; dòng nằm trong lệnh nhiều dòng thì
  tách ra biến riêng. Heredoc chứa dấu nháy đơn có thể làm Bash tool lỗi parse: ghi script ra file.
- Evidence chi tiết: [phase-1a-local-2026-10-05.md](../../evidence/phase-1a-local-2026-10-05.md).
