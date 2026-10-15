# INV-01 — stock authority, ledger bất biến và luồng Kafka đầu tiên

**Trạng thái: đã duyệt ngày 2026-10-15.** Plan: [INV-01](../plans/2026-10-15-inv-01-stock-ledger.md). Khi lập plan sửa: health `kafka` không vào readiness; internal callers theo mẫu catalog (map caller→public key + danh sách cố định). Làm trên `dev`; chỉ push/mở PR khi
chủ dự án yêu cầu.

`TASK:INV-01` · `REQ:INV-01`, `REQ:INV-08`, hỗ trợ `REQ:INV-06`.
Dependency: PLT-03, CAT-01, USR-02 (Done local 2026-10-11), SEC-01 phần service token (ADR-19).
Base là `dev/1f8a02e` (sau PR #19). Nguồn chủ quản:
[03 Interfaces](../../design/03_interfaces.md) §4 và §5,
[04 Architecture](../../design/04_architecture.md),
[05 Database](../../design/05_database_design.md) §8,
[13 Operations](../../operations/13_operations_security.md) (permission `inventory.read`/`inventory.adjust`),
[17 Tech Stack](../../engineering/17_tech_stack.md) (Kafka 4.1.2 KRaft, Spring Kafka 4.0.7),
[plan Phase 1A](../plans/2026-09-19-phase-1a-commerce-core.md) Task 3.

## 1. Phạm vi và quyết định đã chốt

INV-01 tạo `inventory-service` làm nguồn sự thật tồn kho (PostgreSQL) với ledger chỉ ghi thêm,
và là **luồng Kafka thật đầu tiên** của hệ thống: chưa có broker trong compose, chưa service nào
chạy relay, chưa có consumer.

Quyết định của chủ dự án trong phiên:

- **Nối đầy đủ:** Kafka 4.1.2 KRaft single-node trong compose local; relay outbox ở catalog đẩy
  `VARIANT_CREATED` lên `catalog.events`; inventory consume để đăng ký SKU; smoke end-to-end.
- **Inventory cung cấp cả hai đầu ra:** event `INVENTORY_UPDATED`/`INVENTORY_RESTOCKED` qua outbox
  của inventory, và internal `GET /internal/api/v1/inventory/skus/{sku}` cho catalog/cart.
- **Import là phiếu nhập hàng** (`INBOUND`, quantity > 0 cộng vào on_hand); **adjust là delta có dấu**
  (`ADJUST`, reason bắt buộc). Không có chế độ kiểm kê ghi đè tuyệt đối.
- **Module dùng chung `platform-messaging` ở mức tối thiểu** vì 03 §5.2 đã định 7 producer và nhiều
  consumer cho Phase 1B/1C: relay, helper consumer (inbox + ack + retry/DLT) và envelope/header.
  Không phải framework: không schema registry server, Kafka Streams, Kafka transaction hay plugin.

Không thuộc INV-01: reserve/commit/release/expire (INV-02), return (INV-03), restock subscription
và fan-out (INV-04), low stock batch (INV-05), đồng bộ status SKU theo `CATALOG_CHANGED`, công cụ
replay DLT, SASL/TLS cho Kafka (PLT-04 staging), catalog consume `inventory.events` (CAT-02b),
UI admin stock (ADM-01).

## 2. Kiến trúc

| Thành phần | Vai trò |
|---|---|
| `services/platform-messaging` (mới) | `OutboxRelay`, `InboxListenerSupport`, envelope codec + Kafka headers, topic/partition key từ `contracts/events/registry.json`. Phụ thuộc Spring Kafka (BOM) và `platform-durability`. `platform-durability` giữ nguyên, không Kafka trong main scope. |
| `services/inventory-service` (mới) | Port `8083`. DB `inventory`, role `inventory_migration`/`inventory_runtime` qua `infra/local/postgres-init/03-inventory-db.sh` idempotent theo mẫu `02-user-db.sh`; `scripts/local-up.sh` chạy lại cho volume hiện có, không reset volume. |
| Kafka trong `infra/local/compose.yaml` | `apache/kafka:4.1.2` pin digest như PLT-03, KRaft combined single-node, chỉ loopback, không auth (local-only). Auto-create topic tắt; script init tạo `catalog.events`, `catalog.events.DLT`, `inventory.events`. |
| catalog-service | Bật `OutboxRelay`; event PENDING cũ (gồm `VARIANT_CREATED` từ CAT-01a) được gửi khi relay chạy. Không đổi API catalog. |
| Gateway | Route `/admin/api/v1/inventory/**` → inventory. `/internal/**` không có route public. |

Inventory không gọi HTTP sang catalog và không gọi HTTP/Kafka khi giữ row lock. PostgreSQL quyết định;
Kafka chỉ vận chuyển.

### 2.1 Đăng ký SKU

```
catalog: tạo variant → outbox VARIANT_CREATED (cùng TX, đã có từ CAT-01a)
→ OutboxRelay catalog → catalog.events (key product_id)
→ inventory listener: InboxGuard.applyOnce("inventory-service", event_id) {
     insert stock_items(sku, product_id, variant_id) on conflict (variant_id) do nothing
     nếu dòng mới: outbox INVENTORY_UPDATED (on_hand 0, reserved 0, available 0)
  } → commit DB → ack offset
```

- Cùng `event_id` → inbox bỏ qua. Event khác cùng `variant_id` và cùng SKU → không ghi, tồn giữ nguyên.
- Cùng `variant_id` khác SKU, hoặc SKU đã thuộc variant khác → lỗi vĩnh viễn (DLT, log ERROR).
  SKU catalog bất biến sau tạo (`PUT /variants` không sửa SKU) nên chỉ xảy ra khi dữ liệu hỏng.
- `CATALOG_CHANGED` và event type hợp lệ khác inventory không dùng → ack, không ghi inbox.

### 2.2 Import/adjust

```
POST import | adjust + Idempotency-Key → IdempotencyStore (hash request)
→ một TX inventory-db:
   khóa stock_items các SKU liên quan theo thứ tự sku tăng dần (FOR UPDATE)
   validate toàn bộ → lỗi: 400/404/409, không ghi gì
   update on_hand, version+1, updated_at
   ledger stock_transactions (INBOUND|ADJUST, operation_key = Idempotency-Key, reason, actor_id)
   outbox INVENTORY_UPDATED mỗi SKU; thêm INVENTORY_RESTOCKED khi available 0 → > 0
→ OutboxRelay inventory → inventory.events (key sku)
```

## 3. Schema `inventory` (V001)

DDL outbox/inbox/idempotency chép từ `platform-durability/.../durability.sql` theo mẫu catalog V002.

| Bảng | Cột | Ràng buộc |
|---|---|---|
| `stock_items` | `sku varchar(64) PK`, `product_id uuid`, `variant_id uuid`, `on_hand int = 0`, `reserved int = 0`, `min_stock int = 0`, `status varchar(32) = 'ACTIVE'`, `version bigint = 0`, `created_at`, `updated_at` | UNIQUE `variant_id`; CHECK `0 <= reserved AND reserved <= on_hand`, `min_stock >= 0`, status ACTIVE/INACTIVE |
| `stock_transactions` | `id uuid PK`, `sku FK stock_items`, `operation_key text`, `type varchar(16)`, `on_hand_delta int`, `reserved_delta int`, `order_id uuid?`, `reason text`, `actor_id uuid?`, `created_at` | UNIQUE `(operation_key, sku)`; type INBOUND/RESERVE/COMMIT/RELEASE/ADJUST/RETURN; index `(sku, created_at, id)` |

`inventory_runtime` chỉ có SELECT/INSERT trên `stock_transactions` (không UPDATE/DELETE): ledger bất
biến do DB bảo đảm. `available = on_hand - reserved`, không lưu cột. Không tạo bảng reservation,
return, subscription (INV-02/03/04).

## 4. API admin

Base `/admin/api/v1/inventory`, JWT người dùng, quyền kiểm tại service (không tin Gateway), envelope
và metadata/`X-Correlation-Id` như catalog.

| API | Quyền | Hành vi |
|---|---|---|
| `GET /skus?sku_prefix=&limit=&cursor=` | `inventory.read` | `sku, variant_id, product_id, on_hand, reserved, available, min_stock, status, version`; keyset theo `sku`; limit mặc định 20, 1..100 |
| `GET /skus/{sku}/transactions?limit=&cursor=` | `inventory.read` | Ledger mới nhất trước theo `(created_at, id)`; SKU không có → 404 |
| `POST /adjust` | `inventory.adjust` | Body `{sku, delta, reason}`, Idempotency-Key bắt buộc; trả stock sau chỉnh |
| `POST /import` | `inventory.adjust` | `multipart/form-data`: `file` (CSV), `reason`; Idempotency-Key bắt buộc; trả `{rows, total_quantity}` |

CSV: UTF-8, header đúng `sku,quantity`, 1..1.000 dòng dữ liệu, file <= 128 KB, không quote. SKU trim +
uppercase như catalog; quantity nguyên 1..1.000.000. Dòng sai định dạng, SKU lặp trong file hoặc SKU
chưa đăng ký → từ chối cả file với lỗi từng dòng `rows[<số dòng>].sku|quantity`.

| Trường hợp | Phản hồi |
|---|---|
| Field thiếu/sai, `delta = 0`, reason ngoài 1..500 ký tự, CSV lỗi | 400 `VALIDATION_ERROR` kèm field/dòng, không ghi |
| `on_hand` sau thay đổi vượt `int` | 400, field `delta` hoặc `rows[n].quantity` |
| Adjust SKU không tồn tại | 404 `NOT_FOUND` / `SKU_NOT_FOUND` |
| Adjust làm on_hand < reserved hoặc < 0 | 409 `CONFLICT` / `ON_HAND_BELOW_RESERVED`, tồn không đổi |
| Idempotency-Key trùng khác body | 409 `CONFLICT` theo `IdempotencyStore`; cùng body → replay response cũ |
| Thiếu JWT / thiếu quyền | 401 / 403 |

## 5. Internal API

`GET /internal/api/v1/inventory/skus/{sku}` → `{sku, variant_id, available, version, observed_at}`,
`Cache-Control: no-store`. Service token ADR-19 theo mẫu catalog: `fashion.inventory.internal-callers=${INVENTORY_INTERNAL_CALLERS:}`
là map caller→public key, controller chỉ cho `catalog-service` và `cart-service`. Auth trước validate: thiếu/sai token 401, caller không được phép 403,
SKU không tồn tại 404.

## 6. Event

| Event | Khi phát | Envelope |
|---|---|---|
| `INVENTORY_UPDATED` | Mọi thay đổi stock, kể cả đăng ký SKU | aggregate_id = sku, aggregate_version = `version`, partition key sku, payload `StockSnapshot` (`stock-events.schema.json`) |
| `INVENTORY_RESTOCKED` | Thêm khi available 0 → > 0 | Như trên |

Kafka headers: `event_id`, `event_type`, `traceparent`, `correlation_id`.

## 7. platform-messaging và xử lý lỗi

- **`OutboxRelay`:** `@Scheduled` 500 ms, batch 100, lease 30 s, gọi `OutboxRepository.relay(...)`;
  producer `acks=all`, idempotent, gửi đồng bộ có timeout; `markSent` chỉ sau ACK. Kafka down →
  PENDING, service vẫn nhận request. Crash sau publish trước `markSent` → gửi lại cùng `event_id`.
- **`InboxListenerSupport`:** group theo tên service, manual ack sau DB commit, effect trong
  `InboxGuard.applyOnce`.
  - Lỗi tạm thời (DB down, timeout): retry backoff tăng dần, trần 30 s, không giới hạn số lần.
  - Lỗi vĩnh viễn (JSON/envelope sai schema, `event_type`/`version` không hỗ trợ, dữ liệu mâu thuẫn):
    publish nguyên record + header sang `<topic>.DLT`, log ERROR kèm `event_id`.
    DLT dùng producer byte[] riêng, đợi ACK; gửi DLT lỗi phải retry record gốc, không bỏ message.
    Relay vẫn dùng producer String/String (chủ dự án duyệt làm rõ ngày 2026-10-15).
- **Envelope:** theo 03 §5.1; topic/key tra `contracts/events/registry.json`, không hardcode trong service.

## 8. Vận hành

- Health indicator `kafka` ở catalog và inventory, **không** nằm trong readiness: Kafka down không chặn nhận request vì outbox giữ event.
- Metric: `outbox_pending`, tuổi bản ghi PENDING cũ nhất, relay failures, số message DLT.
- Log JSON có redaction, không log payload/CSV.
- Runbook README: đọc DLT bằng `kafka-console-consumer`; không có công cụ replay trong INV-01.

## 9. Kiểm thử

PostgreSQL + Kafka thật qua Testcontainers; không mock broker.

| Phạm vi | Test |
|---|---|
| platform-messaging | SENT chỉ sau ACK; Kafka down → PENDING rồi gửi khi Kafka lên; lỗi vĩnh viễn → DLT; tạm thời → retry thành công; `traceparent` truyền qua |
| Đăng ký SKU | Trùng `event_id` không ghi; event mới cùng variant không reset `on_hand` đã adjust; cùng variant khác SKU → DLT; SKU mới phát đúng một UPDATED |
| Adjust/import | on_hand < reserved → 409 không đổi; CSV một dòng lỗi → ledger rỗng; SKU lặp/chưa đăng ký; 1.001 dòng; Idempotency replay và khác body; RESTOCKED khi 0 → > 0; hai adjust đồng thời không mất cập nhật; tràn `int` |
| Bảo mật | OPS thiếu quyền 401/403; `inventory.read` không adjust; internal sai caller/thiếu token; Gateway không public `/internal/**` |
| Contract | `contracts/openapi/inventory.yaml` và payload event khớp registry (`tests/contracts`) |
| Smoke local | Tạo variant catalog → SKU có ở inventory trong 10 s → adjust +5 → internal `available=5` → có `INVENTORY_UPDATED` trên `inventory.events` |
| Mutation (~6) | Bỏ `FOR UPDATE`; bỏ check reserved; `markSent` trước ACK; bỏ inbox; ack trước DB commit; bỏ check hash idempotency |

## 10. Tài liệu đồng bộ

03 (inventory admin/internal và event đã hiện thực), 05 §8, 08 (ADR `platform-messaging` + Kafka local),
12 (lệnh chạy/test), 17 (Spring Kafka đã dùng), README inventory/catalog/platform-messaging,
contracts README, backlog INV-01, evidence và handoff INV-01.
