# Handoff CAT-01b — 2026-10-07

`TASK:CAT-01` phần 1b · REQ CAT-03/06 · dependency CAT-01a/PLT-02/03/USR-02.
Thay [handoff CAT-01a](2026-10-07-cat-01a-handoff.md) về tiến độ hiện tại;
không thay trạng thái parent/gate trong [backlog](../../delivery/10_backlog.md).

## Tiến độ và branch

Trên `dev`, Native tuần tự theo [plan đã duyệt](2026-10-07-cat-01b-catalog-admin.md)
và [spec](../specs/2026-10-07-cat-01b-design.md). Task1–6 đã commit/test/mutation,
Task1–7 đã thực hiện; full verification/evidence PASS, whole-branch review0Critical/0Important,
1Minor stale docs đã đồng bộ; verdict Ready to merge, không thay nghiệm thu của chủ dự án.
Chỉ local: **không push/PR/merge**.
Base để review/PR sau này là `origin/main`/`8f27663` (PR14 đã merge), không dùng local main cũ.
Chỉ push dev/mở PR main khi chủ dự án yêu cầu riêng. Parent CAT-01 **In progress**.

Commits: docs checkpoint6ccbbd3; docs-approved7cd95d7; schema d317c03;
collection read7ab1f98; collection write52de350; guide read18e049a;
guide PUT93cb85b; smoke9a2395a; Task7 evidence a0def6e (review range8f27663..a0def6e).
Commit docs follow-up ghi kết quả review và sửa trạng thái stale; xem git log dev.
Chi tiết commands/results/mutation và docs tại
[evidence CAT-01b](../../evidence/cat-01b-local-2026-10-07.md).

## Hành vi và file chính

V004 thêm collections/collection_items/size_guides, upgrade V003 và giữ legacy data/append-only audit.
4 Java file CollectionAdminService/Controller và SizeGuideAdminService/Controller tại admin;
JdbcClient/AdminAuth/Api/AdminCommands/AuditLog/HtmlSanitizer/durability có sẵn, không dependency mới/JPA.
AdminCommands chỉ tách requireKey giữ contract CAT-01a.
5 integration classes mới và support cleanup FK, scripts/smoke-local.sh mở rộng flow Gateway.

Collections GET list/detail mọi status; detail một SQL json_agg `(sort_order, product_id)`.
POST key→201/DRAFT/version0; PUT không key/full replacement/expected_version→200/version+1.
Missing product trong items400 field index gốc, path missing404; activation không cần product ACTIVE.
Dates offset→UTC; cover_url null, cover/lookbook không nhận. Closed request any-setter kể cả unknown-null;
version/sort JsonNode null như thiếu400. Row lock/version/audit/items transaction atomic.

Guide GET/PUT category/locale vi/en. PUT key bắt buộc +version0 tạo201/1;
current version cập nhật200/+1, lookup idempotency trước version nhưng category404 trước replay.
Create race ON CONFLICT DO NOTHING RETURNING: một201/còn409, không catch unique violation.
Table columns/rows đóng, strip/plain text, Locale.ROOT header unique, compact UTF-8≤32768 bytes;
guideline optional/sanitize/max20000 sau sanitize. Audit/key/data cùng transaction.
Replay cached data/status, metadata mới/no audit. Mọi route kiểm catalog.write tại service.

## Lệnh và môi trường

Repo root, **Git Bash**; Docker Desktop phải chạy. JDK21.0.12.1+1, Node24.21.0,
Maven wrapper3.9.16; PostgreSQL17.11/Kafka thật trong tests, Redis8.2.9 local.

```bash
export JAVA_HOME=/c/Users/<user>/.local/toolchains/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:/c/Users/<user>/.local/toolchains/node-v24.21.0-win-x64:/d/Git/bin:$PATH"
./mvnw -B test
./mvnw -B -pl services/catalog-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SizeGuideAdminIntegrationTest test
bash scripts/validate-contracts.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
bash scripts/scan-secrets.sh
git diff --check
```

Runtime đã upgrade volume V003→V004, không down -v: local-up PASS, 6 healthy;
smoke PASS, Playwright desktop/mobile2/2. Lệnh không đổi:

```bash
bash scripts/local-up.sh
bash scripts/smoke-local.sh
npx playwright test
```

13 Java mutations bị bắt với Tests run1/failure1/error0/compilationOK và module restore GREEN riêng;
mutation JWT permission HTTP403 rồi restore smoke GREEN. Các module catalog sau từng task:
77→83→96→99→114; durability36/security38 mỗi module run0skip. Full reactor281 PASS:
durability36/security38/user65/catalog114/Gateway28,0fail/error/skip; contract32/scripts14,
docs48/gitleaks history70commits và staged/diff PASS. Xem evidence để biết scope từng lần scan.
Maven chỉ ./mvnw trong Git Bash; không mvnw.cmd qua cmd /c cho mutation.

## Giới hạn và bước tiếp

Smoke dùng OPS JWT synthetic ES256/catalog.write/300s, key/token chỉ memory từ .env;
không chứng minh login→token OPS, giữ401 không token/403 member thật. UUID slug/SKU/category
để dữ liệu tích lũy trên volume không DELETE. Helper mới gửi/đọc JSON UTF-8 rõ ràng vì
Windows curl argv ANSI/Python CP1252 làm sai ô Unicode; assertion `96–100` kiểm việc này.
Runbook local đã ở12 §1; Task6/7 không tạo infra README hoặc sửa README gốc.
README gốc giữ link CAT-01a từ checkpoint trước Task1; docs index/plans index dẫn handoff CAT-01b mới.

Đọc verdict whole-branch review và evidence trước khi yêu cầu push/PR. Chưa chạy remote CI,
staging, AWS/provider/Kafka relay hoặc nghiệm thu parent. Public read CAT-02 chỉ collection
ACTIVE/trong[start,end)/có ACTIVE product và chỉ trả ACTIVE items; CAT-03 quản lý media/upload/publish gate.
Legacy ACTIVE từ V001 vẫn có thể published_at null; CAT-02 quyết định sort/backfill, không sửa ở đây.
Hai Minor PR14 deadlock409/numeric coercion CAT-01a còn để riêng; không refactor interceptor/tags.
Không tự quyết business/contract mới nếu plan/spec/code mâu thuẫn; hỏi chủ dự án và tiếp tục phần độc lập.
