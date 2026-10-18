# PLT-05 (local) — trace liền mạch qua Kafka, xem trong Grafana

`TASK:PLT-05` phần local · `REQ:NFR-01/03/04` · dependency PLT-03 (Done local). Trạng thái: **đã duyệt**
2026-10-17; plan [PLT-05 Kafka trace](../plans/2026-10-17-plt-05-kafka-trace.md). Chủ dự án mô tả kết quả mong muốn và giao agent tự chốt phần còn
lại; các lựa chọn của agent ghi ở §8. Không thay acceptance staging của PLT-05 (cần PLT-04).

## 1. Mục tiêu

Một thao tác admin tạo variant qua Gateway cho ra **một trace** trong Grafana (Tempo) đi liền mạch:

Gateway → catalog HTTP (ghi DB + outbox) → catalog relay publish `VARIANT_CREATED` → inventory consume,
đăng ký SKU, ghi outbox → inventory relay publish `INVENTORY_UPDATED` → catalog consume, ghi tồn hiển thị.

Chạy được bằng `scripts/local-up.sh`; smoke chứng minh trace có đủ span của các service trên.

Không làm: staging/Tempo cluster, dashboard/alert, Loki/log tập trung, metric mới, tracing cho
user-service ngoài việc xuất span HTTP sẵn có, sampling theo route.

Anti-goals: trace làm chậm/hỏng request khi Tempo không chạy; secret/PII trong span attribute; đổi
envelope/contract event; đổi thứ tự hoặc độ bền của outbox.

## 2. Hiện trạng

- 4 service + Gateway đã có Micrometer Tracing (bridge OTel) nhưng **không exporter** nên không trace nào
  rời process; sampling mặc định Boot (0.1).
- `OutboxRelay` đã thêm header `traceparent` dựng từ `correlation_id` (trace id) với **span id ngẫu
  nhiên** không tồn tại → Tempo thấy span mồ côi. Relay chạy trên thread scheduler, không có context.
- Listener Kafka (`InboxListenerSupport`) không bật observation → consumer không nối trace.

## 3. Lưu ngữ cảnh trace trong outbox

- Cột mới nullable `trace_parent varchar(55)` trên `outbox_events` (W3C `traceparent`
  `00-<32 hex>-<16 hex>-<2 hex>`). Migration append-only ở **mọi** service có bảng này: catalog V009,
  inventory V003, user-service V005; `platform-durability/db/platform/durability.sql` đồng bộ.
- `OutboxRepository` nhận thêm một `Supplier<String>` trả `traceparent` của span hiện tại (null khi
  không có) và ghi vào cột khi `append`. Constructor cũ `OutboxRepository(JdbcClient)` giữ nguyên
  (supplier trả null) để không phá caller/test; service dùng constructor mới.
- Giá trị supplier lấy từ `Tracer.currentSpan()` (trace id, span id, sampled flag). Giá trị không đúng
  định dạng W3C → ghi null. `correlation_id` của envelope **không đổi** (vẫn là trace id như hiện nay).

## 4. Relay và consumer

- Relay: khi gửi một message có `trace_parent`, mở span `outbox publish <event_type>` kind PRODUCER có
  parent là `trace_parent` đã lưu, gửi record trong scope span đó; header `traceparent` của record là
  span relay này (thay giá trị span ngẫu nhiên hiện tại). Không có `trace_parent` (row cũ) → giữ hành
  vi hiện tại. Lỗi tracing không được làm hỏng publish.
- Span relay có attribute `messaging.destination.name` (topic), `event_type`, `event_id`; không có
  payload, partition key hay dữ liệu khách hàng.
- Consumer: bật observation cho listener container factory của platform-messaging để mỗi record tạo span
  CONSUMER là con của `traceparent` trong header. Xử lý inbox, ghi DB và outbox mới chạy trong span này,
  nên `trace_parent` ghi ra là span consumer và chuỗi tiếp tục qua service kế tiếp.
- Record không có/hỏng `traceparent` → span consumer mới (trace riêng); không lỗi.

## 5. Xuất trace và hạ tầng local

- Mọi service + Gateway: exporter OTLP HTTP khi đặt `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`
  (Compose đặt `http://tempo:4318/v1/traces`; không đặt = không xuất). Sửa khi hiện thực: Boot 4 coi
  endpoint rỗng là đã cấu hình nên yaml không khai báo mặc định. Sampling local 1.0 qua biến
  `TRACING_SAMPLING_PROBABILITY` (mặc định 1.0 ở compose, giữ mặc định Boot khi không đặt).
- Export bất đồng bộ (batch); Tempo down → span bị bỏ, request không chậm/không lỗi. Readiness không
  phụ thuộc Tempo.
- Compose thêm `tempo` (Tempo **3.0.3**, monolithic, lưu local trong volume, retention ngắn) và `grafana`
  (Grafana OSS **13.2.2**, datasource Tempo provision bằng file trong Git, đăng nhập anonymous viewer chỉ
  ở local). Image pin digest như các image khác. Chỉ publish loopback: Grafana `127.0.0.1:3000`, Tempo API
  `127.0.0.1:3200`; OTLP 4318 chỉ trong mạng compose.
- `local-up` không reset volume; container mới khởi động không chặn service (service không `depends_on`
  Tempo healthy).

## 6. Kiểm thử và acceptance

| Nhóm | Case |
|---|---|
| Outbox | Append trong span → `trace_parent` đúng trace/span hiện tại; ngoài span → null; giá trị sai định dạng → null |
| Relay | Row có `trace_parent` → record header `traceparent` cùng trace id, parent span khác span gốc và là span relay đã kết thúc; row không có → hành vi cũ |
| Consumer | Listener nhận record có `traceparent` → outbox do handler ghi có cùng trace id; header hỏng → vẫn xử lý, trace mới |
| Không xuất | Exporter trống / Tempo down → request và relay vẫn chạy (test hiện có xanh) |
| E2E smoke | Sau bước tạo variant: lấy `trace_id` từ response admin, poll Tempo API (`/api/v2/traces/<id>`) tối đa 30 s, thấy span của `gateway`, `catalog-service`, `inventory-service` và span consumer ở catalog cho `INVENTORY_UPDATED` |

Integration dùng PostgreSQL/Kafka thật (Testcontainers) và tracer in-memory của Micrometer/OTel test
(không cần Tempo trong test Java). Mutation: bỏ ghi `trace_parent`; relay bỏ parent; tắt observation
listener — mỗi cái phải bị test bắt. Full verify, contract, smoke, scan-secrets.

PLT-05 vẫn **In progress** sau việc này: còn gauge outbox cũ nhất, dashboard/alert và staging.

## 7. Tài liệu đồng bộ

04 (observability), 05 (cột `trace_parent`), 06 §1.3 (relay/consumer), 08 (quyết định §8), 10 (PLT-05),
12 §1 (Grafana/Tempo local, cách xem trace), 13 (attribute không chứa PII), 17 (Tempo/Grafana đã dùng
local), README platform-messaging/durability và các service, evidence/handoff.

## 8. Quyết định của agent (cần duyệt)

1. Lưu `traceparent` vào cột outbox thay vì dựng lại từ `correlation_id` — để parent/child chính xác.
2. Supplier truyền vào `OutboxRepository` (platform-durability không phụ thuộc thư viện tracing).
3. Observation của Spring Kafka cho consumer; relay tự mở span producer có parent từ outbox.
4. OTLP HTTP trực tiếp từ service tới Tempo, không thêm OTel Collector (17: không pin collector chưa cần).
5. Sampling 1.0 chỉ ở local qua biến môi trường.
6. Grafana anonymous viewer, chỉ loopback, chỉ local.
