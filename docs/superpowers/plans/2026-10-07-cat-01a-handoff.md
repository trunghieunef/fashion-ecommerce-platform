# Handoff CAT-01a — 2026-10-07

Thay [handoff đầu phiên](2026-10-07-session-handoff.md) cho phiên tiếp theo; file cũ là lịch sử.
Đọc AGENTS.md → handoff này → [spec](../specs/2026-10-07-cat-01a-design.md) và
[plan](2026-10-07-cat-01a-catalog-admin.md). Evidence tại
[CAT-01a local](../../evidence/cat-01a-local-2026-10-07.md). Tác giả: Codex.

## 1. Trạng thái

Trên nhánh `dev`, implementation Tasks 1–6 tới `8eb467d`, Task 7 `c24a96f` đồng bộ docs/evidence.
Giai đoạn đầu chỉ commit local. Sau yêu cầu chủ dự án đã push dev/mở
[PR #14](https://github.com/trunghieunef/fashion-ecommerce-platform/pull/14) vào main; chưa merge.
`origin/main` là `826e361`,
local `main` còn ở `3132dc7`; giữ nguyên các branch ref đó. Spec/plan được chủ dự án giao thực thi;
nghiệm thu CAT-01 còn mở. Review độc lập Superpowers có 2 Important (error envelope và audit
request_id); chủ dự án đã duyệt sửa plan Task 2/4 cùng code, RED 2 FAIL → GREEN 2 PASS.
Full suite sau fix PASS 230/230, gồm catalog 63/63; fallback vẫn log exception server.
Minor expected_version âm trả 409 thay vì 400 được giữ để chủ dự án chốt plan; xem evidence.
Review PR14 có Important catch-all đổi lỗi HTTP Spring thành 500: chủ dự án duyệt sửa,
RED 2 FAIL → GREEN 2 regression + rollback audit 500 PASS. ErrorResponse 4xx đến admin advice
giữ status/header, envelope VALIDATION_ERROR/INVALID_HTTP_REQUEST JSON và không log ERROR;
fallback 500 còn lại vẫn log exception. Các Minor mới để riêng theo yêu cầu chủ dự án.
Full Maven sau fix PR14 PASS 232/232 (catalog 65); lint/contract 27 PASS. Local Compose vẫn
là image baseline f2d0b6a: lần sửa này kiểm regression qua HTTP Testcontainers, chưa rebuild
Compose. Theo dõi CI commit mới trên PR trước nghiệm thu.
Phase 1A tiếp tục trên local trước G0 theo quyết định chủ dự án; O01 còn mở, G1/G2 chưa nghiệm thu.

## 2. Đã có

V002 append-only; category/brand/product/variant admin với ES256 catalog.write, JdbcClient,
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
Fallback 500 chỉ scope admin, body INTERNAL/INTERNAL_ERROR/metadata + X-Correlation-Id;
exception vẫn log server qua logger redact PII hiện có, không lộ exception/SQL cho client.

Smoke JWT synthetic chỉ trong script local, .env key/token trong memory, ADR-21 ES256/kid,
iss user-service/aud fashion-api, random UUID sub, auth_version 0, chỉ catalog.write, TTL 300 giây.
Không sửa user DB; giữ 401/403 member thật. UUID slug/SKU tránh trùng; dữ liệu tích lũy vì không
DELETE. Smoke không chứng minh login → token OPS; không đặt helper vào service/production.

## 4. Việc tiếp theo

1. Chủ dự án review bản cập nhật PR14 và CI; chỉ merge sau review được duyệt.
2. CAT-01b collection/size-guide cần spec/plan riêng; CAT-03 sở hữu ảnh và điều kiện ảnh publish.
3. INV-01/relay/Kafka và CATALOG_CHANGED nằm ngoài 1a; không tự kéo vào bước bàn giao này.
4. USR-01/02 parent còn chờ nghiệm thu, G0/O01 và các mục mở staging giữ nguyên handoff cũ/08.

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

Reactor sau review PR14 232/232: durability 36, security 38, user 65, catalog 65, Gateway 28. Contract 27/27.
Script tests 14/14, Playwright 2/2 và smoke Docker ở baseline f2d0b6a; docs 44 file PASS. Trạng thái review xem evidence;
không suy CI remote từ local PASS.

## 6. Môi trường

Docker Desktop phải chạy. Mutation Maven chỉ chạy ./mvnw trong Git Bash, log có Tests run:
và không COMPILATION ERROR; không dùng mvnw.cmd qua cmd /c. Node là toolchain pinned,
smoke cần --env-file và crypto built-in. CATALOG_JWT_PUBLIC_KEYS chỉ truyền public keys vào
catalog, không truyền private key. Chạy host catalog cần export public key như README service.
Volume hiện có SUPER_ADMIN synthetic, bootstrap không tạo admin mới; smoke không sửa role/DB.
Không dùng bash -x khi chạy smoke vì token/keys phải ở trong memory, không log.
Không down -v hoặc reset migration để vượt lỗi; V001 và dữ liệu cũ phải được giữ.
