# User service

`TASK:USR-01` phần 1a · REQ: USR-01, USR-03, USR-05, USR-07 · dependency: PLT-02, PLT-03, SEC-01.

Service sở hữu database `users`: tài khoản member và refresh-token family. Phạm vi 1a:
đăng ký, đăng nhập (khóa tài khoản 15 phút sau 5 lần sai), refresh xoay vòng và đăng xuất.
Contract: [`contracts/openapi/user.yaml`](../../contracts/openapi/user.yaml); thiết kế ở
03 §2.1, 05 §4, 06 §2.1.

| Endpoint (qua Gateway) | Hành vi |
|---|---|
| `POST /api/v1/auth/register` | Email trim + lowercase, password 8 ký tự tới 72 byte (BCrypt cost 12), `full_name`, `locale` vi/en. 201 + access token + cookie refresh; email đã có → 409 `CONFLICT`. Ghi `USER_CREATED` (chỉ `user_id`, `locale`) vào outbox cùng transaction |
| `POST /api/v1/auth/login` | 200 + token + cookie. Email không tồn tại, sai mật khẩu và tài khoản đang khóa đều trả cùng 401 `INVALID_CREDENTIALS`; tài khoản không tồn tại vẫn chạy BCrypt với hash giả để thời gian phản hồi không lộ thông tin |
| `POST /api/v1/auth/refresh` | Cookie `refresh_token` dùng một lần: xoay sang token mới cùng family; response chỉ có `access_token`, `token_type`, `expires_in`. Dùng lại token đã xoay → thu hồi cả family, trả 401 và xóa cookie. Refresh, logout và thu hồi khi replay cùng lấy khóa `pg_advisory_xact_lock` theo family, nên việc thu hồi không bỏ sót token đang được tạo song song |
| `POST /api/v1/auth/logout` | Thu hồi family, xóa cookie, 204 (idempotent) |

- **Access token:** JWT ES256, 900 giây, `iss=user-service`, `aud=fashion-api`, `sub` = user id,
  có claim `auth_version`, `kid` lấy từ `USER_JWT_KEY_ID`. Verifier nhận public key qua config
  (quyết định 2026-10-05, ADR-21).
- **Refresh token:** 32 byte ngẫu nhiên, DB chỉ lưu SHA-256; cookie
  `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=30 ngày`. Request có cookie
  phải qua kiểm tra Origin của Gateway (ADR-20).
- Log JSON ECS có redaction, trace W3C, `/actuator/prometheus` như catalog (PLT-05).

## Chạy và test

```bash
./mvnw -pl services/user-service -am test     # cần Docker (Testcontainers postgres:17.11)
bash scripts/local-up.sh && bash scripts/smoke-local.sh
```

`local-up.sh` tạo database `users` và 2 role bằng `infra/local/postgres-init/02-user-db.sh`
(chạy lại mỗi lần, idempotent, nên volume cũ cũng nhận được DB). Script cũng tự sinh khóa ký
local vào `infra/local/.env` (đã gitignore). Trong Compose, service không publish port; chỉ
Gateway gọi được.

## Config

| Variable | Default local | Ý nghĩa |
|---|---|---|
| `USER_DB_URL` / `USER_DB_USERNAME` / `USER_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/users` / `user_runtime` / synthetic | Runtime: chỉ DML, không DDL |
| `USER_MIGRATION_DB_URL` / `_USERNAME` / `_PASSWORD` | `.../users` / `user_migration` / synthetic | Flyway |
| `USER_JWT_PRIVATE_KEY` | không có mặc định: thiếu thì service không khởi động | Base64 PKCS#8 EC P-256; staging/production lấy từ Secret |
| `USER_JWT_KEY_ID` | `user-local` | `kid` trong header JWT |

Migration: `V001__users.sql` (users, refresh_tokens, outbox_events). Topic: ghi outbox
`user.events` / `USER_CREATED`, **chưa có relay** vì stack local chưa có Kafka và chưa có
consumer bắt buộc (03 §5.2). Health: `/actuator/health/{liveness,readiness}` trên port 8082.

## Giới hạn (phần 1b / USR-02)

- Chưa có quên/đặt lại/đổi mật khẩu và bàn giao secret cho notification (1b, cần Redis).
- Chưa có rate limit theo IP: chỉ khóa theo tài khoản trong PostgreSQL. Redis 8.2.9 thêm ở 1b.
- Chưa có verifier JWT ở Gateway/service, chưa có `/users/me`, địa chỉ, role/permission (USR-02).
- Chưa có manifest staging cho user-service (staging G0 chỉ có service mẫu).
