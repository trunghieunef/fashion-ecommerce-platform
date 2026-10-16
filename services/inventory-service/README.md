# Inventory service

`TASK:INV-01` · `REQ:INV-01`, `REQ:INV-08`, hỗ trợ `REQ:INV-06`.

Port 8083, PostgreSQL inventory là nguồn sự thật tồn kho. V001 tạo stock_items,
stock_transactions và ba bảng durability. Runtime chỉ SELECT/INSERT ledger;
stock_items SELECT/INSERT/UPDATE. Không tạo reservation/return trong INV-01.

`TASK:INV-02` Task 2 · `REQ:INV-02/03/06`, phụ thuộc INV-01 và PLT-02:
V002 thêm stock_reservations/stock_reservation_items, UNIQUE order_id, CHECK trạng thái/
timestamp và quantity 1..99, FK SKU, partial index ACTIVE theo expires_at. Runtime không
DELETE/TRUNCATE reservation/items; terminal được giữ làm tombstone. Task này chỉ thêm
schema; chưa nghiệm thu toàn bộ INV-02.
Task 3 thêm `ReservationSnapshot` canonical/hash và `ReservationRequests` parse reserve/commit/release:
body đóng, normalize/gộp/sắp xếp SKU, đối chiếu SHA-256 theo 03; lỗi 400 nêu field.
Parser chỉ kiểm hình dạng timestamp (UTC `Z`, năm 0001..9999 theo OpenAPI date-time,
tối đa mili giây), chưa kiểm deadline theo DB clock. Chưa có API hay job reservation.
Unit test parser (không cần Spring/Docker):

```bash
./mvnw -B -pl services/inventory-service -am test -Dtest=ReservationRequestsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Kiểm tra schema và regression INV-01 từ repo root:

```bash
./mvnw -B -pl services/inventory-service -am test -Dtest=ReservationSchemaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -pl services/inventory-service -am test -Dtest='Stock*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Chạy từ repo root với Java 21 và Docker đang hoạt động:

```bash
./mvnw -B -pl services/inventory-service -am test
bash scripts/local-up.sh
```

`local-up.sh` sinh và giữ hai password inventory trong `.env` ignored, chạy init
idempotent cho volume hiện có, không reset dữ liệu. JWT public key dùng cùng
`USER_JWT_PUBLIC_KEYS` qua Compose; internal caller keys cấu hình bằng
`INVENTORY_INTERNAL_CALLERS` (caller:base64-X.509), mặc định rỗng.

Datasource/flyway: `INVENTORY_DB_URL`, `INVENTORY_DB_PASSWORD`,
`INVENTORY_MIGRATION_DB_URL`, `INVENTORY_MIGRATION_DB_PASSWORD`; role mặc định
inventory_runtime/inventory_migration. Host URL mặc định localhost:5432/inventory.
`INVENTORY_JWT_PUBLIC_KEYS` bắt buộc. `SPRING_KAFKA_BOOTSTRAP_SERVERS` mặc định
localhost:19092, Compose kafka:9092. `INVENTORY_RELAY_ENABLED` mặc định true.

Readiness chỉ readinessState/db; health kafka độc lập, broker outage không chặn
business transaction. Relay gửi outbox ngoài transaction, ACK trước SENT, retry lease
30 giây. Metrics outbox_pending/outbox_oldest_pending_age_seconds,
outbox_relay_failures_total/messaging_dlt_total; ECS logs redact PII, không payload/CSV.

Topics đã init: catalog.events, catalog.events.DLT, inventory.events (3 partitions,
RF 1); inventory group inventory-service, earliest khi chưa có committed offset.
Local-only Kafka single node PLAINTEXT; chưa có SASL/TLS/HA hay công cụ replay DLT.
Task 4 đăng ký SKU từ VARIANT_CREATED qua inbox transaction; cùng event_id bỏ qua,
event mới cùng variant/SKU không reset tồn. Payload lỗi/mâu thuẫn sang catalog.events.DLT;
CATALOG_CHANGED được ack và bỏ qua. Snapshot INVENTORY_UPDATED phát khi đăng ký,
topic/key lấy từ registry.json được đóng gói trong jar.

Admin `/admin/api/v1/inventory`: GET `/skus` (sku_prefix, limit 1..100 mặc định 20,
cursor base64url SKU), GET `/skus/{sku}/transactions` (mới nhất trước, cursor t/id)
cần inventory.read. POST `/adjust` cần inventory.adjust và Idempotency-Key,
body đóng `{sku,delta,reason}`; delta nguyên khác 0, reason strip 1..500 ký tự.
SKU chuẩn hóa ASCII uppercase trước hash/lưu; không thay identity catalog cũ.
Điều chỉnh dưới reserved trả 409 ON_HAND_BELOW_RESERVED; tràn int 400 field delta.
Ledger operation_key `user:<actor UUID>:<operation>:<key>` tách actor/adjust/import;
replay cùng body chỉ trả data/status đã lưu với metadata mới.
AdminCommands theo khuôn catalog; cập nhật/ledger/outbox chung transaction khóa SKU.
POST `/import` cần inventory.adjust/Idempotency-Key, multipart file + reason;
CSV UTF-8 (BOM/CRLF được nhận), header sku,quantity, 1..1000 dòng,
quantity 1..1000000, file tối đa 128 KiB, multipart tối đa 160 KiB.
Parser gom lỗi rows[n].sku/quantity (header là dòng 1); SKU trùng/không có hoặc
stock tràn int từ chối cả file. Import cộng INBOUND, không ghi đè on_hand.
Hash idempotency bao gồm SHA-256 toàn bộ bytes file và reason đã strip, theo
AdminCommands, serialize record ImportRequest(fileSha256, reason) theo thứ tự field cố định;
không dùng iteration order của Map.of. Cùng nội dung parse nhưng bytes khác vẫn là body khác.
Mọi SKU được khóa theo thứ tự tăng, kiểm toàn bộ trước khi update/ledger/outbox.
GET `/internal/api/v1/inventory/skus/{sku}` trả sku/variant_id/available/version/observed_at,
Cache-Control no-store cả lỗi; observed_at là updated_at cùng snapshot stock.
Service token audience inventory-service, caller catalog-service/cart-service với key
allowlist; kiểm token/caller trước chuẩn hóa SKU. Gateway chỉ route admin inventory,
không route internal; local trực tiếp 127.0.0.1:8083, staging dùng private service network.

Smoke INV-01 tạo variant, đợi SKU tối đa10s, adjust +5 rồi admin GET xác nhận available5
và đọc Kafka snapshot. Internal chỉ có integration test; smoke chưa kiểm cấu hình
INVENTORY_INTERNAL_CALLERS trong Compose. CART-01 sẽ kiểm với caller thật đầu tiên;
smoke INV-01 không tạo private key hoặc service token.

Đọc DLT (từ repo root; Git Bash cần tắt path conversion):

```bash
MSYS_NO_PATHCONV=1 docker compose --env-file infra/local/.env -f infra/local/compose.yaml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic catalog.events.DLT --from-beginning
```

Không replay tự động; điều tra event_id và nguyên nhân, không chỉnh ledger/counter bằng tay.
Smoke local dùng token synthetic riêng chỉ inventory.read/inventory.adjust, khóa đọc từ .env,
TTL 300 giây, token trong memory, không chứng minh login → token OPS. SKU có suffix UUID;
không có DELETE, fixtures/ledger/outbox tích lũy trên volume local. Không reset volume khi phát triển.
