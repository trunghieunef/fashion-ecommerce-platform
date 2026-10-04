# Handoff Phase 0 — cuối ngày 2026-10-04

Thay cho [handoff PLT-02](2026-10-04-plt-02-handoff.md) (bản đó còn giữ làm lịch sử).
Tác giả: Claude Code (agent). Handoff này không thay trạng thái task trong
[backlog](../../delivery/10_backlog.md) hay acceptance của gate.

## 1. Trạng thái repo

- `main` = `0e96ee0` (đã merge PR #3 PLT-02, PR #4 PLT-03, PR #5 SEC-01).
- `dev` đi trước `main` 3 commit, **chưa push**, chưa có PR:

| Commit | Nội dung |
|---|---|
| `58e5cf6` | docs(SEC-01): bảng retention O06 mà PO đã duyệt (nội dung của chủ dự án) |
| `367d9ee` | fix(SEC-01): actuator Gateway chuyển sang management port 9080 không publish |
| `661d171` | build(PLT-04): chuẩn bị hạ tầng AWS staging offline, chưa provision |

- Working tree sạch. Chưa chạy lệnh AWS nào, chưa có tài nguyên cloud.

## 2. Trạng thái task

| Task | Trạng thái | Đã có | Còn mở |
|---|---|---|---|
| PLT-01 | In progress | Phần S1-local đã nghiệm thu | Xác nhận SEO cho SPA (FE/PO), O02 ngoài S1 |
| PLT-02 | In progress | Contract core đã review và merge (event schema, registry, `openapi/common.yaml`) | Mock API, Mermaid renderer (17 hoãn tới khi có nhu cầu) |
| PLT-03 | In progress | `services/platform-durability`: outbox/inbox/idempotency/background task/lease, 36 test PostgreSQL + Kafka thật | Chưa service nào dùng; chưa có backoff relay, retention cleanup |
| SEC-01 | In progress | `services/platform-security` (ADR-19), CSRF ở Gateway (ADR-20), gitleaks trong CI, data inventory + permission codes ở 13, actuator tách port | JWT người dùng/`auth_version` (USR-01/02), service đầu tiên nối service token |
| PLT-04 | In progress — chuẩn bị offline | Xem [16 §5.1](../../engineering/16_aws_deployment.md) | Toàn bộ phần cần O01, mục 3 |
| PLT-05 | Planned | — | Phụ thuộc PLT-04; catalog đang tự sinh `trace_id` ngẫu nhiên |
| O06 | Mở | PO đã duyệt bảng retention ngày 2026-10-04 (13 §3) | Ngày hạn hoàn tất, giá trị cụ thể trong các khoảng, nhóm dữ liệu còn lại, xác minh pháp lý; chưa có job cleanup |

## 3. Việc cần chủ dự án quyết định hoặc cung cấp

1. **Push `dev` và mở PR** cho 3 commit ở mục 1 để review và chạy CI. Đây là lần đầu bước
   `validate-infra.sh` chạy trên CI (Linux, kubectl của runner).
2. **O01 / PLT-04.A** trước khi tạo bất kỳ tài nguyên AWS nào:
   - region, loại plan, số dư và ngày hết hạn credit;
   - điền giá vào [mẫu dự toán](../../evidence/aws-cost-template.md) từ AWS Pricing Calculator;
   - ngân sách tháng được duyệt và email nhận cảnh báo;
   - domain cho staging (TLS, `GATEWAY_ALLOWED_ORIGINS`);
   - thời hạn backup cụ thể trong khoảng 30–90 ngày (`BackupRetentionDays`).
3. **O06:** ngày hạn hoàn tất và giá trị cụ thể cho từng khoảng retention, trước khi viết job cleanup.
4. **Lưu ý cho CART-01:** retention "giỏ hàng bỏ quên 30–90 ngày" áp dụng cả giỏ member.
   Xóa audit sau 12 tháng cần role riêng vì `audit_logs` là append-only (05).

## 4. Bước tiếp theo đề xuất

Khi O01 được duyệt (thứ tự theo plan Phase 0, Task 4–5):

1. Chọn AMI Ubuntu 24.04 cho region và lấy SHA-256 của binary K3s `v1.35.8+k3s1`.
2. `aws cloudformation validate-template` cho 2 template, tạo change set, đưa chủ dự án review.
   **Chỉ thực thi change set khi có phê duyệt riêng.**
3. Tạo GitHub environment `staging-publish` (chỉ branch `main`, có reviewer); đặt biến
   `AWS_PUBLISH_ROLE_ARN`, `AWS_REGION`, `AWS_PUBLISH_ENABLED=true`.
4. Chọn cơ chế refresh credential ECR cho K3s và test sau khi token hết hạn.
5. PR cập nhật registry/digest thật vào overlay; `bash scripts/render-staging.sh --strict` phải pass.
6. Tạo Secret DB và TLS ngoài Git, cài Argo CD 3.5.3 pin digest, áp project/app.
7. Đổi storefront sang image nginx không chạy root.

Nếu O01 chưa có, việc local không bị chặn:
- PLT-05 phần local: JSON log, correlation ID xuyên HTTP/Kafka, test redaction PII, RED metrics.
- Hoặc Phase 1A (USR-01) nếu chủ dự án chấp nhận lệch thứ tự gate; phải ghi lý do vào 08.

## 5. Lệnh kiểm tra (chạy từ repo root)

```bash
./mvnw -B test                                   # durability 36, security 16, catalog 8, gateway 12
bash scripts/validate-contracts.sh               # venv: tests/contracts/requirements.txt
bash scripts/validate-infra.sh                   # venv: tests/infrastructure/requirements.txt
bash scripts/scan-secrets.sh                     # gitleaks qua Docker
bash scripts/local-up.sh && bash scripts/smoke-local.sh && npx playwright test
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py && git diff --check
```

Kết quả lần chạy cuối (2026-10-04, trên `661d171`): tất cả PASS. Riêng
`scripts/test_maven_wrapper.py` skip khi JDK không có trong PATH.

## 6. Lưu ý môi trường (máy Windows của tác giả)

- Toolchain R1 nằm ở `C:\Users\<user>\.local\toolchains\` (Temurin 21.0.12.1+1, Node 24.21.0);
  PATH mặc định trỏ Node 24.14.1 và không có Java, nên phải tự thêm cho từng process.
- Trong Git Bash, đường dẫn venv trong PATH phải ở dạng `/c/...` (`cygpath -u`).
  Python hệ thống có sẵn `jsonschema`, nên chỉ kết quả chạy trong venv đã pin mới là bằng chứng.
- `subprocess` của Python trên Windows gọi `bash` sẽ ra WSL bash; dùng đường dẫn tuyệt đối từ `shutil.which`.
- Docker Desktop Engine 29.2.0 (lệch R1, đã chấp nhận cho local). Nếu `nacos-compat-check.sh` báo
  `network ... not found`, chạy lại; lần trước lỗi này tự hết.
- `.gitleaksignore` chỉ bỏ qua token Nacos synthetic trong `.env.example`; bản `.env` local đã gitignore.
