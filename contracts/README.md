# Contracts

`TASK:PLT-02` · REQ: ORD-01/07, XCT-02 · dependency: PLT-01 S1-local accepted.
Assignee: Codex (GPT-6); reviewer contract core PR #3: Codex (GPT-6), theo chỉ định chủ dự án. Parent task còn In progress.

## Xác nhận review contract core — 2026-10-04

Codex (GPT-6) xác nhận phạm vi contract core của [PR #3](https://github.com/trunghieunef/fashion-ecommerce-platform/pull/3)
tại commit `816527baa1ef91c242b449f8171209c4af6791ff` đạt sau review lại. Hai finding P2
đã được xử lý: order item quantity 1–99 theo 05 và đọc JSON bằng `Decimal` để không
làm mất phần thập phân của tiền trước khi validate. Không phát hiện blocker mới trong
phạm vi đã kiểm tra; chủ dự án cho phép merge PR #3.

Bằng chứng: 23 contract tests PASS; scripts tests 11 PASS, 1 SKIP vì JDK không có
trong PATH; docs checker và `git diff --check` PASS. [Application CI](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/37190463356)
và [Documentation CI](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/37190463361)
PASS trên commit được review; reviewer không chạy lại Redocly/backend/browser ở local.

Xác nhận này chấp nhận contract core để tiếp tục PLT-03 trong phạm vi dependency đã
review; không đóng parent PLT-02 hoặc thay review producer/consumer khi có implementation.
Consumer Java của trường tiền phải chặn chuyển token thập phân sang integer; schema
tests chưa chứng minh hành vi runtime đó. PostgreSQL/Kafka, dedupe và recovery vẫn cần
bằng chứng riêng của PLT-03.

## Phạm vi thực có

- `openapi/common.yaml`: thư viện component theo 03 §1.2/1.3 — `Metadata`, `ApiError`
  (đủ 17 code của bảng lỗi, `additionalProperties: false` nên không lọt stack trace),
  `FieldError`, `AcceptedResponse` (202 + `status_url`), `ExpectedVersion`, tham số
  `Idempotency-Key` (1..128 theo 05), `limit`/`cursor` public, `page`/`size` admin,
  header `X-Correlation-Id`/`Retry-After`. Không khai báo operation; được lint qua API
  `$ref` tới nó.
- `openapi/user.yaml` (`TASK:USR-01` phần 1a/1b-i, `TASK:USR-02`): register/login/refresh/logout, đổi/quên/đặt lại mật khẩu, `/users/me`, địa chỉ, `/admin/api/v1/users`.
- `openapi/user-internal.yaml` (`TASK:USR-01` phần 1b-i): `GET /internal/api/v1/users/notification-secrets/{challenge_id}` cho notification-service (service token ADR-19).
- `openapi/catalog.yaml`: operation đã implement duy nhất là `GET /api/v1/catalog/products`;
  dùng `Limit`, `Metadata`, `CorrelationId`, `ApiError` từ `common.yaml` (lỗi 400 thu hẹp
  về `VALIDATION_ERROR`). Wire format không đổi so với catalog-service.
- `events/event-envelope.schema.json`: envelope Draft 2020-12 theo 03 §5.1:
  `version` là schema version, `correlation_id` là trace context. Timestamp UTC `Z`,
  UUID hợp lệ, sequence dương trong giới hạn PostgreSQL bigint.
- `events/registry.json`: mỗi `event_type` → `schema` (payload), `topic`, `partition_key`
  và `aggregate_id` (tên field trong payload) theo 03 §5.1/5.2; outbox của producer lấy
  topic/key từ đây. `event_type` không đăng ký bị từ chối. `test_event_routing.py` đọc
  bảng 03 §5.2 nên đổi topic/key ở một phía mà không sửa phía kia sẽ fail; partition key
  phải là field bắt buộc và `aggregate_id` của envelope phải bằng field đã khai báo. `events/common.schema.json`: UUID, UTC `Z`, business version, tiền VND
  integer 0..bigint (không float/string), `currency` = `VND`, `payment_method`
  COD/VNPAY/MOMO, SKU ≤ 64, stock int ≥ 0, order item quantity 1–99 (05).
  SKU mới: strip → kiểm ASCII `[A-Za-z0-9._-]` (ký tự đầu chữ/số) → uppercase Locale.ROOT
  trước hash/lưu DB/audit/outbox; unique không phân biệt hoa/thường. SKU cũ, response replay
  và event đã lưu giữ identity nguyên gốc; schema response/event vẫn nhận chữ thường.
- Payload theo 03 §5.3: `user-events` (USER_CREATED, không password/token), `notification-events` (NOTIFY_RESET_PASSWORD, chỉ `challenge_id`, không token), `catalog-events`
  (VARIANT_CREATED, CATALOG_CHANGED), `stock-events` (INVENTORY_UPDATED/RESTOCKED),
  `order-events` (ORDER_CREATED/CONFIRMED/PAID; CANCELLED bắt buộc `reason`). Payload
  strict (`additionalProperties`/`unevaluatedProperties: false`) để bắt lệch tên field ở
  producer; thêm field là thay đổi contract, sửa schema cùng PR. Order `status` là string,
  enum thuộc state machine 06/task ORD. `reserved <= on_hand`, `available = on_hand - reserved`
  và total theo công thức 05 là invariant producer, JSON Schema không biểu diễn được.
- `fixtures/valid/*.json`: một envelope synthetic cho mỗi event đã đăng ký; mỗi file phải
  qua cả envelope và payload schema. Không tuyên bố service nào đã phát event.
- `fixtures/invalid/payload-cases.json`: negative cases payload (tiền float/string/âm/tràn
  bigint, currency, method, quantity, thiếu field, camelCase, leak password, locale, SKU dài,
  stock âm/lẻ, timestamp không UTC), kiểm đúng validator và path. Test đọc JSON với
  `parse_float=Decimal`: token thập phân (`398000.0`, `9007199254740992.5`) giữ nguyên và bị
  field integer từ chối, chặt hơn mặc định JSON Schema (coi `1.0` là integer) để khớp
  "không float" ở 03/05; không làm tròn qua float trước khi validate.
- `fixtures/invalid/envelope-cases.json`: các mutation và loại lỗi phải bị từ chối;
  test kiểm cả reason và field path, không chấp nhận một lỗi bất kỳ thay thế.

## Kiểm tra

CAT-01b đang thiết kế: `catalog.yaml` đã có contract POST/PUT collection, PUT size guide và hai GET admin
detail collection/size guide theo quyết định chủ dự án 2026-10-07 (03 §3). POST key tạo
DRAFT/version 0; PUT chỉ expected_version, thay toàn bộ items/version/audit cùng transaction.
Items tối đa 1.000; sort_order integer 0..2147483647 được trùng; thiếu product body trả
400 VALIDATION_ERROR tại items[i].product_id. Timestamp có offset, lưu UTC; cover_url luôn
null trong response, không nhận cover/lookbook trong request (CAT-03). Collection trả items theo
`sort_order, product_id` và version; size guide trả nội dung/version, locale vi/en,
chưa có 404 NOT_FOUND. Size guide PUT bắt buộc key + version (0 tạo 201/version 1,
cập nhật 200/version +1), replay trước version guard. `guideline_html` không bắt buộc:
thiếu/null thành chuỗi rỗng, sanitize như product, tối đa 20.000 ký tự sau sanitize.
Cả hai GET cần `catalog.write`, đọc cả DRAFT/INACTIVE và giữ envelope.
Spec CAT-01b đã duyệt: request collection/guide từ chối unknown field qua @JsonAnySetter
với field tên key, giữ mapper global/CAT-01a; header so trùng strip + lowercase Locale.ROOT.
PUT guide kiểm category404 trước idempotency/version; create ON CONFLICT DO NOTHING RETURNING.
GET collection detail dùng một SQL json_agg ordered để items/version cùng snapshot.
Examples 2xx/4xx cùng contract regression kiểm Bearer, errors và version đã có;
đây chưa phải endpoint chạy được hoặc bằng chứng HTTP 401/403/404/GET → PUT.
Các integration test đó thuộc implementation CAT-01b sau duyệt spec/plan; public read
vẫn thuộc CAT-02. Response size guide table ghi rõ giới hạn shape; số ô khớp số cột,
strip và tổng serialized bytes cần validation tại service, JSON Schema chưa chứng minh chúng.

Python 3.10+; cài tooling vào virtualenv của bạn:

```bash
python3 -m venv /tmp/fashion-contracts-venv
source /tmp/fashion-contracts-venv/bin/activate
python3 -m pip install -r tests/contracts/requirements.txt
python3 -B -m unittest discover -s tests/contracts -p 'test_*.py' -v
```

Kiểm cả OpenAPI và event contracts (cần Node/npm đúng `.tool-versions` và `npm ci`):

```bash
bash scripts/validate-contracts.sh
```

Application CI cài requirements và gọi cùng script. Python docs checker vẫn chỉ cần
stdlib. Thư viện `jsonschema`, `pyyaml` (đọc OpenAPI cho negative tests) và dependencies được khóa theo bộ cài đã kiểm tra local;
không có dependency mới trong runtime Java/React. Không có network `$ref` khi validate.

Trên Windows, dùng virtualenv trong thư mục scratch bạn chọn; chạy Python bằng đường
dẫn `<venv>/Scripts/python.exe` thay cho activation nếu PowerShell chặn script.

## Phần còn lại

Schema các event Phase 1B/1C (ORDER_COMPLETED, INVENTORY_RESERVED/RELEASED/DEDUCTED,
PAYMENT_*, SHIPMENT_*, COD_*, PRICE_CHANGED, NOTIFY_*, NOTIFICATION_*) do task producer
tương ứng thêm vào registry. Operation OpenAPI của user/inventory/cart thuộc task 1A,
`$ref` tới `common.yaml`. Còn thiếu cho parent PLT-02: mock, producer/consumer review
(ký tên tại đây) và Mermaid preview ở 07. Contract tests không chứng
minh dedupe, thứ tự Kafka, transaction hoặc recovery; những bằng chứng đó thuộc PLT-03.
PLT-03 implementation chờ contract review và chạy PostgreSQL/Kafka thật; chưa tạo
module/migration cho primitive chưa kiểm thử.
