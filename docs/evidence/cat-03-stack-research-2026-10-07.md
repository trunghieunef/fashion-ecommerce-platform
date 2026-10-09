# CAT-03 — research object storage local và S3 client

Ngày kiểm tra: **2026-10-07**. `TASK:CAT-03`, `REQ:CAT-11`; hỗ trợ ảnh sản phẩm
REQ:CAT-05 và media collection REQ:CAT-03. Chủ dự án đã chọn RustFS/SDK có điều
kiện; **spike local PASS tại §5**, chưa nghiệm thu feature CAT-03 hoặc staging.

## 1. Điều kiện đã duyệt

Chủ dự án chọn object storage S3-compatible trong Docker local; service dùng S3 API
với endpoint cấu hình. Bind host `127.0.0.1`, bucket private, không bucket policy
public. `local-up.sh` sinh credential vào `.env` local, không commit/in/log.
Không provision AWS hoặc bucket thật. Chủ dự án đã chọn RustFS 1.0.1/digest và
duyệt SDK 2.55.12 có điều kiện spike/dependency tree/StaticCredentialsProvider;
bộ phiên bản tại 17 đã cập nhật. RustFS local-only, không staging/prod; fallback
Garage 2.4.1 được cho phép nếu S3 operation cần thiết fail, chưa cần dùng fallback.

## 2. Hai ứng viên

| Image | License kiểm tại tag | Bằng chứng bảo trì kiểm ngày 2026-10-07 | Đánh đổi |
|---|---|---|---|
| `rustfs/rustfs:1.0.1` — đề xuất | Apache-2.0 | Release stable 1.0.1 ngày 2026-10-03; GitHub API: không archived, pushed_at 2026-10-07T15:17:25Z | License Apache-2.0; dòng 1.0 còn mới, cần chứng minh các S3 operation dự án sử dụng bằng test thật |
| `dxflrs/garage:v2.4.1` | AGPLv3 | Trang release chính thức: v2.4.1 ngày 2026-09-08; mirror Deuxfleurs GitHub: không archived, pushed_at 2026-10-02T19:39:14Z | Hỗ trợ single-node; dùng quyền key/bucket riêng, không triển khai S3 ACL/bucket policy, không có object versioning; bootstrap hạ tầng cần cách tương ứng |

Nguồn RustFS: [license tag 1.0.1](https://github.com/rustfs/rustfs/blob/1.0.1/LICENSE),
[release 1.0.1](https://github.com/rustfs/rustfs/releases/tag/1.0.1),
[repository](https://github.com/rustfs/rustfs),
[container guide](https://docs.rustfs.com/en/installation/container).

Nguồn Garage: [README tag v2.4.1 của Deuxfleurs](https://github.com/deuxfleurs-org/garage/blob/v2.4.1/README.md),
[license tag](https://github.com/deuxfleurs-org/garage/blob/v2.4.1/LICENSE),
[release builds](https://garagehq.deuxfleurs.fr/_releases.html),
[quick start](https://garagehq.deuxfleurs.fr/documentation/quick-start/),
[S3 compatibility](https://garagehq.deuxfleurs.fr/documentation/reference-manual/s3-compatibility/).
Forge/API Deuxfleurs trả anti-bot HTML tại lần kiểm này; không dùng response đó
làm bằng chứng JSON. License/tag dùng mirror của tổ chức upstream, ngày release
dùng trang release chính thức. Không dùng bản tổng hợp bên thứ ba làm căn cứ chọn.

### Digest đã xác minh từ registry

Pin đề xuất dùng **OCI index digest**, hỗ trợ cả amd64/arm64; các manifest con
ghi để đối chiếu trên máy chạy. Đây là digest registry, không phải bằng chứng
đã scan CVE hoặc chạy image.

```text
rustfs/rustfs:1.0.1@sha256:1803faef57627e2d9c2e7d89d655d712ddded5389040054987163043fecb6a3c
  linux/amd64: sha256:7465b31993156ca5cc0eb4b3c59a01ff69651961be62bcfeb6ce569f22034a56
  linux/arm64: sha256:3bc0a69f7636faf49cbddd38901bb688dc7fe3fa6d2e2f87cc56e71890f66bc8

dxflrs/garage:v2.4.1@sha256:9c96caa2612d3411acc5b0e6701fb238dbfba33e533a6d7d3d811a4b12d0d020
  linux/amd64: sha256:0d7c74fc8ca6fef68a5a941c0e7558c8b1e92ba3588fa7505400e1350456c796
  linux/arm64: sha256:2749e37137dae41459f49955e8082951f4a1ebea4e25d153182039f20a4b5974
```

Lệnh đã chạy, chỉ đọc metadata registry; cả hai exit 0:

```bash
docker buildx imagetools inspect rustfs/rustfs:1.0.1
docker buildx imagetools inspect dxflrs/garage:v2.4.1
```

GitHub public API `repos/rustfs/rustfs`, `repos/deuxfleurs-org/garage`,
`repos/rustfs/rustfs/releases/latest` và contents LICENSE theo tag đã đọc thành công.
Giá trị pushed_at là snapshot kiểm tra, không tự suy ra mọi commit đều là sửa lỗi.

## 3. Dependency đã duyệt có điều kiện

**AWS SDK for Java v2 2.55.12**, Apache-2.0. BOM `software.amazon.awssdk:bom`
2.55.12 để khóa các module cùng version; chỉ thêm trực tiếp `s3` và
`url-connection-client` cho catalog-service. Dùng `S3Client` đồng bộ và
`S3Presigner` trong module S3; chọn HTTP client rõ ràng, kiểm dependency tree
để loại transport Apache/Netty/native CRT không sử dụng, không thêm toàn bộ SDK bundle.

GitHub release stable 2.55.12: **2026-10-06T22:56:09Z**; repo không archived,
pushed_at **2026-10-07T14:25:52Z**. Cả ba POM `s3`, `bom`,
`url-connection-client` phiên bản 2.55.12 trên Maven Central trả **HTTP 200**;
POM S3 tham chiếu parent version 2.55.12. Đã resolve vào catalog-service và kiểm
tree/module tests ở §5, không thêm dependency cho các service khác.

Nguồn: [release 2.55.12](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.55.12),
[license tag](https://github.com/aws/aws-sdk-java-v2/blob/2.55.12/LICENSE.txt),
[S3 POM](https://repo.maven.apache.org/maven2/software/amazon/awssdk/s3/2.55.12/s3-2.55.12.pom),
[BOM POM](https://repo.maven.apache.org/maven2/software/amazon/awssdk/bom/2.55.12/bom-2.55.12.pom),
[HTTP client POM](https://repo.maven.apache.org/maven2/software/amazon/awssdk/url-connection-client/2.55.12/url-connection-client-2.55.12.pom),
[URLConnection guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-url.html),
[presigned URL guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html).

URLConnection dùng HTTP của JDK, không hỗ trợ PATCH; các S3 operation dự kiến
không cần PATCH. Hạn chế idle timeout do JVM quản lý, không phải pool riêng
có thể cấu hình tùy ý. Java 21 spike và module tests trên Boot 4 đã PASS ở §5;
chưa chứng minh flow CAT-03 hoàn chỉnh hoặc compatibility staging.

## 4. Compatibility phải chứng minh trong implementation

- Private bucket từ chối anonymous GET/HEAD/LIST; không bật website/public policy.
- Presigned upload chạy từ browser host và API S3 từ mạng Docker; không đổi host
  của URL sau ký. CORS chỉ origin local đã cho phép, không coi CORS là quyền đọc.
- PUT/HEAD/GET ảnh nhỏ bằng SDK đã chọn, checksum và header ký thực sự tương thích;
  test giả MIME, sai size, object khác, URL hết hạn/ghi đè và outage.
- Credential sinh một lần, giữ qua restart; bucket/objects giữ trên volume,
  upgrade không reset DB hoặc storage. Không log presigned URL/credential.
- S3 I/O có timeout ngoài DB transaction; cả ảnh chính và thumb phải tồn tại
  trước khi commit APPROVED. Không suy ra atomicity giữa DB và object storage.
- Endpoint, region, path-style và credential provider cấu hình được; staging
  dùng bucket được provision riêng theo 16. Chưa chứng minh runtime AWS thật.

## 5. Spike local sau phê duyệt — PASS

Docker Desktop Linux 29.2.0; Java 21.0.12.1+1, SDK 2.55.12. Container riêng
`fashion-cat03-spike-rustfs`, pin đúng index digest §2, bind duy nhất
`127.0.0.1:19000:9000`, không publish console. Volume riêng
`fashion-cat03-spike-data`, không dùng/xóa volume nghiệp vụ. Bucket tên UUID
synthetic được tạo bằng CreateBucket S3, không public policy/website/admin API.

S3Client cấu hình path-style/endpoint override/region từ .env, static credential,
URLConnection tường minh và timeout. Presigner ký offline với cùng static config,
truyền S3Client đã cấu hình URLConnection, disable S3 Express session auth;
không dùng default credential chain hoặc AWS profile thật.

| Kiểm tra thật | Kết quả |
|---|---|
| Presigned PUT PNG | 200; signed headers gồm content-type và content-length |
| Đổi Content-Type trên cùng URL ký | 403, không được ghi |
| Body khác Content-Length đã ký | 403, không được ghi |
| HeadObject | Size và MIME khớp ảnh gửi |
| GetObject | Bytes khớp hoàn toàn |
| SDK PutObject với checksum mặc định → GET | Bytes khớp hoàn toàn, không tắt checksum để làm pass |
| CopyObject → GET | PASS bytes; compatibility bổ sung, không dùng COPY raw trong approve đề xuất |
| PutObject If-None-Match:* lần hai | 412; GET vẫn bytes lần đầu, đủ primitive first-writer-wins đã duyệt |
| DeleteObject → HEAD | 404 sau xóa đúng object synthetic của probe |
| Anonymous GET / HEAD / LIST bucket | Đều 403; bucket private |
| PutBucketCors (S3 API) + Chromium fetch PUT | 200, browser tự gửi Content-Length; CORS chỉ origin probe local |
| Put/GetBucketLifecycleConfiguration | Probe lại sau chốt grace: round-trip quarantine/ và Expiration.days=2 PASS; thay1ngày trước, chỉ cấu hình, chưa chờ expiry thực thi |

Probe browser ban đầu dùng Playwright route.fulfill cho trang origin; Chromium
chặn truy cập loopback vì address-space/permission của origin mock, chưa có S3
response. Đổi **harness** sang HTTP server thật bind loopback đã PASS; không
thay RustFS hoặc bỏ validation/signature/CORS. Probe CORS origin
`http://localhost:19001`, không phải full frontend/admin integration.

Credential regression: chạy phần chuẩn bị environment thực của local-up.sh trên
fixture scratch rồi retry; sinh hai credential ngẫu nhiên, stdout rỗng, lần hai
giữ nguyên. Chạy cùng prefix trên .env local để chuẩn bị spike; **không chạy toàn
bộ local-up/build/Compose flow trong bước này**, không thay giá trị secret cũ.
Khóa/presigned URL chỉ process memory/stdin và .env local cho credential;
không ghi URL ký vào evidence/log/file. Probe đặt tại `.superpowers/cat-03-spike`
gitignored, là code thăm dò, chưa phải implementation media trong service.

### Dependency tree trước/sau

Baseline: 161 tọa độ artifact resolved; sau SDK: 192, thêm 31, không mất artifact
cũ, **0 thay đổi version artifact cũ** (gồm các artifact Boot quản lý).
SDK 2.55.12 kéo mặc định `apache5-client`: exclusion Apache cũ chưa đủ; đã thêm
exclusion apache5-client theo yêu cầu chỉ giữ URLConnection. Tree cuối không có
AWS `apache-client`, `apache5-client`, `netty-nio-client`, `aws-crt-client` hay native
`software.amazon.awssdk.crt:aws-crt`; không thêm async/CRT transport.

`crt-core` là module Java transitive của S3, không phải native CRT client;
`third-party-jackson-core` là Jackson được SDK relocate, không phải artifact
Jackson thường mới. Jackson 2 qua Nacos/dependency cũ vẫn giữ như baseline;
Jackson 3 của Boot giữ 3.1.5. Không loại dependency cũ ngoài phạm vi chỉ để tree
trông ít hơn. Chỉ hai dependency trực tiếp S3/URLConnection, BOM ở catalog-service.

Lệnh đã chạy từ root, Maven trong Git Bash:

```bash
export JAVA_HOME=/c/Users/<user>/.local/toolchains/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -pl services/catalog-service -am dependency:tree -DoutputFile=target/cat-03-baseline-dependencies.txt
./mvnw -B -pl services/catalog-service -am dependency:tree dependency:build-classpath -DincludeScope=runtime -DoutputFile=target/cat-03-sdk-dependencies.txt -Dmdep.outputFile=target/cat-03-classpath.txt
python -X utf8 .superpowers/cat-03-spike/prepare.py
bash .superpowers/cat-03-spike/run.sh
./mvnw -B -pl services/catalog-service -am test
```

Tree/build-classpath BUILD SUCCESS. Probe cuối **SPIKE PASS**, gồm các kiểm tra
trong bảng và Chromium real fetch; compile có hai unchecked varargs warnings ở
builder CORS/lifecycle probe, không compilation error. Module Maven **193 tests PASS**:
durability36/security38/catalog119, 0 failure/error/skip. Không chạy full reactor
user-service/Gateway vì không đổi dependencies/code của hai module đó.

Image/digest/license/date và SDK đã duyệt đã ghi
[17 Tech stack](../engineering/17_tech_stack.md). Spec tiếp tục tại
[bản nháp CAT-03](../superpowers/specs/2026-10-07-cat-03-media-design.md).
Chưa kiểm restart/lifecycle/storage outage, Docker-internal endpoint riêng,
full admin attach/publish gate hoặc AWS thật; các mục này còn trong acceptance
implementation. Flow/limits/visibility/state/cleanup/attach đã chốt từng phần;
schema/replay-create và toàn bộ written spec còn phải review. Chốt mới PUT300s/
complete expiry+24h, lifecycle quarantine2ngày; config local-up/spike đã cập nhật
và chạy lại SPIKE PASS. Container spike dừng sau kiểm tra; volume riêng giữ nguyên.

### Checks bổ sung

- `python -X utf8 -B -m unittest discover -s scripts -p 'test_*.py' -v` trong
  Git Bash với JAVA_HOME/PATH như handoff: **14 PASS, 0 skip**. Lần gọi trước
  trong PowerShell chọn WSL bash khiến test scan clean-history fail127 và
  wrapper skip; sửa môi trường thực thi, không sửa test hoặc scan logic.
- `python -X utf8 -B scripts/check_docs.py`: **50 Markdown PASS**.
- `git diff --check`: PASS.
- Gitleaks 8.30.1/digest theo scan-secrets.sh, `dir` trên snapshot **20 file thay
  đổi không bị gitignore** (không .env hoặc scratch): **no leaks**. Lần đầu
  phát hiện token Nacos synthetic cũ trong .env.example; đã thêm `gitleaks:allow`
  đúng dòng synthetic, không thay credential hoặc ignore cả file/rule. Scan này
  kiểm working changes, không tự thay bằng kết quả history scan cũ của PR15.

Không commit/push/PR, không sửa migration/schema/endpoint implementation.
OpenAPI media đã bổ sung planned và examples/schema; contract tests có regression
closed request/null/integer/bounds, key policy và binary/no-store. Chưa phải bằng
chứng endpoint/ownership/GC runtime. `bash scripts/validate-contracts.sh` trong
Git Bash với Node/toolchain handoff: **Redocly 3 APIs PASS, 0 warning;36 tests PASS**.
Contract RED13 cases:2FAIL/5ERROR do routes/schema chưa có; GREEN13PASS và suite
36PASS sau bổ sung contract. Không gọi đây là runtime TDD/mutation CAT-03.

Quyết định retention/deadline/partial recovery đồng bộ03/05/06/08/13, README
catalog-service và spec. Partial GC/sweep/lease/crash/image validation/heap vẫn
chưa hiện thực hoặc kiểm thử runtime; không đánh dấu CAT-03 hoặc parent CAT-01 Done.

## 6. Sửa spec theo review ngày 2026-10-08

Đã sửa đủ 2 Important và 4 Minor; **spec vẫn chưa duyệt**, chưa viết plan:

- Public dùng Cache-Control: public, max-age=300, ETag cố định theo SHA-256 của
  approved representation và If-None-Match → 304 không body. Cache fresh có thể
  hiển thị ảnh tối đa 5 phút sau unpublish. Admin dùng private, no-store.
- S3 không tham gia readiness catalog-service. Media route cần storage lỗi trả
  503 DEPENDENCY_UNAVAILABLE; metric/health group media báo lỗi riêng.
- Media_uploads có UNIQUE(id, product_id)/(id, collection_id); association có
  composite FK tới upload để DB chặn sai target. Chưa viết migration/test DB.
- Kind lạ hoặc rỗng trả 400 field kind ở public/admin. Quarantine sweep dùng
  row claim FOR UPDATE SKIP LOCKED, cleanup lease/token và CAS; GC giữ
  single-runner lease platform riêng. Concurrency runtime tests nằm trong §7 spec.
- Spec được viết lại với khoảng trắng/câu đầy đủ; bảng route tách request/response.
  Đồng bộ 03/05/06/08/13, OpenAPI, README catalog-service và cẩm nang docs.

Lệnh đã chạy: contract validation và tests scripts dùng Git Bash với
JAVA_HOME/Node/PATH như handoff; các Python checks khác được gọi từ PowerShell.

```bash
python -X utf8 -B -m unittest discover -s tests/contracts -p 'test_catalog_admin.py' -v
bash scripts/validate-contracts.sh
python -X utf8 -B -m unittest discover -s scripts -p 'test_*.py' -v
python -X utf8 -B scripts/check_docs.py
git diff --check
python -X utf8 .superpowers/cat-03-spike/scan-final.py
```

Contract RED: 14 tests, 3 failures ở header public/admin và conditional contract
chưa có. Sau sửa, Redocly 3 APIs PASS, 0 warning và **37 contract tests PASS**.
Scripts **14 PASS**, docs **50 Markdown PASS**, diff PASS. Gitleaks `dir` kiểm
snapshot **20 file working changes: no leaks**, không .env hoặc scratch.
Không chạy lại Maven/spike vì không đổi Java,
dependency hoặc S3 config trong đợt sửa spec này; số193 Maven tại §5 là run trước.
Chưa chứng minh cache/ETag/304, health group, FK hoặc sweep trong service runtime;
đây là contract checks và acceptance dự kiến, không nghiệm thu CAT-03.
