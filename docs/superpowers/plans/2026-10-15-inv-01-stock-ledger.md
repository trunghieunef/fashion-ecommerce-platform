# INV-01 Stock Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `inventory-service` là nguồn sự thật tồn kho với ledger bất biến, đăng ký SKU từ `VARIANT_CREATED` qua luồng Kafka thật đầu tiên (outbox relay → Kafka → inbox consumer).

**Architecture:** Module mới `platform-messaging` (Spring Kafka) bọc `OutboxRepository.relay` và `InboxGuard.applyOnce` của `platform-durability`; catalog và inventory cùng dùng relay, inventory dùng consumer helper. Inventory theo mẫu catalog: `JdbcClient`, Flyway, `Api` envelope, `AdminCommands` idempotency, JWT/service token của `platform-security`.

**Tech Stack:** Java 21, Spring Boot 4.0, Spring Kafka 4.0.7 (Boot BOM), Kafka 4.1.2 KRaft, PostgreSQL 17, Flyway, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-10-15-inv-01-stock-ledger-design.md`

## Global Constraints

- Làm trên `dev`, commit sau mỗi task; không push/PR khi chủ dự án chưa yêu cầu.
- Kafka `apache/kafka:4.1.2` (compose pin digest như PLT-03), Spring Kafka qua Boot BOM, không thêm version tự chọn.
- `platform-durability` giữ nguyên: không Kafka client trong main scope.
- PostgreSQL quyết định; không gọi HTTP/Kafka khi giữ row lock hoặc trong business TX.
- Migration append-only; DDL outbox/inbox/idempotency chép từ `services/platform-durability/src/main/resources/db/platform/durability.sql`.
- Không `docker compose down -v`, không reset volume; DB mới qua script init idempotent + `scripts/local-up.sh`.
- Test DB/broker thật qua Testcontainers (`postgres:17.11`, `apache/kafka:4.1.2`); không H2, không mock broker.
- Envelope lỗi/thành công, metadata và `X-Correlation-Id` giống catalog (`Api`).
- SKU canonical: trim + `toUpperCase(Locale.ROOT)`, regex `^[A-Za-z0-9][A-Za-z0-9._-]*$`, 1..64 ký tự.
- Không log payload event, body CSV, token.

## Review Focus

- Inventory khởi động sau khi catalog đã có event cũ: consumer group mới phải đọc từ đầu topic (`auto-offset-reset: earliest`), không bỏ SKU tạo trước đó. Task 4 test `startsFromEarliestForNewGroup`.
- CSV xuất từ Excel có BOM UTF-8 và CRLF: phải được chấp nhận như file thường. Task 6 test `acceptsBomAndCrlf`.
- SKU chữ thường trong path/body/CSV (`smoke-1`) phải tìm đúng SKU uppercase. Task 5 test `lowercaseSkuIsCanonicalized`, Task 7 test internal tương tự.
- Envelope hợp lệ nhưng payload thiếu `sku` hoặc `variant_id` sai UUID: vào DLT ngay, không retry vô hạn chặn partition. Task 4 test `invalidPayloadGoesToDltWithoutBlocking`.
- Outbox catalog có event PENDING từ trước khi bật relay (CAT-01a) phải được gửi đúng thứ tự aggregate. Task 2 test `drainsPreExistingPendingEventsInOrder`.

---

## File Map

| Path | Trách nhiệm |
|---|---|
| `services/platform-messaging/` | `OutboxRelay`, `InboxListenerSupport`, `EventEnvelope`, `PermanentEventException`, `MessagingAutoConfiguration` |
| `infra/local/compose.yaml`, `infra/local/kafka-init/create-topics.sh`, `infra/local/postgres-init/03-inventory-db.sh` | Kafka, topics, DB inventory |
| `services/catalog-service/` | Bật relay (config + dependency) |
| `services/inventory-service/` | Service mới: schema, consumer, admin API, import, internal API |
| `services/gateway/src/main/resources/application.yaml` | Route admin inventory |
| `contracts/openapi/inventory.yaml`, `tests/contracts/test_inventory.py` | Contract |
| `scripts/smoke-local.sh`, `scripts/local-up.sh` | Smoke end-to-end, khởi tạo DB/topic |
| Docs | 03, 05 §8, 08, 12, 17, READMEs, contracts README, backlog, evidence, handoff |

---

### Task 1: platform-messaging — relay, envelope, consumer helper

**Files:**
- Create: `services/platform-messaging/pom.xml` (deps: `platform-durability`, `spring-kafka`, `spring-boot-autoconfigure`, `jackson-databind`; test: `spring-boot-starter-test`, `testcontainers-kafka`, `testcontainers-postgresql`, `postgresql`)
- Create: `services/platform-messaging/src/main/java/vn/fashion/platform/messaging/{EventEnvelope,OutboxRelay,InboxListenerSupport,PermanentEventException,MessagingAutoConfiguration}.java`
- Create: `services/platform-messaging/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `services/platform-messaging/README.md`
- Modify: `pom.xml` (module `services/platform-messaging` sau `platform-security`)
- Test: `services/platform-messaging/src/test/java/vn/fashion/platform/messaging/{EventEnvelopeTest,OutboxRelayKafkaIntegrationTest,InboxListenerSupportKafkaIntegrationTest}.java`

**Interfaces:**
- Consumes: `OutboxRepository.relay(int, Duration, Consumer<OutboxMessage>)`, `OutboxMessage(eventId, topic, partitionKey, aggregateSequence, envelopeJson, leaseToken)`, `InboxGuard.applyOnce(String, UUID, Supplier<T>)`.
- Produces:
  - `record EventEnvelope(UUID eventId, String eventType, int version, Instant occurredAt, String aggregateId, long aggregateVersion, long aggregateSequence, String correlationId, JsonNode payload)` với `static EventEnvelope parse(byte[] json, ObjectMapper m)` — ném `PermanentEventException` khi JSON/field bắt buộc sai.
  - `class PermanentEventException extends RuntimeException` (ctor `(String message)`, `(String, Throwable)`).
  - `class OutboxRelay` ctor `(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Duration sendTimeout)`; `int relayOnce()` (batch 100, lease 30 s); `@Scheduled(fixedDelayString = "${fashion.messaging.relay.interval:500ms}")` gọi `relayOnce()` khi `fashion.messaging.relay.enabled=true`.
  - `class InboxListenerSupport` ctor `(InboxGuard inbox, ObjectMapper json, String consumerName)`; `void handle(ConsumerRecord<String, byte[]> record, Acknowledgment ack, Map<String, Function<EventEnvelope, Object>> handlers)`: parse envelope → type không có trong `handlers` thì `ack.acknowledge()` và return; có → `inbox.applyOnce(consumerName, eventId, () -> handler.apply(env))` rồi `ack.acknowledge()`.
  - `MessagingAutoConfiguration`: bean `DefaultErrorHandler` với `ExponentialBackOff(1000, 2.0)` max interval 30 s, `maxElapsedTime = Long.MAX_VALUE` (retry vô hạn); `addNotRetryableExceptions(PermanentEventException.class)`; recoverer `DeadLetterPublishingRecoverer` đích `<topic>.DLT` cùng partition; bean `OutboxRelay` khi `fashion.messaging.relay.enabled=true`; `@EnableScheduling`.
  - Header khi publish: `event_id`, `event_type` (từ envelope), `correlation_id`, và `traceparent = "00-" + correlationId + "-" + 16 hex ngẫu nhiên + "-01"` khi `correlationId` khớp `[0-9a-f]{32}`.
  - Producer props bắt buộc: `acks=all`, `enable.idempotence=true`, key/value `StringSerializer`. Consumer: `enable.auto.commit=false`, `AckMode.MANUAL`, `auto.offset.reset=earliest`, value `ByteArrayDeserializer`.
  - Metrics (Micrometer): gauge `outbox_pending` (`count(*) where status <> 'SENT'`), gauge `outbox_oldest_pending_age_seconds`; counter `outbox_relay_failures_total` (publish lỗi), counter `messaging_dlt_total{topic}` (recoverer gửi DLT).
  - `KafkaHealthIndicator` (tên `kafka`): `AdminClient.describeCluster()` timeout 2 s. **Không** đưa vào readiness: Kafka down không chặn nhận request vì outbox giữ event (sửa spec §8 cho khớp).

- [ ] **Step 1: Write failing tests**

```java
// EventEnvelopeTest
@Test void parsesOutboxEnvelope() { var e = EventEnvelope.parse(sampleJson().getBytes(UTF_8), m);
  assertThat(e.eventType()).isEqualTo("VARIANT_CREATED"); assertThat(e.payload().path("sku").asText()).isEqualTo("SKU-1"); }
@Test void rejectsMissingEventId() { assertThatThrownBy(() -> EventEnvelope.parse("{\"event_type\":\"X\"}".getBytes(UTF_8), m))
  .isInstanceOf(PermanentEventException.class); }
@Test void rejectsNonJson() { assertThatThrownBy(() -> EventEnvelope.parse("not json".getBytes(UTF_8), m)).isInstanceOf(PermanentEventException.class); }

// OutboxRelayKafkaIntegrationTest (Postgres + Kafka containers, schema từ durability.sql)
@Test void marksSentOnlyAfterBrokerAck()            // append 1 → relayOnce()==1 → record trên topic, status SENT
@Test void keepsPendingWhileBrokerDown()            // relay với KafkaTemplate trỏ 127.0.0.1:1 (max.block.ms 500, send-timeout 1 s, lease 1 s) → relayOnce()==0, status != SENT, last_error not null, outbox_relay_failures_total == 1; sau 1 s relay với template thật gửi được, SENT
@Test void publishesTraceAndEventHeaders()          // header traceparent bắt đầu "00-" + correlation_id; event_id == outbox id
@Test void preservesAggregateOrder()                // 3 event cùng aggregate → offset tăng đúng aggregate_sequence
@Test void exposesOutboxGauges()                    // 2 PENDING → outbox_pending == 2, oldest age > 0

// InboxListenerSupportKafkaIntegrationTest (listener test với @KafkaListener trong test config)
@Test void appliesOnceForDuplicateEventId()         // gửi cùng envelope 2 lần → handler chạy 1 lần, processed_events 1 dòng
@Test void permanentFailureGoesToDlt()              // handler ném PermanentEventException → record trên "<topic>.DLT", messaging_dlt_total tăng, message kế tiếp cùng partition vẫn xử lý
@Test void transientFailureIsRetriedThenApplied()   // handler ném IllegalStateException 2 lần rồi thành công → áp dụng 1 lần, không có DLT
@Test void unknownEventTypeIsAckedWithoutInbox()    // handlers không chứa type → processed_events rỗng, offset được commit
```

- [ ] **Step 2: Run** `./mvnw -pl services/platform-messaging -am test` — Expected: FAIL (class chưa tồn tại).
- [ ] **Step 3: Implement** các class theo Interfaces. `OutboxRelay` publisher: `kafka.send(new ProducerRecord<>(m.topic(), m.partitionKey(), m.envelopeJson()) + headers).get(sendTimeout)`; bọc `ExecutionException/TimeoutException` thành `IllegalStateException` (relay của durability ghi `last_error` và để lease hết hạn). Timeout mặc định `fashion.messaging.relay.send-timeout=5s`.
- [ ] **Step 4: Run** lại — Expected: PASS toàn bộ 12 test.
- [ ] **Step 5: Docs** README module (mục đích, cấu hình `fashion.messaging.*`, cách dùng relay/consumer, giới hạn: không replay DLT, không Kafka transaction).
- [ ] **Step 6: Commit** `feat(INV-01): add platform-messaging relay and inbox consumer support`

---

### Task 2: Kafka local và relay catalog

**Files:**
- Modify: `infra/local/compose.yaml` — service `kafka` (`apache/kafka:4.1.2@<digest PLT-03>`, KRaft combined, `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`, listener nội bộ `kafka:9092`, port host `127.0.0.1:19092`, healthcheck `kafka-topics.sh --bootstrap-server localhost:9092 --list`, volume `kafka-data`); catalog `depends_on: kafka (healthy)` và env `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092`
- Create: `infra/local/kafka-init/create-topics.sh` — idempotent `--create --if-not-exists`, 3 partitions, RF 1: `catalog.events`, `catalog.events.DLT`, `inventory.events`
- Modify: `scripts/local-up.sh` — chạy `create-topics.sh` qua `docker compose exec -T kafka` sau khi kafka healthy
- Modify: `services/catalog-service/pom.xml` (dep `platform-messaging`), `services/catalog-service/src/main/resources/application.yaml` (`spring.kafka.bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:19092}`, `fashion.messaging.relay.enabled: ${CATALOG_RELAY_ENABLED:true}`)
- Modify: `services/catalog-service/src/test/java/vn/fashion/catalog/admin/CatalogAdminTestSupport.java` — property `fashion.messaging.relay.enabled=false` để test cũ không cần broker
- Test: `services/catalog-service/src/test/java/vn/fashion/catalog/admin/CatalogRelayIntegrationTest.java`
- Modify: `scripts/validate-infra.sh` nếu nó kiểm tra digest/loopback cho mọi image (thêm kafka)

**Interfaces:**
- Consumes: `OutboxRelay` (Task 1), `CatalogAdminTestSupport.createProduct/createVariant`.
- Produces: topic `catalog.events` chứa envelope `VARIANT_CREATED` (key `product_id`); `SPRING_KAFKA_BOOTSTRAP_SERVERS` env cho các service sau.

- [ ] **Step 1: Write failing tests** trong `CatalogRelayIntegrationTest` (kế thừa `CatalogAdminTestSupport`, thêm `@Container` Kafka `apache/kafka:4.1.2` và override `fashion.messaging.relay.enabled=true` + bootstrap):

```java
@Test void variantCreatedReachesCatalogEvents() {
  UUID p = createProduct("relay"); createVariant(p, "relay-1");
  var rec = pollOne("catalog.events", Duration.ofSeconds(10));
  assertThat(rec.key()).isEqualTo(p.toString());
  JsonNode env = mapper.readTree(rec.value());
  assertThat(env.path("event_type").asText()).isEqualTo("VARIANT_CREATED");
  assertThat(env.path("payload").path("sku").asText()).isEqualTo("RELAY-1");
  assertThat(jdbc.queryForObject("select status from outbox_events where id=?::uuid", String.class, env.path("event_id").asText())).isEqualTo("SENT");
}
@Test void drainsPreExistingPendingEventsInOrder() // insert trực tiếp 2 outbox row PENDING cùng aggregate (sequence 1, 2; created_at lùi 1 ngày) → poll 2 record, sequence 1 trước 2, cả hai SENT
```

- [ ] **Step 2: Run** `./mvnw -pl services/catalog-service -Dtest=CatalogRelayIntegrationTest test` — Expected: FAIL (không có relay).
- [ ] **Step 3: Implement** dependency + config; compose/topic script.
- [ ] **Step 4: Run** `./mvnw -pl services/catalog-service -am verify` — Expected: toàn bộ test catalog PASS (gồm test cũ với Kafka container mới).
- [ ] **Step 5: Validate infra** `bash scripts/validate-infra.sh` và `docker compose -f infra/local/compose.yaml config -q` — Expected: exit 0.
- [ ] **Step 6: Commit** `feat(INV-01): run local Kafka and relay catalog outbox`

---

### Task 3: inventory-service khung, schema V001 và DB local

**Files:**
- Create: `services/inventory-service/pom.xml` (theo catalog: web, jdbc, flyway, actuator, tracing, `platform-durability`, `platform-security`, `platform-messaging`, postgresql; test như catalog + testcontainers-kafka)
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/InventoryApplication.java`
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/web/{Api,ApiExceptionHandler,AdminAuth}.java` — chép từ catalog `vn.fashion.catalog.web`, đổi package; `AdminAuth.require(String authorization, String permission) -> UUID` (thay `requireCatalogWriter`); `ApiExceptionHandler` giữ phần Problem/validation/HTTP 4xx/500, log "Inventory request failed"
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/InventoryBeans.java` (beans `OutboxRepository`, `InboxGuard`, `IdempotencyStore`)
- Create: `services/inventory-service/src/main/resources/application.yaml` (port `${SERVER_PORT:8083}`, datasource runtime/flyway migration user theo mẫu catalog, `fashion.inventory.jwt.public-keys: ${INVENTORY_JWT_PUBLIC_KEYS}`, `fashion.inventory.internal-callers: ${INVENTORY_INTERNAL_CALLERS:}`, `spring.kafka.bootstrap-servers`, `spring.kafka.consumer.group-id: inventory-service`, relay enabled, logging ECS + `PiiRedactingJsonCustomizer`, readiness chỉ `db`; health `kafka` hiển thị riêng)
- Create: `services/inventory-service/src/main/resources/db/migration/V001__stock_and_ledger.sql`
- Create: `services/inventory-service/Dockerfile` (theo catalog)
- Create: `infra/local/postgres-init/03-inventory-db.sh` (theo `02-user-db.sh`: DB `inventory`, role `inventory_migration`/`inventory_runtime`, REVOKE PUBLIC)
- Modify: `infra/local/compose.yaml` (postgres env `INVENTORY_MIGRATION_DB_PASSWORD`, `INVENTORY_DB_PASSWORD`, mount `03-inventory-db.sh`; service `inventory` port `127.0.0.1:8083:8083`, depends_on postgres/kafka), `scripts/local-up.sh` (sinh password như user, export JWT public keys cho inventory), `pom.xml` (module)
- Create: `services/inventory-service/src/test/java/vn/fashion/inventory/InventoryTestSupport.java` (Postgres + Kafka containers, token helpers chép từ `CatalogAdminTestSupport`: `token(String...)`, `serviceToken(String caller)`, `send(...)`, `json`, `data`)
- Test: `services/inventory-service/src/test/java/vn/fashion/inventory/StockSchemaIntegrationTest.java`

**Interfaces:**
- Produces: bảng `stock_items`, `stock_transactions` (spec §3) + `outbox_events`, `processed_events`, `idempotency_requests`; `AdminAuth.require(...)`; `InventoryTestSupport` helpers dùng cho Task 4–7.
- V001: sau DDL, `grant select, insert on stock_transactions to inventory_runtime` — **không** update/delete; `stock_items` select/insert/update; bảng durability theo grant của catalog V002.

- [ ] **Step 1: Write failing tests** `StockSchemaIntegrationTest` (kết nối bằng role runtime):

```java
@Test void reservedCannotExceedOnHand()   // insert on_hand=1 reserved=2 → DataIntegrityViolation
@Test void onHandCannotBeNegative()
@Test void variantIdIsUnique()
@Test void ledgerIsAppendOnlyForRuntime() // runtime update/delete stock_transactions → "permission denied"
@Test void operationKeyUniquePerSku()     // 2 dòng cùng (operation_key, sku) → vi phạm unique
@Test void ledgerTypeIsConstrained()      // type 'FOO' → check violation
```

- [ ] **Step 2: Run** `./mvnw -pl services/inventory-service -am -Dtest=StockSchemaIntegrationTest test` — Expected: FAIL.
- [ ] **Step 3: Implement** V001, app, config, init script, compose.
- [ ] **Step 4: Run** lại — Expected: PASS; `GET /actuator/health/readiness` trong test trả UP.
- [ ] **Step 5: Docs** `services/inventory-service/README.md` (chạy/test, config, migration, topics, health, giới hạn), 05 §8 ghi V001 đã hiện thực + grant ledger.
- [ ] **Step 6: Commit** `feat(INV-01): scaffold inventory-service with stock and ledger schema`

---

### Task 4: Đăng ký SKU từ VARIANT_CREATED và snapshot event

**Files:**
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/stock/Sku.java`
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/stock/StockEvents.java`
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/stock/SkuRegistration.java`
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/stock/CatalogEventsListener.java`
- Test: `services/inventory-service/src/test/java/vn/fashion/inventory/stock/VariantCreatedConsumerIntegrationTest.java`

**Interfaces:**
- Consumes: `InboxListenerSupport.handle(...)`, `EventEnvelope`, `PermanentEventException` (Task 1); `InventoryTestSupport` (Task 3).
- Produces:
  - `vn.fashion.inventory.stock.Sku`: `static boolean valid(String canonical)` (regex + 1..64) và `static String canonical(String raw)` (trim + uppercase; null/sai → `Api.Problem` 400 field `sku`). Task 4 dùng `valid` (sai → `PermanentEventException`), Task 5/6/7 dùng `canonical`.
  - `record StockRow(String sku, UUID productId, UUID variantId, int onHand, int reserved, int minStock, String status, long version, Instant updatedAt)` với `int available()`.
  - `StockEvents.append(StockRow after, boolean restocked, String correlationId)`: ghi outbox `INVENTORY_UPDATED` (aggregate_type `stock`, aggregate_id/partition key = sku, aggregate_version = `version`, topic `inventory.events`, schema version 1, payload `{sku,on_hand,reserved,available,stock_version,observed_at}` với `observed_at = updatedAt` UTC ISO); thêm `INVENTORY_RESTOCKED` cùng payload khi `restocked`. Phải gọi trong TX đang giữ row lock SKU.
  - `SkuRegistration.register(EventEnvelope env) -> Boolean` (true khi tạo mới): đọc `product_id`, `variant_id`, `sku` từ payload (thiếu/sai UUID/SKU sai regex → `PermanentEventException`); `insert ... on conflict do nothing`; nếu không chèn: đọc dòng theo `variant_id` hoặc `sku` — khác cặp (variant_id, sku) → `PermanentEventException("sku/variant mismatch")`, trùng → false; chèn mới → `StockEvents.append(row, false, env.correlationId())`.
  - `CatalogEventsListener`: `@KafkaListener(topics = "catalog.events", groupId = "inventory-service")` → `support.handle(record, ack, Map.of("VARIANT_CREATED", registration::register))`.

- [ ] **Step 1: Write failing tests** (publish envelope thô bằng `KafkaTemplate` test lên `catalog.events`):

```java
@Test void registersSkuWithZeroStockAndEmitsSnapshot()   // sau ≤10 s: stock_items 0/0, outbox INVENTORY_UPDATED payload available=0, stock_version=0
@Test void duplicateEventIdIsIgnored()                    // cùng event 2 lần → 1 outbox UPDATED
@Test void newEventSameVariantDoesNotResetStock()         // register → update on_hand=7 → event_id mới cùng variant/sku → on_hand vẫn 7, không outbox mới
@Test void sameVariantDifferentSkuGoesToDlt()             // record xuất hiện trên catalog.events.DLT, stock_items giữ SKU cũ
@Test void invalidPayloadGoesToDltWithoutBlocking()       // payload thiếu sku → DLT; event hợp lệ gửi sau vẫn được đăng ký
@Test void catalogChangedIsIgnored()                      // CATALOG_CHANGED → không stock_items, processed_events rỗng
@Test void startsFromEarliestForNewGroup()                // publish trước khi listener container start (autoStartup=false rồi start) → vẫn đăng ký
@Test void inventoryRelayPublishesSnapshot()              // record trên inventory.events key = sku, event_type INVENTORY_UPDATED
```

- [ ] **Step 2: Run** `./mvnw -pl services/inventory-service -Dtest=VariantCreatedConsumerIntegrationTest test` — Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces.
- [ ] **Step 4: Run** lại — Expected: PASS 8 test.
- [ ] **Step 5: Commit** `feat(INV-01): register SKUs from VARIANT_CREATED with stock snapshots`

---

### Task 5: Admin đọc stock/ledger và adjust

**Files:**
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/admin/{AdminCommands,StockAdminController,StockAdminService,StockQueries}.java` (`AdminCommands` chép từ catalog `vn.fashion.catalog.admin.AdminCommands`)
- Test: `services/inventory-service/src/test/java/vn/fashion/inventory/admin/{StockAdjustmentIntegrationTest,StockReadIntegrationTest}.java`

**Interfaces:**
- Consumes: `Sku.canonical`, `StockRow`, `StockEvents.append` (Task 4); `AdminAuth.require` (Task 3).
- Produces:
  - `StockAdminService.adjust(UUID actor, String key, AdjustRequest req, String traceId) -> AdminCommands.Replay`; `record AdjustRequest(String sku, Integer delta, String reason)`.
  - `StockAdminService.lockForUpdate(List<String> sortedSkus) -> Map<String, StockRow>` (`select ... where sku = any(:skus) order by sku for update`) — Task 6 dùng lại.
  - `StockAdminService.applyDelta(StockRow row, int delta, String type, String key, String reason, UUID actor, String traceId) -> StockRow`: kiểm tràn bằng `Math.addExact` (→ 400 field do caller truyền), `on_hand + delta < reserved` → 409 `CONFLICT`/`ON_HAND_BELOW_RESERVED`; update `on_hand`, `version+1`, `updated_at=now()`; insert ledger; `StockEvents.append(after, before.available()==0 && after.available()>0, traceId)`.
  - Routes: `GET /admin/api/v1/inventory/skus` (`sku_prefix`, `limit` 1..100 mặc định 20, `cursor` = base64url của sku cuối), `GET /admin/api/v1/inventory/skus/{sku}/transactions` (`limit`, `cursor` = base64url `{"t":created_at,"id":uuid}`), `POST /admin/api/v1/inventory/adjust`. Idempotency operation `inventory.adjust`, actor key = user id.
  - Response stock: `{sku, variant_id, product_id, on_hand, reserved, available, min_stock, status, version}`; ledger item: `{id, type, on_hand_delta, reserved_delta, reason, actor_id, order_id, created_at}`.

- [ ] **Step 1: Write failing tests**

```java
// StockAdjustmentIntegrationTest
@Test void adjustmentCannotMoveOnHandBelowReserved() { seedStock("SKU-1", 5, 4);
  assertThat(adjust("SKU-1", -2, "damage", "op-1").statusCode()).isEqualTo(409);
  assertStock("SKU-1", 5, 4); assertLedgerEmpty("op-1"); }
@Test void positiveAdjustWritesLedgerAndRestocked()   // 0 → +5: on_hand 5, version 1, ledger ADJUST +5 reason, outbox UPDATED + RESTOCKED
@Test void negativeAdjustToZeroEmitsOnlyUpdated()
@Test void replaySameKeyReturnsSameResponse()          // 2 lần cùng key/body → cùng body, 1 ledger
@Test void sameKeyDifferentBodyIsConflict()            // 409 CONFLICT
@Test void zeroDeltaAndBlankReasonAreRejected()        // 400 field delta / reason; reason 501 ký tự → 400
@Test void overflowIsRejected()                        // on_hand=Integer.MAX_VALUE-1, delta 2 → 400 field delta
@Test void unknownSkuIs404()                           // SKU_NOT_FOUND
@Test void lowercaseSkuIsCanonicalized()               // adjust "sku-1" tác động "SKU-1"
@Test void concurrentAdjustsDoNotLoseUpdates()         // 20 thread × +1 key khác nhau → on_hand 20, 20 ledger, version 20
@Test void readPermissionCannotAdjust()                // token inventory.read → 403; không token → 401; thiếu Idempotency-Key → 400
// StockReadIntegrationTest
@Test void listsSkusWithKeysetAndPrefix()              // 3 SKU, limit 2 → next_cursor, trang 2 còn 1; sku_prefix lọc
@Test void transactionsNewestFirstAnd404ForUnknown()
@Test void readRequiresInventoryRead()                 // inventory.adjust-only token → 403
```

- [ ] **Step 2: Run** `./mvnw -pl services/inventory-service -Dtest='Stock*IntegrationTest' test` — Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces; khóa và ghi trong `AdminCommands.create` (một TX), không gọi Kafka.
- [ ] **Step 4: Run** lại — Expected: PASS.
- [ ] **Step 5: Commit** `feat(INV-01): add OPS stock reads and idempotent adjustments`

---

### Task 6: Import CSV phiếu nhập

**Files:**
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/admin/{InboundCsv,StockImportController}.java`
- Modify: `StockAdminService` thêm `importInbound(...)`
- Modify: `application.yaml` (`spring.servlet.multipart.max-file-size: 128KB`, `max-request-size: 160KB`)
- Test: `services/inventory-service/src/test/java/vn/fashion/inventory/admin/{InboundCsvTest,StockImportIntegrationTest}.java`

**Interfaces:**
- Consumes: `Sku.canonical` (Task 4), `lockForUpdate`, `applyDelta` (Task 5).
- Produces:
  - `record InboundCsv.Row(int line, String sku, int quantity)`; `static List<Row> InboundCsv.parse(byte[] file)` — bỏ BOM UTF-8, chấp nhận `\n`/`\r\n`, header đúng `sku,quantity`, 1..1.000 dòng dữ liệu, bỏ dòng trống cuối file, quantity 1..1.000.000, SKU lặp; gom mọi lỗi rồi ném `Api.Problem` 400 `VALIDATION_ERROR`/`INVALID_CSV` với field `rows[<line>].sku|quantity` (`line` tính từ 1 = header) hoặc `file`.
  - `StockAdminService.importInbound(UUID actor, String key, byte[] file, String reason, String traceId) -> AdminCommands.Replay`: hash request = SHA-256 của file + reason; lock SKU đã sort; SKU không tồn tại → 400 `rows[n].sku` "unknown sku" (gom hết trước khi ghi); `applyDelta(..., "INBOUND", ...)`; response 200 `{rows, total_quantity}`; operation `inventory.import`.
  - Route `POST /admin/api/v1/inventory/import` (`multipart/form-data`, parts `file`, `reason`).

- [ ] **Step 1: Write failing tests**

```java
// InboundCsvTest (unit)
@Test void acceptsBomAndCrlf()             // "﻿sku,quantity\r\nA,1\r\n" → 1 row
@Test void rejectsWrongHeader()            // field file
@Test void rejectsDuplicateSku()           // "a,1\nA,2" → rows[3].sku
@Test void rejects1001Rows()
@Test void reportsEveryBadLine()           // 2 dòng lỗi → 2 FieldError
// StockImportIntegrationTest
@Test void invalidCsvRollsBackEveryRow()   // "SKU-1,5\nUNKNOWN,4" → 400, ledger theo key rỗng, SKU-1 on_hand không đổi
@Test void importAddsInboundForEachRow()   // 2 SKU → on_hand cộng, 2 ledger INBOUND, outbox per SKU, RESTOCKED cho SKU từ 0
@Test void replayDoesNotAddTwice()
@Test void oversizedFileIs400Or413()       // 129 KB → không ghi; ghi rõ status thực tế trong test
@Test void requiresAdjustPermissionAndKey()
```

- [ ] **Step 2: Run** `./mvnw -pl services/inventory-service -Dtest='InboundCsvTest,StockImportIntegrationTest' test` — Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces.
- [ ] **Step 4: Run** lại — Expected: PASS.
- [ ] **Step 5: Commit** `feat(INV-01): import inbound stock CSV atomically`

---

### Task 7: Internal stock read và Gateway

**Files:**
- Create: `services/inventory-service/src/main/java/vn/fashion/inventory/internal/InternalStockController.java`
- Modify: `services/gateway/src/main/resources/application.yaml` (route `inventory-admin`, `uri: ${INVENTORY_BASE_URL:http://localhost:8083}`, `Path=/admin/api/v1/inventory/**`), `infra/local/compose.yaml` (gateway env `INVENTORY_BASE_URL: http://inventory:8083`; inventory env `INVENTORY_INTERNAL_CALLERS`)
- Test: `services/inventory-service/src/test/java/vn/fashion/inventory/internal/InternalStockIntegrationTest.java`, `services/gateway/src/test/java/.../GatewayBoundaryTest.java` (thêm case)

**Interfaces:**
- Consumes: `Sku.canonical` (Task 4), `StockQueries` (Task 5); `ServiceTokenVerifier`, `AccessTokenVerifier.parseKeys` theo mẫu `VariantQuoteController`.
- Produces: `GET /internal/api/v1/inventory/skus/{sku}` → `{sku, variant_id, available, version, observed_at}` + `Cache-Control: no-store`. Verifier audience `"inventory-service"`; `fashion.inventory.internal-callers` là map caller→public key (`parseKeys`) như catalog; `ALLOWED_CALLERS = Set.of("catalog-service", "cart-service")` trong controller (spec §5 ghi "giá trị local catalog-service,cart-service": hiện thực theo mẫu catalog là danh sách cố định + key map).

- [ ] **Step 1: Write failing tests**

```java
@Test void returnsAvailableForAllowedCaller()  // seed 5/2 → available 3, version, observed_at, no-store
@Test void lowercaseSkuIsCanonicalized()
@Test void missingTokenIs401AndOtherCallerIs403BeforeValidation() // token "order-service" + sku sai regex → 403, không 400
@Test void unknownSkuIs404()
// GatewayBoundaryTest
@Test void inventoryInternalIsNotRouted()      // GET /internal/api/v1/inventory/skus/X qua gateway → 404
@Test void inventoryAdminIsRouted()            // GET /admin/api/v1/inventory/skus tới stub upstream
```

- [ ] **Step 2: Run** `./mvnw -pl services/inventory-service,services/gateway -am -Dtest='InternalStockIntegrationTest,GatewayBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false test` — Expected: FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** lại — Expected: PASS.
- [ ] **Step 5: Commit** `feat(INV-01): add internal stock read and gateway route`

---

### Task 8: Contract, smoke, mutation và tài liệu

**Files:**
- Create: `contracts/openapi/inventory.yaml` (4 admin + 1 internal operation, `x-implementation-status: implemented`, `$ref` `common.yaml`, `additionalProperties: false` cho body adjust)
- Create: `tests/contracts/test_inventory.py` (theo `test_catalog_admin.py`: operation tồn tại, error codes thuộc common, schema response khớp field spec §4/§5)
- Modify: `tests/contracts/test_event_routing.py` nếu cần assert inventory producer dùng `inventory.events`/key `sku` khớp `registry.json`
- Modify: `scripts/smoke-local.sh`, `scripts/validate-contracts.sh` (thêm inventory.yaml)
- Create: `docs/evidence/inv-01-local-2026-10-15.md`, `docs/superpowers/plans/2026-10-15-inv-01-handoff.md`
- Modify: `docs/design/03_interfaces.md` (inventory admin/internal + §5.1 relay/consumer đang chạy), `docs/design/08_decisions.md` (ADR: `platform-messaging`, Kafka local single-node không auth, DLT, earliest), `docs/engineering/12_engineering_guide.md` (lệnh), `docs/engineering/17_tech_stack.md` (Spring Kafka đã dùng), `contracts/README.md`, `services/catalog-service/README.md` (relay), runbook DLT trong `services/inventory-service/README.md` (`kafka-console-consumer.sh --topic catalog.events.DLT --from-beginning`, không replay tự động), `docs/delivery/10_backlog.md` (INV-01 In progress → hiện thực local), `docs/superpowers/plans/README.md`, `docs/README.md` nếu có index, spec header (trạng thái + link plan)

- [ ] **Step 1: Smoke** — thêm vào `smoke-local.sh` sau bước tạo variant: poll `GET $GATEWAY_URL/admin/api/v1/inventory/skus?sku_prefix=SMOKE-$catalog_suffix` tối đa 10 s đến khi có 1 item; `POST /admin/api/v1/inventory/adjust` `{sku, delta:5, reason:"smoke"}`; kiểm `available=5`; đọc 1 record `INVENTORY_UPDATED` của SKU từ `inventory.events` bằng `docker compose exec -T kafka kafka-console-consumer.sh --from-beginning --timeout-ms 10000` + grep SKU.
- [ ] **Step 2: Run** `bash scripts/local-up.sh && bash scripts/smoke-local.sh` — Expected: `SMOKE PASS` (cùng output hiện có) gồm bước inventory.
- [ ] **Step 3: Contract** `bash scripts/validate-contracts.sh` và `python -B -m pytest tests/contracts -q` — Expected: PASS.
- [ ] **Step 4: Full reactor** `./mvnw verify` — Expected: BUILD SUCCESS; ghi số test từng module vào evidence.
- [ ] **Step 5: Mutation** — áp từng mutation tạm thời, chạy test đích, ghi FAIL mong đợi, revert (`git diff` sạch sau mỗi lần):

| # | Mutation | Test phải đỏ |
|---|---|---|
| M1 | Bỏ `for update` trong `lockForUpdate` | `concurrentAdjustsDoNotLoseUpdates` |
| M2 | Bỏ kiểm `on_hand + delta < reserved` | `adjustmentCannotMoveOnHandBelowReserved` |
| M3 | `markSent` trước `send().get()` trong relay | `keepsPendingWhileBrokerDown` |
| M4 | Gọi handler trực tiếp, bỏ `applyOnce` | `duplicateEventIdIsIgnored` |
| M5 | `ack.acknowledge()` trước `applyOnce` | `transientFailureIsRetriedThenApplied` |
| M6 | Bỏ so sánh hash trong idempotency (`begin` luôn replay) | `sameKeyDifferentBodyIsConflict` |

- [ ] **Step 6: Docs checks** `python -B -m unittest discover -s scripts -p 'test_*.py' -v`, `python -B scripts/check_docs.py`, `git diff --check` — Expected: PASS.
- [ ] **Step 7: Commit** `docs(INV-01): contract, smoke, mutation evidence and docs for stock ledger`
