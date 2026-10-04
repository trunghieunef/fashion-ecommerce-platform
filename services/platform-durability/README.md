# platform-durability

`TASK:PLT-03` · REQ: ORD-02, PAY-07 · dependency: PLT-01, PLT-02 (contract core review 2026-10-04).

Thư viện Java dùng chung cho các service về độ bền dữ liệu, theo 04 §2, 05 §3/§15 và 06 §1.2/1.3. Đây không phải service chạy riêng.
Thư viện dùng `DataSource`/transaction của chính service gọi và chỉ phụ thuộc `spring-jdbc`. Trong main
không có Kafka client, HTTP client hay domain entity.

| Class | Việc làm |
|---|---|
| `outbox.OutboxRepository` | `append` trong transaction nghiệp vụ, tự cấp `aggregate_sequence`. `claim` dùng lease, `SKIP LOCKED` và chỉ lấy sequence nhỏ nhất chưa SENT của mỗi aggregate. `markSent` CAS theo lease token và lease còn hạn. `relay` = claim → publish → markSent |
| `inbox.InboxGuard` | `applyOnce(consumer, eventId, effect)`: ghi `processed_events` và effect trong cùng transaction; consumer chỉ commit offset sau khi hàm này trả về |
| `idempotency.IdempotencyStore` | `begin`/`finish` theo `(actor_key, operation, key)` và `request_hash`. `CONFLICT` → service trả 409, `IN_PROGRESS` → 202, `COMPLETED` → trả lại response đã lưu |
| `work.BackgroundTaskRepository` | `background_tasks`: `enqueue` trong transaction nghiệp vụ (unique theo kind + business key), `claimDue`, `complete`/`fail` CAS theo token; hết số lần retry thì chuyển `MANUAL` |
| `work.LeaseRepository` | Lease và CAS trên bảng work của service (ví dụ `order_sagas`): worker có lease đã hết hạn không ghi được kết quả |

Envelope Kafka được dựng bằng SQL theo 03 §5.1: `id` → `event_id`, `schema_version` → `version`,
`created_at` (UTC `Z`) → `occurred_at`. Topic và partition key lấy từ `contracts/events/registry.json`.

## Migration

`src/main/resources/db/platform/durability.sql` là DDL tham chiếu, **không phải migration**.
Service nào cần thì chép phần bảng mình dùng vào migration Flyway kế tiếp **trong database riêng**
của service đó, không dùng chung DB hay lịch sử Flyway. Migration đã deploy là append-only.
So với 05, DDL có thêm `outbox_events.correlation_id` (envelope bắt buộc trường này) và các CHECK ràng buộc trạng thái với lease.

## Test

Cần Docker cho Testcontainers (`postgres:17.11`, `apache/kafka:4.1.2` KRaft). Chạy từ repo root:

```bash
./mvnw -pl services/platform-durability test
./mvnw -pl services/platform-durability -Dtest=OutboxKafkaIntegrationTest test
```

Surefire đặt `-Duser.timezone=UTC` (xem lưu ý timezone Windows ở 12 §1). Mọi thời điểm hết hạn
dùng đồng hồ DB `now()`; test giả lập lease hết hạn bằng SQL, không `sleep`.

## Giới hạn

- At-least-once: crash sau publish và trước `markSent` sẽ gửi lại cùng `event_id`/sequence;
  consumer phải dùng `InboxGuard` và business key.
- Relay chỉ retry khi lease hết hạn; chưa có backoff qua `next_attempt_at`, chưa có
  worker/scheduler. Service tự gọi `relay` và tự viết publisher Kafka (`acks=all`, `send().get()`).
- Consumer tiền viết bằng Java phải tắt `ACCEPT_FLOAT_AS_INT` của Jackson (xem contracts README).
- Chưa có metrics/log correlation (PLT-05), chưa dọn `processed_events`/`idempotency_requests`
  theo retention, chưa có `audit_logs` (thuộc task admin/SEC).
