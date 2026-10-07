# Handoff CAT-01a — 2026-10-07

Thay [handoff đầu phiên](2026-10-07-session-handoff.md) cho phiên tiếp theo; file cũ là lịch sử.
Đọc AGENTS.md → handoff này → [spec](../specs/2026-10-07-cat-01a-design.md) và
[plan](2026-10-07-cat-01a-catalog-admin.md). Evidence tại
[CAT-01a local](../../evidence/cat-01a-local-2026-10-07.md). Tác giả: Codex.

## 1. Trạng thái

Trên nhánh `dev`, implementation Tasks 1–6 tới `8eb467d`, Task 7 `c24a96f` đồng bộ docs/evidence.
Giai đoạn đầu chỉ commit local. Sau yêu cầu chủ dự án đã push dev/mở
[PR #14](https://github.com/trunghieunef/fashion-ecommerce-platform/pull/14) vào main;
chủ dự án đã merge ngày 2026-10-07 lúc 12:33:51 +07:00, merge commit `8f27663`.
Phiên tiếp theo đã fetch và fast-forward `dev` local lên `origin/main` (`8f27663`);
`origin/dev` còn `11d4cce`, chưa push phần việc mới. Local `main` giữ nguyên ở `3132dc7`.
Spec/plan được chủ dự án giao thực thi;
nghiệm thu CAT-01 còn mở. Review độc lập Superpowers có 2 Important (error envelope và audit
request_id); chủ dự án đã duyệt sửa plan Task 2/4 cùng code, RED 2 FAIL → GREEN 2 PASS.
Full suite sau fix PASS 230/230, gồm catalog 63/63; fallback vẫn log exception server.
Minor expected_version âm trả 409 thay vì 400 được giữ để chủ dự án chốt plan; xem evidence.
Review PR14 có Important catch-all đổi lỗi HTTP Spring thành 500: chủ dự án duyệt sửa,
RED 2 FAIL → GREEN 2 regression + rollback audit 500 PASS. ErrorResponse 4xx đến admin advice
giữ status/header, envelope VALIDATION_ERROR/INVALID_HTTP_REQUEST JSON và không log ERROR;
fallback 500 còn lại vẫn log exception. Các Minor mới để riêng theo yêu cầu chủ dự án.
Full Maven fix handler `1ec1142` PASS 232/232 (catalog 65); CI Application/Documentation PASS.
Follow-up SKU được chủ dự án duyệt: SKU mới strip/kiểm ASCII rồi uppercase Locale.ROOT,
SKU cũ bất biến, không backfill. V003 thêm INSERT guard và unique không phân biệt casing;
key/hash/response/audit/outbox legacy giữ nguyên, replay data/status cũ. Catalog RED 8 FAIL
→ module 71 PASS; contract RED 1 FAIL → 28 PASS; 3 mutation thật FAIL rồi khôi phục.
Full Maven follow-up PASS 238/238, catalog 71; smoke/runtime upgrade xem evidence.
Compose rebuild/V003 upgrade trên volume hiện có, 6 healthy; smoke Gateway và Playwright
2/2 PASS trên image mới. Fingerprint 2 SKU legacy, audit/outbox/key/hash/response liên quan
giữ nguyên; 1 SKU uppercase mới, 0 collision. Không reset volume hoặc backfill.
Application CI và Documentation CI trên đúng HEAD SKU `11d4cce` đều PASS trước merge;
link run ở evidence. Merge phần 1a không thay nghiệm thu parent CAT-01.
Phase 1A tiếp tục trên local trước G0 theo quyết định chủ dự án; O01 còn mở, G1/G2 chưa nghiệm thu.

## 2. Đã có

V002/V003 append-only; category/brand/product/variant admin với ES256 catalog.write, JdbcClient,
version/idempotency/audit. Variant bất biến SKU/size/color/product, tạo cùng VARIANT_CREATED
outbox transaction. Sanitize jsoup 1.23.2; publish/unpublish kiểm taxonomy/variant ACTIVE.
Gateway admin route, Compose public keys, OpenAPI 14 operation và smoke đầy đủ.
Docker stack 6 service healthy trên volume hiện có; kết quả từng lệnh và mutation ở evidence.

Commits Tasks 1–6: 707db51, c59d3bc, 7a21e96, 13b9dc4, 19e8c33, 8eb467d;
Task 7 `c24a96f`; review fix xem `git log dev`. Commit `9cab06f` do phía người dùng sửa plan null-safe và chứa
helper insert sample Task 1; đã giữ nguyên.

## 3. Quyết định giữ nguyên

Publish mới đặt coalesce(published_at, now()); unpublish không sửa timestamp. ACTIVE từ V001
có thể còn null; CAT-02 chốt backfill/xử lý null khi sort/index. Reason tùy chọn, canonical record
nhận null; thiếu version 400. Không DELETE, không sửa V001, không JPA; dependency mới chỉ jsoup.
Audit dùng request_id của response mutation đầu; retry metadata mới, không thêm audit.
SKU mới canonical uppercase ASCII, unique không phân biệt casing; SKU legacy không đổi.
V003 chỉ guard INSERT để mutable UPDATE legacy vẫn chạy; collision dừng migration,
cần quyết định dữ liệu riêng. Response/event schema giữ casing của identity cũ.
Fallback 500 chỉ scope admin, body INTERNAL/INTERNAL_ERROR/metadata + X-Correlation-Id;
exception vẫn log server qua logger redact PII hiện có, không lộ exception/SQL cho client.

Smoke JWT synthetic chỉ trong script local, .env key/token trong memory, ADR-21 ES256/kid,
iss user-service/aud fashion-api, random UUID sub, auth_version 0, chỉ catalog.write, TTL 300 giây.
Không sửa user DB; giữ 401/403 member thật. UUID slug/SKU tránh trùng; dữ liệu tích lũy vì không
DELETE. Smoke không chứng minh login → token OPS; không đặt helper vào service/production.

## 4. Việc tiếp theo

1. Tiếp tục CAT-01b collection/items và size guide admin trên dev: [spec đã viết](../specs/2026-10-07-cat-01b-design.md),
   chờ chủ dự án review, gồm các chi tiết đề xuất tại §6. Chưa có plan được duyệt hoặc
   implementation. Sau duyệt spec mới viết plan, sau duyệt plan mới TDD và PR vào main
   để chủ dự án review trước merge.
2. CAT-03 sở hữu ảnh/upload và điều kiện ảnh publish; CAT-01b không tự nhận URL ảnh hoặc
   kéo media pipeline vào khi chưa có thiết kế được duyệt.
3. INV-01/relay/Kafka và CATALOG_CHANGED nằm ngoài 1a; không tự kéo vào bước bàn giao này.
4. USR-01/02 parent còn chờ nghiệm thu, G0/O01 và các mục mở staging giữ nguyên handoff cũ/08.

CAT-01b đã chốt quyết định collection (chủ dự án, 2026-10-07): PUT collection sang ACTIVE
không yêu cầu product ACTIVE. Public list/detail thuộc CAT-02, chỉ ACTIVE + đang trong
[start_at, end_at) + có ≥ 1 product ACTIVE; chỉ trả product ACTIVE. Đã ghi 03/06/08 và
acceptance CAT-02 trong backlog. Size guide cũng đã chốt shape columns/rows và giới hạn:
đúng hai key, 1–20 cột/1–100 hàng, số ô khớp số cột, tiêu đề strip 1–100 không trùng,
ô strip 0–100, serialize ≤ 32 KB, plain text/FE escape; guideline HTML sanitize như product.
Cột đầu khớp variant.size là quy ước không ép. 03/05/08 đã đồng bộ quyết định chủ dự án
2026-10-07. PUT size guide đã chốt: expected_version 0 tạo 201/version 1, cập nhật
200/version +1; thiếu version 400, stale/tạo khi tồn tại 409 VERSION_CONFLICT.
Key bắt buộc, replay trước version guard, giữ data/status và không thêm audit.
Locale vi/en (khác 400), category không tồn tại 404 CATEGORY_NOT_FOUND. Race tạo key khác
nhau dựa UNIQUE(category_id, locale): một 201, còn lại 409; cùng key/body replay.
GET admin detail collection và size guide đã được chủ dự án duyệt: collection/items thứ tự
sort_order/product_id + version, guide nội dung/version (chưa có 404, locale sai 400),
catalog.write tại service, gồm DRAFT/INACTIVE. 03/05/08/catalog.yaml đã đồng bộ contract;
contract tests/examples đã có, HTTP 401/403/404/GET → PUT chưa chạy vì chưa có endpoint.
Collection mutation đã được chủ dự án duyệt: POST key DRAFT/version 0; PUT chỉ version,
thay toàn bộ nội dung/items, tăng version, audit atomic. Tối đa 1.000 items, sort_order
0..2147483647 được trùng; thiếu product body 400 tại items[i].product_id; timestamp có offset
lưu UTC, null bound không giới hạn. Không nhận cover/lookbook, cover_url luôn null.
03/05/06/08 và catalog.yaml đồng bộ, contract tests RED → GREEN; chưa có implementation.
guideline_html cũng đã được duyệt: không bắt buộc, thiếu/null thành rỗng, sanitize như
product, giới hạn 20.000 ký tự sau sanitize. PUT guide contract/examples đã có.
Spec CAT-01b đã viết, chưa được duyệt; chưa có implementation plan. Các chi tiết ở spec
§6 đang đề xuất, không coi là quyết định đã chốt. Không tự triển khai public read trong 1b.

## 5. Lệnh kiểm tra (repo root, Git Bash)

```bash
export JAVA_HOME=/c/Users/<user>/.local/toolchains/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:/c/Users/<user>/.local/toolchains/node-v24.21.0-win-x64:/d/Git/bin:$PATH"
./mvnw -B test
bash scripts/validate-contracts.sh
bash scripts/scan-secrets.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
git diff --check
bash scripts/local-up.sh && bash scripts/smoke-local.sh && npx playwright test
```

Reactor sau SKU follow-up 238/238: durability 36, security 38, user 65, catalog 71, Gateway 28. Contract 28/28.
Script tests 14/14, docs 44 file PASS; smoke/Playwright và upgrade volume xem evidence. Trạng thái review xem evidence;
không suy CI remote từ local PASS.

## 6. Môi trường

Docker Desktop phải chạy. Mutation Maven chỉ chạy ./mvnw trong Git Bash, log có Tests run:
và không COMPILATION ERROR; không dùng mvnw.cmd qua cmd /c. Node là toolchain pinned,
smoke cần --env-file và crypto built-in. CATALOG_JWT_PUBLIC_KEYS chỉ truyền public keys vào
catalog, không truyền private key. Chạy host catalog cần export public key như README service.
Volume hiện có SUPER_ADMIN synthetic, bootstrap không tạo admin mới; smoke không sửa role/DB.
Không dùng bash -x khi chạy smoke vì token/keys phải ở trong memory, không log.
Không down -v hoặc reset migration để vượt lỗi; V001 và dữ liệu cũ phải được giữ.
V002/V003 đã áp vào volume local, giữ append-only. SKU mới uppercase; legacy có chữ thường
không đổi, kể cả response/event đã lưu. Trước upgrade volume khác cần kiểm collision casing;
không tự chỉnh identity hoặc sửa outbox/idempotency để vượt unique index.
