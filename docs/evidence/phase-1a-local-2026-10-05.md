# Phase 1A local — USR-01 phần 1a, ngày 2026-10-05

`TASK:USR-01` (phần 1a) · REQ: USR-01, USR-03, USR-05, USR-07 · dependency: PLT-02, PLT-03, SEC-01.
Assignee: Claude Code (agent); reviewer: chủ dự án. Phase 1A bắt đầu **trước G0** theo quyết định
của chủ dự án (ghi ở [08](../design/08_decisions.md)); G0 vẫn chờ O01. Đây không phải checkpoint
tích hợp Phase 1A.

## Quyết định trong phiên

| Câu hỏi | Lựa chọn của chủ dự án |
|---|---|
| Access token member | ES256, public key qua config (ADR-21) |
| Chia USR-01 | 1a: đăng ký, đăng nhập, refresh, logout; 1b: mật khẩu + bàn giao secret |
| Redis | Hoãn tới 1b; 1a chỉ khóa theo tài khoản trong PostgreSQL |

## TDD

| Bước | Kết quả |
|---|---|
| RED: `AuthIntegrationTest` (16 test) | FAIL: chưa có ứng dụng (`Unable to find @SpringBootConfiguration`) |
| GREEN: user-service (V001, `AuthService`, `AuthController`, `AccessTokenIssuer`) | 16/16 PASS ngay lần chạy đầu |
| Mutation (vì test pass ngay) | Đều FAIL đúng test: bỏ lowercase email (3 test); replay không thu hồi family; ngưỡng khóa 999; BCrypt cost 4; cookie không HttpOnly; email lạ trả lỗi khác; không ghi outbox; logout không thu hồi |
| RED Gateway: route `/api/v1/auth/**` | 404 |
| GREEN | route `user-auth-public` → `USER_BASE_URL`; gateway 18/18 |

## Chạy chuỗi local (Docker Desktop 29.2.0)

| Lệnh | Kết quả |
|---|---|
| `bash scripts/local-up.sh` trên volume local **đã có** | PASS: script init tạo DB `users` + role; khóa ký được sinh vào `.env`; user-service healthy |
| `bash scripts/smoke-local.sh` | PASS: đăng ký 201 → refresh 200 (cookie mới) → dùng lại cookie cũ 401 → logout từ Origin lạ 403 → logout 204 |
| Kiểm cách ly DB | `catalog_runtime` vào DB `users`: không có quyền CONNECT; `user_runtime` tạo bảng: `permission denied for schema public` |
| `npx playwright test`; `nacos-compat-check.sh` | 2/2; 4/4 PASS |
| `bash scripts/validate-contracts.sh` | `user.yaml` hợp lệ, 0 warning; 23 test PASS |

Chưa chạy fresh volume trên máy này (không xóa volume local của chủ dự án); Application CI chạy
fresh. Chưa có relay Kafka cho outbox `user.events` (chưa có Kafka trong stack local, chưa có
consumer bắt buộc).

## Còn mở

1b: quên/đặt lại/đổi mật khẩu, bàn giao secret cho notification, Redis + rate limit theo IP.
USR-02: verifier JWT ở Gateway/service, `/users/me`, địa chỉ, role/permission, `auth_version`.
Manifest staging cho user-service khi deploy được.
