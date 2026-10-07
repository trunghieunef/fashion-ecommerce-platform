# CAT-03 — safe media, object storage và image publish gate

**Trạng thái: đã duyệt ngày 2026-10-08** kèm hai chỉnh cuối (public read không HEAD S3;
UPLOAD_URL_EXPIRED là hành vi dự kiến). Plan: [CAT-03 media](../plans/2026-10-08-cat-03-media.md).
Chưa có code feature media. SDK, cấu hình local và
spike đã được cho phép riêng; không tự commit, push hoặc mở PR.

`TASK:CAT-03` · `REQ:CAT-11`, hỗ trợ `REQ:CAT-03/05`.
Dependency: CAT-01, SEC-01. CAT-01a/1b đã merge PR14/15; parent CAT-01 và SEC-01
chưa nghiệm thu đầy đủ. Base là `dev/f5c64d9`, schema catalog hiện tại là V004.
Nguồn chủ quản:
[03 Interfaces](../../design/03_interfaces.md),
[05 Database](../../design/05_database_design.md),
[06 Flows](../../design/06_service_flows.md),
[13 Operations](../../operations/13_operations_security.md),
[OpenAPI](../../../contracts/openapi/catalog.yaml) và
[spike evidence](../../evidence/cat-03-stack-research-2026-10-07.md).

## 1. Phạm vi và cách tiếp cận đã chốt

Luồng gồm presigned PUT vào quarantine private, complete kiểm bytes và re-encode
thành approved image/thumb, rồi PUT images chỉ cập nhật DB. Phạm vi bao gồm ảnh
product, cover/lookbook collection, admin preview, public stream và điều kiện
ảnh khi publish product. Không thêm public browse CAT-02, review upload CAT-04,
CATALOG_CHANGED, relay Kafka, WebP hoặc provision AWS. VARIANT_CREATED vẫn được
ghi khi tạo variant như code hiện có.

Hai cách đã cân nhắc là upload qua Gateway/service hoặc presigned PUT kèm
complete. Chủ dự án chọn cách thứ hai để tách ingress khỏi service; service vẫn
quản lý ownership, state và recovery. Không dùng CopyObject để approve raw vì
raw có thể bị ghi đè khi URL PUT còn hiệu lực. Service re-encode sang key riêng,
không cấp quyền PUT approved key cho client.

Local dùng RustFS 1.0.1 với digest đã xác minh, license Apache-2.0; image chỉ dùng
local, không staging/prod. Catalog-service dùng AWS SDK Java v2 2.55.12 qua BOM,
hai dependency trực tiếp S3 và URLConnection, không async/native CRT transport.
Pin, license và ngày kiểm 2026-10-07 ghi tại 17. Exclude apache5-client,
apache-client và netty-nio-client; dependency tree không đổi version artifact
Boot quản lý hoặc thêm Jackson thường. Credential dùng StaticCredentialsProvider
từ config/.env, endpoint và region tường minh; không dùng default AWS profile/
credential chain. Code chỉ gọi S3 API mà AWS hỗ trợ, không provision AWS.

Storage bind 127.0.0.1, bucket private và không có policy public. local-up sinh
credential vào .env, không log hoặc commit. Spike đã PASS tại evidence, gồm
conditional PUT lần hai trả 412 và anonymous read bị từ chối.

Giữ khuôn JdbcClient, Api/ApiExceptionHandler, audit metadata.request_id và
platform-durability. Không dùng JPA hoặc gọi S3/HTTP trong DB transaction/row lock.
Migration tiếp theo là V005; không sửa V001–V004. Phase1A plan cũ nêu V002/V003
và emit event lúc publish cần được đồng bộ khi viết plan. Parent CAT-01 chưa Done.
Thực thi Native trên dev, TDD, mutation và commit từng task chỉ sau duyệt plan.

## 2. Contract HTTP để review

Prefix admin là `/admin/api/v1`; mọi route admin kiểm `catalog.write` tại service.
Request JSON dùng schema đóng với @JsonAnySetter và báo full field path cho key
lạ. JsonNode integer không coercion; null ở field bắt buộc được xử lý như thiếu
và trả 400. Error dùng Api envelope hiện có, metadata mới và X-Correlation-Id;
không lộ exception, SQL, object key hoặc URL ký.

| Route | Request | Response |
|---|---|---|
| POST /catalog/images/uploads | Bắt buộc Idempotency-Key; filename, content_type, size_bytes, target_type, target_id | 201 upload intent |
| GET /catalog/images/uploads/{upload_id} | Chỉ uploader được đọc | 200 status; thiếu ID hoặc khác owner trả 404 |
| POST /catalog/images/uploads/{upload_id}/complete | Không body; không yêu cầu Idempotency-Key; idempotent theo upload_id | 200 approved asset; terminal replay giữ kết quả, không gọi S3 hoặc thêm audit |
| GET /catalog/products/{id}/images | Kiểm catalog.write | 200 snapshot id/version/images |
| PUT /catalog/products/{id}/images | Full replacement expected_version và images; không Idempotency-Key | 200 snapshot với version tăng 1 |
| GET /catalog/collections/{id}/images | Kiểm catalog.write | 200 snapshot id/version/images/cover_asset_id |
| PUT /catalog/collections/{id}/images | Full replacement expected_version, images và cover_asset_id nullable; không Idempotency-Key | 200 snapshot với version tăng 1 |
| GET /catalog/images/{asset_id} | Admin preview; kind=image hoặc thumb, mặc định image | 200 approved bytes với private, no-store |
| GET /api/v1/catalog/images/{asset_id} | Public read; kind và If-None-Match tùy chọn | 200 approved bytes hoặc 304 theo §6 |

Filename là string dài 1–255 ký tự sau strip. Content_type chỉ nhận image/jpeg
hoặc image/png; size_bytes là integer 1..5242880. Target_type là PRODUCT hoặc
COLLECTION và target_id là UUID. Không nhận URL, bucket hoặc key từ client.

Intent trả upload_id, put_expires_at, complete_deadline, put_url và put_headers
gồm Content-Type/Content-Length. URL là capability; không lưu trong idempotency
body, audit hoặc log. Example OpenAPI là unsigned synthetic, không credential.
Status trả upload_id, target_type, target_id, state, put_expires_at,
complete_deadline, lease_until nullable, reason_code nullable, asset nullable
và asset_availability nullable. Không lộ lease token hoặc raw key.

Approved asset có asset_id bằng upload_id, metadata image/thumb gồm content_type,
size_bytes, width, height và sha256. URL/thumb_url là path Gateway do server sinh,
không URL S3. Terminal approved result giữ metadata lúc approve; object đã GC có
thể không còn đọc được. GET status cho biết availability; không resurrect asset.

Product item gồm asset_id, alt_vi/alt_en plain text dài 1–255 sau strip,
sort_order integer 0..2147483647 và variant_color tùy chọn. Variant_color null
hoặc khớp màu variant của cùng product. Collection item gồm asset_id, sort_order
và caption_vi/en tùy chọn, plain text dài 0–500 sau strip, mặc định rỗng.
Frontend phải escape plain text, không render HTML.

Product tối đa 20 ảnh; collection tối đa 50 ảnh, bao gồm cover. Cả hai dùng
`images`, cho phép sort_order trùng và đọc theo (sort_order, asset_id).
Cover_asset_id bắt buộc nhưng nhận null; khác null phải nằm trong images của cùng
request, sai trả 400 field cover_asset_id. Duplicate asset trả 400
images[i].asset_id. Không nhận URL hoặc key trong item.

Ownership chỉ áp cho **asset mới gắn**, kể cả reattach: phải APPROVED, thuộc actor
JWT đã upload, đúng target và trong retention. Asset đang gắn với target được giữ
khi actor khác sửa alt, caption hoặc sort; không chuyển owner/target. GET snapshot
dành cho mọi OPS có catalog.write. Upload thiếu hoặc khác owner trả 404; asset
tham chiếu không hợp lệ trong body trả 400 images[i].asset_id. Resource path thiếu
trả 404. Không thêm DELETE endpoint.

PUT khóa resource, guard version, khóa asset theo UUID tăng dần, validate replace,
rồi tăng version, ghi audit và trả snapshot trong cùng transaction. Không gọi S3.
GET dùng một statement aggregate snapshot và version. Audit media diff chỉ chứa
asset_id thêm/gỡ và cover trước/sau; không URL, key, bytes, alt hoặc caption.
Các field actor/action/resource/version/request_id vẫn theo khuôn audit hiện có.

`kind` chỉ nhận image hoặc thumb. Bỏ query thì mặc định image; giá trị lạ hoặc
chuỗi rỗng trả **400 VALIDATION_ERROR / INVALID_FIELDS, field kind** ở cả admin
và public. Validate kind trước đọc asset, không chuyển giá trị lạ thành raw route.

| HTTP / code | Message và điều kiện |
|---|---|
| 400 VALIDATION_ERROR | INVALID_FIELDS; lỗi field cụ thể, gồm thiếu/null version hoặc kind sai |
| 400 VALIDATION_ERROR | IMAGE_REJECTED; errors[0].field=upload_id và message=reason_code đã lưu |
| 400 VALIDATION_ERROR | ACTIVE_PRODUCT_REQUIRES_IMAGE; field=images |
| 401 UNAUTHORIZED / 403 FORBIDDEN | Giữ khuôn auth hiện có |
| 404 NOT_FOUND | Resource path thiếu, upload khác owner hoặc media không visible |
| 409 VERSION_CONFLICT | Expected_version stale |
| 409 CONFLICT | UPLOAD_PROCESSING khi lease còn hiệu lực |
| 409 CONFLICT | UPLOAD_EXPIRED khi quá complete_deadline; không đọc S3 |
| 409 CONFLICT | UPLOAD_NOT_UPLOADED khi HEAD quarantine trả 404 trước deadline; giữ PENDING |
| 409 CONFLICT | UPLOAD_URL_EXPIRED khi replay create sau hạn PUT; status/complete vẫn dùng được |
| 429 RATE_LIMITED | IMAGE_PROCESSING_CAPACITY và Retry-After: 1; chưa claim, không đổi state |
| 503 TEMPORARILY_UNAVAILABLE | DEPENDENCY_UNAVAILABLE khi thao tác S3 cần thiết lỗi hoặc timeout |
| 500 INTERNAL | INTERNAL_ERROR cho lỗi nội bộ ngoài dự kiến; server log được redact |

Reason code rejection gồm IMAGE_TYPE_NOT_ALLOWED, IMAGE_SIZE_MISMATCH,
IMAGE_TOO_LARGE, IMAGE_DIMENSIONS_INVALID, IMAGE_DECODE_FAILED,
IMAGE_OUTPUT_TOO_LARGE và APPROVED_OBJECT_INVALID. Không trả bytes, filename
hoặc URL ký trong errors. HTTP framework 4xx giữ status/envelope như CAT-01a/1b.

Create kiểm auth, validate, target path, idempotency rồi ghi intent/audit trong
transaction. Canonical request dùng giá trị đã normalize; cùng key khác body
trả 409, replay không thêm audit. Signer chạy sau commit; cache descriptor thay
vì URL và chỉ ký phần TTL còn lại, không gia hạn deadline khi retry.
Complete không nhận checksum, dimensions, URL hoặc key từ client làm authority.

UPLOAD_URL_EXPIRED là hành vi dự kiến: client mất response gốc và retry create sau
300 giây sẽ không có upload_id để đọc status. Frontend tạo intent mới với
Idempotency-Key mới; đây là ngoại lệ có chủ đích của quy tắc giữ key qua retry.
Upload cũ tự EXPIRED theo sweep.

## 3. State, deadline và recovery

Chốt mới thay TTL intent 15 phút trước đó: URL PUT sống 300 giây từ created_at,
`complete_deadline = put_expires_at + 24h`. DB clock quyết định thời hạn.
Quá deadline chuyển EXPIRED, trả 409 UPLOAD_EXPIRED và không đọc S3.
Sweep không expire hoặc xóa quarantine của PROCESSING còn lease.
Claim 120 giây không kéo dài deadline; attempt không commit APPROVED sau deadline.
Worker kiểm deadline trước I/O tiếp theo; CAS kết quả cũng kiểm DB deadline.

State đi từ PENDING sang PROCESSING, rồi APPROVED hoặc REJECTED. PENDING hoặc
PROCESSING hết lease có thể chuyển EXPIRED khi quá deadline. Trùng PROCESSING
còn lease trả 409 UPLOAD_PROCESSING; client đọc GET status.
APPROVED/REJECTED/EXPIRED là terminal và không đọc lại quarantine. Replay dùng
metadata HTTP mới, không audit lặp. Rejection phải commit trước khi trả 400.

Complete kiểm ownership, deadline và state; acquire một slot/instance trước claim.
Không có slot trả 429, không đổi state. Transaction ngắn claim lease_token,
attempt và state; S3 chạy ngoài transaction. Transaction kết quả dùng CAS
lease_token/attempt, lease_until còn hiệu lực và deadline để ghi kết quả/audit.
Preflight terminal/PROCESSING diễn ra trước acquire slot để replay không trả 429.

Lỗi tạm S3 release claim bằng CAS nếu còn owner, giữ PENDING cho retry; không
overwrite lease worker khác. Lỗi commit DB không tự reject/expire; retry sau
takeover nếu lease còn giữ. Sau claim, ưu tiên HEAD/GET approved primary trước
quarantine. Có primary hợp lệ thì phục hồi thumb, metadata thực tế và commit
APPROVED với fencing, không đọc raw. Chỉ khi primary chưa có mới HEAD quarantine.
Raw thiếu trước deadline trả UPLOAD_NOT_UPLOADED/PENDING, không REJECTED hoặc 500.
Quá deadline vẫn EXPIRED trước S3; không coi lỗi tạm thumb/DB là lý do expire.

Keys cố định theo upload_id: quarantine/{id}/raw, approved/{id}/image và
approved/{id}/thumb. Mọi approved/thumb PUT dùng **If-None-Match:***, ghi lần đầu
và không overwrite. Existing object hoặc 412 đi nhánh recovery; không lộ S3 412
ra client. Thumb lấy từ approved primary thực tế, không candidate thua race.
Trước DB commit phải đọc/kiểm primary và thumb thực tế, tính metadata/hash từ
các bytes đó. Attempt hết lease không commit dù đã thắng PUT object.
DB chỉ APPROVED khi đủ cặp object hợp lệ; retry dùng cùng keys/upload_id.

Không dùng AdminCommands.create bao trùm S3 vì helper mở transaction quanh work.
Reuse TransactionTemplate/IdempotencyStore, không thêm framework transaction.
S3 call có timeout; không giữ connection/stream vô hạn. Timeout PUT có thể
UNKNOWN, nên recovery đọc object thực tế thay vì suy luận chưa ghi.

## 4. Validation ảnh đã duyệt

Chỉ JPEG/PNG; raw và mỗi output nằm trong 1..5242880 bytes. Presigned URL ký
Content-Type/Content-Length, nhưng complete vẫn HEAD rồi đọc stream bounded,
tối đa cap + 1, và kiểm actual size khớp khai báo. Không tin extension hoặc HEAD
MIME. Magic bytes và ImageReader phải nhận cùng format với declared type.

Width/height nằm trong 1..8192 và tổng không quá 25.000.000 pixels. Kiểm dimensions
trước full decode; phép nhân dùng long để tránh overflow. Java 21 ImageIO có sẵn;
CMYK JPEG, file hỏng hoặc decode exception bị REJECTED có kiểm soát, không 500.
Re-encode loại metadata, EXIF/GPS và trailing payload.

Approved có cạnh dài tối đa 2560; thumb tối đa 800. Giữ tỷ lệ, không upscale.
Output encoder cũng bounded; vượt 5 MiB trả IMAGE_OUTPUT_TOO_LARGE.
Một re-encode/instance, không queue trong memory; slot giữ suốt decode, encode
và recovery, luôn release trong finally. Hết slot trả 429 và Retry-After: 1
trước đổi state. WebP, SVG, GIF và format khác bị từ chối; format/dependency mới
phải duyệt riêng.

Một buffer RGBA 25 MP khoảng 100 MB, chưa gồm codec, temp buffers và app heap.
README/16 ghi đây là ước lượng, cần đo sizing; không tự tăng tài nguyên.
CORS chỉ cho origin admin/storefront local cụ thể, không wildcard.

## 5. Schema dự kiến V005 và retention đã duyệt

Schema này chưa migration hoặc deploy; không FK sang user DB.

- media_uploads có id UUID PK, actor_id UUID, target_type và product_id hoặc
  collection_id FK. CHECK bảo đảm đúng một target, khớp target_type. Thêm
  **UNIQUE(id, product_id)** và **UNIQUE(id, collection_id)** để làm composite FK.
  Lưu filename/content_type/size_bytes, quarantine_key UNIQUE, created_at,
  put_expires_at, complete_deadline, state CHECK, attempt >= 0, lease_token/
  lease_until, reason_code/result_json và terminal_at. Cleanup quarantine có
  quarantine_lease_token/quarantine_lease_until, marker và retry; partial cleanup
  có marker, claim token và retry riêng. Asset_id dùng cùng upload_id.
- media_assets có id PK/FK media_uploads, image_key/thumb_key UNIQUE, approved_at
  và metadata type/bytes/dimensions/hash của image/thumb. Availability gồm
  AVAILABLE/DELETING/DELETED; lưu detached_at nullable, GC attempts, next_retry,
  safe error code và gc_claim_token. Owner/target lấy từ upload immutable.
- product_images có PK(product_id, asset_id), FK product_id tới products và
  FK asset_id tới media_assets. Thêm **FK(asset_id, product_id) REFERENCES
  media_uploads(id, product_id)** để DB chặn sai target. Lưu variant_color
  nullable, alt_vi/en, sort_order >= 0; index(product_id, sort_order, asset_id).
- lookbook_images có PK(collection_id, asset_id), FK collection_id tới collections
  và FK asset_id tới media_assets. Thêm **FK(asset_id, collection_id) REFERENCES
  media_uploads(id, collection_id)**. Caption_vi/en mặc định rỗng,
  sort_order >= 0; index(collection_id, sort_order, asset_id).
- collections thêm cover_asset_id nullable. Composite FK(id, cover_asset_id)
  tới lookbook_images(collection_id, asset_id) được deferred trong replacement
  transaction. Không nhận client cover_url; response tính từ cover reference.
- media_job_leases có id/lease_token/lease_until và một dòng cố định cho GC
  runner, dùng LeaseRepository platform-durability. Quarantine sweep dùng row
  claim bên dưới, không dùng lease single-runner này.

DB FK/check bảo vệ identity và target; service kiểm owner, APPROVED, retention
và limits. Asset rows dùng chung lock cho PUT/GC; không tạo image version riêng.
PUT core collection vẫn từ chối cover_url/lookbook; GET core trả controlled
Gateway path khi có cover. Core PUT không gỡ media.

Retention là **7 ngày** từ lần gỡ gần nhất; asset chưa từng attach tính từ approve.
Reattach trước hạn chỉ uploader và target cũ, không ngoại lệ OPS. Actor khác
phục hồi bằng re-upload. Asset đang attach không GC. PUT gỡ chỉ cập nhật DB/
detached_at, không DeleteObject; availability độc lập upload terminal/history.

GC mỗi giờ, batch tối đa 100, single-runner bằng lease platform-durability.
Transaction claim khóa asset và chỉ chuyển DELETING khi quá hạn, không reference
hoặc evidence. Attach dùng cùng row lock, từ chối DELETING/DELETED với 400
images[i].asset_id. Xóa image/thumb ngoài transaction; key thiếu coi thành công.
Finalize bằng CAS; crash thì retry cùng keys. Retry có giới hạn/backoff và log/
metric khi lỗi lặp, không chặn batch. Plan phải nêu ngưỡng retry, lease và run
budget phù hợp timeout S3. Không xóa attached asset, evidence, bucket hoặc volume.

Quarantine terminal được dọn ngay sau commit; cleanup lỗi không đảo complete.
Chưa complete chỉ sweep sau put_expires_at + 24h và chuyển EXPIRED khi không
còn lease PROCESSING. **Sweep chạy mỗi 60 giây, batch 100, dùng row claim
FOR UPDATE SKIP LOCKED**, cho phép nhiều instance xử lý các dòng khác nhau.
Trong transaction ngắn, lọc dòng đến hạn retry, không có complete lease hoặc
cleanup lease còn hiệu lực; cấp quarantine_lease_token/quarantine_lease_until,
ghi EXPIRED/terminal_at nếu cần, rồi commit. DeleteObject chạy ngoài transaction.
Finalize marker/result dùng CAS token và cleanup lease còn hiệu lực. Khi crash
hoặc lease hết hạn, instance khác reclaim; không giữ row lock trong S3 I/O.
Object đã mất coi thành công. Có test claim đồng thời không nhận cùng dòng,
takeover và retry DeleteObject không expire PROCESSING còn lease.

Lifecycle chuẩn S3 chỉ áp quarantine/, expire 2 ngày làm lưới an toàn bắt PUT muộn.
Không lifecycle approved/. Config 2 ngày đã kiểm round-trip trong spike/local-up;
chưa chờ đủ thời gian để chứng minh lifecycle thực sự xóa object.

Partial object của upload terminal EXPIRED/REJECTED chưa asset được GC cùng job
sau 7 ngày từ terminal_at, chỉ khi không asset/reference/lease. Keys suy ra
upload_id, không list bucket và không xóa trong request. Batch 100 là tổng asset/
partial intent. Trước terminal ưu tiên phục hồi approved primary và thumb để
commit APPROVED; lỗi tạm không reject/expire. Chưa triển khai các job này.

## 6. Read visibility, cache và publish gate

Bucket luôn private; Gateway stream approved image/thumb, không trả signed GET
URL hoặc raw download. Thành công 200 là binary với Content-Type thực và
X-Correlation-Id; lỗi dùng JSON envelope. Kind sai trả 400 như §2.

**Public:** khi request tới service, kiểm visibility trước conditional response.
Asset phải AVAILABLE, APPROVED và đang attach vào product ACTIVE, hoặc collection
ACTIVE trong [start_at, end_at) có ít nhất một product ACTIVE. Null start/end
không giới hạn. Không visible trả 404, kể cả If-None-Match trùng. Visibility trong DB quyết định
304, không gọi S3. Response 200 GET object ngoài transaction; object thiếu hoặc
storage lỗi trả 503 DEPENDENCY_UNAVAILABLE, không biến lỗi dependency thành 304.

Response 200 và 304 có **Cache-Control: public, max-age=300** và ETag cố định cho
representation đã approve. ETag là SHA-256 đã lưu của image hoặc thumb, đặt trong
dấu ngoặc kép; không dùng ETag multipart từ storage. Gateway giữ nguyên headers
và bytes. If-None-Match khớp trả **304 không body**; ETag khác trả 200.
List validator, weak validator và wildcard dùng quy tắc conditional GET của
[RFC 9110 §13.1.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2).
Ảnh/thumb có validator theo bytes tương ứng, không dùng chung validator nếu khác bytes.

Đánh đổi đã chốt: cache còn fresh có thể tiếp tục hiển thị ảnh **tối đa 5 phút sau
unpublish** hoặc khi visibility đổi; request này có thể không tới service.
Khi request/revalidation tới service, visibility được kiểm lại và không trả 304
cho asset không còn visible. Không cấu hình phục vụ stale kéo dài thời hạn này.

**Admin preview:** kiểm catalog.write mỗi lần; DRAFT/INACTIVE được xem.
Asset đang attach dành cho mọi OPS; detached/chưa attach chỉ uploader.
DELETING/DELETED trả 404. Response có **Cache-Control: private, no-store**,
không dùng public cache hoặc contract 304 của public. Không expose raw.

Publish product cần ít nhất một APPROVED image đã attach và alt_vi/en hợp lệ,
giữ gates taxonomy/brand/ACTIVE variant và published_at CAT-01a. Kiểm DB dưới
resource lock/version/idempotency/audit; không HEAD S3 khi publish.
PUT images không được làm product ACTIVE còn 0 ảnh: trả 400
ACTIVE_PRODUCT_REQUIRES_IMAGE, field images; cần unpublish trước khi gỡ hết.
Product ACTIVE legacy từ V001 giữ status, không backfill/unpublish, được attach.
Collection không có cover/lookbook gate hoặc gate ảnh theo từng màu.
Collection ACTIVE được gỡ cover nếu replacement hợp lệ; cover khác null vẫn
phải nằm trong images request.

**Storage outage không đổi readiness catalog-service.** Giữ nhóm readiness hiện
có: readinessState, db và catalogMigration; liveness cũng không đổi.
Route ảnh, upload và complete trả 503 TEMPORARILY_UNAVAILABLE với message
DEPENDENCY_UNAVAILABLE khi thao tác storage cần thiết lỗi/timeout. Terminal
complete replay không gọi S3 và vẫn giữ kết quả đã lưu. Không biến lỗi đọc ảnh
thành thay đổi state APPROVED hoặc đưa S3 vào catalogMigration/readiness.

Theo dõi S3 qua metric lỗi/latency theo operation và health group **media** riêng
với indicator catalogObjectStorage, chỉ qua management access. Không dùng group
media làm readiness/liveness probe; label metric không chứa key, URL ký, actor
hoặc upload_id. Đây là thiết kế, chưa thêm indicator/group vào runtime.

## 7. Acceptance phải có trong implementation plan

- PostgreSQL Testcontainers và S3 Docker thật dùng image/SDK đã pin. Anonymous
  raw/approved GET bị từ chối; restart giữ bucket/keys và upgrade V004 không reset.
- Auth 401/403 qua service/Gateway; IDOR trả 404. Key lạ/full path/null/type trả
  400; không fetch arbitrary URL/key. Create key/replay/canonical conflict không
  ghi capability vào audit.
- JPEG/PNG thật; magic/type/size mismatch, truncated/CMYK/corrupt, dimensions cap
  trước decode, output cap và strip metadata. Slot 429 không để lại PROCESSING.
- PUT 300 giây, complete deadline +24h và lease 120 giây. Expired không S3 read;
  raw HEAD 404 được xử lý có kiểm soát; sweep key thiếu thành công/EXPIRED.
  Lifecycle config 2 ngày chỉ áp quarantine.
- Raw reupload sau complete không đổi approved/thumb. Conditional PUT race trả
  412 rồi recovery metadata từ bytes thực; stale lease không commit; terminal
  không đọc lại S3 hoặc thêm audit.
- Outage/crash sau primary, thumb, commit và cleanup được phục hồi bằng cùng
  upload/key. Primary đã ghi nhưng commit lỗi được retry thành APPROVED trước
  deadline; partial terminal được GC sau 7 ngày, không list bucket.
- PUT kiểm stale version, new owner, target, status, retention và availability.
  Retained asset của actor khác được giữ. Kiểm duplicates, cover membership,
  alt/color/sort, snapshot/version và audit rollback.
- Test DB trực tiếp chèn product_images/lookbook_images với asset sai target
  hoặc sai loại target bị composite FK chặn; không chỉ chứng minh service guard.
- Publish thiếu ảnh fail, có ảnh pass; gỡ ảnh cuối ACTIVE fail; legacy giữ status.
  Race publish/remove dùng cùng resource lock; collection ACTIVE được cover null.
- Public 200 có Cache-Control: public, max-age=300 và quoted stable ETag; image/
  thumb validator theo representation. If-None-Match match/list/weak/wildcard
  trả 304 không body, giữ ETag/cache/correlation headers; mismatch trả 200.
  Unpublish hoặc ra ngoài collection time window khiến request tới service
  trả 404, không 304; kiểm đánh đổi cache fresh tối đa 5 phút.
- Admin preview có Cache-Control: private, no-store và kiểm catalog.write.
  Public/admin kind lạ hoặc rỗng đều trả 400 field kind; omitted mặc định image.
  Raw không có route đọc và không thêm browse CAT-02.
- S3 outage làm các thao tác route ảnh/upload/complete cần storage trả 503
  DEPENDENCY_UNAVAILABLE; readiness catalog-service vẫn giữ trạng thái theo
  DB/migration. Health group media báo lỗi riêng; không dùng nó làm pod readiness.
- GC 7 ngày từ approve/detach; reattach uploader trong hạn. Attach vs GC race
  dùng cùng row lock; single-runner GC, partial delete, crash và retry có giới
  hạn không chặn batch hoặc xóa reference/evidence.
- Quarantine sweep row claim FOR UPDATE SKIP LOCKED có test multi-instance không
  cùng claim một dòng, lease takeover và finalize CAS; không expire PROCESSING
  còn complete lease hoặc giữ transaction khi DeleteObject.
- Gateway OPS smoke dùng token synthetic theo CAT-01b: khóa từ .env, token chỉ
  trong memory, issuer/audience/ES256/kid đúng, sub UUID, auth_version 0,
  permissions chỉ catalog.write, exp <= 300 giây. Vẫn kiểm 401 và member thật
  không quyền trả 403; không coi smoke là proof login → token OPS.
- RED → GREEN → mutation thật có Tests run và không COMPILATION ERROR; full
  Maven/contracts/scripts/docs/diff/secret checks và docs/README/runbook đồng bộ.
  Chưa chạy integration, mutation, heap hoặc recovery media; spike không thay
  các acceptance này.

## 8. Review và bước tiếp theo

Review ngày 2026-10-08 đã sửa public cache/ETag/304, tách S3 khỏi readiness,
composite FK target, lỗi kind và quarantine row claim. Các quyết định trước về
ownership, validation, deadline và retention vẫn giữ. Spec **đã duyệt ngày 2026-10-08**;
OpenAPI gắn planned và không tuyên bố feature runtime đã có.

Implementation plan đã viết bằng superpowers:writing-plans; plan cần review/duyệt
trước Native trên dev, local commits theo authorization và whole-branch review/
handoff. Push dev hoặc mở PR main chỉ khi yêu cầu; chủ dự án quyết định merge.
