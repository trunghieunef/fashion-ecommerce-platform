# Platform messaging

`TASK:INV-01` · `REQ:INV-01`, `REQ:INV-08`. Thư viện Spring Kafka dùng chung,
không phải service. Phụ thuộc `platform-durability`; không đưa Kafka vào main scope
của durability và không chia sẻ database giữa service.

## Sử dụng

Service cung cấp `JdbcClient`, `OutboxRepository`, `MeterRegistry` (Actuator) và bảng
durability trong database riêng. Boot tự nạp `MessagingAutoConfiguration`.
`fashion.messaging.relay.enabled=true` tạo relay: batch100, lease30s, publish đồng bộ
chờ broker ACK rồi mới cho `OutboxRepository.relay` mark SENT. Claim/publish ngoài
business transaction, không giữ row lock trong lúc gọi Kafka. Kafka lỗi giữ event
chưa SENT tới khi lease hết hạn; crash sau publish có thể gửi trùng cùng event_id.
Scheduling và bean OutboxRelay chỉ bật trong cấu hình con khi relay.enabled=true;
tắt relay không kích hoạt scheduler của MediaJobs hay component khác. Catalog muốn
tắt mọi scheduled method phải đồng thời đặt media.jobs-enabled=false.

Consumer gọi `new InboxListenerSupport(inbox, mapper, consumerName).handle(record,
ack, handlers)` trong listener. Handler ghi DB trong transaction do InboxGuard sở hữu;
ack sau commit, duplicate event_id không chạy lại effect. Event type không có handler
được ack mà không ghi inbox, debug log chỉ event_id (kể cả loại chưa đăng ký; không có
registry gate ở consumer). Envelope hỏng vào DLT; loại có handler sai version/payload
phải ném PermanentEventException. Quy tắc DLT chỉ áp dụng hai trường hợp này.
Handler dùng message
chung cho dữ liệu lỗi vĩnh viễn; không đưa payload vào exception/log.

| Config | Mặc định |
|---|---|
| `spring.kafka.bootstrap-servers` | `localhost:19092` |
| `fashion.messaging.relay.enabled` | không bật nếu thiếu |
| `fashion.messaging.relay.interval` | `500ms` |
| `fashion.messaging.relay.send-timeout` | `5s` |

Relay producer String/String dùng acks=all, idempotence=true. Consumer String/byte[]
dùng auto-commit=false, earliest và AckMode.MANUAL. DLT producer riêng String/byte[]
giữ nguyên bytes/headers, gửi tới `<topic>.DLT` cùng partition và chờ ACK; gửi DLT lỗi
không được coi đã recover. Topic/DLT phải tạo trước, không dựa auto-create.
Lỗi tạm retry 1s,2s,... trần30s, không giới hạn; PermanentEventException đi DLT.
Không log payload/CSV/token; logging producer mặc định có thể lộ payload nên tắt.

## Quan sát và health

Trace (PLT-05): `OutboxRelay(outbox, kafka, timeout, tracer, propagator)` mở span PRODUCER
`outbox publish <event_type>` với parent là `OutboxMessage.traceParent()` và đặt header
`traceparent` của span đó; không có tracer/traceParent thì giữ header cũ dựng từ `correlation_id`.
Listener container factory bật observation. `TraceParents.current(tracer)` là supplier cho
`OutboxRepository(jdbc, supplier)`.

MeterBinder đăng ký `outbox_pending` (status khác SENT),
`outbox_oldest_pending_age_seconds`; auto-wired relay đếm
`outbox_relay_failures_total`, recoverer đếm `messaging_dlt_total{topic}` sau ACK DLT.
Health indicator `kafka` kiểm describeCluster với timeout2s. Service phải giữ readiness
chỉ các dependency đã chốt (DB); không thêm kafka vào readiness vì outbox vẫn nhận intent.
Kafka down có thể làm overall health DOWN mà readiness vẫn UP.

## Test và giới hạn

Từ repo root, Java21/Maven wrapper/Docker Desktop:

```bash
./mvnw -B -pl services/platform-messaging -am test
```

Tests dùng PostgreSQL `postgres:17.11`, Kafka `apache/kafka:4.1.2` thật qua
Testcontainers và DDL durability, không H2/mock broker. Retry lease trong test dùng
SQL cho lease hết hạn; production vẫn30s. Ack observer đọc DB bằng connection độc lập
để bắt ack trước commit. DLT test kiểm cả bytes không phải UTF-8 và header event_id
có giá trị null: log dùng unknown, record hợp lệ kế tiếp cùng partition vẫn được xử lý.

At-least-once, không Kafka transaction, replay DLT tự động, schema registry server
hoặc SASL/TLS local. Staging authentication thuộc PLT-04; không triển khai ở đây.
