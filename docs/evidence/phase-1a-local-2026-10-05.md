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

## Xử lý review PR #8 — 2026-10-05

Review của Codex (GPT-6) tại `be7c4cc` gồm 1 P1 và 1 P2; CI của PR PASS. Cả hai đều đúng.

| Finding | Nguyên nhân | RED | GREEN |
|---|---|---|---|
| P1: logout/replay chạy song song với rotation bỏ sót token mới | refresh chỉ khóa dòng token được gửi lên; `UPDATE` thu hồi family (READ COMMITTED) dùng snapshot trước lúc token mới được insert | 2 test race tất định (trigger test-only giữ insert lại, đợi phiên thứ hai đang chờ khóa rồi mới thả): token con vẫn refresh 200 | refresh, logout, thu hồi khi replay cùng lấy `pg_advisory_xact_lock` theo family trước khi đụng dòng token |
| P2: refresh trả `"user": null` trái OpenAPI | dùng chung `SessionData` với user null | test kiểm data chỉ có 3 key FAIL | kiểu `TokenData` riêng; contract `RefreshTokens` (`additionalProperties: false`) + example |

Mutation: bỏ khóa ở logout làm test logout-race FAIL; bỏ khóa ở refresh làm cả 2 test race FAIL.
3 lần chạy lại 3 test đồng thời: 0 lỗi. user-service 19/19.

## USR-02 phần 2a — 2026-10-05

Chủ dự án chọn làm USR-02 trước USR-01b; USR-02 chia 2a/2b (ghi ở 08).

| Chu trình | RED | GREEN |
|---|---|---|
| `AccessTokenVerifier` (platform-security) | thiếu class (compile) | 8 test: chọn khóa theo `kid` và xoay 2 khóa, sai khóa/kid/issuer/audience, hết hạn, `iat` tương lai, sống quá 15 phút, `sub` không phải UUID, thiếu `auth_version`, HS256, JWE, `alg: none`. Bỏ catch NPE, thay bằng kiểm tra `sub` tường minh |
| `/users/me` + địa chỉ | bảng `user_addresses` chưa có | 9 test PostgreSQL thật; user-service 28/28 |
| Route Gateway `/api/v1/users/**` | 404 | 19/19 |
| Refactor envelope chung `Api` | — | không đổi hành vi, 28/28 |

Mutation (đều FAIL đúng test): verifier bỏ kiểm issuer, audience, giới hạn thời gian sống, `auth_version`;
profile bỏ khóa dòng user khi update (test race giờ bắt buộc mọi PUT song song đều 200), bỏ kiểm
`auth_version` hiện tại, xóa mặc định không chọn địa chỉ thay thế, địa chỉ đầu không tự thành mặc
định, cho bỏ mặc định, bỏ lọc chủ sở hữu. Một mutation tôi viết sai (gán `auth_version = auth_version`)
không đổi hành vi nên không có giá trị; PUT bỏ qua `email`/`auth_version` vì record request không có
các field này.

Chuỗi local: `local-up.sh` suy ra `USER_JWT_PUBLIC_KEYS` từ private key; smoke PASS (`/users/me` qua
Gateway, tạo địa chỉ đầu tiên thành mặc định, không token → 401); Playwright 2/2; Nacos 4/4.

## Xử lý review PR #9 — 2026-10-05

Review của Codex (GPT-6) tại `89768d6` có 1 finding P2, đúng.

| Finding | Nguyên nhân | RED | GREEN |
|---|---|---|---|
| P2: lỗi binding ở profile/địa chỉ trả body mặc định của Spring (`timestamp/status/error/path`), thiếu `code`/`metadata` của `ApiError` | handler `MALFORMED_JSON` chỉ nằm cục bộ trong `AuthController`; lỗi `{id}` không phải UUID chưa có handler | test `bindingErrorsUseTheApiErrorEnvelope` (JSON hỏng ở PUT `/users/me`, POST địa chỉ, register; `{id}` sai ở PUT/DELETE): `code` rỗng | `ApiExceptionHandler` dùng chung xử lý `HttpMessageNotReadableException` (`MALFORMED_JSON`) và `MethodArgumentTypeMismatchException` (`INVALID_PARAMETER`, `errors` nêu tham số); contract thêm `400` cho DELETE địa chỉ |

Mutation: bỏ handler type-mismatch thì test FAIL ở `{id}` sai. user-service 29/29 (Testcontainers,
Docker Desktop 29.2.0); `validate-contracts.sh` PASS.

## USR-02 phần 2b — 2026-10-05

`TASK:USR-02` (phần 2b) · REQ: USR-08, ADM-06. Chủ dự án chọn: bootstrap admin bằng biến môi
trường, service kiểm quyền bằng claim `permissions` trong access token (ghi ở 08).

| Chu trình | RED | GREEN |
|---|---|---|
| Verifier đọc `permissions` (bắt buộc, mảng chuỗi) | `Actor` chưa có field (compile) | security 35/35 |
| `AdminIntegrationTest` (10 test PostgreSQL thật): seed đúng ma trận 13 §2; `audit_logs` runtime không UPDATE/DELETE được; bootstrap một lần + audit SYSTEM; token mang `permissions`; 401/403/200 theo `user.manage`; đổi role tăng `auth_version` + audit có `request_id` của response; version cũ 409, role lạ/thiếu reason/thiếu version 400, user lạ 404; tự đổi/tự khóa 403; khóa thu hồi phiên + replay theo `Idempotency-Key`; race khóa ↔ refresh | thiếu `AdminBootstrap` (compile) | `V003`, `AdminService`/`AdminController`/`AdminBootstrap`, issuer thêm claim, refresh/login khóa dòng user `FOR SHARE`; user-service 39/39 lần chạy đầu |
| Route Gateway `/admin/api/v1/users` | 404 | 20/20 |

Mutation (đều FAIL đúng test): refresh bỏ `FOR SHARE` (PostgreSQL báo deadlock giữa refresh và
khóa, 500, nên thứ tự khóa "dòng user rồi token" là bắt buộc); bỏ `REVOKE` trên `audit_logs`; khóa
không thu hồi refresh token; đổi role không tăng `auth_version`; bỏ kiểm permission; bootstrap không
kiểm SUPER_ADMIN đã có; cho tự sửa mình; bỏ version guard; bỏ replay idempotency; token không có
permission.

Chuỗi local (Docker Desktop 29.2.0, volume cũ): `local-up.sh` chạy V003 (flyway 001–003 thành công)
và tự thêm `USER_BOOTSTRAP_ADMIN_EMAIL` vào `.env`; smoke PASS (member gọi `/admin/api/v1/users` → 403).
Bootstrap qua Compose với tài khoản synthetic: trước 403 → đặt biến, khởi động lại user-service → log
"Admin bootstrap granted SUPER_ADMIN" (không có email trong log) → danh sách admin 200, đúng 1
SUPER_ADMIN; biến được trả về rỗng sau đó. Tài khoản synthetic này còn trong volume local nên
bootstrap lần sau trên volume này sẽ bỏ qua. `./mvnw -B test`: durability 36, security 35, user 39,
catalog 12, gateway 20; Playwright 2/2; Nacos PASS; `validate-contracts.sh` PASS.

## Còn mở

1b: quên/đặt lại/đổi mật khẩu, bàn giao secret cho notification, Redis + rate limit theo IP.
USR-02: reviewer nghiệm thu; mở khóa tài khoản và UI admin (ADM-01) chưa có; service khác kiểm
`permissions` khi tạo endpoint admin.
Manifest staging cho user-service khi deploy được.
