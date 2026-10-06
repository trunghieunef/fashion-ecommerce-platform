# USR-01b-ii — auth rate limit local, 2026-10-06

`TASK:USR-01b-ii` thuộc `TASK:USR-01` · REQ: USR-03/05, XCT-02 · dependency:
PLT-02/03, SEC-01. Assignee: Codex; reviewer nghiệm thu: chủ dự án.
Branch `feat/usr-01b-ii-rate-limit` từ `origin/main` tại `dc24935` (PR #11 đã merge).
Thực hiện trên Windows/Git Bash, Docker Desktop 29.2.0, Java 21.0.12.1+1,
Node 24.21.0, Maven wrapper 3.9.16; PostgreSQL 17.11/Redis 8.2.9 thật.

## Thiết kế được chủ dự án duyệt

- Login: 20/300 giây/IP; forgot: 20/3600 giây/IP + 5/3600 giây/email trim/lowercase.
  Redis Lua atomic; cửa sổ bắt đầu lần đầu, request bị chặn không gia hạn TTL.
- Proxy allowlist theo socket peer: nginx overwrite → Gateway canonical `X-Client-IP`
  → service chỉ tin Gateway đã cấu hình. Default allowlist rỗng; local dùng hostname
  `storefront`/`gateway`. Header giả, trùng, chain hoặc hostname do client gửi không được tin.
- 429 chung + Retry-After; Redis lỗi 503 trước auth/reset mutation. Readiness DB + Redis,
  liveness độc lập. Connect/command Redis 500ms, Gateway response budget 2s.

## TDD và review độc lập

| Bằng chứng | Kết quả |
|---|---|
| Baseline `./mvnw -B test` trước thay đổi | 157 test PASS |
| RED Gateway spoof + Redis down login | FAIL đúng assertion: header chưa bị xóa, login chưa fail closed |
| RED quota/normalization/window/concurrency + trusted proxy | FAIL đúng assertion: chưa có 429/canonical client IP |
| GREEN auth quota và IP boundary | HTTP quota/concurrency/expiry, real Redis; DNS allowlist, IPv6 canonical, tách bucket, duplicate header fallback |
| Review độc lập Superpowers | 1 Important timeout + 2 Minor coverage/docs; đã sửa và re-review không còn Important |
| RED public timeout smoke trên Redis đã kết nối rồi pause | Login qua Gateway 504, trái 503 contract; script unpause khi exit |
| GREEN public timeout smoke sau budget fix | Login/forgot 503 với ApiError metadata; DB session/reset/outbox không đổi; readiness 503, liveness 200; unpause rồi readiness 200 |

Full-suite đầu tiên bắt lỗi fixture cũ: auth/admin chưa khởi tạo Redis và test password đếm
mọi Redis key thay vì namespace secret. Đã bổ sung Redis thật/flush fixture và chỉ đếm
`user:notification-secret:*`. Smoke Windows gặp MSYS rewrite `/dev/null` trong container;
đã đặt `MSYS_NO_PATHCONV=1` tại health command, chạy lại PASS.

## Kiểm tra local thực chạy

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B test` trên implementation trước review (`96e6ea0`) | 169/169 PASS, 0 failures/errors/skips: durability 36, security 35, user 61, catalog 12, Gateway 25 |
| `npm run typecheck`; `npm test`; `npm run build` | PASS; Vitest 2/2 |
| `bash scripts/validate-contracts.sh` | 3 OpenAPI hợp lệ, 23 contract test PASS |
| `bash scripts/validate-infra.sh` | cfn-lint offline + 34 test PASS; không gọi AWS |
| `python3 -B -m unittest discover -s scripts -p 'test_*.py' -v` | 14 test PASS |
| `bash scripts/local-up.sh`; `bash scripts/smoke-local.sh` | 6 service healthy; auth/profile/admin/catalog smoke PASS trên volume local đã có |
| `bash scripts/smoke-auth-redis-timeout.sh` | PASS; local Redis đã khôi phục |
| `npx playwright test` | Desktop/mobile Chromium 2/2 PASS |
| `bash scripts/nacos-compat-check.sh` | 4/4 PASS: config import, discovery, lb route, reconnect; đã trả về profile mặc định, 6 service healthy |
| `python3 -B scripts/check_docs.py`; `git diff --check` | PASS: 38 Markdown; diff không lỗi whitespace |
| `bash scripts/scan-secrets.sh`; gitleaks stdin trên staged diff | History 46 commit PASS; staged diff PASS sau ghi `gitleaks:allow` đúng dòng password fixture synthetic |

## Tài liệu và phần chưa nghiệm thu

Đã đồng bộ OpenAPI user, 03/04/06/08/10/12/13, README root/docs/service/frontend,
handoff và workflow CI. Không đổi schema, migration, event hoặc dependency runtime.
CI thêm public Redis timeout smoke; chưa có kết quả remote cho branch tại lúc ghi evidence.
CI của PR phải được đối chiếu đúng SHA, không dùng kết quả baseline thay bằng chứng patch mới.

Chưa chạy fresh-volume Compose local (giữ dữ liệu chủ dự án); Testcontainers tạo DB/Redis
mới, Application CI chạy Compose fresh. Npm ci báo 1 high vulnerability có sẵn; không đổi
dependency trong task này. Browse/member/checkout/admin generic limiter, notification/relay,
constant-time forgot, license Redis và staging topology chưa được nghiệm thu.
Parent USR-01 vẫn In progress, phần 1b-ii Review; PR chờ chủ dự án review rồi mới merge.
Không deploy AWS/provider hoặc tuyên bố G0/G1/G2/MVP hoàn thành.

## Xử lý review PR #12 trên `96e6ea0`

Review của chủ dự án (Claude Code reviewer) ghi 1 Important implementation, 1 Important
mức plan, 7 Minor. Codex kiểm lại code/callers và tái hiện lỗi trước khi sửa.

| Finding | Đánh giá và xử lý |
|---|---|
| Important: forwarded `999.1.1.1` đi DNS | Đúng. RED child JVM hosts file ánh xạ thành `10.9.9.9` bị chấp nhận. GREEN: IPv4 octet strict, kiểm trusted peer trước parse header; DNS chỉ cho proxy hostname operator cấu hình |
| Important plan: Redis readiness rút toàn pod K8s | Đúng về tác động tương lai; không phải implementation sai. Giữ readiness đã được chủ dự án duyệt, ghi quyết định mở trước manifest user-service staging trong 08/04/13 |
| Minor: IPv6 đổi địa chỉ trong /64 có quota mới | Giới hạn đúng của per-IP; gom subnet đổi chính sách quota/actor đã duyệt. Ghi open PO/TL + QA trước ingress dual-stack, không tự đổi contract |
| Minor: SHA-256 key có thể dò IP/email | Đúng. Thay bằng HMAC-SHA256 với khóa dẫn xuất từ master sẵn có, domain/env riêng; `Mac` mới/request, không log key |
| Minor: socket zone/null gây 500 | RED: scoped peer exception và Gateway NPE. GREEN: bỏ zone chỉ trên socket peer; thiếu/unresolved remote thì xóa identity header, không đặt client IP giả, service lấy peer thật |
| Minor: mọi request offload | RED: request không có forwarded header vẫn sang boundedElastic. GREEN: chỉ offload khi có một header và hostname allowlist cần DNS; không thể biết peer thuộc hostname allowlist trước lookup |
| Minor: thiếu unit parser | Thêm `ProxyClientIpTest` (3 test), hosts fixture trong child JVM tách cache, không gọi public DNS |
| Minor: thiếu env namespace | `FASHION_ENV` default local, validated; key `<env>:user:rate:<scope>:<HMAC>`, Compose/example đồng bộ, env/secret khác có quota độc lập |
| Minor: allowlist rỗng im lặng | RED không có log. GREEN WARN tại startup khi không có gateway hiệu lực, kể cả local/test; giữ default standalone không tin proxy |

Không đổi quota/window, schema, API payload hoặc dependency. Chuyển key format/đổi env/master
tạo bucket mới; key cũ tự hết TTL, không purge. `USER_SECRET_KEY` còn mã hóa reset secret,
rotation cần xử lý secret đang chờ theo runbook trước deploy. Redis vẫn private/auth, mỗi env
có instance/credential riêng; HMAC không làm key thành dữ liệu vô danh.

| Kiểm tra patch review | Kết quả |
|---|---|
| Focused parser/Gateway/RateLimit | Parser 3/3, Gateway 27/27, RateLimit 9/9 GREEN; full suite thêm isolation test |
| `./mvnw -B test` | 178/178 PASS, 0 failures/errors/skips: durability 36, security 38, user 65, catalog 12, Gateway 27 |
| `bash scripts/validate-infra.sh`; Compose config quiet | 34 test + cfn-lint offline PASS; Compose hợp lệ, không gọi AWS |
| Scripts unit tests trong Git Bash với pinned Java/PATH | 14/14 PASS. Lượt PowerShell trước đó chọn WSL bash, `test_clean_history_passes` fail127 và Maven wrapper test skip thiếu JAVA_HOME; đã xác định môi trường và chạy lại đúng toolchain, không sửa script ngoài scope |
| `bash scripts/local-up.sh`; `bash scripts/smoke-local.sh`; `bash scripts/smoke-auth-redis-timeout.sh`; `npx playwright test` | PASS: 6 service healthy; public 503 + DB không đổi + health/recovery đúng; Chromium desktop/mobile 2/2 |
| Docs/diff/gitleaks staged diff | PASS: 38 Markdown, diff không lỗi whitespace, staged patch không có secret |

Review độc lập Superpowers trên patch source không còn finding cần sửa; reviewer không tự
chạy suite/Compose thay tác giả. CI remote trên `96e6ea0` đã PASS:
[Application](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/37491448957),
[Documentation](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/37491449019).
Patch mới phải chạy CI riêng trên SHA mới; chưa merge và chưa nghiệm thu parent.
Không chạy lại frontend unit/contract lint/Nacos proof trong patch review: frontend, contract,
route/profile/dependency không đổi; build frontend và browser/HTTP smoke đã chạy lại.
