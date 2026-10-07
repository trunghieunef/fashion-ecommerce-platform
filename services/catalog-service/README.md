# Catalog service

`TASK:PLT-01` · partial `TASK:CAT-01` · `REQ:CAT-01`, `REQ:CAT-03`, `REQ:CAT-06`, `REQ:XCT-06`.

Service sở hữu database `catalog`. Phần nền read-only Sprint 1 chạy Flyway `V001`;
CAT-01a bổ sung `V002` (category/brand, product fields, variant identity trigger,
outbox/idempotency/audit) và follow-up `V003` (SKU mới uppercase ASCII, unique không phân biệt casing).
CAT-01b Task 1 thêm `V004__collections_and_size_guides.sql`: collections/items và size_guides,
FK/unique/version/locale/time/sort constraints; upgrade từ V003 giữ SKU legacy/sample và quyền
audit append-only. Schema đã kiểm bằng PostgreSQL Testcontainers và áp V004 thành công
trên volume local V003 tại Task 6, giữ dữ liệu hiện có.
CAT-01b Task 2 có GET `/admin/api/v1/catalog/collections` (page1/size20/max100/status optional,
created_at DESC/id DESC, summary không có items) và GET `/collections/{id}` (one SQL json_agg,
items sort_order/product_id, nội dung/version cùng snapshot, DRAFT/INACTIVE cũng đọc được).
Cả hai kiểm catalog.write, không audit read. CAT-01b Task 3 thêm POST collections
(key, DRAFT/version0, replay snapshot/no audit) và PUT collections/{id} (không key,
expected_version, full replacement/items/status/version+1). Request collection từ chối field
lạ qua @JsonAnySetter; NullNode sort/version như thiếu400; header/schema CAT-01a vẫn giữ nguyên.
Product body thiếu trả400 ở index request gốc; activation không yêu cầu product ACTIVE.
Date có offset→UTC, năm UTC1..9999, truncate microsecond trước so sánh/hash/lưu;
end phải sau start sau truncate, ngoài phạm vi trả400 đúng field, null bound giữ nguyên.
Key lạ lồng nhau báo items[i].<key>/table_json.<key>, top-level vẫn tên key.
Mutation/audit/key atomic, rollback audit giữ cả items/version.
Task 4 có GET `/admin/api/v1/catalog/size-guides/{category_id}/{locale}` (catalog.write,
locale vi/en; locale sai400, thiếu guide404 NOT_FOUND; category INACTIVE vẫn đọc được).
Trả guideline_html/table_json/version để edit; không audit GET. Task 5 thêm PUT cùng route:
key bắt buộc + version0 tạo201/version1; update200/version+1. Validation trước category404,
category trước replay, replay trước version guard; create race ON CONFLICT DO NOTHING RETURNING.
Bảng đóng columns/rows, strip plain text, chỉ header hợp lệ tham gia unique Locale.ROOT,
header sai type/rỗng/quá dài không thêm lỗi duplicate; tối đa32768 UTF-8 bytes;
guideline optional/sanitize/max20000 sau sanitize. Audit/key/data cùng transaction.
Không public read CAT-02; Task 6 đã smoke đầy đủ qua Gateway với 6 container healthy.
Admin taxonomy đã có GET/POST categories/brands và PUT theo id
tại `/admin/api/v1/catalog`. Mọi endpoint kiểm ES256 và `catalog.write` tại service;
POST cần `Idempotency-Key`, PUT cần `expected_version`; mutation và audit cùng transaction.
Thu hồi quyền ở catalog trễ tối đa TTL access token 15 phút theo ADR-21.
Audit dùng `request_id` của metadata response mutation đầu; retry giữ data/status đã lưu,
metadata mới và không thêm audit. Lỗi nội bộ admin trả 500 `INTERNAL`/`INTERNAL_ERROR`
với body chỉ code/message/metadata và X-Correlation-Id; exception vẫn log server qua
logger redact PII hiện có. Handler admin không áp cho ProductQueryController public.
Lỗi Spring ErrorResponse 4xx đến advice admin giữ status/header gốc (415 Content-Type,
406 Accept), envelope VALIDATION_ERROR/INVALID_HTTP_REQUEST bằng application/json,
metadata/X-Correlation-Id và không log ERROR. Lỗi còn lại mới dùng fallback 500;
không đổi các handler cụ thể hoặc thứ tự auth trong controller.
Public API hiện có `GET /api/v1/catalog/products`.
Endpoint trả các product `ACTIVE` theo `created_at DESC, id DESC`; `limit` mặc định
`20`, tối đa `100`. Response thành công theo [contract 03 §1.2](../../docs/design/03_interfaces.md):
`code: "OK"`, `data`, `metadata.request_id`, và `metadata.trace_id`.

`limit` ngoài khoảng `1..100` trả HTTP 400 với `code: "VALIDATION_ERROR"`,
`message: "INVALID_LIMIT"`, một `errors` field-level (`field: "limit"`, `message`,
`rejected_value`) và metadata đầy đủ. Header `X-Correlation-Id` bằng `trace_id` của
response, kể cả lỗi validation.

Product admin đã có GET list (page/size/status), POST draft, GET/PUT theo id; PUT giữ trạng thái,
kiểm version. Description VI/EN qua jsoup 1.23.2 Safelist.basic + h2/h3, link chỉ http/https,
rel nofollow/noopener; giới hạn sau sanitize 20.000 ký tự. Giá VND bigint, từ chối JSON thập phân.

Variant admin đã có POST `/products/{id}/variants` và PUT `/variants/{id}`: SKU/size/color/product
bất biến, sửa price_override/weight/status với version. Tạo variant tăng product version và ghi
đúng một `VARIANT_CREATED` PENDING tại `catalog.events`, key product_id, trong cùng transaction
với variant, audit và kết quả idempotency. Chưa có relay/Kafka local; chưa có inventory consumer.
SKU mới: strip → kiểm 1–64 ký tự ASCII `[A-Za-z0-9._-]`, đầu chữ/số → uppercase Locale.ROOT
trước hash/DB/audit/outbox. Retry đổi casing vẫn replay; đổi field khác trả 409.
V003 thêm unique index `upper(sku COLLATE "C")` và BEFORE INSERT guard; giữ V001/V002.
SKU cũ giữ identity và vẫn sửa mutable được; audit/outbox/hash/response cũ không backfill.
Replay key legacy giữ data/status nguyên gốc. Schema response/event vẫn nhận legacy lowercase.
Trước upgrade kiểm collision bằng `GROUP BY upper(sku COLLATE "C") HAVING count(*) > 1`;
có collision thì migration dừng, cần quyết định dữ liệu riêng, không tự merge hoặc reset volume.

Publish/unpublish qua POST `/products/{id}/publish|unpublish` cần key + expected_version,
reason tùy chọn ≤ 500. Publish DRAFT/INACTIVE → ACTIVE kiểm category/brand ACTIVE và có variant
ACTIVE; thiếu điều kiện trả 400 và rollback. Unpublish ACTIVE → INACTIVE; cạnh khác 409.
Published_at giữ mốc publish đầu. Điều kiện ảnh được chủ dự án hoãn tới CAT-03.
Product ACTIVE từ V001 có thể còn `published_at` null: unpublish giữ null, publish lại mới đặt
timestamp. Quyết định backfill hoặc xử lý null trong sort/index theo `published_at` thuộc CAT-02.

Đây chưa phải acceptance đầy đủ của `TASK:CAT-01`: còn
reviewer nghiệm thu, media (CAT-03), filter/search public (CAT-02), cursor thật, cache hoặc Kafka. `next_cursor` luôn
`null`; producer intent `catalog.events` trong outbox, chưa publish; consumer: none.

## Chạy local

`bash scripts/smoke-local.sh` giữ kiểm tra không token 401 và member thật thiếu quyền 403,
rồi tạo category → product → variant → publish qua Gateway bằng JWT synthetic ký từ khóa
local trong `.env` (chỉ memory, quyền `catalog.write`, TTL 300 giây). Smoke này không chứng minh
chuỗi login → token OPS. Slug/SKU ngẫu nhiên; dữ liệu smoke tích lũy trên volume local vì không có DELETE.
Smoke tạo SKU có whitespace/chữ thường rồi retry cùng key bằng uppercase; kiểm cùng variant id
và product version không tăng thêm trước publish.
Smoke CAT-01b tiếp tục bằng cùng JWT/category/product: collection POST201/DRAFT/version0 →
GET items/version → PUT không key200/version1 → stale409; guide PUT0/key201/version1 →
GET → PUT1/newkey200/version2 → stale409 và replay key tạo giữ201/data version1, metadata mới.
Mọi response kiểm envelope/metadata/X-Correlation-Id; collection/guide giữ cả401 và403 member thật.
Token synthetic dùng ES256/kid, iss=user-service, aud=fashion-api, sub UUID ngẫu nhiên,
auth_version0, chỉ catalog.write và exp300s; khóa/token không log/ghi file, không tạo helper production
hoặc sửa user DB để cấp quyền OPS. Không chứng minh login → cấp token OPS.
Collection slug có hậu tố UUID, guide dùng category mới mỗi lần: dữ liệu smoke tích lũy,
không DELETE/reset volume. Trên Git Bash Windows, helper gửi JSON qua stdin và giải mã HTTP UTF-8
để giữ nguyên text bảng Unicode, không phụ thuộc ANSI argv/default Python codepage.

Yêu cầu JDK 21 và Docker daemon mà user hiện tại được phép truy cập. Từ repo root:

```bash
bash scripts/local-up.sh # sinh khóa local, tạo roles và chạy stack
set -a; source infra/local/.env; set +a
export CATALOG_JWT_PUBLIC_KEYS="$USER_JWT_PUBLIC_KEYS"
./mvnw -pl services/catalog-service spring-boot:run
```

Service mặc định nghe tại `http://localhost:8081`. Trong Compose đủ chuỗi
(`bash scripts/local-up.sh`), catalog chạy từ `Dockerfile` (JAR build trên host, JRE Temurin
pin digest), không publish port, chỉ Gateway gọi được qua mạng Compose. Dừng dependency bằng
`docker compose --env-file infra/local/.env -f infra/local/compose.yaml down`.
Lệnh này giữ volume local; chỉ dùng `down -v` khi chủ động xóa toàn bộ dữ liệu local.

## Configuration và database roles

CAT-03 đang thiết kế; chủ dự án đã duyệt S3/URLConnection SDK v2 2.55.12 (BOM
chỉ service này). [Spike RustFS local](../../docs/evidence/cat-03-stack-research-2026-10-07.md)
PASS, chưa có upload/attach/publish image gate trong code service. RustFS local-only,
không staging/prod; SDK không dùng Apache/Netty/native CRT transport mới.
local-up.sh sinh CATALOG_S3_ACCESS_KEY/SECRET_KEY vào .env local, giữ qua retry,
không log/commit.

Storage adapter (TASK:CAT-03, Task 2) đã có: `MediaStorage` (presign PUT ký
Content-Type/Content-Length, head, read giới hạn, putIfAbsent với If-None-Match,
delete, open, ensureBucket), mọi lỗi SDK/timeout thành `MediaStorage.Unavailable`
không chứa key/URL. Chưa có endpoint upload/complete/read/GC (các task sau).
Config (`fashion.catalog.media.*`, env): `CATALOG_S3_BUCKET` (catalog-media-local),
`CATALOG_S3_ENDPOINT` (nội bộ, bắt buộc), `CATALOG_S3_PUBLIC_ENDPOINT` (URL browser
dùng cho presigned PUT, mặc định = endpoint), `CATALOG_S3_REGION`,
`CATALOG_S3_ACCESS_KEY/SECRET_KEY` (bắt buộc), `CATALOG_S3_CORS_ORIGINS`,
`CATALOG_S3_QUARANTINE_RETENTION_DAYS` (2), `CATALOG_S3_BOOTSTRAP_BUCKET` (false),
`CATALOG_MEDIA_JOBS_ENABLED` (true; mới được bind, scheduling ở task sau), timeout S3 10s.
Compose local chạy `rustfs` (digest ghim, chỉ `127.0.0.1:19000`, không console, volume
`catalog-media-data`), catalog bật bootstrap bucket (tạo bucket, CORS PUT cho origin
storefront, lifecycle chỉ prefix `quarantine/` 2 ngày, không bucket policy; retry 10x1s
rồi fail startup). `depends_on rustfs` chỉ service_started, readiness catalog không
phụ thuộc S3. `.env` cũ tự nhận `CATALOG_S3_PUBLIC_ENDPOINT` khi chạy local-up.sh;
`CATALOG_S3_ENDPOINT` trong `.env` không còn dùng (Compose đặt http://rustfs:9000).
RustFS/credential local chỉ cho local, không staging/prod. Test:
`MediaStorageIntegrationTest` (RustFS Testcontainers thật, credential ngẫu nhiên mỗi run).

Contract [CAT-03 bản nháp](../../docs/superpowers/specs/2026-10-07-cat-03-media-design.md)
và OpenAPI gắn planned: PUT300s; complete deadline=put_expires_at+24h (thay TTL
intent15phút); quarantine lifecycle2ngày, sweep60s/batch100, terminal dọn ngay.
Compose truyền CATALOG_S3_QUARANTINE_RETENTION_DAYS=2 và bootstrap bucket đã áp
lifecycle quarantine; các job cleanup (sweep/GC) chưa triển khai (Task 8). Orphan/detached7ngày,
GC hourly/batch100/single-runner lease platform; reattach chỉ uploader/target cũ.
Asset đang attach cho OPS khác giữ/sửa alt/sort; DELETING/DELETED không attach.
Audit media chỉ asset_id. Policy đã duyệt tại13; các job/endpoint chưa hiện thực.
Partial objects terminal EXPIRED/REJECTED chưa asset/reference/lease GC7ngày từ
terminal, key suy ra upload_id/không list bucket; retry trước terminal ưu tiên
approved đã ghi, phục hồi thumb/commit fenced, không reject vì lỗi tạm S3/DB.

Review spec ngày 2026-10-08: public media dùng Cache-Control: public, max-age=300,
ETag cố định và If-None-Match → 304; cache fresh có thể giữ ảnh tối đa 5 phút sau
unpublish. Admin preview dùng private, no-store. Kind lạ/rỗng trả 400 field kind
ở cả public/admin. Quarantine sweep dùng row claim FOR UPDATE SKIP LOCKED,
cleanup token/lease và CAS; GC approved/partial vẫn single-runner lease platform.
Composite FK association tới media_uploads sẽ chặn sai target trong V005.
S3 không tham gia readiness; thao tác media cần S3 lỗi trả 503
DEPENDENCY_UNAVAILABLE, metric/health group media riêng báo lỗi. Spec chưa duyệt,
chưa thêm route, constraint, indicator hoặc health group media vào runtime.

Giới hạn media đã duyệt: JPEG/PNG, raw/mỗi output <=5 MiB, dimensions <=8192
và <=25M pixels; approved cạnh dài <=2560, thumb <=800, không upscale. Một
re-encode/instance, không queue; hết slot trả 429/Retry-After: 1 trước đổi state.
Một buffer RGBA 25MP khoảng **100 MB** (95 MiB), chưa gồm decoded source/resize/
encoder buffers và phần heap của Spring. Đây là ước lượng, chưa là đo peak heap
hoặc bằng chứng pod sizing; không tự tăng node/heap để nhận ảnh lớn hơn.

| Variable | Default local | Dùng bởi |
|---|---|---|
| `CATALOG_DB_URL` | `jdbc:postgresql://localhost:5432/catalog` | Runtime datasource |
| `CATALOG_DB_USERNAME` / `CATALOG_DB_PASSWORD` | `catalog_runtime` / synthetic local value | Runtime datasource |
| `CATALOG_DB_MAX_POOL_SIZE` | `10` | Hikari runtime pool |
| `CATALOG_JWT_PUBLIC_KEYS` | bắt buộc, không default | `kid:base64-X.509` để kiểm access token ES256; dùng public keys của user-service |
| `CATALOG_MIGRATION_DB_URL` | `jdbc:postgresql://localhost:5432/catalog` | Flyway datasource |
| `CATALOG_MIGRATION_DB_USERNAME` / `CATALOG_MIGRATION_DB_PASSWORD` | `catalog_migration` / synthetic local value | Flyway migration |

`infra/local/postgres-init/01-catalog-roles.sh` tạo roles idempotently. Migration
role có `CREATE` trên database/schema để cài extension và tạo migration; runtime role
chỉ có `USAGE` schema cùng quyền DML mặc định trên tables/sequences do migration role
tạo. Runtime role không có `CREATE` schema và không được dùng làm Flyway credential.

`V001__catalog_baseline.sql` là migration append-only đầu tiên. Migration sau phải
tương thích với dữ liệu cũ; không sửa `V001` sau khi nó đã được áp dụng ở bất kỳ môi
trường nào.

## Xử lý ảnh media (CAT-03)

`vn.fashion.catalog.media.ImageProcessor` là lớp thuần JDK (ImageIO/`java.awt`, không thêm dependency)
dùng cho luồng upload complete; chưa nối vào endpoint nào ở bước này. Giới hạn (hằng số trong code):

- File gốc 1..5 242 880 byte; chỉ JPEG/PNG, magic bytes phải khớp `Content-Type` khai báo.
- Width/height 1..8192 và tối đa 25 000 000 pixel; kích thước đọc từ header và kiểm **trước** khi decode.
- Decode lỗi (file cắt cụt, CMYK/4-band, cảnh báo của reader) -> `IMAGE_DECODE_FAILED`.
- Re-encode không metadata: JPEG RGB quality 0.85, PNG giữ alpha nếu có; EXIF orientation 1-8 (IFD0) được
  áp dụng trước khi bỏ EXIF, EXIF hỏng được coi là 1. Cạnh dài tối đa 2560 (thumbnail 800), không upscale.
- Output vượt 5 MiB -> `IMAGE_OUTPUT_TOO_LARGE`; `verify` kiểm lại object approved (`APPROVED_OBJECT_INVALID`).

Ước lượng heap: buffer ARGB 25 MP khoảng 100 MB (4 byte/pixel), chưa đo thực tế; cần đo trước khi chốt
giới hạn đồng thời (slot re-encode 1/instance) và heap của container. Test: `ImageProcessorTest` (unit, không Docker).

## Test và health

```bash
./mvnw -pl services/catalog-service -Dtest=ProductQueryIntegrationTest test
./mvnw -pl services/catalog-service test
```

Surefire chạy JVM test với `-Duser.timezone=UTC` để pgjdbc không gửi alias zone cũ
(ví dụ `Asia/Saigon` trên Windows) mà PostgreSQL từ chối.
Hai lệnh dùng Testcontainers PostgreSQL `17.11`; chúng cần Docker daemon khả dụng,
không dùng H2 hoặc mock repository thay thế. Test kiểm tra migration, active-only
query, limit, response envelope, readiness và runtime role không thể tạo table, cùng admin
auth, version/SKU races, idempotency, sanitize, publish và rollback outbox/audit. CAT-01a local
có 71 test catalog sau follow-up SKU PR14; tổng reactor và mutation tại [evidence](../../docs/evidence/cat-01a-local-2026-10-07.md).
CAT-01b Task7 ban đầu có114 test catalog (43 mới), 13 mutation Java và smoke Gateway trên V004;
follow-up 4Minor PR15 có119 test catalog/286 toàn reactor, contracts33; regression và mutation
year guard tại evidence (không thêm schema/dependency hoặc đổi phạm vi).
full verification/whole-branch review tại [evidence CAT-01b](../../docs/evidence/cat-01b-local-2026-10-07.md)
và [handoff](../../docs/superpowers/plans/2026-10-07-cat-01b-handoff.md). Parent CAT-01 vẫn In progress.

Sau khi service chạy:

```bash
curl -fsS http://localhost:8081/actuator/health/liveness
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS 'http://localhost:8081/api/v1/catalog/products?limit=20'
curl -fsS http://localhost:8081/actuator/metrics
```

Liveness chỉ gồm `livenessState`, nên outage DB tạm thời không làm probe yêu cầu
restart JVM. Readiness gồm `db` và `catalogMigration`: database trống/chưa có V001
trả HTTP 503. Để kiểm tra case đó trên một database local mới, chạy service với
`SPRING_FLYWAY_ENABLED=false` trước khi chạy migration, rồi gọi readiness; không dùng
profile này cho runtime bình thường.

## Profile `nacos-compat` (O02)

Mặc định Nacos tắt (`spring.cloud.nacos.*.enabled=false`). Profile `nacos-compat`
(`application-nacos-compat.yaml`) import `nacos:catalog-service.yaml` (không optional, fail
fast nếu Nacos down lúc khởi động), đăng ký discovery `catalog-service` và bật
`management.info.env` để proof đọc giá trị import. Biến: `NACOS_SERVER_ADDR`,
`NACOS_USERNAME`, `NACOS_PASSWORD`. Chạy proof: `bash scripts/nacos-compat-check.sh`.

Observability (PLT-05, phần local): `metadata.trace_id` và header `X-Correlation-Id` là trace
W3C của request (tiếp nối `traceparent` từ Gateway; không có span thì sinh 32 hex), còn
`request_id` vẫn là UUID riêng của mỗi response. Log JSON dạng ECS qua
`PiiRedactingJsonCustomizer` ([platform-security](../platform-security/README.md)). Actuator
expose `health`, `info`, `metrics`, `prometheus` trên port 8081; port này không publish ra ngoài,
label metrics dùng URI template. Chưa có tracing exporter, dashboard, alert hay retention policy
(phần staging của PLT-05, sau PLT-04).
