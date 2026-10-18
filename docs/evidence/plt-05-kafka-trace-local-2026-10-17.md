# PLT-05 (local) — trace qua Kafka trong Grafana, evidence 2026-10-17/18

`TASK:PLT-05` phần local · `REQ:NFR-01/03/04` · dependency PLT-03.
[Spec đã duyệt](../superpowers/specs/2026-10-17-plt-05-kafka-trace-design.md),
[plan](../superpowers/plans/2026-10-17-plt-05-kafka-trace.md).

Trạng thái: merge vào `main` qua PR #24 (`2c9b032`, 2026-10-18); CI PR trên `ed91ddc` PASS (s1-local run 39095806625 gồm smoke trace, docs-check). PLT-05 vẫn **In progress**: còn
dashboard/alert và trace trên staging (cần PLT-04). Gauge `outbox_oldest_pending_age_seconds` đã có
từ PLT-03.

## Môi trường

Windows 11, Git Bash, Docker Desktop 28.5.2; Testcontainers PostgreSQL 17.11, Kafka 4.1.2; image
`grafana/tempo:3.0.3@sha256:0296560a…` và `grafana/grafana:13.2.2@sha256:ac461fb3…` (bản OSS 13.x
dùng tên `grafana/grafana`; tag `grafana-oss:13.2.2` không tồn tại). Pull Docker Hub qua proxy lỗi
`server gave HTTP response to HTTPS client` chập chờn, thử lại thì được.

## Commit theo task (base `f5b248f`)

| Task | Commit | Nội dung |
|---|---|---|
| 1 | `5c825ac` | `trace_parent` trên outbox (durability.sql, catalog V009, inventory V003, user V005); `OutboxRepository(jdbc, supplier)` |
| 2 | `8c4c364` | `OutboxRelay` mở span PRODUCER có parent từ outbox; `TraceParents.current(tracer)` |
| 3 | `d024581` | Observation cho listener Kafka; catalog/inventory ghi `trace_parent` |
| 4 | `fac50dd` | `opentelemetry-exporter-otlp`, sampling `TRACING_SAMPLING_PROBABILITY` |
| 5 | `91223f1` + commit docs | Tempo + Grafana trong compose, smoke kiểm trace, tài liệu |

## Kết quả

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B verify` (sau Task 4) | BUILD SUCCESS, **636 test**, 0 failure (durability 40, security 38, messaging 18, user 65, catalog 339, inventory 100, Gateway 36) |
| `bash scripts/local-up.sh` | exit 0; Tempo `/ready` = ready; Grafana `/api/health` 13.2.2 |
| `bash scripts/smoke-local.sh` | exit 0; `PLT-05 Kafka trace PASS`, `CAT-02b cache invalidation PASS`, `S1-local smoke PASS` |
| Mutation 3/3 | bỏ ghi `trace_parent` → `OutboxRepositoryTest#appendStoresTraceParent`; relay không dùng parent → `OutboxRelayTraceIntegrationTest#publishSpanIsChildOfStoredTraceParent`; tắt observation listener → `VariantCreatedConsumerIntegrationTest#traceContinuesIntoOutbox` |

Trace mẫu trong Tempo (smoke, một `trace_id`), các span theo thời gian trong ~0,9 s:

| Service | Span | Kind |
|---|---|---|
| gateway | `http post` / `HTTP POST` | SERVER / CLIENT |
| catalog-service | `http post /admin/api/v1/catalog/products/{id}/variants` | SERVER |
| catalog-service | `outbox publish VARIANT_CREATED` | PRODUCER |
| inventory-service | `catalog.events process` | CONSUMER |
| inventory-service | `outbox publish INVENTORY_UPDATED` | PRODUCER |
| catalog-service | `inventory.events process` | CONSUMER |

Tempo dừng: smoke dừng đúng ở bước trace với `PLT-05 Kafka trace FAIL …`, các bước trước PASS.

## Review toàn nhánh và fix pass

Reviewer độc lập (f5b248f..ec4cb36): 0 Critical, 1 Important, 9 Minor. Đã sửa (test RED→GREEN):

| Finding | Sửa | Test |
|---|---|---|
| Boot tự tạo span cho mọi `@Scheduled` → sweep/relay/worker có `trace_parent`, ~5 trace nhiễu/giây | `management.observations.enable.tasks.scheduled=false` ở 4 ứng dụng | `ScheduledSweepTraceIntegrationTest`; Tempo không còn trace `tasks.scheduled.execution` |
| Span relay báo OK khi publish lỗi (nâng từ Minor) | `span.error(e)` rồi ném lại | `OutboxRelayTraceIntegrationTest#failedPublishMarksSpanAsError` |
| Comment pom ghi `OTEL_EXPORTER_OTLP_ENDPOINT` | sửa theo biến thật | — |

Sau fix: `./mvnw -B verify` **638 test** 0 failure; local-up + smoke PASS (`PLT-05 Kafka trace PASS`).
Minor để sau: inject lỗi sau `start()` để span chưa end; chưa có test relay cho hàng `-00`; hàng cũ
không `trace_parent` vẫn nhận header `-01` dựng tay (ép sample); Grafana vẫn nhận basic auth
`admin:admin` trên loopback; test traceparent hỏng chưa assert DLT trống; test republish ép PENDING bằng SQL.

## Sai khác so với plan (ruling)

- Endpoint không khai báo trong `application.yaml`: Boot 4 coi
  `management.opentelemetry.tracing.export.otlp.endpoint` rỗng là đã cấu hình và dừng khởi động
  (`Invalid endpoint`). Compose đặt `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` thay cho
  `OTEL_EXPORTER_OTLP_ENDPOINT` trong spec (spec đã sửa).
- Tempo 3 không có khối `compactor`; retention 24h qua `backend_worker.compaction.block_retention`.
- Grafana anonymous Viewer cần `GF_USERS_VIEWERS_CAN_EDIT=true` để dùng Explore; chỉ local, loopback.
- Test exporter in-memory tự viết thay `opentelemetry-sdk-testing` (không có trong repo Maven local).
- Smoke: `read` giữ CR do Python Windows in CRLF → trace id được lọc chỉ còn hex; thông báo lỗi in
  trace id dạng `%q` và phản hồi Tempo.

## Giới hạn

- Chỉ local; không Collector, không dashboard/alert, không Loki; staging cần quyết định riêng (PLT-04).
- user-service xuất span HTTP nhưng outbox của nó không có relay/Kafka nên không ghi `trace_parent`.
- Lần chạy đầu sau khi bật consumer group `catalog-service` mới, catalog đọc lại `inventory.events` cũ.
- Giữ nguyên 10 `.sh` đang `M` và `.gitattributes`; không push/PR.
