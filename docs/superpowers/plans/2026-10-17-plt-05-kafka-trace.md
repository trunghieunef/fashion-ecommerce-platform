# PLT-05 (local) — trace qua Kafka trong Grafana Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Một thao tác admin tạo variant cho ra một trace liền mạch Gateway → catalog → Kafka → inventory → Kafka → catalog, xem được trong Grafana/Tempo local.

**Architecture:** Outbox lưu `traceparent` của span đang chạy khi append; relay mở span PRODUCER với parent đó và đưa `traceparent` của span relay vào header; listener Kafka bật observation để consumer nối trace và outbox mới ghi tiếp span consumer. Service xuất OTLP HTTP thẳng tới Tempo; Grafana provision datasource Tempo.

**Tech Stack:** Java 21, Spring Boot 4.0, Micrometer Tracing (bridge OTel, Boot BOM), OTLP exporter, Spring Kafka observation, PostgreSQL 17.11/Kafka 4.1.2 Testcontainers, Tempo 3.0.3, Grafana OSS 13.2.2.

**Spec:** [`docs/superpowers/specs/2026-10-17-plt-05-kafka-trace-design.md`](../specs/2026-10-17-plt-05-kafka-trace-design.md) (đã duyệt 2026-10-17).

## Global Constraints

- Cột `trace_parent varchar(55)` nullable trên `outbox_events`; migration append-only: catalog **V009**, inventory **V003**, user-service **V005**; `platform-durability/.../durability.sql` đồng bộ.
- Định dạng hợp lệ: `^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$`; khác → ghi null. `correlation_id` envelope không đổi.
- Constructor `OutboxRepository(JdbcClient)` giữ nguyên hành vi (trace_parent null).
- Span relay tên `outbox publish <event_type>`, kind PRODUCER, attribute `messaging.destination.name`, `event_type`, `event_id`; không payload/partition key/PII.
- Lỗi tracing không làm hỏng publish/consume; Tempo down không chậm/lỗi request; readiness không gồm Tempo.
- Exporter endpoint `${OTEL_EXPORTER_OTLP_ENDPOINT:}` (trống = không xuất); sampling `${TRACING_SAMPLING_PROBABILITY:0.1}`, compose đặt 1.0.
- Compose: `tempo` 3.0.3 monolithic, `grafana` 13.2.2, image pin digest, publish chỉ `127.0.0.1:3000` (Grafana) và `127.0.0.1:3200` (Tempo API); 4318 chỉ trong mạng compose; Grafana anonymous viewer chỉ local.
- Service không `depends_on` Tempo/Grafana healthy. Không thêm OTel Collector.

## Review Focus

1. Relay gửi lại (re-publish sau lease hết hạn) cùng row → mỗi lần là span relay mới cùng trace, không lỗi. Test: `OutboxRelayTraceIntegrationTest.republishKeepsTraceId` (Task 2).
2. `traceparent` header hỏng/không phải W3C trên record → consumer vẫn xử lý, ACK, không DLT. Test: `VariantCreatedConsumerIntegrationTest.malformedTraceparentIsStillConsumed` (Task 3).
3. Append ngoài request (scheduler, sweep INV-02) không có span → `trace_parent` null, relay giữ hành vi cũ. Test: `OutboxRepositoryTest.appendWithoutSpanStoresNull` (Task 1).
4. Sampling flag `-00` (không sample) được lưu và truyền nguyên, không ép sample. Test: `OutboxRepositoryTest.unsampledTraceParentIsKept` (Task 1).
5. Tempo không chạy khi `local-up`/smoke → các bước smoke cũ vẫn PASS; chỉ bước trace báo lỗi rõ. Kiểm thủ công trong Task 5 Step 4 (dừng tempo, chạy smoke phần cũ).

---

### Task 1: `trace_parent` trong outbox

**Files:**
- Modify: `services/platform-durability/src/main/java/vn/fashion/platform/outbox/OutboxRepository.java`, `OutboxMessage.java`, `src/main/resources/db/platform/durability.sql`
- Create: `services/catalog-service/src/main/resources/db/migration/V009__outbox_trace_parent.sql`, `services/inventory-service/.../V003__outbox_trace_parent.sql`, `services/user-service/.../V005__outbox_trace_parent.sql` (mỗi file: `ALTER TABLE outbox_events ADD COLUMN trace_parent varchar(55);`)
- Test: `services/platform-durability/src/test/java/vn/fashion/platform/outbox/OutboxRepositoryTest.java`

**Interfaces:**
- Produces: `public OutboxRepository(JdbcClient jdbc, Supplier<String> traceParent)`; `OutboxRepository(JdbcClient)` = `this(jdbc, () -> null)`.
- Produces: `OutboxMessage` thêm thành phần cuối `String traceParent` (null khi không có); `claim` select `o.trace_parent`.

- [ ] **Step 1: Tests** — `appendStoresTraceParent` (supplier trả `00-` + 32 hex + `-` + 16 hex + `-01` → claim trả đúng chuỗi); `invalidTraceParentStoredAsNull` (`"garbage"`, chuỗi 56 ký tự, chữ hoa hex → null); `appendWithoutSpanStoresNull` (constructor cũ → null); `unsampledTraceParentIsKept` (`...-00` giữ nguyên).
- [ ] **Step 2:** `./mvnw -q -pl services/platform-durability -Dtest=OutboxRepositoryTest test` — Expected: FAIL (compile: constructor/field chưa có).
- [ ] **Step 3:** Implement; regex trong Global Constraints; cập nhật `durability.sql` và 3 migration.
- [ ] **Step 4:** Chạy lại Step 2 — PASS; `./mvnw -q -pl services/platform-durability,services/catalog-service,services/inventory-service,services/user-service -am -Dtest='*Schema*' -Dsurefire.failIfNoSpecifiedTests=false test` — PASS (migration áp dụng).
- [ ] **Step 5:** Commit `feat(PLT-05): store traceparent with outbox events`

### Task 2: Span relay có parent từ outbox

**Files:**
- Modify: `services/platform-messaging/pom.xml` (`io.micrometer:micrometer-tracing`; test: `micrometer-tracing-bridge-otel`, `io.opentelemetry:opentelemetry-sdk-testing`), `OutboxRelay.java`, `MessagingAutoConfiguration.java`
- Test: `services/platform-messaging/src/test/java/vn/fashion/platform/messaging/OutboxRelayTraceIntegrationTest.java`

**Interfaces:**
- Consumes: `OutboxMessage.traceParent()` (Task 1).
- Produces: `OutboxRelay(OutboxRepository, KafkaTemplate<String,String>, Duration, Tracer tracer, Propagator propagator)` (tracer/propagator nullable → hành vi cũ); autoconfig lấy qua `ObjectProvider<Tracer>`/`ObjectProvider<Propagator>`.
- Produces: `public static Supplier<String> TraceParents.current(Tracer tracer)` trong platform-messaging — trả `00-<traceId>-<spanId>-01|00` của `tracer.currentSpan()`, null khi không có span.

- [ ] **Step 1: Tests** (Kafka + PostgreSQL thật, OtelTracer dựng tay với `W3CTraceContextPropagator` và `InMemorySpanExporter`):
  - `publishSpanIsChildOfStoredTraceParent`: append với traceparent T (trace t, span s) → `relayOnce()` → record header `traceparent` có trace t; span exported tên `outbox publish VARIANT_CREATED`, kind PRODUCER, parentSpanId = s, spanId = span id trong header; attribute `messaging.destination.name`, `event_id`; không attribute nào chứa payload.
  - `rowWithoutTraceParentKeepsLegacyHeader`: correlation 32 hex, trace_parent null → header trace id = correlation_id (hành vi cũ).
  - `republishKeepsTraceId` (Review Focus 1): ép row về PENDING sau lần gửi đầu, relay lại → hai record cùng trace t, span id khác nhau.
- [ ] **Step 2:** `./mvnw -q -pl services/platform-messaging -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OutboxRelayTraceIntegrationTest test` — Expected: FAIL.
- [ ] **Step 3:** Implement: `propagator.extract(Map.of("traceparent", tp), Map::get)` → builder `.name(...).kind(PRODUCER).tag(...)` → `start()`; trong `tracer.withSpan(span)` gửi record; `propagator.inject(span.context(), record.headers(), setter thay header)`; `span.end()` trong finally; mọi `RuntimeException` từ tracing → log debug, gửi như cũ.
- [ ] **Step 4:** Chạy test mới + `OutboxRelayKafkaIntegrationTest` — PASS.
- [ ] **Step 5:** Commit `feat(PLT-05): relay publish span parented by outbox traceparent`

### Task 3: Consumer nối trace; service ghi traceparent

**Files:**
- Modify: `MessagingAutoConfiguration.kafkaListenerContainerFactory` (`factory.getContainerProperties().setObservationEnabled(true)`)
- Modify: `services/catalog-service/.../admin/CatalogBeans.java`, `services/inventory-service/.../InventoryBeans.java` (`new OutboxRepository(jdbc, TraceParents.current(tracer))`)
- Test: `services/inventory-service/src/test/java/vn/fashion/inventory/stock/VariantCreatedConsumerIntegrationTest.java`, `services/catalog-service/src/test/java/vn/fashion/catalog/admin/CatalogRelayIntegrationTest.java`

**Interfaces:**
- Consumes: `TraceParents.current` (Task 2), constructor Task 1.

- [ ] **Step 1: Tests**
  - inventory `traceContinuesIntoOutbox`: publish `VARIANT_CREATED` với header `traceparent` trace t → sau khi SKU đăng ký, `select trace_parent from outbox_events where event_type='INVENTORY_UPDATED'` có trace t và span id ≠ span trong header.
  - inventory `malformedTraceparentIsStillConsumed` (Review Focus 2): header `traceparent: not-a-trace` → SKU vẫn đăng ký, không bản ghi trên `catalog.events.DLT`.
  - catalog `adminRequestTraceIsStoredInOutbox`: POST tạo variant với header `traceparent` trace t → outbox `VARIANT_CREATED` có trace t.
- [ ] **Step 2:** Chạy 3 test — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Chạy lại + `./mvnw -q -pl services/catalog-service,services/inventory-service -am test` — PASS.
- [ ] **Step 5:** Commit `feat(PLT-05): continue traces through Kafka consumers`

### Task 4: Xuất OTLP

**Files:**
- Modify: `pom.xml` của gateway, catalog, inventory, user-service (`io.opentelemetry:opentelemetry-exporter-otlp`, version từ BOM — kiểm `./mvnw dependency:tree`); `application.yaml` 5 ứng dụng.

**Interfaces:** cấu hình `management.tracing.sampling.probability=${TRACING_SAMPLING_PROBABILITY:0.1}` và endpoint OTLP tracing của Boot 4 (tra tên property chính xác trong `spring-boot-micrometer-tracing-opentelemetry` metadata, `META-INF/spring-configuration-metadata.json`, trước khi viết) `=${OTEL_EXPORTER_OTLP_ENDPOINT:}`; trống thì không tạo exporter (dùng `@ConditionalOnProperty`/thuộc tính enabled sẵn có của Boot nếu có).

- [ ] **Step 1: Test** catalog `TracingExportPropertiesTest` (`@SpringBootTest` sẵn có qua `CatalogAdminTestSupport`): endpoint trống → context lên, không bean exporter OTLP (hoặc exporter noop); readiness UP.
- [ ] **Step 2:** Chạy — FAIL nếu chưa cấu hình (assert property sampling có trong environment).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `./mvnw -B verify` — BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat(PLT-05): export traces over OTLP when configured`

### Task 5: Tempo + Grafana local, smoke, tài liệu

**Files:**
- Modify: `infra/local/compose.yaml`, `infra/local/.env.example` (`TRACING_SAMPLING_PROBABILITY=1.0`, `GRAFANA_PORT=3000`)
- Create: `infra/local/tempo/tempo.yaml` (monolithic, receiver OTLP HTTP 4318, storage local `/var/tempo`, retention 24h), `infra/local/grafana/provisioning/datasources/tempo.yaml` (Tempo `http://tempo:3200`, default, không editable)
- Modify: `scripts/smoke-local.sh` (sau bước VARIANT_CREATED/inventory: lấy `metadata.trace_id` từ response tạo variant; poll `http://127.0.0.1:3200/api/v2/traces/<id>` tối đa 30 s; assert `service.name` gồm `gateway`, `catalog-service`, `inventory-service` và có span `outbox publish INVENTORY_UPDATED`; in `PLT-05 Kafka trace PASS`)
- Modify docs theo spec §7; Create `docs/evidence/plt-05-kafka-trace-local-2026-10-17.md`

- [ ] **Step 1:** `docker pull grafana/tempo:3.0.3` và `grafana/grafana-oss:13.2.2`, lấy digest (`docker image inspect --format '{{index .RepoDigests 0}}'`). Không pull được → dừng, báo chủ dự án (blocker hạ tầng).
- [ ] **Step 2:** Compose + config; `bash scripts/local-up.sh` — exit 0; `curl -fsS 127.0.0.1:3200/ready` = `ready`; Grafana `127.0.0.1:3000/api/health` OK.
- [ ] **Step 3:** `bash scripts/smoke-local.sh` — exit 0 có `PLT-05 Kafka trace PASS`; mở Grafana Explore → Tempo → trace id thấy chuỗi span (ghi vào evidence).
- [ ] **Step 4:** Review Focus 5: `docker compose stop tempo` → `bash scripts/smoke-local.sh` báo lỗi ở bước trace với thông điệp rõ, các bước trước PASS; `start tempo` lại.
- [ ] **Step 5: Mutants** (mỗi cái 1 lần, khôi phục): bỏ ghi `trace_parent` trong `append` → `OutboxRepositoryTest#appendStoresTraceParent`; relay không dùng parent → `OutboxRelayTraceIntegrationTest#publishSpanIsChildOfStoredTraceParent`; tắt `setObservationEnabled` → `VariantCreatedConsumerIntegrationTest#traceContinuesIntoOutbox`.
- [ ] **Step 6:** Verify: `./mvnw -B verify`, contract, docs checker, `git diff --check`, scan-secrets — PASS. PLT-05 giữ **In progress** (còn gauge outbox cũ nhất, dashboard/alert, staging).
- [ ] **Step 7:** Commit `docs(PLT-05): local Tempo/Grafana trace evidence and docs`
