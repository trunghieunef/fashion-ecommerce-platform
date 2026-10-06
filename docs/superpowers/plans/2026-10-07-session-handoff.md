# Handoff phiên làm việc — 2026-10-07

Đọc file này đầu tiên khi mở phiên mới. Nó thay cho
[handoff USR-01b-ii](2026-10-06-usr-01b-ii-handoff.md) và
[handoff Phase 1A](2026-10-06-phase-1a-handoff.md) (giữ làm lịch sử). Tác giả: Claude Code (agent).
Handoff không thay trạng thái task trong [backlog](../../delivery/10_backlog.md) hay acceptance gate.

## 1. Đang ở đâu

- `main` = `dev` = `826e361` (merge PR #13). Không có PR mở, working tree sạch.
- Nhánh `feat/usr-01b-ii-rate-limit` đã merge (PR #12) nhưng chưa xóa (local + origin).
- Phase 1A đang chạy trên local **trước G0** (quyết định chủ dự án, 08); G0 còn chờ O01.
- Stack Compose local có thể vẫn chạy (postgres, redis, catalog, user-service, gateway, storefront).
  Volume local có một SUPER_ADMIN synthetic nên bootstrap admin trên volume này sẽ bỏ qua.

| PR | Nội dung | Trạng thái |
|---|---|---|
| #8 | USR-01a: đăng ký/đăng nhập/refresh/logout | Merged |
| #9 | USR-02a: verifier JWT, `/users/me`, địa chỉ | Merged |
| #10 | USR-02b: role/permission, claim `permissions`, `/admin/api/v1/users`, audit, bootstrap | Merged |
| #11 | USR-01b-i: đổi/quên/đặt lại mật khẩu, secret mã hóa trong Redis, endpoint internal | Merged |
| #12 | USR-01b-ii: rate limit login/forgot, trust chain IP (Gateway → user-service) | Merged |
| #13 | Đồng bộ handoff vào `main` | Merged |

## 2. Trạng thái task

| Task | Trạng thái | Còn mở |
|---|---|---|
| USR-01 | 1a + 1b-i + 1b-ii đã merge | Reviewer nghiệm thu parent; UI auth (WEB) |
| USR-02 | 2a + 2b đã merge | Reviewer nghiệm thu; mở khóa tài khoản; UI admin (ADM-01) |
| PLT-01/02/03, SEC-01, PLT-05 | In progress (phần local) | Như handoff 2026-10-04; phần cần staging chờ PLT-04 |
| PLT-04 | Chuẩn bị offline | Toàn bộ phần cần O01 |
| CAT-01, INV-01, cart, NOT-01 | Chưa bắt đầu | Xem §4 |

## 3. Quyết định và mục mở cần chủ dự án

Đã chốt (ghi ở 08): ADR-21 access token ES256 + claim `permissions`; bootstrap admin bằng
`USER_BOOTSTRAP_ADMIN_EMAIL`; Redis 8.2.9 cho local/test; ngưỡng rate limit login 20/5 phút/IP,
forgot 20/giờ/IP và 5/giờ/email, 429 + `Retry-After`, Redis lỗi → 503.

Chờ xác nhận hoặc quyết định:

- **Agent tự chọn, chờ reviewer:** admin không tự đổi role/tự khóa; chưa có mở khóa; SUPER_ADMIN chỉ
  có `user.manage`; event reset mang `recipient.email`; service token qua `Authorization: Bearer`;
  đổi mật khẩu thu hồi cả phiên hiện tại; sai mật khẩu hiện tại tính vào khóa; reset xóa khóa đăng nhập sai.
- **Trước manifest staging user-service (PO/TL):** giữ Redis trong readiness (Redis lỗi rút cả pod,
  kể cả refresh/`/users/me`) hay tách health group.
- **Trước ingress dual-stack (PO/TL + QA):** gom quota IPv6 theo /64 hay giữ theo địa chỉ.
- **Trước release:** license Redis 8 (AGPLv3/RSALv2/SSPLv1, 17); ngưỡng 20/5 phút/IP có thể chặt với
  NAT nhà mạng di động.
- **O01** (region, credit, ngân sách, alert, domain, backup) để deploy G0; **O06** ngày hạn/giá trị retention.

## 4. Bước tiếp theo đề xuất

1. **CAT-01** theo [plan Phase 1A](2026-09-19-phase-1a-commerce-core.md) và 10: catalog admin CRUD,
   variant, `VARIANT_CREATED` qua outbox, SKU immutable. Endpoint admin kiểm permission `catalog.write`
   bằng `AccessTokenVerifier` (claim `permissions`). Đọc 03 §3, 05, 06 trước khi sửa; dùng brainstorming
   rồi TDD như các task trước.
2. **INV-01**, rồi cart (cần user + catalog).
3. **NOT-01**: cần relay outbox + Kafka trong stack local để tiêu thụ `NOTIFY_RESET_PASSWORD` và gọi
   `GET /internal/api/v1/users/notification-secrets/{id}`. Hiện chưa gửi email thật.
4. Dọn nhánh `feat/usr-01b-ii-rate-limit` nếu chủ dự án đồng ý.

## 5. Quy trình đã dùng (giữ nguyên)

- Chủ dự án giao việc bằng câu ngắn: "tiếp tục", "push dev và mở PR vào main", "đã có review trên PRx,
  xem và fix nếu đúng", "đã merged PRx". Review thường do Codex hoặc agent khác đăng dạng COMMENT.
- Mỗi task: hỏi quyết định nghiệp vụ/bảo mật còn thiếu → TDD (RED → GREEN) → mutation thật → đồng bộ
  docs (03/05/08/10/13, README service, evidence) → commit trên `dev` → chỉ push/mở PR khi được bảo.
- Xử lý review: tái hiện từng finding bằng test trước khi sửa, sửa, trả lời từng thread kèm SHA.
- Không commit secret; dữ liệu synthetic giống secret phải có `gitleaks:allow` đúng dòng.

## 6. Lệnh kiểm tra (từ repo root, Git Bash)

```bash
export JAVA_HOME=/c/Users/<user>/.local/toolchains/jdk-21.0.12.1+1 PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B test        # durability 36, security 38, user 65, catalog 12, gateway 27 (cần Docker)
bash scripts/validate-contracts.sh      # venv: tests/contracts/requirements.txt
bash scripts/scan-secrets.sh
bash scripts/local-up.sh && bash scripts/smoke-local.sh && npx playwright test
bash scripts/smoke-auth-redis-timeout.sh   # chỉ stack local không có người dùng
bash scripts/nacos-compat-check.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py && git diff --check
```

## 7. Lưu ý môi trường

- JDK 21.0.12.1+1 và Node 24.21.0 ở `C:\Users\<user>\.local\toolchains\`; Docker Desktop phải chạy.
- **Mutation test:** chạy `./mvnw` trong bash và kiểm log có `Tests run: 1,`, không có
  `COMPILATION ERROR`. Không gọi `mvnw.cmd` qua `cmd /c` từ Python (không chạy được, từng làm sai evidence).
- **Race test:** kết nối superuser giữ `FOR SHARE` trên dòng user, chờ `pg_locks` rồi commit. Thứ tự
  khóa chuẩn: dòng user trước token (login `FOR NO KEY UPDATE`, refresh `FOR SHARE`, admin `FOR UPDATE`).
- Sau khi tạo lại container user-service, smoke lần đầu có thể gặp 504 ở Gateway; chạy lại là PASS.
- Heredoc chứa dấu nháy đơn có thể làm Bash tool lỗi parse: ghi script ra file rồi chạy.
- OpenCodeReview `ocr` 1.12.12 đã cài global (Node toolchain), telemetry tắt, chưa cấu hình LLM;
  dùng `ocr delegate preview/rule` để chọn file + rule khi review. Muốn chế độ LLM, chủ dự án tự chạy
  `ocr config provider` (không đưa key qua chat).
- Evidence chi tiết: [Phase 1A 2026-10-05](../../evidence/phase-1a-local-2026-10-05.md),
  [USR-01b-ii](../../evidence/usr-01b-ii-local-2026-10-06.md).
