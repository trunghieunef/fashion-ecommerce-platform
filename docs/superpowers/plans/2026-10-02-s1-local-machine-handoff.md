# Handoff S1-local sang máy mới — 2026-10-02

## Mục tiêu cho agent tiếp theo

Tiếp tục nghiệm thu **S1-local** từ branch `dev`: clone sạch chạy storefront shell →
Gateway → catalog sample → PostgreSQL, kiểm chứng Nacos compatibility/reconnect,
lưu evidence và bàn giao reviewer/TL. Không bắt đầu G0 staging hoặc business MVP
G1/G2 trong task này.

`TASK:PLT-01` · core `TASK:PLT-02` · nền `TASK:SEC-01`.
REQ: XCT-06, XCT-01, XCT-05, USR-07, XCT-03; dependency/acceptance theo 09/10.
Các package vẫn **In progress**, S1-local và O02 chưa nghiệm thu.

## Trạng thái mang sang máy mới

- Repo đã có Maven Wrapper/reactor, catalog read-only + PostgreSQL/Flyway `V001`,
  Gateway boundary, React/Vite shell, Dockerfile/nginx, Compose, OpenAPI catalog,
  backend/frontend/browser tests và các script local/contract/Nacos proof.
- Bản sửa trong commit chứa handoff này: `mvnw.cmd` chuẩn hóa project path để Java
  nhận đúng arguments trên Windows, kể cả path có khoảng trắng. Regression thực thi
  launcher bằng Java fixture nằm ở `scripts/test_maven_wrapper.py`.
- Máy công ty chạy regression RED → GREEN, bộ Python tests PASS 12/12;
  docs/typecheck/OpenAPI/Compose config PASS. [Evidence chi tiết](../../evidence/s1-local-2026-10-02.md)
  phân biệt kiểm tra clone sạch baseline và patch.
- [Application CI](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/36662487345)
  và [Documentation CI](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/36662487374)
  đã PASS trên baseline `d16151d` ngày 2026-09-30. Chúng không chứng minh commit handoff
  mới; phải đối chiếu run với SHA đang nghiệm thu.
- Gateway mạng công ty chặn tải Node binary, Maven distribution và native npm
  dependencies Windows bằng HTTP 403. Chủ dự án yêu cầu bỏ qua trên máy đó và đổi
  môi trường. Đây là blocker môi trường, không phải quyền bỏ acceptance trên máy mới.
- Full-chain fresh-clone runtime, Nacos reconnect và reviewer/TL acceptance còn thiếu.
  Không làm lại catalog/Gateway/storefront hoặc scaffold thêm service chỉ để nghiệm thu.

## Quyền thao tác

Chủ dự án đã yêu cầu commit và push bản bàn giao này lên `origin/dev` để pull sang
máy mới. Quyền đó áp dụng bản bàn giao này; không suy thành quyền merge/deploy, provision
AWS, gọi provider thật hoặc commit/push các thay đổi tiếp theo khi chưa được yêu cầu.
Tuân thủ `AGENTS.md`, giữ nguyên thay đổi của người dùng và không reset/force-push.

## Đọc trước khi chạy

1. `AGENTS.md`, [root README](../../../README.md), [docs index](../../README.md).
2. [09 Delivery](../../delivery/09_delivery_plan.md), [10 Backlog](../../delivery/10_backlog.md),
   [08 Decisions](../../design/08_decisions.md), [17 Tech Stack](../../engineering/17_tech_stack.md).
3. [12 Engineering guide](../../engineering/12_engineering_guide.md),
   [11 Test strategy](../../quality/11_test_strategy.md),
   [evidence hiện tại](../../evidence/s1-local-2026-10-02.md) và
   [template fresh-clone](../../evidence/s1-local-template.md).
4. README catalog/Gateway/storefront và [Sprint 1 plan](2026-09-19-sprint-1-local-foundation.md).
   Nếu sửa API/schema/boundary, đọc thêm 03/05/13 trước khi sửa.

[Handoff 2026-09-22](2026-09-22-s1-local-handoff.md) là lịch sử; các mục Task 5 chưa làm
trong snapshot đó không còn là việc cần tạo mới. Handoff này là hướng dẫn tiếp tục hiện tại.

## Chuẩn bị môi trường

Máy cần Git, Bash, curl, Python 3.10+, Docker Linux containers và Compose; shell chạy
scripts phải gọi được `python3`. Windows dùng Git Bash; shim Microsoft Store không
thay interpreter thật. Ghi OS/CPU/JDK/Node/npm/Docker/context vào evidence.

Toolchain đúng `.tool-versions`: Temurin **21.0.12.1+1**, Maven **3.9.16** qua Wrapper,
Node **24.21.0**, npm **11.19.0**. Container tooling R1 chọn Docker **29.8.1** / Compose
**5.5.1**; ghi phiên bản thực và các khác biệt để TL đánh giá. Image PostgreSQL **17.11**,
Nacos **3.2.4**, JRE và nginx đã pin digest trong Compose/Dockerfile; không dùng `latest`.

Cho phép tải từ upstream toolchain, Maven Central, npm registry, container registry
và Playwright browser distribution. Cài đúng versions và kiểm checksum theo upstream;
không hạ version hoặc tắt TLS/compatibility verifier để làm test pass.
Kafka/Redis/Kubernetes/AWS/provider chưa cần cho checkpoint này.

Artifacts dưới `target/`, `node_modules/`, `.worktrees/` và `.env` trên máy cũ được
ignore; máy mới phải tự build. Không copy credential hoặc dùng JAR/dist máy cũ làm proof.

## Thứ tự thực hiện

### 1. Checkout và nhận diện commit

Để chứng minh fresh clone, dùng thư mục mới:

```bash
git clone --branch dev https://github.com/trunghieunef/fashion-ecommerce-platform.git fashion-s1-review
cd fashion-s1-review
git status --short
git rev-parse HEAD
```

Nếu chỉ nhận code trong checkout đã có, kiểm tra status, rồi `git pull --ff-only origin dev`
khi đang ở `dev`. Checkout cũ không thay fresh-clone evidence. SHA nghiệm thu phải chứa
file handoff này và bản sửa `mvnw.cmd`; ghi SHA thực vào report, không dùng `d16151d`
để đại diện cho patch mới. Cổng local 5432/8080/4173 cần trống.

### 2. Toolchain, unit/integration và contract

Chạy từ repo root trong Bash, ghi kết quả từng lệnh và dừng để chẩn đoán khi lỗi:

```bash
bash scripts/verify-toolchain.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
./mvnw test
npm ci
npm run typecheck
npm test
npm run build
bash scripts/validate-contracts.sh
```

Backend phải dùng PostgreSQL thật qua Testcontainers. Docker daemon/context phải khả
dụng cho JVM; đối chiếu 12 khi lỗi Ryuk/Docker socket. Python suite có regression
Windows: trên Linux test này skip có chủ ý; báo số pass/skip thực, không ghi 12/12
cho Linux. `npm ci` exit 0 vẫn chưa đủ: optional native package có thể bị chặn,
unit/build mới chứng minh runtime frontend.

### 3. Negative smoke rồi chạy đủ stack

```bash
bash scripts/smoke-local.sh
```

Trước start phải fail do stack chưa chạy; xác nhận nguyên nhân, không coi lỗi syntax
hoặc thiếu command là negative proof. Sau đó:

```bash
bash scripts/local-up.sh
bash scripts/smoke-local.sh
npx playwright install chromium
npx playwright test
bash scripts/nacos-compat-check.sh
bash scripts/smoke-local.sh
```

`local-up.sh` tự tạo `.env` từ example synthetic, build JAR/dist rồi start Compose.
Không dùng `docker compose up --build` trần để đóng gói artifact cũ. Nacos script
restart Nacos cho reconnect proof rồi trả stack về profile mặc định; smoke cuối
phải pass. Nếu Playwright thiếu thư viện OS, cài dependency theo hướng dẫn chính thức
của đúng Playwright version và quyền của máy, rồi chạy lại.

### 4. Kiểm tra thủ công và evidence

- Mở `http://localhost:4173/products`, reload; không error state, kiểm desktop/360px.
- Internal path qua Gateway trả 404; catalog/Nacos không publish port; các port
  được publish chỉ bind `127.0.0.1`. Đối chiếu `docker compose ... ps` và config.
- Ghi version, migration, test counts, smoke/browser/Nacos outputs, SHA, ngày và
  người chạy vào bản evidence mới theo template. Không ghi `.env`, password, token
  hoặc log chưa được redaction. Giữ evidence máy cũ làm lịch sử.
- Kiểm tra CI mới trên đúng SHA nếu có; không tự rerun/trigger workflow hoặc push
  khi chưa được yêu cầu. Run baseline chỉ là bằng chứng cho baseline.
- Khi sửa lỗi phát hiện trong acceptance: sửa đúng nguyên nhân, thêm regression phù
  hợp, đồng bộ docs và chạy lại checks liên quan; không refactor ngoài S1.

### 5. Bàn giao

Chạy lại docs checks sau cập nhật evidence, `git diff --check` và `git status --short`.
Nếu cần dừng stack, lệnh dưới giữ volume:

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml --profile nacos-compat down
```

Không thêm `-v` hoặc purge volume để che lỗi. S1-local chỉ được nghiệm thu khi đủ
evidence runtime và reviewer khác tác giả/TL xác nhận theo 09. Báo rõ phần pass,
fail, skip, blocker; giữ các package/O02 mở nếu acceptance còn thiếu. Gate này không
chứng minh toàn bộ O02, G0/G1/G2 hoặc business MVP.

## Prompt có thể đưa cho agent mới

```text
Đọc AGENTS.md và docs/superpowers/plans/2026-10-02-s1-local-machine-handoff.md.
Tiếp tục nghiệm thu S1-local trên môi trường mới, chỉ trong phạm vi handoff.
Kiểm tra git status/SHA và toolchain thực tế, chạy đầy đủ checklist trên clone sạch,
sửa lỗi S1 nếu có, cập nhật evidence/docs và báo kết quả thật.
Không tự đóng gate thiếu reviewer/TL hoặc mở rộng sang AWS/G0/G1/G2.
Không commit/push thêm khi chưa được yêu cầu.
```
