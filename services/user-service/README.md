# User service

`TASK:USR-01` phần 1a, `TASK:USR-02` phần 2a + 2b · REQ: USR-01, USR-03, USR-05, USR-06, USR-07,
USR-08, ADM-06 · dependency: PLT-02, PLT-03, SEC-01.

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
| `GET, PUT /api/v1/users/me` | Cần Bearer access token. PUT chỉ đổi `full_name` và `locale`; `email`, role, `auth_version` trong body bị bỏ qua |
| `GET /admin/api/v1/users?page=&size=` | Cần permission `user.manage`. Danh sách tài khoản (cũ trước) kèm `roles`, `status`, `version` (= `auth_version`); không trả hash mật khẩu |
| `PUT /admin/api/v1/users/{id}/roles` | `roles`, `reason`, `expected_version`. Khóa dòng user, thay role, tăng `auth_version`, ghi `audit_logs` (`user.roles_changed`, role trước/sau, `request_id` của response). Gửi đúng role hiện có → 200 không đổi gì; version cũ → 409 `VERSION_CONFLICT`; role lạ → 400 |
| `POST /admin/api/v1/users/{id}/lock` | Header `Idempotency-Key`, `reason`, `expected_version`. Đặt `LOCKED`, tăng `auth_version`, thu hồi mọi refresh token, audit `user.locked`; cùng key + body trả lại response cũ, khác body → 409. Chưa có mở khóa (03 §3) |
| `GET, POST /api/v1/users/me/addresses`, `PUT, DELETE /api/v1/users/me/addresses/{id}` | Địa chỉ đầu tiên tự thành mặc định; luôn đúng một mặc định (khóa dòng user + unique index một phần); không bỏ được mặc định nếu chưa chọn địa chỉ khác (400 `DEFAULT_ADDRESS_REQUIRED`); xóa mặc định thì địa chỉ mới nhất còn lại thành mặc định; địa chỉ của người khác → 404 |

Mọi endpoint `/users/me` kiểm token bằng `AccessTokenVerifier` (platform-security, ADR-21), rồi
kiểm dòng user hiện tại: tài khoản phải `ACTIVE`, không bị khóa, và `auth_version` trong token phải
bằng giá trị trong DB. Đổi role, khóa tài khoản hay đổi mật khẩu (tăng `auth_version`) sẽ làm token cũ
mất hiệu lực ngay, không đợi hết 15 phút.

Admin không được tự đổi role hay tự khóa mình (403). Thứ tự khóa: dòng user rồi mới tới token, ở cả
refresh (`FOR SHARE`), login (`FOR NO KEY UPDATE`, vì login ghi lại dòng user; hai khóa share cùng
nâng cấp sẽ deadlock) và thao tác admin (`FOR UPDATE`), nên khóa tài khoản chạy song song với một lần
refresh sẽ đợi rồi thu hồi luôn token vừa xoay (test race tất định trong `AdminIntegrationTest`).

- **Access token:** JWT ES256, 900 giây, `iss=user-service`, `aud=fashion-api`, `sub` = user id,
  có claim `auth_version`, `permissions` (permission code của các role, 13 §2), `kid` lấy từ
  `USER_JWT_KEY_ID`. Verifier nhận public key qua config (ADR-21). Service khác kiểm `permissions`
  chỉ thấy role bị thu hồi sau tối đa 15 phút; user-service kiểm thêm `auth_version` nên có hiệu lực ngay.
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
| `USER_JWT_PUBLIC_KEYS` | không có mặc định | `kid:base64-X.509`, cách nhau bằng dấu phẩy (2 kid khi xoay khóa); local do `local-up.sh` suy ra từ private key |
| `USER_BOOTSTRAP_ADMIN_EMAIL` | rỗng (tắt) | Email của tài khoản **đã đăng ký** sẽ thành SUPER_ADMIN đầu tiên lúc khởi động, chỉ khi chưa có SUPER_ADMIN nào; audit actor SYSTEM. Không có mật khẩu admin mặc định. Đăng ký sau khi service chạy thì phải khởi động lại |

Migration: `V001__users.sql` (users, refresh_tokens, outbox_events), `V002__user_addresses.sql`,
`V003__roles_permissions_audit.sql` (roles/permissions + seed theo 13 §2, `audit_logs` chỉ
INSERT/SELECT cho `user_runtime`, `idempotency_requests`). Topic: ghi outbox
`user.events` / `USER_CREATED`, **chưa có relay** vì stack local chưa có Kafka và chưa có
consumer bắt buộc (03 §5.2). Health: `/actuator/health/{liveness,readiness}` trên port 8082.

## Admin bootstrap

Tạo SUPER_ADMIN đầu tiên trên local: đăng ký tài khoản, đặt `USER_BOOTSTRAP_ADMIN_EMAIL` trong
`infra/local/.env`, rồi chạy lại `bash scripts/local-up.sh` (service khởi động lại và cấp role).

## Giới hạn (phần 1b / USR-02)

- Chưa có quên/đặt lại/đổi mật khẩu và bàn giao secret cho notification (1b, cần Redis).
- Chưa có rate limit theo IP: chỉ khóa theo tài khoản trong PostgreSQL. Redis 8.2.9 thêm ở 1b.
- Chưa có mở khóa tài khoản và UI admin (ADM-01). `audit_logs.source_ip` để NULL: chưa có cách tin
  IP client qua Gateway. Gateway chưa tự kiểm JWT; service đích kiểm (04 §7).
- Chưa có manifest staging cho user-service (staging G0 chỉ có service mẫu).
