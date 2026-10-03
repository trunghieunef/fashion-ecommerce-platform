# Phase 0 local — khởi động ngày 2026-10-04

`TASK:PLT-02` · REQ: ORD-01/07, XCT-02 · dependency: PLT-01 S1-local accepted.
`TASK:PLT-03` · REQ: ORD-02, PAY-07 · dependency: PLT-01, PLT-02.
Assignee PLT-02: Codex (GPT-6); reviewer: chủ dự án. Acceptance theo 09/10,
parent PLT-02 còn In progress, PLT-03 implementation chưa bắt đầu.

## Quyết định thực hiện

- S1-local đã được duyệt tại [evidence](s1-local-2026-10-04.md) theo chỉ định chủ dự án.
- Theo 03, envelope dùng `version/correlation_id`; sửa ví dụ plan dùng nhầm
  `event_version/trace_id`. Không đổi contract B1. Nếu theo tên sai, producer/consumer
  sẽ lệch wire format.
- O01 chỉ chặn cloud theo 08/10; local contracts không cần AWS account/budget.
- Chuẩn bị PLT-03: sửa SQL minh họa trong plan theo nguồn schema 05, gồm
  `aggregate_type`, `id`, `schema_version`, `consumer_name`, `IN_FLIGHT`,
  `next_attempt_at`, `published_at`; claim sequence đầu tiên chưa SENT và CAS lease
  còn hạn. Đây là thiết kế, chưa tạo migration/module hoặc chạy PostgreSQL/Kafka.
- JSON Schema dùng library `jsonschema`, không viết validator subset. Pin dependencies
  đang cài, gồm `rfc3339-validator` vì date-time format checker cần dependency này.
  CI dùng virtualenv, không cài vào Python hệ thống.

## Deliverable và kiểm tra local

[Contracts README](../../contracts/README.md) mô tả envelope Draft 2020-12, fixtures,
tooling và giới hạn. Payload vẫn chỉ là object, chưa phải schema từng event.

| Lệnh | Kết quả trong phiên này |
|---|---|
| `python -B -m unittest discover -s tests/contracts -p 'test_*.py' -v` trước schema | RED: thiếu file schema |
| Cùng lệnh sau schema/fixtures | GREEN: 5 tests PASS, có 11 negative fixtures và kiểm bigint boundary |
| `python -B -m unittest discover -s scripts -p 'test_*.py' -v` | 11 PASS, 1 skip Windows Maven wrapper vì JDK không có trong PATH |
| `python -B scripts/check_docs.py` | PASS |
| `git diff --check` | PASS |

Lúc tác giả chạy, checkout chưa có node_modules, Node trong PATH là 24.14.1 (lệch R1)
và không có Java, nên chưa chạy full `validate-contracts.sh` hoặc Maven. Phần đó được
bổ sung ở review dưới đây. Không commit/push/PR hoặc deploy.

## Review và kiểm tra bổ sung — Claude Code, 2026-10-04

Review diff chưa commit trên `b62f963` (đọc schema/tests/fixtures, đối chiếu 03 §5.1 và 05).
Toolchain đúng R1: Temurin 21.0.12.1+1, Node 24.21.0 / npm 11.19.0; Python 3.11.9.

| Lệnh | Kết quả |
|---|---|
| `npm ci` | PASS, 89 packages |
| venv mới + `pip install -r tests/contracts/requirements.txt`, so `pip freeze` | Lần đầu thiếu pin `typing_extensions` (referencing kéo vào khi Python < 3.13); sau khi thêm pin, freeze trùng khớp requirements |
| `bash scripts/validate-contracts.sh` với venv đã pin đứng đầu PATH | PASS: Redocly `catalog.yaml` valid; envelope 5/5 (`sys.prefix` là venv, jsonschema 4.26.0) |
| Cùng script với venv trống | Exit 1, in `Missing contract tooling: python3 -m pip install -r tests/contracts/requirements.txt` |
| `python3 -B -m unittest discover -s scripts -p 'test_*.py'` có JDK | PASS 12/12 |
| `python3 -B scripts/check_docs.py`; `git diff --check` | PASS 31 Markdown files; PASS |

Lưu ý: trong Git Bash trên Windows, đường dẫn venv dạng `C:/...` trong `PATH` bị tách ở
dấu `:` và lặng lẽ rơi về Python hệ thống; dùng `cygpath -u` (`/c/...`). Python hệ thống
máy này có sẵn `jsonschema`, nên lần chạy đầu của review (trước khi phát hiện) không phải
bằng chứng của venv đã pin; bảng trên là lần chạy lại đúng.

Sửa theo review:

1. Pin `typing_extensions==4.16.0` trong `tests/contracts/requirements.txt`.
2. `validate-contracts.sh` dừng rõ ràng khi thiếu `jsonschema`/`rfc3339_validator`;
   cập nhật bước cài requirements ở [template S1](s1-local-template.md) và
   [handoff S1](../superpowers/plans/2026-10-02-s1-local-machine-handoff.md).
3. PLT-01 parent giữ **In progress** (SEO SPA FE/PO, O02 core ngoài S1); chỉ phần S1
   được chấp nhận ([10](../delivery/10_backlog.md), [docs index](../README.md)).

Chưa có CI remote cho diff này; chưa chạy Maven vì diff không đổi Java. Review của agent
không thay producer/consumer review của parent PLT-02.

CI sau commit `ed4f8f5` (PR #2, đã merge vào `main`):
[Application CI 37149713261](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/37149713261)
và [Documentation CI 37149496139](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/37149496139)
PASS; bước Contract validation cài đủ 8 package đã pin vào venv và chạy envelope 5/5.

## PLT-02 contract core — Claude Code, TDD, 2026-10-04

Theo `superpowers:test-driven-development`: mỗi hành vi có test RED (đúng lý do) trước khi
thêm schema, rồi GREEN; mutation check cho các ràng buộc dễ mất. Toolchain như trên,
venv đã pin (`sys.prefix` kiểm tra mỗi lần chạy).

| Chu trình | RED (lý do đã xác nhận) | GREEN |
|---|---|---|
| Registry payload | Thiếu `contracts/events/registry.json` | `common`, `catalog-events` (CATALOG_CHANGED), registry; `event_type` lạ bị từ chối |
| Tiền VND / ORDER_CREATED | `ORDER_CREATED` chưa đăng ký; 11 negative cases fail | `order-events`; tiền integer 0..bigint, `VND`, method enum, items ≥ 1, user_id nullable UUID |
| Order lifecycle | CONFIRMED/PAID/CANCELLED chưa đăng ký; CANCELLED thiếu reason | `OrderLifecycle` + `unevaluatedProperties`; case camelCase đổi validator mong đợi sang `unevaluatedProperties` (vẫn từ chối) |
| Event Phase 1A | USER/VARIANT/INVENTORY chưa đăng ký; 7 negative cases | `user-events`, VARIANT_CREATED, `stock-events` |
| OpenAPI common | Thiếu `contracts/openapi/common.yaml` | `common.yaml` theo 03 §1.2/1.3; 9 tests |

Mutation check (sửa tạm rồi khôi phục): bỏ `unevaluatedProperties` ở `OrderCancelled`;
fixture `occurred_at` +07:00; `minimum: "6"` sai kiểu trong schema; example 400 của
catalog đổi code `OOPS` — đều làm test/lint FAIL như mong đợi.

Refactor: `catalog.yaml` dùng `Limit`/`Metadata`/`CorrelationId`/`ApiError` của
`common.yaml` (400 thu hẹp `VALIDATION_ERROR`; khớp record `ApiError` của catalog-service).
`validate-contracts.sh` chỉ lint API entrypoint (common lint qua `$ref`, tránh
`no-empty-servers`/unused-components giả) và kiểm thêm `yaml` trong tooling.

| Lệnh | Kết quả |
|---|---|
| `bash scripts/validate-contracts.sh` (venv đã pin) | PASS: Redocly catalog (resolve common), 19 contract tests |
| Python script tests (có JDK) / docs check / `git diff --check` | PASS 12/12 / PASS 31 Markdown files / PASS |

Không đổi Java/React; không chạy Maven/Playwright cho thay đổi chỉ-contract. Chưa có CI
remote cho thay đổi này.

## PLT-02 event routing — Claude Code, TDD, 2026-10-04

Khoảng trống: 03 §5.1/5.2 quy định topic, partition key và aggregate_id cho từng event nhưng
registry chỉ ánh xạ schema; outbox PLT-03 (`topic`, `partition_key`) cần đúng các giá trị này.

| Chu trình | RED (lý do đã xác nhận) | GREEN |
|---|---|---|
| Topic/key khớp 03 §5.2 | 9 failures: entry là string, chưa có topic/key (lần đầu là TypeError, sửa test thành assertion trước khi viết code) | `registry.json` dạng `{schema, topic, partition_key}`; loader payload đọc `schema` |
| aggregate_id theo 03 §5.1 | 9 failures: registry thiếu `aggregate_id` | thêm `aggregate_id` (user_id, product_id, sku, order_id) |
| Partition key bắt buộc | Pass ngay (schema đã bắt buộc) — test guard, chứng minh bằng mutation | — |

Mutation check (sửa tạm rồi khôi phục): topic `orders.events`; bỏ `order_no` khỏi `required`
của order lifecycle; `aggregate_id` fixture bằng `order_no` — đều FAIL như mong đợi.

| Lệnh (Temurin 21.0.12.1+1, Node 24.21.0/npm 11.19.0, venv đã pin, freeze khớp requirements) | Kết quả |
|---|---|
| `bash scripts/validate-contracts.sh` | PASS: Redocly catalog, 22 contract tests |
| `python3 -B -m unittest discover -s scripts -p 'test_*.py'` | PASS 12/12 |
| `python3 -B scripts/check_docs.py`; `git diff --check` | PASS 31 Markdown files; PASS |

Không đổi Java/React nên không chạy Maven/Playwright. Mock API và Mermaid renderer chưa
thêm: 17 §PLT-02 hoãn chọn tới khi có consumer/nhu cầu thật (FE mock khi WEB-01 bắt đầu).
Producer/consumer review vẫn cần người ký trong contracts README.

## Tiếp theo

Hoàn thiện OpenAPI core, schemas payload/VND, fixtures/mock và review PLT-02; sau đó
triển khai outbox/inbox/idempotency/background lease cùng tests PostgreSQL/Kafka thật
của PLT-03. Không đánh dấu parent Done hoặc G0 accepted từ test envelope.
