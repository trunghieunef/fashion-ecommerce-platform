# Inventory service

`TASK:INV-01` · `REQ:INV-01`, `REQ:INV-08`, hỗ trợ `REQ:INV-06`.

Port 8083, PostgreSQL inventory là nguồn sự thật tồn kho. V001 tạo stock_items,
stock_transactions và ba bảng durability. Runtime chỉ SELECT/INSERT ledger;
stock_items SELECT/INSERT/UPDATE. Không tạo reservation/return trong INV-01.

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
topic/key lấy từ registry.json được đóng gói trong jar. Admin/internal stock API ở task sau.
