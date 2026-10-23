# Handoff sang máy mới — 2026-10-22

Runbook dựng môi trường trên máy mới và tiếp tục dự án từ đúng chỗ đang dừng. Handoff này **thay**
[handoff máy mới 2026-10-02](2026-10-02-s1-local-machine-handoff.md) (lịch sử, chỉ còn giá trị tham khảo
các lỗi môi trường đã gặp). Trạng thái task vẫn theo [10 Backlog](../../delivery/10_backlog.md); handoff không thay
nghiệm thu.

## 1. Trạng thái khi bàn giao

| Mục | Giá trị |
|---|---|
| `origin/main` | `5f76885` — merge PR #36 (NOT-01). Application CI + Documentation CI PASS ([run 39728178328](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39728178328)) |
| `origin/dev` | `a6146a0` — `main` + 1 commit docs (ghi merge PR #36). Documentation CI PASS |
| PR đang mở | Không có |
| Nhánh làm việc | `dev` → PR sang `main`; **chủ dự án merge**, agent không merge |
| Worktree | Một worktree duy nhất (repo root), không có stash |

Task **Done (local, trước G0)** — chi tiết và ngoại lệ ở 10 và các file `docs/evidence/acceptance-*.md`:
INV-01/02/03, CAT-02 (cùng CAT-01/03 nghiệm thu 2026-10-11), CART-01/02/03, WEB-01 (bản thô), SHP-01, SHP-02, PAY-01,
ORD-01, ORD-02, ORD-03, ORD-04, NOT-01. Backend luồng G1 COD đã có đủ: guest/member → quote → đơn → saga giữ hàng/payment
COD → OPS xác nhận → giao SELF → DELIVERED → FINANCE settlement, cùng hủy/hết hạn/hoàn hàng và email giao dịch.

Phase 0 parent (PLT-01/02/03/05, SEC-01, USR-01/02) vẫn ghi **In progress** ở 10 vì phần staging/O02 hoãn tới sau G1 —
không phải việc dở trên code local. Không suy ra G0/G1/G2 hay MVP đã xong.

## 2. Những gì KHÔNG đi theo Git (phải tự xử lý trên máy mới)

| Thứ | Trên máy cũ | Trên máy mới |
|---|---|---|
| `infra/local/.env` (mật khẩu DB, khóa JWT, service key, khóa checkout token, secret quote…) | Sinh bởi `local-up`, gitignored | **Không copy.** `bash scripts/local-up.sh` tự tạo từ `.env.example` và sinh lại toàn bộ khóa. Volume Docker cũng mới nên không lệch mật khẩu |
| Volume Docker (dữ liệu PostgreSQL/Kafka/RustFS local) | Dữ liệu smoke tích lũy | Bắt đầu rỗng; không cần mang |
| `.gitattributes` (untracked) | `*.sh text eol=lf` — giữ LF cho script chạy trong container khi `core.autocrlf=true` | Xem §3.3. 10 file `.sh` hiện báo `M` trên máy cũ **không có khác biệt nội dung** (diff rỗng, chỉ stat cũ); không có gì cần mang |
| Memory/quy ước làm việc của Claude Code | Ở `~/.claude/projects/...` | Không có trên máy mới → quy ước ghi ở §6 |
| Wheels offline cho contract test | Thư mục scratchpad của phiên Claude | Không cần: máy có mạng cài bằng `pip` (§3.2) |
| Trang tiến độ (artifact claude.ai) | `https://claude.ai/artifact/8h8zQT2k26Czbbz2Z4YewV`, bản cuối do phiên này cập nhật là v14 (sau ORD-01) | Có thể đã cũ; nguồn đúng là 10 + evidence |
| `.superpowers/sdd/2026-10-20-ord-02-checkout-saga/` | Ledger cũ còn sót (ORD-02 đã Done, gitignored) | Không mang; bỏ qua |
| Nhánh local `main` | Chậm 200 commit so với `origin/main` | Máy mới clone sẽ đúng; luôn so với `origin/main` |

## 3. Chuẩn bị máy mới

### 3.1 Công cụ (đúng `.tool-versions`, không dùng `latest`)

- Git, Bash (Windows: **Git Bash**), curl, `gh` hoặc Git Credential Manager có quyền push repo
  `trunghieunef/fashion-ecommerce-platform`.
- **Temurin 21.0.12.1+1**, Maven **3.9.16** qua `./mvnw` (không cần cài Maven), **Node 24.21.0**, **npm 11.19.0**.
- **Docker** Linux containers + Compose (R1 chọn Docker 29.8.1 / Compose 5.5.1; ghi version thực vào evidence).
- **Python 3.10+** gọi được bằng `python3` trong Bash (Windows: không dùng shim Microsoft Store).
- RAM: stack local có ~13 container (PostgreSQL, Redis, Kafka, RustFS, Tempo, Grafana, Mailpit, Gateway, storefront và
  các service). Máy cũ ít RAM nên phải tắt stack khi chạy verify và test theo từng module; máy mới nên có ≥ 16 GB.

### 3.2 Môi trường Python cho contract/infra

```bash
python3 -m venv ~/.venvs/fashion && source ~/.venvs/fashion/bin/activate   # Windows Git Bash: .../Scripts/activate
python3 -m pip install -r tests/contracts/requirements.txt -r tests/infrastructure/requirements.txt
```

Đặt venv **ngoài repo** (`.venv` không có trong `.gitignore`).

### 3.3 Lưu ý Windows (đã gặp trên máy cũ)

- **CRLF:** với `core.autocrlf=true`, script `.sh` bị đổi sang CRLF và hỏng shebang trong container. Chọn một trong hai
  và ghi vào evidence máy mới:
  - `git config --global core.autocrlf input` trước khi clone (khuyên dùng), hoặc
  - tạo lại `.gitattributes` với dòng `*.sh text eol=lf` (đang untracked; chủ dự án quyết định có commit hay không).
  Sau khi clone, `git ls-files --eol scripts/local-up.sh` phải là `i/lf w/lf`.
- **Múi giờ JVM:** Windows VN cho `Asia/Saigon`, `postgres:17.11` từ chối. Surefire các service đã đặt
  `-Duser.timezone=UTC`; khi chạy JVM trên host nối DB, đặt `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`.
- **Testcontainers/Ryuk:** lỗi `Could not connect to Ryuk` là lỗi môi trường (12 §1), không sửa firewall.
- Heredoc dài trong Bash tool đôi khi lỗi quote — agent nên ghi script ra file rồi chạy.

## 4. Runbook — clone tới smoke xanh

Chạy từ repo root. Ghi lệnh, version và kết quả vào evidence mới nếu dùng làm bằng chứng.

```bash
# 1. Clone và nhánh làm việc
git clone https://github.com/trunghieunef/fashion-ecommerce-platform.git
cd fashion-ecommerce-platform
git switch dev
git log --oneline -1                       # a6146a0 hoặc mới hơn
git fetch origin && git rev-list --left-right --count dev...origin/main
git ls-files --eol scripts/local-up.sh     # i/lf w/lf

# 2. Toolchain và lockfile
bash scripts/verify-toolchain.sh

# 3. Docs (không cần Docker)
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
git diff --check

# 4. Backend — từng module (Testcontainers cần Docker; stack compose nên tắt khi chạy)
./mvnw -B -q install -DskipTests
./mvnw -B -pl services/order-service verify     # lặp cho từng service khi máy yếu
./mvnw -B verify                                # toàn reactor nếu đủ RAM (CI: ~850+ test)

# 5. Frontend
npm ci && npm run typecheck && npm test && npm run build

# 6. Contract (trong venv §3.2)
bash scripts/validate-contracts.sh

# 7. Stack local + smoke
bash scripts/local-up.sh                         # sinh infra/local/.env, khóa, DB/role/topic idempotent
bash scripts/smoke-local.sh
# browser smoke (tùy chọn): npx playwright install chromium && xem lệnh ở 12 §1

# 8. Dừng (giữ dữ liệu); thêm -v chỉ khi chủ động xóa dữ liệu local
docker compose --env-file infra/local/.env -f infra/local/compose.yaml --profile nacos-compat down
```

Smoke đạt khi in đủ các dòng sau (thứ tự như script) và kết thúc bằng `S1-local smoke PASS`:
`PLT-05 Kafka trace PASS`, `CAT-03 upload/complete/attach PASS`, `CAT-02a browse/detail/search PASS`,
`CART-01 guest/member cart PASS`, `SHP-01 shipping estimate PASS`, `ORD-01 guest quote/create/read PASS`,
`ORD-02 saga confirm PASS`, `SHP-02 self-event route PASS`, `PAY-01 payment admin route PASS`,
`ORD-03 COD end-to-end PASS`, `ORD-04 cancel after confirm PASS`, `ORD-04 return PASS`, `ORD-04 customer cancel PASS`,
`CAT-02b cache invalidation PASS`, `CAT-03 publish/public read/304/unpublish PASS`, ba dòng `CAT-01b ...`,
`NOT-01 order emails PASS`, `NOT-01 reset email PASS`.

Cổng local (chỉ `127.0.0.1`): storefront 4173, Gateway 8080, PostgreSQL 5432, Grafana 3000, Tempo 3200, Mailpit 8025
(xem email giao dịch), RustFS 19000. Bảng lệnh đầy đủ và giới hạn: [12 §1 "Lệnh local thực tế"](../../engineering/12_engineering_guide.md).

**Checklist máy mới sẵn sàng:** `verify-toolchain` PASS · docs check PASS · ít nhất `order-service` và `gateway` verify
PASS · contract PASS · `local-up` + smoke đủ dòng trên · `git status` sạch (ngoài `.gitattributes` nếu
chọn giữ local).

## 5. Việc tiếp theo

Đường găng tới **G1 COD** (09): backend đã đủ; còn **UI cơ bản** và harness.

1. Đồng bộ `dev` với `origin/main` trước task mới: `git merge --no-edit origin/main` (dev có 1 commit docs riêng nên
   không fast-forward được; merge là bình thường), rồi push `dev`.
2. Ứng viên theo thứ tự đề xuất (chủ dự án chọn):
   - **WEB-02** — login/profile/địa chỉ/giỏ guest/merge, access token in-memory, refresh single-flight (deps WEB-01,
     USR-01, CART-02 đã có).
   - **WEB-03** — checkout: quote, `PRICE_CHANGED`, submit giữ key, poll 202, lịch sử đơn; guest dùng checkout token
     (14 §3 đã mô tả luồng).
   - **ADM-01** — admin catalog/media/stock/users theo quyền.
   - **QA-01** — harness DB thật/provider stub cho T01–T05/T09.
   - Online (PAY-02 VNPay/PAY-03 MoMo) và refund (PAY-04) thuộc G2.
3. Quyết định còn chờ chủ dự án (ghi ở 08/evidence):
   - SHP-02: bằng chứng bàn giao/giao trên shipment đã hủy có chuyển MANUAL + incident và báo order không.
   - O04: phí ship/COD thật và vùng SELF (bảng phí hiện là mẫu) — trước demo G1.
   - O07: provider/domain email thật; tên shop/hotline trong email đang là mẫu.
   - O06: PII trên `notification.events`/DLT và retention topic trước staging; job dọn (PII đơn 12 tháng, credential guest
     90 ngày, notification 90 ngày) chưa làm.
   - O01 (AWS) / O02 (version runtime) — G0 đã hoãn tới sau G1.

## 6. Quy ước làm việc với chủ dự án (thay cho memory của máy cũ)

- Trao đổi **tiếng Việt**, giữ tên kỹ thuật; tuân thủ `AGENTS.md`, `CLAUDE.md`.
- Mỗi task: đồng bộ `dev` với `origin/main` → **agent tự chọn mặc định, viết spec, chờ duyệt** → viết plan, chờ duyệt →
  thực hiện inline theo plan (TDD: thấy test fail trước khi code; ledger ở `.superpowers/sdd/<plan>/progress.md`) →
  review toàn nhánh bằng subagent, sửa mọi Critical/Important (mỗi lỗi có test RED→GREEN) → cập nhật docs/evidence →
  push `dev`, mở PR sang `main`. **Không bao giờ tự merge.**
- Khi chủ dự án báo "có review trên PR": đọc comment PR, kiểm từng điểm trong code, sửa điểm đúng (cả Minor nếu được
  yêu cầu), cập nhật evidence + mô tả PR, push.
- "CI xong, nghiệm thu": kiểm CI xanh trên SHA cuối, ghi mục mới trong `docs/evidence/acceptance-<ngày>.md` (bảng tiêu chí,
  ngoại lệ, ghi rõ *không độc lập*), đổi trạng thái ở 10/evidence/README, cập nhật trang tiến độ nếu còn dùng.
- "Đã merge PR": đồng bộ `dev`, ghi commit merge vào acceptance/evidence/10, push, rồi sang task kế.
- Không commit/push/gọi hệ thống ngoài khi chưa được yêu cầu; không reset/force-push; không đụng thay đổi local của chủ dự án.
- Máy yếu: tắt stack compose khi full verify, chạy test theo module (máy mới mạnh hơn thì có thể bỏ ràng buộc này — hỏi
  chủ dự án).
- Lấy token GitHub cho API bằng `git credential fill` (hoặc `gh auth token`), không in token ra log.

## 7. Đọc trước khi bắt đầu task mới

`AGENTS.md` → [root README](../../../README.md) → [docs index](../../README.md) → [10 Backlog](../../delivery/10_backlog.md)
(các dòng Update đầu file) → [08 Decisions](../../design/08_decisions.md) → acceptance mới nhất
(`docs/evidence/acceptance-2026-10-22.md`) → [plans README](README.md) và spec/plan của task trước liên quan
(ví dụ ORD-01…04 khi làm WEB-03).
