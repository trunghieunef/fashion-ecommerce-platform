# INV-02 Reservations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** inventory-service giữ hàng all-or-nothing cho một đơn, commit/release/expire đúng một lần dưới race, có tombstone, event và job hết hạn.

**Architecture:** Bảng `stock_reservations` là nguồn sự thật; dedupe theo `UNIQUE(order_id)` + `request_hash` và state machine (phương án A). Mọi transaction khóa reservation rồi SKU theo `ORDER BY sku`; job hết hạn quét bảng bằng `FOR UPDATE SKIP LOCKED`. Internal API chỉ cho `order-service` bằng service token ADR-19; event qua outbox hiện có.

**Tech Stack:** Java 21, Spring Boot 4.0 (JdbcClient, TransactionTemplate, `@Scheduled`), Jackson 3 (`tools.jackson`), PostgreSQL 17.11 + Flyway, platform-durability outbox, platform-security `ServiceTokenVerifier`, Micrometer, Testcontainers, Python contract tests (jsonschema) + Redocly.

**Spec:** [INV-02 design](../specs/2026-10-16-inv-02-reservations-design.md) (duyệt 2026-10-16). `TASK:INV-02` · REQ INV-02/03/06.

## Global Constraints

- Chỉ caller `order-service`; thứ tự kiểm: token (401 `UNAUTHORIZED`/`INVALID_SERVICE_TOKEN`) → caller (403 `FORBIDDEN`/`CALLER_NOT_ALLOWED`) → header/body.
- Mọi response `/internal/**` có `Cache-Control: no-store`, kể cả lỗi xảy ra trước controller.
- Mutation cần `Idempotency-Key` 1–128 ký tự (400 `VALIDATION_ERROR`/`INVALID_KEY`).
- Items: mảng 1–100 phần tử; quantity mỗi phần tử 1–99; sau gộp SKU trùng vẫn 1–100 SKU và mỗi SKU ≤ 99.
- `now() < expires_at <= now() + 24h` (DB clock), định dạng UTC kết thúc `Z`, độ chính xác tối đa mili giây; release chỉ kiểm hình dạng snapshot.
- `request_hash` = SHA-256 hex lowercase của `order_id|order_no|expires_at|SKU1:q1,SKU2:q2` (order_id lowercase, order_no escape `\`→`\\` rồi `|`→`\|`, expires_at `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, SKU `Sku.canonical`, gộp + sắp xếp).
- Mã lỗi nằm trong envelope `ApiError` của common.yaml (closed): `code` ∈ enum hiện có, chi tiết ở `message`; OUT_OF_STOCK liệt kê `errors[{field: "items[<SKU>]", message: "requested=<n> available=<m>"}]` (cách biểu diễn `{sku, requested, available}` của spec trong envelope chung).
- Body có field lạ → 400 (giống quote CAT-02a sau F3).
- Không gọi HTTP trong transaction; thời gian lấy từ `now()` của DB; stock CHECK `0 <= reserved <= on_hand` là lớp bảo vệ cuối.
- Migration append-only: thêm `V002`, không sửa `V001`. Runtime không có DELETE/TRUNCATE trên bảng reservation.
- Không đổi `ApiError`/`common.yaml`, không thêm dependency mới.
- Test money/stock dùng PostgreSQL thật; race dùng thread + latch thật.
- Lệnh Java chạy qua Git Bash: `./mvnw -B -pl services/inventory-service -am test ...` (cần Docker Desktop). Python: `python3` 3.10+ (máy Windows có thể cần shim nếu `python3` là alias Store).

## Review Focus

1. Reserve lặp lại khi `expires_at` của request gốc đã qua (order retry sau timeout dài) → phải trả reservation hiện có (200), không 400 do kiểm thời gian. Test ở Task 4 (`replayAfterDeadlineReturnsExistingReservation`).
2. Release tombstone với SKU chưa đăng ký → 400 rõ field, không 500 do vi phạm FK. Test ở Task 6 (`tombstoneWithUnknownSkuIs400`).
3. Commit trên reservation quá hạn phải **lưu** EXPIRED dù trả 409 (không bị rollback). Test ở Task 5 (`commitAfterDeadlineExpiresAndPersists`).
4. Body JSON hỏng/không phải object/field lạ trước khi parse → 400 có `no-store`, nhưng chỉ sau khi auth pass. Test ở Task 4 (`malformedBodyIs400OnlyAfterAuth`).
5. `order_no` chứa `|` hoặc `\` không được đụng hash với order_no khác. Test ở Task 3 (`escapingPreventsDelimiterCollision`).

---

## File Structure

| File | Trách nhiệm |
|---|---|
| `contracts/events/stock-events.schema.json` (modify) | Thêm `$defs/ReservationLifecycle` |
| `contracts/events/registry.json` (modify) | 3 event reservation |
| `contracts/fixtures/valid/inventory-{reserved,deducted,released}.json` (create) | Fixture hợp lệ |
| `contracts/fixtures/invalid/payload-cases.json` (modify) | Case quantity 100 |
| `contracts/openapi/inventory.yaml` (modify) | 4 operation internal + schemas |
| `tests/contracts/test_inventory.py` (modify) | Kiểm operation/schema mới |
| `docs/design/03_interfaces.md` (modify) | Tách dòng §5.2 inventory theo partition, route + hash rule |
| `services/inventory-service/src/main/resources/db/migration/V002__stock_reservations.sql` (create) | Bảng + grants |
| `.../inventory/reservation/ReservationSnapshot.java` (create) | Snapshot đã normalize + canonical/hash |
| `.../inventory/reservation/ReservationRequests.java` (create) | Parse/validate JsonNode → command |
| `.../inventory/reservation/ReservationRow.java`, `ReservationView.java` (create) | Row DB / response |
| `.../inventory/reservation/ReservationRepository.java` (create) | SQL reservation/items |
| `.../inventory/reservation/ReservationEvents.java` (create) | Outbox reservation lifecycle |
| `.../inventory/reservation/ReservationService.java` (create) | Reserve/commit/release/expire transaction |
| `.../inventory/reservation/ReservationController.java` (create) | 4 route internal |
| `.../inventory/reservation/ReservationSweep.java` (create) | Job hết hạn + metric |
| `.../inventory/internal/InternalCallers.java` (create) | Verify token + caller, dùng chung |
| `.../inventory/internal/InternalNoStoreFilter.java` (create) | `no-store` cho `/internal/**` trước controller |
| `.../inventory/internal/InternalStockController.java` (modify) | Dùng `InternalCallers` |
| `.../inventory/stock/StockEvents.java` (modify) | Expose `loadRoutes` |
| `.../inventory/admin/StockAdminService.java` (đọc, không đổi) | `lockForUpdate` dùng lại |
| `services/inventory-service/src/main/resources/application.yaml` (modify) | Cấu hình sweep |
| Test: `.../inventory/InventoryTestSupport.java` (modify), `.../reservation/*Test.java` (create), `services/gateway/.../GatewayBoundaryTest.java` (modify) | |

Package Java gốc: `services/inventory-service/src/main/java/vn/fashion/inventory/`; test: `services/inventory-service/src/test/java/vn/fashion/inventory/`.

---

### Task 1: Contract event và OpenAPI

**Files:**
- Modify: `contracts/events/stock-events.schema.json`, `contracts/events/registry.json`, `contracts/fixtures/invalid/payload-cases.json`, `contracts/openapi/inventory.yaml`, `tests/contracts/test_inventory.py`, `docs/design/03_interfaces.md` (§5.2 dòng `inventory.events`)
- Create: `contracts/fixtures/valid/inventory-reserved.json`, `inventory-deducted.json`, `inventory-released.json`

**Interfaces:**
- Produces: event types `INVENTORY_RESERVED|INVENTORY_DEDUCTED|INVENTORY_RELEASED` → schema `stock-events.schema.json#/$defs/ReservationLifecycle`, topic `inventory.events`, partition `order_no`, aggregate `reservation_id`. OpenAPI schemas `ReserveRequest`, `CommitRequest`, `ReleaseRequest`, `Reservation`, `ReservationResponse`.

- [ ] **Step 1: Viết test contract thất bại** — trong `tests/contracts/test_inventory.py` thay `test_five_implemented_operations_and_permissions` bằng phiên bản 9 operation và thêm test schema:

```python
    def test_nine_implemented_operations_and_permissions(self):
        doc = self.load()
        expected = {("get", "/admin/api/v1/inventory/skus"),
                    ("get", "/admin/api/v1/inventory/skus/{sku}/transactions"),
                    ("post", "/admin/api/v1/inventory/adjust"),
                    ("post", "/admin/api/v1/inventory/import"),
                    ("get", "/internal/api/v1/inventory/skus/{sku}"),
                    ("post", "/internal/api/v1/inventory/reserve"),
                    ("post", "/internal/api/v1/inventory/commit"),
                    ("post", "/internal/api/v1/inventory/release"),
                    ("get", "/internal/api/v1/inventory/reservations/{order_id}")}
        actual = {(method, path) for path, item in doc["paths"].items() for method in item if method != "parameters"}
        self.assertEqual(actual, expected)
        for method, path in expected:
            op = doc["paths"][path][method]
            self.assertEqual(op["x-implementation-status"], "implemented")
            self.assertTrue(op["security"])
            if method == "post":
                self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, op["parameters"])
            if path.startswith("/internal/"):
                for response in op["responses"].values():
                    self.assertEqual(response["headers"]["Cache-Control"]["schema"]["const"], "no-store")

    def test_reserve_request_is_closed_and_bounded(self):
        validator = self.validator("ReserveRequest")
        good = {"order_id": "4c8a1f2e-0000-4000-8000-000000000010", "order_no": "ORD202610000001",
                "items": [{"sku": "SKU-1", "quantity": 2}], "expires_at": "2026-10-16T10:15:00.000Z",
                "request_hash": "a" * 64}
        self.assertEqual(list(validator.iter_errors(good)), [])
        bad_items = [{"sku": "SKU-1", "quantity": 1}] * 101
        for change in ({"items": []}, {"items": bad_items}, {"items": [{"sku": "SKU-1", "quantity": 100}]},
                       {"items": [{"sku": "SKU-1", "quantity": 0}]}, {"request_hash": "A" * 64},
                       {"order_no": "x" * 33}, {"expires_at": "2026-10-16T10:15:00+07:00"}, {"extra": 1}):
            self.assertTrue(list(validator.iter_errors(good | change)), change)

    def test_release_and_reservation_shapes(self):
        schemas = self.load()["components"]["schemas"]
        self.assertEqual(set(schemas["ReleaseRequest"]["required"]),
                         {"order_id", "original_reservation", "original_request_hash", "reason"})
        self.assertEqual(set(schemas["Reservation"]["required"]),
                         {"reservation_id", "order_id", "order_no", "status", "expires_at", "items", "version",
                          "committed_at", "released_at", "release_reason", "restore_required"})
        self.assertEqual(schemas["Reservation"]["properties"]["status"]["enum"],
                         ["ACTIVE", "COMMITTED", "RELEASED", "EXPIRED"])
        for name in ("ReserveRequest", "CommitRequest", "ReleaseRequest", "Reservation"):
            self.assertFalse(schemas[name]["additionalProperties"], name)
```

- [ ] **Step 2: Chạy, xác nhận fail**

Run: `python3 -B -m unittest discover -s tests/contracts -p 'test_inventory.py' -v`
Expected: FAIL — `test_nine_implemented_operations_and_permissions` thiếu 4 operation; `KeyError: 'ReserveRequest'`.

- [ ] **Step 3: Event schema** — thêm vào `$defs` của `contracts/events/stock-events.schema.json` (sau `StockSnapshot`):

```json
    "ReservationLifecycle": {
      "description": "INVENTORY_RESERVED, INVENTORY_DEDUCTED, INVENTORY_RELEASED (release, expire or tombstone; see status)",
      "type": "object",
      "required": ["order_id", "order_no", "reservation_id", "status", "expires_at", "items", "reason", "version"],
      "properties": {
        "order_id": {"$ref": "common.schema.json#/$defs/uuid"},
        "order_no": {"type": "string", "minLength": 1, "maxLength": 32},
        "reservation_id": {"$ref": "common.schema.json#/$defs/uuid"},
        "status": {"enum": ["ACTIVE", "COMMITTED", "RELEASED", "EXPIRED"]},
        "expires_at": {"$ref": "common.schema.json#/$defs/utc_timestamp"},
        "items": {
          "type": "array", "minItems": 1, "maxItems": 100,
          "items": {
            "type": "object",
            "required": ["sku", "quantity"],
            "properties": {
              "sku": {"$ref": "common.schema.json#/$defs/sku"},
              "quantity": {"type": "integer", "minimum": 1, "maximum": 99}
            },
            "additionalProperties": false
          }
        },
        "reason": {"type": ["string", "null"], "maxLength": 500},
        "version": {"type": "integer", "minimum": 1, "maximum": 9223372036854775807}
      },
      "additionalProperties": false
    }
```

- [ ] **Step 4: Registry** — thêm vào `contracts/events/registry.json` sau `INVENTORY_RESTOCKED`:

```json
  "INVENTORY_RESERVED": {"schema": "stock-events.schema.json#/$defs/ReservationLifecycle", "topic": "inventory.events", "partition_key": "order_no", "aggregate_id": "reservation_id"},
  "INVENTORY_DEDUCTED": {"schema": "stock-events.schema.json#/$defs/ReservationLifecycle", "topic": "inventory.events", "partition_key": "order_no", "aggregate_id": "reservation_id"},
  "INVENTORY_RELEASED": {"schema": "stock-events.schema.json#/$defs/ReservationLifecycle", "topic": "inventory.events", "partition_key": "order_no", "aggregate_id": "reservation_id"},
```

- [ ] **Step 5: 03 §5.2** — `test_event_routing.py` lấy partition key là từ đầu tiên sau `/`, nên dòng hiện tại `| inventory.events / sku cho snapshot; order_no cho reservation | ...` sẽ gán `sku` cho reservation. Thay bằng hai dòng:

```markdown
| inventory.events / sku | inventory | INVENTORY_UPDATED, INVENTORY_RESTOCKED | catalog stock projection; inventory sở hữu restock fan-out |
| inventory.events / order_no | inventory | INVENTORY_RESERVED, INVENTORY_RELEASED, INVENTORY_DEDUCTED | order reconcile reservation (INV-02) |
```

- [ ] **Step 6: Fixtures** — `contracts/fixtures/valid/inventory-reserved.json`:

```json
{
  "event_id": "77777777-7777-4777-8777-777777777781",
  "event_type": "INVENTORY_RESERVED",
  "version": 1,
  "occurred_at": "2026-10-16T03:00:00Z",
  "aggregate_id": "88888888-8888-4888-8888-888888888881",
  "aggregate_version": 1,
  "aggregate_sequence": 1,
  "correlation_id": "synthetic-contract-example",
  "payload": {
    "order_id": "99999999-9999-4999-8999-999999999991",
    "order_no": "ORD202610000001",
    "reservation_id": "88888888-8888-4888-8888-888888888881",
    "status": "ACTIVE",
    "expires_at": "2026-10-16T03:15:00.000Z",
    "items": [{"sku": "SYNTH-TEE-M-BLK", "quantity": 2}],
    "reason": null,
    "version": 1
  }
}
```

`inventory-deducted.json`: giống trên nhưng `event_id` `...782`, `event_type` `INVENTORY_DEDUCTED`, `aggregate_version` 2, `aggregate_sequence` 2, `payload.status` `COMMITTED`, `payload.version` 2.
`inventory-released.json`: `event_id` `...783`, `event_type` `INVENTORY_RELEASED`, `aggregate_version` 2, `aggregate_sequence` 2, `payload.status` `EXPIRED`, `payload.reason` `"expired"`, `payload.version` 2. Viết đủ cả ba file (không dùng tham chiếu).

Thêm vào mảng `contracts/fixtures/invalid/payload-cases.json`:

```json
  {"name": "reservation item quantity above 99", "fixture": "inventory-reserved.json", "changes": {"items": [{"sku": "SYNTH-TEE-M-BLK", "quantity": 100}]}, "validator": "maximum", "path": ["items", 0, "quantity"]},
  {"name": "reservation version starts at 1", "fixture": "inventory-reserved.json", "changes": {"version": 0}, "validator": "minimum", "path": ["version"]}
```

- [ ] **Step 7: OpenAPI** — trong `contracts/openapi/inventory.yaml`, sau path `/internal/api/v1/inventory/skus/{sku}` (trước `components:`), thêm 4 operation. Anchor YAML giữ header/response chung (yaml.safe_load và Redocly đều resolve anchor):

```yaml
  /internal/api/v1/inventory/reserve:
    post:
      operationId: reserveStock
      summary: Reserve every SKU of one order (all-or-nothing)
      description: >-
        TASK:INV-02. ADR-19 service token audience inventory-service; caller order-service only. Token and caller are
        checked before headers and body. Dedupe by order_id + request_hash, the lowercase SHA-256 hex of
        order_id|order_no|expires_at|SKU1:q1,SKU2:q2 (order_no escapes backslash then pipe; expires_at
        yyyy-MM-ddTHH:mm:ss.SSSZ; SKUs trimmed, uppercased, merged and sorted). 1-100 items, quantity 1-99 before and after
        merge, now < expires_at <= now + 24h (database clock, at most millisecond precision). Same order and hash returns the
        existing ACTIVE/COMMITTED reservation (200); RELEASED/EXPIRED or tombstone returns 409 RESERVATION_CLOSED; another
        hash 409 RESERVATION_CONFLICT. OUT_OF_STOCK rolls back every SKU and lists errors field items[SKU], message
        requested=N available=M; unknown or INACTIVE SKUs count as available=0. Not routed by Gateway.
      tags:
      - Internal reservations
      x-implementation-status: implemented
      security:
      - serviceToken: []
      parameters:
      - $ref: ./common.yaml#/components/parameters/IdempotencyKey
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ReserveRequest'
      responses:
        '200':
          description: Existing reservation for the same order and request hash
          headers: &reservationHeaders
            X-Correlation-Id:
              $ref: ./common.yaml#/components/headers/CorrelationId
            Cache-Control:
              description: Never cache reservations or their errors
              schema:
                type: string
                const: no-store
          content: &reservationContent
            application/json:
              schema:
                $ref: '#/components/schemas/ReservationResponse'
              example:
                code: OK
                data:
                  reservation_id: 88888888-8888-4888-8888-888888888881
                  order_id: 99999999-9999-4999-8999-999999999991
                  order_no: ORD202610000001
                  status: ACTIVE
                  expires_at: '2026-10-16T03:15:00.000Z'
                  items:
                  - sku: SKU-1
                    quantity: 2
                  version: 1
                  committed_at: null
                  released_at: null
                  release_reason: null
                  restore_required: false
                metadata: &reservationMetadata
                  request_id: 4c8a1f2e-0000-4000-8000-000000000001
                  trace_id: 4bf92f3577b34da6a3ce929d0e0e4736
        '201':
          description: Reservation created
          headers: *reservationHeaders
          content: *reservationContent
        '400': &reservation400
          description: VALIDATION_ERROR (INVALID_INPUT, INVALID_KEY, MALFORMED_JSON, REQUEST_HASH_MISMATCH)
          headers: *reservationHeaders
          content:
            application/json:
              schema:
                $ref: ./common.yaml#/components/schemas/ApiError
              example:
                code: VALIDATION_ERROR
                message: REQUEST_HASH_MISMATCH
                metadata: *reservationMetadata
        '401': &reservation401
          description: INVALID_SERVICE_TOKEN
          headers: *reservationHeaders
          content:
            application/json:
              schema:
                $ref: ./common.yaml#/components/schemas/ApiError
              example:
                code: UNAUTHORIZED
                message: INVALID_SERVICE_TOKEN
                metadata: *reservationMetadata
        '403': &reservation403
          description: CALLER_NOT_ALLOWED
          headers: *reservationHeaders
          content:
            application/json:
              schema:
                $ref: ./common.yaml#/components/schemas/ApiError
              example:
                code: FORBIDDEN
                message: CALLER_NOT_ALLOWED
                metadata: *reservationMetadata
        '409':
          description: OUT_OF_STOCK, RESERVATION_CONFLICT or RESERVATION_CLOSED
          headers: *reservationHeaders
          content:
            application/json:
              schema:
                $ref: ./common.yaml#/components/schemas/ApiError
              example:
                code: OUT_OF_STOCK
                message: OUT_OF_STOCK
                errors:
                - field: items[SKU-1]
                  message: requested=2 available=1
                metadata: *reservationMetadata
        '500': &reservation500
          description: Safe error envelope
          headers: *reservationHeaders
          content:
            application/json:
              schema:
                $ref: ./common.yaml#/components/schemas/ApiError
              example:
                code: INTERNAL
                message: INTERNAL
                metadata: *reservationMetadata
        '503': &reservation503
          description: Database unavailable
          headers: *reservationHeaders
          content:
            application/json:
              schema:
                $ref: ./common.yaml#/components/schemas/ApiError
              example:
                code: TEMPORARILY_UNAVAILABLE
                message: DEPENDENCY_UNAVAILABLE
                metadata: *reservationMetadata
      servers: &privateInventory
      - url: http://localhost:8083
        description: Private inventory service; bypasses public Gateway
  /internal/api/v1/inventory/commit:
    post:
      operationId: commitReservation
      summary: Deduct a reserved order
      description: >-
        TASK:INV-02. order-service only. ACTIVE and database now < expires_at deducts on_hand and reserved once; a repeated
        commit of a COMMITTED reservation returns 200 without deducting again. An ACTIVE reservation past its deadline is
        expired and released in the same transaction, which is committed before 409 RESERVATION_EXPIRED is returned.
        RELEASED returns 409 RESERVATION_CLOSED.
      tags:
      - Internal reservations
      x-implementation-status: implemented
      security:
      - serviceToken: []
      parameters:
      - $ref: ./common.yaml#/components/parameters/IdempotencyKey
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CommitRequest'
      responses:
        '200':
          description: COMMITTED (first or repeated)
          headers: *reservationHeaders
          content: *reservationContent
        '400': *reservation400
        '401': *reservation401
        '403': *reservation403
        '404': &reservation404
          description: RESERVATION_NOT_FOUND
          headers: *reservationHeaders
          content:
            application/json:
              schema:
                $ref: ./common.yaml#/components/schemas/ApiError
              example:
                code: NOT_FOUND
                message: RESERVATION_NOT_FOUND
                metadata: *reservationMetadata
        '409':
          description: RESERVATION_EXPIRED or RESERVATION_CLOSED
          headers: *reservationHeaders
          content:
            application/json:
              schema:
                $ref: ./common.yaml#/components/schemas/ApiError
              example:
                code: CONFLICT
                message: RESERVATION_EXPIRED
                metadata: *reservationMetadata
        '500': *reservation500
        '503': *reservation503
      servers: *privateInventory
  /internal/api/v1/inventory/release:
    post:
      operationId: releaseReservation
      summary: Release a reservation or record a tombstone
      description: >-
        TASK:INV-02. order-service only. original_request_hash must equal the hash recomputed from original_reservation
        (only its shape is validated, not the deadline). No reservation yet stores a RELEASED tombstone so a late reserve
        is refused. ACTIVE releases reserved stock; RELEASED/EXPIRED is a no-op; COMMITTED returns 200 with
        restore_required true and changes no stock (restore is INV-03). A stored reservation with another hash returns
        409 RESERVATION_CONFLICT.
      tags:
      - Internal reservations
      x-implementation-status: implemented
      security:
      - serviceToken: []
      parameters:
      - $ref: ./common.yaml#/components/parameters/IdempotencyKey
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ReleaseRequest'
      responses:
        '200':
          description: RELEASED, EXPIRED or COMMITTED (restore_required)
          headers: *reservationHeaders
          content: *reservationContent
        '400': *reservation400
        '401': *reservation401
        '403': *reservation403
        '409':
          description: RESERVATION_CONFLICT
          headers: *reservationHeaders
          content:
            application/json:
              schema:
                $ref: ./common.yaml#/components/schemas/ApiError
              example:
                code: CONFLICT
                message: RESERVATION_CONFLICT
                metadata: *reservationMetadata
        '500': *reservation500
        '503': *reservation503
      servers: *privateInventory
  /internal/api/v1/inventory/reservations/{order_id}:
    get:
      operationId: getReservation
      summary: Read a reservation by order
      description: TASK:INV-02. order-service only; token and caller are checked before order_id validation.
      tags:
      - Internal reservations
      x-implementation-status: implemented
      security:
      - serviceToken: []
      parameters:
      - name: order_id
        in: path
        required: true
        schema:
          type: string
          format: uuid
      responses:
        '200':
          description: Current reservation
          headers: *reservationHeaders
          content: *reservationContent
        '400': *reservation400
        '401': *reservation401
        '403': *reservation403
        '404': *reservation404
        '500': *reservation500
        '503': *reservation503
      servers: *privateInventory
```

Thêm vào `components.schemas` (sau `InternalStockResponse`):

```yaml
    ReservationItem:
      type: object
      required:
      - sku
      - quantity
      additionalProperties: false
      properties:
        sku:
          type: string
          minLength: 1
          maxLength: 64
          description: ASCII SKU; trimmed, uppercased and merged when repeated
        quantity:
          type: integer
          minimum: 1
          maximum: 99
    ReservationSnapshot:
      type: object
      required:
      - order_no
      - items
      - expires_at
      additionalProperties: false
      properties:
        order_no:
          type: string
          minLength: 1
          maxLength: 32
        items:
          type: array
          minItems: 1
          maxItems: 100
          items:
            $ref: '#/components/schemas/ReservationItem'
        expires_at:
          type: string
          format: date-time
          pattern: Z$
    ReserveRequest:
      type: object
      required:
      - order_id
      - order_no
      - items
      - expires_at
      - request_hash
      additionalProperties: false
      properties:
        order_id:
          type: string
          format: uuid
        order_no:
          type: string
          minLength: 1
          maxLength: 32
        items:
          type: array
          minItems: 1
          maxItems: 100
          items:
            $ref: '#/components/schemas/ReservationItem'
        expires_at:
          type: string
          format: date-time
          pattern: Z$
        request_hash:
          type: string
          pattern: ^[0-9a-f]{64}$
    CommitRequest:
      type: object
      required:
      - order_id
      additionalProperties: false
      properties:
        order_id:
          type: string
          format: uuid
    ReleaseRequest:
      type: object
      required:
      - order_id
      - original_reservation
      - original_request_hash
      - reason
      additionalProperties: false
      properties:
        order_id:
          type: string
          format: uuid
        original_reservation:
          $ref: '#/components/schemas/ReservationSnapshot'
        original_request_hash:
          type: string
          pattern: ^[0-9a-f]{64}$
        reason:
          type: string
          minLength: 1
          maxLength: 500
    Reservation:
      type: object
      required:
      - reservation_id
      - order_id
      - order_no
      - status
      - expires_at
      - items
      - version
      - committed_at
      - released_at
      - release_reason
      - restore_required
      additionalProperties: false
      properties:
        reservation_id:
          type: string
          format: uuid
        order_id:
          type: string
          format: uuid
        order_no:
          type: string
          minLength: 1
          maxLength: 32
        status:
          type: string
          enum:
          - ACTIVE
          - COMMITTED
          - RELEASED
          - EXPIRED
        expires_at:
          type: string
          format: date-time
          pattern: Z$
        items:
          type: array
          minItems: 1
          maxItems: 100
          items:
            type: object
            required:
            - sku
            - quantity
            additionalProperties: false
            properties:
              sku:
                type: string
                pattern: ^[A-Z0-9][A-Z0-9._-]{0,63}$
              quantity:
                type: integer
                minimum: 1
                maximum: 99
        version:
          type: integer
          minimum: 1
          maximum: 9223372036854775807
        committed_at:
          type:
          - string
          - 'null'
          format: date-time
        released_at:
          type:
          - string
          - 'null'
          format: date-time
        release_reason:
          type:
          - string
          - 'null'
          maxLength: 500
        restore_required:
          type: boolean
          description: true only when release finds a COMMITTED reservation
    ReservationResponse:
      type: object
      required:
      - code
      - data
      - metadata
      additionalProperties: false
      properties:
        code:
          type: string
          const: OK
        data:
          $ref: '#/components/schemas/Reservation'
        metadata:
          $ref: ./common.yaml#/components/schemas/Metadata
```

Message `RESERVATION_CONFLICT` thay cho cụm "409 CONFLICT" của spec để phân biệt với `IDEMPOTENCY_KEY_REUSED`; code vẫn `CONFLICT`.

- [ ] **Step 8: Chạy contract tests + lint**

Run: `python3 -B -m unittest discover -s tests/contracts -p 'test_*.py' -v` rồi `bash scripts/validate-contracts.sh`
Expected: tất cả PASS (gồm `test_event_routing` với hai dòng §5.2 mới, `test_every_valid_fixture_*`, Redocly lint 0 error).

- [ ] **Step 9: Commit**

```bash
git add contracts tests/contracts/test_inventory.py docs/design/03_interfaces.md
git commit -m "feat(INV-02): reservation event and internal API contracts"
```

---

### Task 2: Schema V002

**Files:**
- Create: `services/inventory-service/src/main/resources/db/migration/V002__stock_reservations.sql`
- Modify: `services/inventory-service/src/test/java/vn/fashion/inventory/InventoryTestSupport.java` (`clearInventory`)
- Create test: `services/inventory-service/src/test/java/vn/fashion/inventory/reservation/ReservationSchemaIntegrationTest.java`

**Interfaces:**
- Produces: bảng `stock_reservations(id, order_id, order_no, request_hash, status, expires_at, committed_at, released_at, release_reason, version, created_at, updated_at)`, `stock_reservation_items(reservation_id, sku, quantity)`.

- [ ] **Step 1: Test thất bại**

```java
package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import vn.fashion.inventory.InventoryTestSupport;

class ReservationSchemaIntegrationTest extends InventoryTestSupport {
  UUID insert(String status,String committedAt,String releasedAt) {
    UUID id=UUID.randomUUID();
    jdbc.update("insert into stock_reservations(id,order_id,order_no,request_hash,status,expires_at,committed_at,released_at,version)"
        +" values (?,?,?,?,?,now()+interval '10 minutes',"+committedAt+","+releasedAt+",1)",
        id,UUID.randomUUID(),"ORD-1","a".repeat(64),status);
    return id;
  }
  @Test void orderIdIsUnique() {
    UUID order=UUID.randomUUID();
    String sql="insert into stock_reservations(id,order_id,order_no,request_hash,status,expires_at,version) values (?,?,'ORD-1',?,'ACTIVE',now(),1)";
    jdbc.update(sql,UUID.randomUUID(),order,"a".repeat(64));
    assertThatThrownBy(()->jdbc.update(sql,UUID.randomUUID(),order,"b".repeat(64))).isInstanceOf(DataAccessException.class);
  }
  @Test void statusAndTimestampsAreConsistent() {
    assertThatThrownBy(()->insert("PENDING","null","null")).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(()->insert("COMMITTED","null","null")).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(()->insert("RELEASED","null","null")).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(()->insert("ACTIVE","now()","null")).isInstanceOf(DataAccessException.class);
    assertThatCode(()->insert("EXPIRED","null","now()")).doesNotThrowAnyException();
    assertThatCode(()->insert("COMMITTED","now()","null")).doesNotThrowAnyException();
  }
  @Test void itemQuantityAndSkuAreConstrained() {
    seedStock("SKU-1",5,0);UUID id=insert("ACTIVE","null","null");
    String sql="insert into stock_reservation_items(reservation_id,sku,quantity) values (?,?,?)";
    assertThatThrownBy(()->jdbc.update(sql,id,"SKU-1",0)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(()->jdbc.update(sql,id,"SKU-1",100)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(()->jdbc.update(sql,id,"NOPE",1)).isInstanceOf(DataAccessException.class);
    jdbc.update(sql,id,"SKU-1",99);
    assertThatThrownBy(()->jdbc.update(sql,id,"SKU-1",1)).isInstanceOf(DataAccessException.class);
  }
  @Test void runtimeCannotDeleteReservations() {
    UUID id=insert("ACTIVE","null","null");
    assertThatThrownBy(()->jdbc.update("delete from stock_reservations where id=?",id)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(()->jdbc.update("delete from stock_reservation_items")).isInstanceOf(DataAccessException.class);
  }
}
```

Trong `InventoryTestSupport.clearInventory` đổi lệnh xóa (superuser, theo thứ tự FK):

```java
      s.execute("delete from stock_reservation_items; delete from stock_reservations; delete from stock_transactions; delete from stock_items; delete from outbox_events; delete from processed_events; delete from idempotency_requests");
```

- [ ] **Step 2: Chạy, xác nhận fail**

Run: `./mvnw -B -pl services/inventory-service -am test -Dtest=ReservationSchemaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `relation "stock_reservations" does not exist`.

- [ ] **Step 3: Migration**

```sql
-- TASK:INV-02: whole-order reservations; terminal rows are kept as tombstones (05 section 8).
CREATE TABLE stock_reservations (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL UNIQUE,
  order_no varchar(32) NOT NULL CHECK (length(order_no) >= 1),
  request_hash char(64) NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED')),
  expires_at timestamptz NOT NULL,
  committed_at timestamptz,
  released_at timestamptz,
  release_reason text CHECK (release_reason IS NULL OR length(release_reason) BETWEEN 1 AND 500),
  version bigint NOT NULL CHECK (version >= 1),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((status = 'COMMITTED') = (committed_at IS NOT NULL)),
  CHECK ((status IN ('RELEASED','EXPIRED')) = (released_at IS NOT NULL))
);
CREATE INDEX stock_reservations_active_expiry_idx ON stock_reservations(expires_at) WHERE status = 'ACTIVE';
CREATE TABLE stock_reservation_items (
  reservation_id uuid NOT NULL REFERENCES stock_reservations(id),
  sku varchar(64) NOT NULL REFERENCES stock_items(sku),
  quantity int NOT NULL CHECK (quantity BETWEEN 1 AND 99),
  PRIMARY KEY (reservation_id, sku)
);
REVOKE DELETE, TRUNCATE ON stock_reservations, stock_reservation_items FROM inventory_runtime;
GRANT SELECT, INSERT, UPDATE ON stock_reservations TO inventory_runtime;
GRANT SELECT, INSERT ON stock_reservation_items TO inventory_runtime;
```

- [ ] **Step 4: Chạy lại** — cùng lệnh Step 2. Expected: 4 test PASS. Chạy thêm `-Dtest='Stock*IntegrationTest'` để chắc `clearInventory` mới không phá test INV-01: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/inventory-service/src
git commit -m "feat(INV-02): add stock reservation schema V002"
```

---

### Task 3: Snapshot, canonical hash và parse request

**Files:**
- Create: `.../inventory/reservation/ReservationSnapshot.java`, `.../inventory/reservation/ReservationRequests.java`
- Test: `.../inventory/reservation/ReservationRequestsTest.java` (unit, không Spring)

**Interfaces:**
- Produces:
  - `record ReservationSnapshot(UUID orderId, String orderNo, Instant expiresAt, List<Item> items)`; `record Item(String sku, int quantity)`; `String canonical()`; `String hash()`; `static String time(Instant)` (định dạng ms `Z`); `List<String> skus()` (đã sắp xếp).
  - `ReservationRequests.reserve(JsonNode) → ReserveCommand(ReservationSnapshot snapshot, String requestHash)`; `release(JsonNode) → ReleaseCommand(UUID orderId, ReservationSnapshot snapshot, String originalRequestHash, String reason)`; `commit(JsonNode) → UUID`; `orderId(String raw, String field) → UUID`. Mọi lỗi ném `Api.Problem` 400 `VALIDATION_ERROR` với message `INVALID_INPUT` (field rõ) hoặc `REQUEST_HASH_MISMATCH`.

- [ ] **Step 1: Test thất bại**

```java
package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.fashion.inventory.web.Api;

class ReservationRequestsTest {
  static final ObjectMapper JSON=JsonMapper.builder().build();
  static final UUID ORDER=UUID.fromString("99999999-9999-4999-8999-999999999991");
  static String sha(String s) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }
  String body(String orderNo,String items,String expires,String hash) {
    return "{\"order_id\":\""+ORDER+"\",\"order_no\":"+JSON.writeValueAsString(orderNo)+",\"items\":"+items
        +",\"expires_at\":\""+expires+"\",\"request_hash\":\""+hash+"\"}";
  }
  ReservationRequests.ReserveCommand parse(String json) { return ReservationRequests.reserve(JSON.readTree(json)); }
  Api.Problem problem(String json) {
    return catchThrowableOfType(Api.Problem.class,()->parse(json));
  }

  @Test void canonicalMergesSortsUppercasesAndHashes() throws Exception {
    var snap=new ReservationSnapshot(ORDER,"ORD-1",Instant.parse("2026-10-16T03:15:00Z"),
        List.of(new ReservationSnapshot.Item("sku-b",1),new ReservationSnapshot.Item("SKU-A",2),new ReservationSnapshot.Item(" sku-b ",3))).normalized();
    assertThat(snap.canonical()).isEqualTo(ORDER+"|ORD-1|2026-10-16T03:15:00.000Z|SKU-A:2,SKU-B:4");
    assertThat(snap.hash()).isEqualTo(sha(snap.canonical()));
  }
  @Test void escapingPreventsDelimiterCollision() {
    var t=Instant.parse("2026-10-16T03:15:00Z");var items=List.of(new ReservationSnapshot.Item("SKU-A",1));
    var pipe=new ReservationSnapshot(ORDER,"A|B",t,items).normalized();
    var slash=new ReservationSnapshot(ORDER,"A\\|B",t,items).normalized();
    assertThat(pipe.canonical()).contains("|A\\|B|");
    assertThat(slash.canonical()).contains("|A\\\\\\|B|");
    assertThat(pipe.hash()).isNotEqualTo(slash.hash());
  }
  @Test void validReserveParsesWithMatchingHash() {
    var snap=new ReservationSnapshot(ORDER,"ORD-1",Instant.parse("2026-10-16T03:15:00.123Z"),
        List.of(new ReservationSnapshot.Item("SKU-A",2))).normalized();
    var cmd=parse(body("ORD-1","[{\"sku\":\"sku-a\",\"quantity\":2}]","2026-10-16T03:15:00.123Z",snap.hash()));
    assertThat(cmd.snapshot()).isEqualTo(snap);assertThat(cmd.requestHash()).isEqualTo(snap.hash());
  }
  @Test void hashMismatchIs400() {
    var p=problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":2}]","2026-10-16T03:15:00Z","0".repeat(64)));
    assertThat(p.status().value()).isEqualTo(400);assertThat(p.getMessage()).isEqualTo("REQUEST_HASH_MISMATCH");
  }
  @Test void shapeErrorsNameTheField() {
    String h="0".repeat(64),t="2026-10-16T03:15:00Z",one="[{\"sku\":\"SKU-A\",\"quantity\":1}]";
    assertThat(problem(body("ORD-1","[]",t,h)).errors().get(0).field()).isEqualTo("items");
    assertThat(problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":0}]",t,h)).errors().get(0).field()).isEqualTo("items[0].quantity");
    assertThat(problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":100}]",t,h)).errors().get(0).field()).isEqualTo("items[0].quantity");
    assertThat(problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":60},{\"sku\":\"sku-a\",\"quantity\":40}]",t,h)).errors().get(0).field()).isEqualTo("items");
    assertThat(problem(body("ORD-1","[{\"sku\":\"BAD@\",\"quantity\":1}]",t,h)).errors().get(0).field()).isEqualTo("items[0].sku");
    assertThat(problem(body("x".repeat(33),one,t,h)).errors().get(0).field()).isEqualTo("order_no");
    assertThat(problem(body("ORD-1",one,"2026-10-16T03:15:00.1234Z",h)).errors().get(0).field()).isEqualTo("expires_at");
    assertThat(problem(body("ORD-1",one,"2026-10-16T10:15:00+07:00",h)).errors().get(0).field()).isEqualTo("expires_at");
    assertThat(problem(body("ORD-1",one,t,"A".repeat(64))).errors().get(0).field()).isEqualTo("request_hash");
    var many=new StringBuilder("[");for(int i=0;i<101;i++) many.append(i==0?"":",").append("{\"sku\":\"S").append(i).append("\",\"quantity\":1}");
    assertThat(problem(body("ORD-1",many+"]",t,h)).errors().get(0).field()).isEqualTo("items");
    var extra=body("ORD-1",one,t,h).replace("{\"order_id\"","{\"extra\":1,\"order_id\"");
    assertThat(problem(extra).errors().get(0).field()).isEqualTo("extra");
    assertThat(catchThrowableOfType(Api.Problem.class,()->ReservationRequests.reserve(JSON.readTree("[1]"))).errors().get(0).field()).isEqualTo("body");
  }
  @Test void releaseRecomputesFromOriginalSnapshotWithoutDeadlineCheck() {
    var snap=new ReservationSnapshot(ORDER,"ORD-1",Instant.parse("2000-01-01T00:00:00Z"),List.of(new ReservationSnapshot.Item("SKU-A",1))).normalized();
    var json="{\"order_id\":\""+ORDER+"\",\"original_reservation\":{\"order_no\":\"ORD-1\",\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1}],"
        +"\"expires_at\":\"2000-01-01T00:00:00Z\"},\"original_request_hash\":\""+snap.hash()+"\",\"reason\":\" cancel \"}";
    var cmd=ReservationRequests.release(JSON.readTree(json));
    assertThat(cmd.snapshot()).isEqualTo(snap);assertThat(cmd.reason()).isEqualTo("cancel");
    var wrong=json.replace(snap.hash(),"1".repeat(64));
    assertThat(catchThrowableOfType(Api.Problem.class,()->ReservationRequests.release(JSON.readTree(wrong))).getMessage()).isEqualTo("REQUEST_HASH_MISMATCH");
  }
  @Test void commitNeedsOnlyOrderId() {
    assertThat(ReservationRequests.commit(JSON.readTree("{\"order_id\":\""+ORDER+"\"}"))).isEqualTo(ORDER);
    assertThat(catchThrowableOfType(Api.Problem.class,()->ReservationRequests.commit(JSON.readTree("{\"order_id\":\"nope\"}"))).errors().get(0).field()).isEqualTo("order_id");
  }
}
```

- [ ] **Step 2: Chạy, xác nhận fail**

Run: `./mvnw -B -pl services/inventory-service -am test -Dtest=ReservationRequestsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL biên dịch — `ReservationSnapshot`/`ReservationRequests` chưa có.

- [ ] **Step 3: `ReservationSnapshot`**

```java
package vn.fashion.inventory.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import vn.fashion.inventory.stock.Sku;

/** Normalized order snapshot; request_hash is computed here, never trusted from the caller (03 section 1.3). */
public record ReservationSnapshot(UUID orderId,String orderNo,Instant expiresAt,List<Item> items) {
  public record Item(String sku,int quantity) { }
  private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  public static String time(Instant t) { return TIME.format(t); }
  /** Canonical SKUs merged (quantities summed) and sorted; quantity limits are checked by the caller. */
  public ReservationSnapshot normalized() {
    var merged=new TreeMap<String,Integer>();
    for(var item:items) merged.merge(Sku.canonical(item.sku()),item.quantity(),Integer::sum);
    return new ReservationSnapshot(orderId,orderNo,expiresAt,
        merged.entrySet().stream().map(e->new Item(e.getKey(),e.getValue())).toList());
  }
  public List<String> skus() { return items.stream().map(Item::sku).toList(); }
  public String canonical() {
    String escaped=orderNo.replace("\\","\\\\").replace("|","\\|");
    return orderId+"|"+escaped+"|"+time(expiresAt)+"|"
        +items.stream().map(i->i.sku()+":"+i.quantity()).collect(Collectors.joining(","));
  }
  public String hash() {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical().getBytes(StandardCharsets.UTF_8))); }
    catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
```

- [ ] **Step 4: `ReservationRequests`**

```java
package vn.fashion.inventory.reservation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import vn.fashion.inventory.stock.Sku;
import vn.fashion.inventory.web.Api;

/** Parses internal reservation bodies after authentication; every failure is a 400 naming the field. */
public final class ReservationRequests {
  public record ReserveCommand(ReservationSnapshot snapshot,String requestHash) { }
  public record ReleaseCommand(UUID orderId,ReservationSnapshot snapshot,String originalRequestHash,String reason) { }
  private ReservationRequests() { }

  public static ReserveCommand reserve(JsonNode body) {
    closed(body,"",Set.of("order_id","order_no","items","expires_at","request_hash"));
    var snap=snapshot(orderId(text(body,"order_id","order_id"),"order_id"),body,"");
    return new ReserveCommand(snap,matching(snap,text(body,"request_hash","request_hash"),"request_hash"));
  }
  public static ReleaseCommand release(JsonNode body) {
    closed(body,"",Set.of("order_id","original_reservation","original_request_hash","reason"));
    UUID order=orderId(text(body,"order_id","order_id"),"order_id");
    var original=body.get("original_reservation");
    closed(original,"original_reservation.",Set.of("order_no","items","expires_at"));
    var snap=snapshot(order,original,"original_reservation.");
    String hash=matching(snap,text(body,"original_request_hash","original_request_hash"),"original_request_hash");
    String reason=text(body,"reason","reason").strip();
    if(reason.isEmpty() || reason.length()>500) throw invalid("reason","must be 1..500 characters");
    return new ReleaseCommand(order,snap,hash,reason);
  }
  public static UUID commit(JsonNode body) {
    closed(body,"",Set.of("order_id"));
    return orderId(text(body,"order_id","order_id"),"order_id");
  }
  public static UUID orderId(String raw,String field) {
    try { UUID id=UUID.fromString(raw); if(id.toString().equals(raw.toLowerCase(Locale.ROOT))) return id; }
    catch(IllegalArgumentException ignored) { }
    throw invalid(field,"must be a UUID");
  }

  private static ReservationSnapshot snapshot(UUID order,JsonNode node,String prefix) {
    String orderNo=text(node,"order_no",prefix+"order_no");
    if(orderNo.isEmpty() || orderNo.length()>32) throw invalid(prefix+"order_no","must be 1..32 characters");
    var raw=node.get("items");
    if(raw==null || !raw.isArray() || raw.isEmpty() || raw.size()>100) throw invalid(prefix+"items","must have 1..100 items");
    var items=new ArrayList<ReservationSnapshot.Item>();
    for(int i=0;i<raw.size();i++) {
      String at=prefix+"items["+i+"].";var item=raw.get(i);
      closed(item,at,Set.of("sku","quantity"));
      String sku;
      try { sku=Sku.canonical(text(item,"sku",at+"sku")); }
      catch(Api.Problem e) { throw invalid(at+"sku","must be 1..64 ASCII SKU characters"); }
      var q=item.get("quantity");
      if(q==null || !q.isIntegralNumber() || !q.canConvertToInt() || q.intValue()<1 || q.intValue()>99)
        throw invalid(at+"quantity","must be an integer 1..99");
      items.add(new ReservationSnapshot.Item(sku,q.intValue()));
    }
    var snap=new ReservationSnapshot(order,orderNo,instant(text(node,"expires_at",prefix+"expires_at"),prefix+"expires_at"),items).normalized();
    if(snap.items().stream().anyMatch(i->i.quantity()>99)) throw invalid(prefix+"items","merged quantity must be at most 99 per SKU");
    return snap;
  }
  private static Instant instant(String raw,String field) {
    try {
      if(raw.endsWith("Z")) { var t=Instant.parse(raw); if(t.getNano()%1_000_000==0) return t; }
    } catch(DateTimeParseException ignored) { }
    throw invalid(field,"must be a UTC timestamp ending in Z with at most millisecond precision");
  }
  private static String matching(ReservationSnapshot snap,String hash,String field) {
    if(!hash.matches("[0-9a-f]{64}")) throw invalid(field,"must be 64 lowercase hex characters");
    if(!snap.hash().equals(hash)) throw new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","REQUEST_HASH_MISMATCH",List.of());
    return hash;
  }
  private static void closed(JsonNode node,String prefix,Set<String> allowed) {
    if(node==null || !node.isObject()) throw invalid(prefix.isEmpty()?"body":prefix.substring(0,prefix.length()-1),"must be a JSON object");
    for(String name:node.propertyNames()) if(!allowed.contains(name)) throw invalid(prefix+name,"is not allowed");
  }
  private static String text(JsonNode node,String name,String field) {
    var value=node.get(name);
    if(value==null || !value.isString()) throw invalid(field,"is required");
    return value.asString();
  }
  static Api.Problem invalid(String field,String message) {
    return new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","INVALID_INPUT",List.of(new Api.FieldError(field,message)));
  }
}
```

- [ ] **Step 5: Chạy lại** — lệnh Step 2. Expected: 7 test PASS.

- [ ] **Step 6: Commit**

```bash
git add services/inventory-service/src
git commit -m "feat(INV-02): canonical reservation snapshot and request parsing"
```

---

### Task 4: Reserve, query, auth và no-store

**Files:**
- Create: `.../inventory/internal/InternalCallers.java`, `.../inventory/internal/InternalNoStoreFilter.java`, `.../inventory/reservation/ReservationRow.java`, `ReservationView.java`, `ReservationRepository.java`, `ReservationEvents.java`, `ReservationService.java`, `ReservationController.java`
- Modify: `.../inventory/internal/InternalStockController.java`, `.../inventory/stock/StockEvents.java`
- Test: `.../inventory/reservation/ReservationTestSupport.java`, `ReserveIntegrationTest.java`

**Interfaces:**
- Consumes: `ReservationRequests`, `ReservationSnapshot` (Task 3); `StockAdminService.lockForUpdate(List<String> sortedSkus) → Map<String,StockRow>`; `StockEvents.append(StockRow after, boolean restocked, String correlationId)`; `AdminCommands.requireKey(String)`.
- Produces:
  - `InternalCallers.require(String authorization, Set<String> allowed) → String caller` (bean, audience `inventory-service`).
  - `ReservationService.Outcome(int status, String error, ReservationView view)`; `reserve(ReserveCommand, String traceId)`, `find(UUID orderId) → ReservationView` (404 nếu không có); Task 5/6/7 thêm `commit`, `release`, `expireDue`.
  - `ReservationRepository`: `lock(UUID orderId) → Optional<ReservationRow>`, `find(UUID orderId)`, `insertIfAbsent(UUID id, ReservationSnapshot, String hash, String status, String reason) → boolean`, `insertItems(UUID id, List<Item>)`, `items(UUID id) → List<Item>`, `transition(UUID id, String status, String reason) → ReservationRow`, `dueIds(int limit) → List<UUID>`, `lockDue(UUID id) → Optional<ReservationRow>`, `overdueCount() → long`.
  - `ReservationEvents.append(String type, ReservationRow row, List<Item> items, String correlationId)`.
  - Test helper `ReservationTestSupport` (abstract, extends `InventoryTestSupport`): `orderToken()`, `snapshot(UUID order, Instant expires, Object... skuQty)`, `reserve(ReservationSnapshot)`, `commit(UUID)`, `release(ReservationSnapshot, String reason)`, `get(UUID)`, `noStore(HttpResponse)`, `reserved(String sku)`, `onHand(String sku)`, `ledger(String type)`.

- [ ] **Step 1: Test helper + test thất bại**

`ReservationTestSupport.java`:

```java
package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import vn.fashion.inventory.InventoryTestSupport;

abstract class ReservationTestSupport extends InventoryTestSupport {
  static final String BASE="/internal/api/v1/inventory";
  String orderToken() { return serviceToken("order-service"); }
  Instant in(long minutes) { return Instant.now().plus(minutes,ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS); }
  ReservationSnapshot snapshot(UUID order,Instant expires,Object... skuQty) {
    var items=new ArrayList<ReservationSnapshot.Item>();
    for(int i=0;i<skuQty.length;i+=2) items.add(new ReservationSnapshot.Item((String)skuQty[i],(Integer)skuQty[i+1]));
    return new ReservationSnapshot(order,"ORD-"+order.toString().substring(0,8),expires,items).normalized();
  }
  Map<String,Object> body(ReservationSnapshot s) {
    var items=s.items().stream().map(i->Map.of("sku",i.sku(),"quantity",i.quantity())).toList();
    return Map.of("order_id",s.orderId().toString(),"order_no",s.orderNo(),"items",items,
        "expires_at",ReservationSnapshot.time(s.expiresAt()),"request_hash",s.hash());
  }
  HttpResponse<String> post(String op,Object body) {
    return send("POST",BASE+"/"+op,orderToken(),UUID.randomUUID().toString(),mapper.writeValueAsString(body));
  }
  HttpResponse<String> reserve(ReservationSnapshot s) { return post("reserve",body(s)); }
  HttpResponse<String> commit(UUID order) { return post("commit",Map.of("order_id",order.toString())); }
  HttpResponse<String> release(ReservationSnapshot s,String reason) {
    var original=new LinkedHashMap<>(body(s));original.remove("order_id");original.remove("request_hash");
    return post("release",Map.of("order_id",s.orderId().toString(),"original_reservation",original,
        "original_request_hash",s.hash(),"reason",reason));
  }
  HttpResponse<String> get(UUID order) { return send("GET",BASE+"/reservations/"+order,orderToken(),null,null); }
  void noStore(HttpResponse<String> r) { assertThat(r.headers().firstValue("Cache-Control")).contains("no-store"); }
  int reserved(String sku) { return jdbc.queryForObject("select reserved from stock_items where sku=?",Integer.class,sku); }
  int onHand(String sku) { return jdbc.queryForObject("select on_hand from stock_items where sku=?",Integer.class,sku); }
  int ledger(String type) { return jdbc.queryForObject("select count(*) from stock_transactions where type=?",Integer.class,type); }
  int outbox(String type) { return jdbc.queryForObject("select count(*) from outbox_events where event_type=?",Integer.class,type); }
  String problem(HttpResponse<String> r,int status) {
    assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(status);noStore(r);return json(r).path("message").asString();
  }
}
```

`ReserveIntegrationTest.java`:

```java
package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ReserveIntegrationTest extends ReservationTestSupport {
  @Test void reservesAllSkusWithLedgerAndEvents() {
    seedStock("SKU-A",5,0);seedStock("SKU-B",3,1);UUID order=UUID.randomUUID();
    var s=snapshot(order,in(15),"sku-b",2,"SKU-A",1);
    var r=reserve(s);var d=data(r,201);noStore(r);
    assertThat(d.path("status").asString()).isEqualTo("ACTIVE");assertThat(d.path("version").asLong()).isEqualTo(1);
    assertThat(d.path("items").toString()).isEqualTo("[{\"sku\":\"SKU-A\",\"quantity\":1},{\"sku\":\"SKU-B\",\"quantity\":2}]");
    assertThat(d.path("restore_required").asBoolean()).isFalse();
    assertThat(reserved("SKU-A")).isEqualTo(1);assertThat(reserved("SKU-B")).isEqualTo(3);
    assertThat(jdbc.queryForList("select operation_key from stock_transactions where type='RESERVE' order by sku",String.class))
        .containsExactly("order:"+order+":RESERVE","order:"+order+":RESERVE");
    assertThat(outbox("INVENTORY_RESERVED")).isEqualTo(1);assertThat(outbox("INVENTORY_UPDATED")).isEqualTo(2);
    var event=jdbc.queryForMap("select aggregate_type,aggregate_id,aggregate_version,partition_key from outbox_events where event_type='INVENTORY_RESERVED'");
    assertThat(event).containsEntry("aggregate_type","reservation").containsEntry("aggregate_id",d.path("reservation_id").asString())
        .containsEntry("aggregate_version",1L).containsEntry("partition_key",s.orderNo());
  }
  @Test void t01LastUnitGoesToExactlyOneOrder() throws Exception {
    seedStock("SKU-A",1,0);var gate=new CountDownLatch(1);var codes=new ConcurrentLinkedQueue<Integer>();
    try(var pool=Executors.newFixedThreadPool(8)) {
      for(int i=0;i<8;i++) pool.submit(()->{gate.await();codes.add(reserve(snapshot(UUID.randomUUID(),in(15),"SKU-A",1)).statusCode());return null;});
      gate.countDown();
    }
    assertThat(codes).filteredOn(c->c==201).hasSize(1);assertThat(codes).filteredOn(c->c==409).hasSize(7);
    assertThat(reserved("SKU-A")).isEqualTo(1);assertThat(ledger("RESERVE")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from stock_reservations",Integer.class)).isEqualTo(1);
  }
  @Test void t02OneShortSkuRollsBackEverything() {
    seedStock("SKU-A",5,0);seedStock("SKU-B",1,0);
    jdbc.update("insert into stock_items(sku,product_id,variant_id,on_hand,status) values ('SKU-C',?,?,9,'INACTIVE')",UUID.randomUUID(),UUID.randomUUID());
    var r=reserve(snapshot(UUID.randomUUID(),in(15),"SKU-A",2,"SKU-B",2,"SKU-C",1,"SKU-Z",1));
    assertThat(problem(r,409)).isEqualTo("OUT_OF_STOCK");assertThat(json(r).path("code").asString()).isEqualTo("OUT_OF_STOCK");
    assertThat(json(r).path("errors").toString()).contains("items[SKU-B]","requested=2 available=1","items[SKU-C]","requested=1 available=0","items[SKU-Z]")
        .doesNotContain("items[SKU-A]");
    assertThat(reserved("SKU-A")).isZero();assertThat(ledger("RESERVE")).isZero();
    assertThat(jdbc.queryForObject("select count(*) from stock_reservations",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from outbox_events",Integer.class)).isZero();
  }
  @Test void t03RetrySameHashReturnsExistingOnce() throws Exception {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",2);
    var first=data(reserve(s),201);
    var again=data(reserve(s),200);
    assertThat(again.path("reservation_id")).isEqualTo(first.path("reservation_id"));
    var gate=new CountDownLatch(1);var codes=new ConcurrentLinkedQueue<Integer>();var t=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);
    try(var pool=Executors.newFixedThreadPool(6)) {
      for(int i=0;i<6;i++) pool.submit(()->{gate.await();codes.add(reserve(t).statusCode());return null;});
      gate.countDown();
    }
    assertThat(codes).containsOnly(200,201).filteredOn(c->c==201).hasSize(1);
    assertThat(reserved("SKU-A")).isEqualTo(3);assertThat(ledger("RESERVE")).isEqualTo(2);
  }
  @Test void sameOrderDifferentHashIsConflict() {
    seedStock("SKU-A",5,0);UUID order=UUID.randomUUID();
    data(reserve(snapshot(order,in(15),"SKU-A",1)),201);
    assertThat(problem(reserve(snapshot(order,in(15),"SKU-A",2)),409)).isEqualTo("RESERVATION_CONFLICT");
    assertThat(reserved("SKU-A")).isEqualTo(1);
  }
  @Test void replayAfterDeadlineReturnsExistingReservation() {
    // Retry of a request whose expires_at already passed: the stored row answers before the deadline check.
    seedStock("SKU-A",5,1);var s=snapshot(UUID.randomUUID(),in(-5),"SKU-A",1);UUID id=UUID.randomUUID();
    jdbc.update("insert into stock_reservations(id,order_id,order_no,request_hash,status,expires_at,version) values (?,?,?,?,'ACTIVE',now()-interval '5 minutes',1)",
        id,s.orderId(),s.orderNo(),s.hash());
    jdbc.update("insert into stock_reservation_items(reservation_id,sku,quantity) values (?,'SKU-A',1)",id);
    var d=data(reserve(s),200);
    assertThat(d.path("reservation_id").asString()).isEqualTo(id.toString());assertThat(reserved("SKU-A")).isEqualTo(1);
  }
  @Test void deadlineBoundsUseDatabaseClock() {
    seedStock("SKU-A",5,0);
    var r=reserve(snapshot(UUID.randomUUID(),in(-1),"SKU-A",1));
    assertThat(problem(r,400)).isEqualTo("INVALID_INPUT");assertThat(json(r).path("errors").get(0).path("field").asString()).isEqualTo("expires_at");
    assertThat(problem(reserve(snapshot(UUID.randomUUID(),in(24*60+1),"SKU-A",1)),400)).isEqualTo("INVALID_INPUT");
    assertThat(reserve(snapshot(UUID.randomUUID(),in(24*60-1),"SKU-A",1)).statusCode()).isEqualTo(201);
    assertThat(jdbc.queryForObject("select count(*) from stock_reservations",Integer.class)).isEqualTo(1);
  }
  @Test void authCallerAndKeyAreCheckedBeforeBody() {
    var path=BASE+"/reserve";
    assertThat(problem(send("POST",path,null,"k","not json"),401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("POST",path,serviceToken("cart-service"),"k","not json"),403)).isEqualTo("CALLER_NOT_ALLOWED");
    assertThat(problem(send("POST",path,orderToken(),null,"{}"),400)).isEqualTo("INVALID_KEY");
  }
  @Test void malformedBodyIs400OnlyAfterAuth() {
    var path=BASE+"/reserve";
    assertThat(problem(send("POST",path,orderToken(),"k","not json"),400)).isEqualTo("MALFORMED_JSON");
    assertThat(problem(send("POST",path,orderToken(),"k","[1]"),400)).isEqualTo("INVALID_INPUT");
    assertThat(problem(send("POST",path,orderToken(),"k",null),400)).isEqualTo("MALFORMED_JSON");
  }
  @Test void getReturnsReservationAnd404() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);data(reserve(s),201);
    var r=get(s.orderId());var d=data(r,200);noStore(r);
    assertThat(d.path("order_no").asString()).isEqualTo(s.orderNo());
    assertThat(d.path("expires_at").asString()).isEqualTo(ReservationSnapshot.time(s.expiresAt()));
    assertThat(problem(get(UUID.randomUUID()),404)).isEqualTo("RESERVATION_NOT_FOUND");
    assertThat(problem(send("GET",BASE+"/reservations/nope",null,null,null),401)).isEqualTo("INVALID_SERVICE_TOKEN");
    assertThat(problem(send("GET",BASE+"/reservations/nope",orderToken(),null,null),400)).isEqualTo("INVALID_INPUT");
  }
  @Test void reserveConcurrentWithAdjustDoesNotDeadlock() throws Exception {
    seedStock("SKU-A",1000,0);seedStock("SKU-B",1000,0);seedStock("SKU-C",1000,0);
    var gate=new CountDownLatch(1);var codes=new ConcurrentLinkedQueue<Integer>();
    try(var pool=Executors.newFixedThreadPool(16)) {
      for(int i=0;i<8;i++) {
        pool.submit(()->{gate.await();codes.add(reserve(snapshot(UUID.randomUUID(),in(15),"SKU-C",1,"SKU-A",1,"SKU-B",1)).statusCode());return null;});
        String sku=i%2==0?"SKU-C":"SKU-A";
        pool.submit(()->{gate.await();codes.add(call("POST","/adjust",Map.of("sku",sku,"delta",1,"reason","race")).statusCode());return null;});
      }
      gate.countDown();pool.shutdown();assertThat(pool.awaitTermination(60,TimeUnit.SECONDS)).isTrue();
    }
    assertThat(codes).hasSize(16).containsOnly(200,201);
  }
}
```

- [ ] **Step 2: Chạy, xác nhận fail**

Run: `./mvnw -B -pl services/inventory-service -am test -Dtest=ReserveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — route `/internal/api/v1/inventory/reserve` chưa có (404 khác kỳ vọng) / class chưa có.

- [ ] **Step 3: Auth dùng chung + no-store filter**

`InternalCallers.java`:

```java
package vn.fashion.inventory.internal;

import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.fashion.inventory.web.Api;
import vn.fashion.platform.security.*;

/** ADR-19 service token check shared by internal routes: token first, then the per-route caller allowlist. */
@Component
public class InternalCallers {
  private final ServiceTokenVerifier verifier;
  public InternalCallers(@Value("${fashion.inventory.internal-callers}") String keys) {
    verifier=new ServiceTokenVerifier("inventory-service",keys.isBlank()?Map.of():AccessTokenVerifier.parseKeys(keys),Clock.systemUTC());
  }
  public String require(String authorization,Set<String> allowed) {
    String token=authorization!=null && authorization.regionMatches(true,0,"Bearer ",0,7)?authorization.substring(7).strip():null;
    String caller=verifier.verify(token).orElseThrow(()->new Api.Problem(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","INVALID_SERVICE_TOKEN",List.of()));
    if(!allowed.contains(caller)) throw new Api.Problem(HttpStatus.FORBIDDEN,"FORBIDDEN","CALLER_NOT_ALLOWED",List.of());
    return caller;
  }
}
```

`InternalNoStoreFilter.java`:

```java
package vn.fashion.inventory.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Internal stock/reservation data and errors are never cached, including errors raised before a controller runs. */
@Component
public class InternalNoStoreFilter extends OncePerRequestFilter {
  @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getServletPath().startsWith("/internal/"); }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    response.setHeader("Cache-Control","no-store");
    chain.doFilter(request,response);
  }
}
```

`InternalStockController` dùng `InternalCallers` (bỏ `ServiceTokenVerifier` riêng và `response.setHeader` vì filter đã đặt):

```java
@RestController
public class InternalStockController {
  private static final Set<String> ALLOWED_CALLERS=Set.of("catalog-service","cart-service");
  private final InternalCallers callers;
  private final StockQueries stocks;
  private final Tracer tracer;
  public InternalStockController(InternalCallers callers,StockQueries stocks,Tracer tracer) {
    this.callers=callers;this.stocks=stocks;this.tracer=tracer;
  }
  @GetMapping("/internal/api/v1/inventory/skus/{sku}")
  public ResponseEntity<?> get(@RequestHeader(name="Authorization",required=false) String authorization,@PathVariable String sku) {
    callers.require(authorization,ALLOWED_CALLERS);
    var row=stocks.stock(Sku.canonical(sku));
    return Api.ok(HttpStatus.OK,Map.of("sku",row.sku(),"variant_id",row.variantId(),"available",row.available(),
        "version",row.version(),"observed_at",row.updatedAt().toString()),tracer);
  }
}
```

(Giữ import cần thiết; xóa import `Clock`, `@Value`, `HttpServletResponse`, `vn.fashion.platform.security.*` không dùng nữa.)

`StockEvents`: tách đọc registry thành static để reservation dùng lại:

```java
  public StockEvents(OutboxRepository outbox,ObjectMapper json) {
    this.outbox=outbox;this.json=json;this.routes=loadRoutes(json);
  }
  public static JsonNode loadRoutes(ObjectMapper json) {
    try(var resource=StockEvents.class.getResourceAsStream("/contracts/events/registry.json")) {
      if(resource==null) throw new IllegalStateException("Event registry missing");
      return json.readTree(resource);
    } catch(IOException e) { throw new IllegalStateException("Cannot read event registry",e); }
  }
```

- [ ] **Step 4: Row, view, repository, events**

`ReservationRow.java`:

```java
package vn.fashion.inventory.reservation;

import java.time.Instant;
import java.util.UUID;

/** stock_reservations row; due = expires_at <= now() evaluated by the database in the same statement. */
public record ReservationRow(UUID id,UUID orderId,String orderNo,String requestHash,String status,Instant expiresAt,
    Instant committedAt,Instant releasedAt,String releaseReason,long version,boolean due) { }
```

`ReservationView.java`:

```java
package vn.fashion.inventory.reservation;

import java.util.List;
import java.util.UUID;

public record ReservationView(UUID reservationId,UUID orderId,String orderNo,String status,String expiresAt,
    List<ReservationSnapshot.Item> items,long version,String committedAt,String releasedAt,String releaseReason,
    boolean restoreRequired) {
  static ReservationView of(ReservationRow r,List<ReservationSnapshot.Item> items,boolean restoreRequired) {
    return new ReservationView(r.id(),r.orderId(),r.orderNo(),r.status(),ReservationSnapshot.time(r.expiresAt()),items,r.version(),
        r.committedAt()==null?null:r.committedAt().toString(),r.releasedAt()==null?null:r.releasedAt().toString(),
        r.releaseReason(),restoreRequired);
  }
}
```

`ReservationRepository.java`:

```java
package vn.fashion.inventory.reservation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {
  private static final String COLUMNS="r.*, r.expires_at <= now() as due";
  private final JdbcClient jdbc;
  public ReservationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
  public Optional<ReservationRow> lock(UUID orderId) {
    return jdbc.sql("select "+COLUMNS+" from stock_reservations r where order_id=:o for update").param("o",orderId)
        .query(ReservationRow.class).optional();
  }
  public Optional<ReservationRow> find(UUID orderId) {
    return jdbc.sql("select "+COLUMNS+" from stock_reservations r where order_id=:o").param("o",orderId)
        .query(ReservationRow.class).optional();
  }
  /** Inserts ACTIVE or a RELEASED tombstone; false when the order already has a row (waits for a concurrent insert). */
  public boolean insertIfAbsent(UUID id,ReservationSnapshot s,String hash,String status,String reason) {
    return jdbc.sql("""
        insert into stock_reservations(id,order_id,order_no,request_hash,status,expires_at,released_at,release_reason,version)
        values (:id,:o,:no,:hash,:status,:expires,case when :status='ACTIVE' then null else now() end,:reason,1)
        on conflict (order_id) do nothing
        """).param("id",id).param("o",s.orderId()).param("no",s.orderNo()).param("hash",hash).param("status",status)
        .param("expires",OffsetDateTime.ofInstant(s.expiresAt(),ZoneOffset.UTC)).param("reason",reason).update()==1;
  }
  public void insertItems(UUID id,List<ReservationSnapshot.Item> items) {
    for(var i:items) jdbc.sql("insert into stock_reservation_items(reservation_id,sku,quantity) values (:id,:sku,:q)")
        .param("id",id).param("sku",i.sku()).param("q",i.quantity()).update();
  }
  public List<ReservationSnapshot.Item> items(UUID id) {
    return jdbc.sql("select sku,quantity from stock_reservation_items where reservation_id=:id order by sku").param("id",id)
        .query((rs,n)->new ReservationSnapshot.Item(rs.getString("sku"),rs.getInt("quantity"))).list();
  }
  public ReservationRow transition(UUID id,String status,String reason) {
    return jdbc.sql("""
        update stock_reservations r set status=:status,version=version+1,updated_at=now(),
          committed_at=case when :status='COMMITTED' then now() else committed_at end,
          released_at=case when :status in ('RELEASED','EXPIRED') then now() else released_at end,
          release_reason=coalesce(:reason,release_reason)
        where id=:id returning r.*, r.expires_at <= now() as due
        """).param("id",id).param("status",status).param("reason",reason).query(ReservationRow.class).single();
  }
  public List<UUID> dueIds(int limit) {
    return jdbc.sql("select id from stock_reservations where status='ACTIVE' and expires_at<=now() order by expires_at limit :n")
        .param("n",limit).query(UUID.class).list();
  }
  /** Skips a row another transaction holds (a commit in flight), so the sweep never waits on checkout. */
  public Optional<ReservationRow> lockDue(UUID id) {
    return jdbc.sql("select "+COLUMNS+" from stock_reservations r where id=:id and status='ACTIVE' and expires_at<=now() for update skip locked")
        .param("id",id).query(ReservationRow.class).optional();
  }
  public long overdueCount() {
    return jdbc.sql("select count(*) from stock_reservations where status='ACTIVE' and expires_at<=now()").query(Long.class).single();
  }
}
```

`ReservationEvents.java`:

```java
package vn.fashion.inventory.reservation;

import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.inventory.stock.StockEvents;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

@Component
public class ReservationEvents {
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final JsonNode routes;
  public ReservationEvents(OutboxRepository outbox,ObjectMapper json) {
    this.outbox=outbox;this.json=json;this.routes=StockEvents.loadRoutes(json);
  }
  public void append(String type,ReservationRow row,List<ReservationSnapshot.Item> items,String correlationId) {
    var payload=new LinkedHashMap<String,Object>();
    payload.put("order_id",row.orderId().toString());payload.put("order_no",row.orderNo());
    payload.put("reservation_id",row.id().toString());payload.put("status",row.status());
    payload.put("expires_at",ReservationSnapshot.time(row.expiresAt()));
    payload.put("items",items.stream().map(i->Map.of("sku",i.sku(),"quantity",i.quantity())).toList());
    payload.put("reason",row.releaseReason());payload.put("version",row.version());
    var route=routes.required(type);
    outbox.append(new OutboxEvent(UUID.randomUUID(),"reservation",row.id().toString(),row.version(),type,1,
        route.required("topic").asText(),row.orderNo(),correlationId,json.writeValueAsString(payload)));
  }
}
```

- [ ] **Step 5: Service (reserve + find) và controller**

`ReservationService.java`:

```java
package vn.fashion.inventory.reservation;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.inventory.admin.StockAdminService;
import vn.fashion.inventory.stock.*;
import vn.fashion.inventory.web.Api;

/** 06 section 6.1: lock the reservation, then SKUs in sku order; no HTTP inside a transaction. */
@Service
public class ReservationService {
  public record Outcome(int status,String error,ReservationView view) { }
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ReservationRepository reservations;
  private final StockAdminService stocks;
  private final StockEvents stockEvents;
  private final ReservationEvents events;
  public ReservationService(JdbcClient jdbc,TransactionTemplate tx,ReservationRepository reservations,StockAdminService stocks,
      StockEvents stockEvents,ReservationEvents events) {
    this.jdbc=jdbc;this.tx=tx;this.reservations=reservations;this.stocks=stocks;this.stockEvents=stockEvents;this.events=events;
  }

  public Outcome reserve(ReservationRequests.ReserveCommand c,String traceId) {
    var s=c.snapshot();
    return tx.execute(status->{
      var existing=reservations.lock(s.orderId());
      if(existing.isPresent()) return replay(existing.get(),c.requestHash());
      boolean inWindow=jdbc.sql("select :e > now() and :e <= now() + interval '24 hours'")
          .param("e",java.time.OffsetDateTime.ofInstant(s.expiresAt(),java.time.ZoneOffset.UTC)).query(Boolean.class).single();
      if(!inWindow) throw ReservationRequests.invalid("expires_at","must be after now and at most 24 hours ahead");
      UUID id=UUID.randomUUID();
      if(!reservations.insertIfAbsent(id,s,c.requestHash(),"ACTIVE",null)) return replay(reservations.lock(s.orderId()).orElseThrow(),c.requestHash());
      var locked=stocks.lockForUpdate(s.skus());
      var shortages=new ArrayList<Api.FieldError>();
      for(var item:s.items()) {
        var row=locked.get(item.sku());
        int available=row==null || !"ACTIVE".equals(row.status())?0:row.available();
        if(available<item.quantity()) shortages.add(new Api.FieldError("items["+item.sku()+"]","requested="+item.quantity()+" available="+available));
      }
      if(!shortages.isEmpty()) throw new Api.Problem(HttpStatus.CONFLICT,"OUT_OF_STOCK","OUT_OF_STOCK",shortages);
      reservations.insertItems(id,s.items());
      for(var item:s.items()) move(locked.get(item.sku()),0,item.quantity(),"RESERVE",s.orderId(),"order reserve",traceId);
      var row=reservations.lock(s.orderId()).orElseThrow();
      events.append("INVENTORY_RESERVED",row,s.items(),traceId);
      return new Outcome(201,null,ReservationView.of(row,s.items(),false));
    });
  }
  public ReservationView find(UUID orderId) {
    var row=reservations.find(orderId).orElseThrow(ReservationService::notFound);
    return ReservationView.of(row,reservations.items(row.id()),false);
  }

  private Outcome replay(ReservationRow row,String hash) {
    if(!row.requestHash().equals(hash)) throw conflict("RESERVATION_CONFLICT");
    if(row.status().equals("ACTIVE") || row.status().equals("COMMITTED")) return new Outcome(200,null,view(row,false));
    throw conflict("RESERVATION_CLOSED");
  }
  ReservationView view(ReservationRow row,boolean restoreRequired) { return ReservationView.of(row,reservations.items(row.id()),restoreRequired); }
  /** Applies one stock movement with its ledger row and stock snapshot event. */
  StockRow move(StockRow before,int onHandDelta,int reservedDelta,String type,UUID orderId,String reason,String traceId) {
    var after=jdbc.sql("update stock_items set on_hand=on_hand+:o,reserved=reserved+:r,version=version+1,updated_at=now() where sku=:sku returning *")
        .param("o",onHandDelta).param("r",reservedDelta).param("sku",before.sku()).query(StockRow.class).single();
    jdbc.sql("""
        insert into stock_transactions(id,sku,operation_key,type,on_hand_delta,reserved_delta,order_id,reason)
        values (:id,:sku,:key,:type,:o,:r,:order,:reason)
        """).param("id",UUID.randomUUID()).param("sku",before.sku()).param("key","order:"+orderId+":"+type)
        .param("type",type).param("o",onHandDelta).param("r",reservedDelta).param("order",orderId).param("reason",reason).update();
    stockEvents.append(after,before.available()==0 && after.available()>0,traceId);
    return after;
  }
  static Api.Problem conflict(String message) { return new Api.Problem(HttpStatus.CONFLICT,"CONFLICT",message,List.of()); }
  static Api.Problem notFound() { return new Api.Problem(HttpStatus.NOT_FOUND,"NOT_FOUND","RESERVATION_NOT_FOUND",List.of()); }
}
```

`ReservationController.java`:

```java
package vn.fashion.inventory.reservation;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.inventory.admin.AdminCommands;
import vn.fashion.inventory.internal.InternalCallers;
import vn.fashion.inventory.web.Api;

/** Internal reservation API (03 section 4): order-service only; auth before headers and body. */
@RestController
@RequestMapping("/internal/api/v1/inventory")
public class ReservationController {
  private static final Set<String> CALLERS=Set.of("order-service");
  private final InternalCallers callers;
  private final ReservationService service;
  private final Tracer tracer;
  private final ObjectMapper json;
  public ReservationController(InternalCallers callers,ReservationService service,Tracer tracer,ObjectMapper json) {
    this.callers=callers;this.service=service;this.tracer=tracer;this.json=json;
  }
  @PostMapping("/reserve")
  public ResponseEntity<?> reserve(@RequestHeader(name="Authorization",required=false) String auth,
      @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request) {
    var meta=authorize(auth,key);
    return respond(service.reserve(ReservationRequests.reserve(body(request)),meta.traceId()),meta);
  }
  @GetMapping("/reservations/{orderId}")
  public ResponseEntity<?> get(@RequestHeader(name="Authorization",required=false) String auth,@PathVariable String orderId) {
    callers.require(auth,CALLERS);
    var meta=Api.metadata(tracer);
    return Api.ok(HttpStatus.OK,service.find(ReservationRequests.orderId(orderId,"order_id")),meta);
  }
  Api.Metadata authorize(String auth,String key) {
    callers.require(auth,CALLERS);AdminCommands.requireKey(key);return Api.metadata(tracer);
  }
  JsonNode body(HttpServletRequest request) {
    try {
      var node=json.readTree(request.getInputStream());
      if(node==null || node.isMissingNode()) throw malformed();
      return node;
    } catch(IOException | JacksonException e) { throw malformed(); }
  }
  ResponseEntity<?> respond(ReservationService.Outcome outcome,Api.Metadata meta) {
    if(outcome.error()!=null) throw ReservationService.conflict(outcome.error());
    return Api.ok(HttpStatus.valueOf(outcome.status()),outcome.view(),meta);
  }
  static Api.Problem malformed() { return new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","MALFORMED_JSON",List.of()); }
}
```

- [ ] **Step 6: Chạy lại** — lệnh Step 2. Expected: 11 test PASS. Chạy thêm `-Dtest=InternalStockIntegrationTest`: PASS (refactor auth không đổi hành vi).

- [ ] **Step 7: Commit**

```bash
git add services/inventory-service/src
git commit -m "feat(INV-02): all-or-nothing reserve and reservation query"
```

---

### Task 5: Commit và hết hạn khi commit

**Files:**
- Modify: `.../reservation/ReservationService.java`, `.../reservation/ReservationController.java`
- Test: `.../reservation/CommitIntegrationTest.java`

**Interfaces:**
- Consumes: `ReservationService.move`, `ReservationRepository.lock/transition/items`, `ReservationEvents.append` (Task 4).
- Produces: `ReservationService.commit(UUID orderId, String traceId) → Outcome`; `ReservationService.finish(ReservationRow row, String status, String reason, String traceId) → ReservationRow` (nhả `reserved`, dùng lại ở Task 6/7).

- [ ] **Step 1: Test thất bại**

```java
package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommitIntegrationTest extends ReservationTestSupport {
  ReservationSnapshot reservedOrder(String sku,int onHand,int q) {
    seedStock(sku,onHand,0);var s=snapshot(UUID.randomUUID(),in(15),sku,q);data(reserve(s),201);return s;
  }
  @Test void commitDeductsOnceAndRepeatIsIdempotent() {
    var s=reservedOrder("SKU-A",5,2);
    var r=commit(s.orderId());var d=data(r,200);noStore(r);
    assertThat(d.path("status").asString()).isEqualTo("COMMITTED");assertThat(d.path("version").asLong()).isEqualTo(2);
    assertThat(d.path("committed_at").isNull()).isFalse();
    assertThat(onHand("SKU-A")).isEqualTo(3);assertThat(reserved("SKU-A")).isZero();
    assertThat(data(commit(s.orderId()),200).path("version").asLong()).isEqualTo(2);
    assertThat(onHand("SKU-A")).isEqualTo(3);assertThat(ledger("COMMIT")).isEqualTo(1);
    assertThat(outbox("INVENTORY_DEDUCTED")).isEqualTo(1);
    assertThat(jdbc.queryForMap("select on_hand_delta,reserved_delta,order_id from stock_transactions where type='COMMIT'"))
        .containsEntry("on_hand_delta",-2).containsEntry("reserved_delta",-2).containsEntry("order_id",s.orderId());
  }
  @Test void t09QueryAfterCommitShowsCommitted() {
    var s=reservedOrder("SKU-A",5,1);data(commit(s.orderId()),200);
    assertThat(data(get(s.orderId()),200).path("status").asString()).isEqualTo("COMMITTED");
  }
  @Test void commitAfterDeadlineExpiresAndPersists() {
    var s=reservedOrder("SKU-A",5,2);
    jdbc.update("update stock_reservations set expires_at=now()-interval '1 second'");
    assertThat(problem(commit(s.orderId()),409)).isEqualTo("RESERVATION_EXPIRED");
    assertThat(jdbc.queryForObject("select status from stock_reservations",String.class)).isEqualTo("EXPIRED");
    assertThat(jdbc.queryForObject("select release_reason from stock_reservations",String.class)).isEqualTo("expired");
    assertThat(onHand("SKU-A")).isEqualTo(5);assertThat(reserved("SKU-A")).isZero();
    assertThat(ledger("RELEASE")).isEqualTo(1);assertThat(ledger("COMMIT")).isZero();
    assertThat(problem(commit(s.orderId()),409)).isEqualTo("RESERVATION_EXPIRED");assertThat(ledger("RELEASE")).isEqualTo(1);
  }
  @Test void unknownOrderIs404AndBadBodyIs400() {
    assertThat(problem(commit(UUID.randomUUID()),404)).isEqualTo("RESERVATION_NOT_FOUND");
    assertThat(problem(post("commit",java.util.Map.of("order_id","x")),400)).isEqualTo("INVALID_INPUT");
    assertThat(problem(send("POST",BASE+"/commit",serviceToken("cart-service"),"k","{}"),403)).isEqualTo("CALLER_NOT_ALLOWED");
  }
}
```

- [ ] **Step 2: Chạy, xác nhận fail**

Run: `./mvnw -B -pl services/inventory-service -am test -Dtest=CommitIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `/commit` 404/405.

- [ ] **Step 3: Service** — thêm vào `ReservationService`:

```java
  public Outcome commit(UUID orderId,String traceId) {
    return tx.execute(status->{
      var row=reservations.lock(orderId).orElseThrow(ReservationService::notFound);
      switch(row.status()) {
        case "COMMITTED": return new Outcome(200,null,view(row,false));
        case "EXPIRED": return new Outcome(409,"RESERVATION_EXPIRED",null);
        case "RELEASED": return new Outcome(409,"RESERVATION_CLOSED",null);
        default: break;
      }
      // An expired deadline wins the row lock: expire here, commit this transaction, then report the failure.
      if(row.due()) { finish(row,"EXPIRED","expired",traceId);return new Outcome(409,"RESERVATION_EXPIRED",null); }
      var items=reservations.items(row.id());
      var locked=stocks.lockForUpdate(items.stream().map(ReservationSnapshot.Item::sku).toList());
      for(var item:items) move(locked.get(item.sku()),-item.quantity(),-item.quantity(),"COMMIT",orderId,"order commit",traceId);
      var after=reservations.transition(row.id(),"COMMITTED",null);
      events.append("INVENTORY_DEDUCTED",after,items,traceId);
      return new Outcome(200,null,ReservationView.of(after,items,false));
    });
  }
  /** Releases reserved stock of an ACTIVE reservation and records RELEASED or EXPIRED; caller holds the row lock. */
  ReservationRow finish(ReservationRow row,String status,String reason,String traceId) {
    var items=reservations.items(row.id());
    var locked=stocks.lockForUpdate(items.stream().map(ReservationSnapshot.Item::sku).toList());
    for(var item:items) move(locked.get(item.sku()),0,-item.quantity(),"RELEASE",row.orderId(),reason,traceId);
    var after=reservations.transition(row.id(),status,reason);
    events.append("INVENTORY_RELEASED",after,items,traceId);
    return after;
  }
```

`Outcome` với `error != null` nhưng transaction vẫn commit vì không ném exception trong `tx.execute`; controller ném 409 sau khi transaction đã xong.

Controller — thêm:

```java
  @PostMapping("/commit")
  public ResponseEntity<?> commit(@RequestHeader(name="Authorization",required=false) String auth,
      @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request) {
    var meta=authorize(auth,key);
    return respond(service.commit(ReservationRequests.commit(body(request)),meta.traceId()),meta);
  }
```

- [ ] **Step 4: Chạy lại** — lệnh Step 2 rồi `-Dtest='Reserv*Test,Commit*Test'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/inventory-service/src
git commit -m "feat(INV-02): commit reservation and expire on late commit"
```

---

### Task 6: Release và tombstone

**Files:**
- Modify: `.../reservation/ReservationService.java`, `.../reservation/ReservationController.java`
- Test: `.../reservation/ReleaseIntegrationTest.java`

**Interfaces:**
- Consumes: `finish`, `replay`, `view` (Task 4/5); `ReservationRequests.ReleaseCommand`.
- Produces: `ReservationService.release(ReleaseCommand, String traceId) → Outcome`.

- [ ] **Step 1: Test thất bại**

```java
package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReleaseIntegrationTest extends ReservationTestSupport {
  @Test void releaseActiveFreesStockAndRepeatIsNoop() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",5);data(reserve(s),201);
    var r=release(s,"customer cancel");var d=data(r,200);noStore(r);
    assertThat(d.path("status").asString()).isEqualTo("RELEASED");assertThat(d.path("release_reason").asString()).isEqualTo("customer cancel");
    assertThat(reserved("SKU-A")).isZero();assertThat(ledger("RELEASE")).isEqualTo(1);
    assertThat(outbox("INVENTORY_RESTOCKED")).isEqualTo(1);
    assertThat(data(release(s,"again"),200).path("version").asLong()).isEqualTo(2);
    assertThat(ledger("RELEASE")).isEqualTo(1);assertThat(outbox("INVENTORY_RELEASED")).isEqualTo(1);
  }
  @Test void t04TombstoneBlocksLateReserve() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",2);
    var d=data(release(s,"reserve timeout"),200);
    assertThat(d.path("status").asString()).isEqualTo("RELEASED");assertThat(d.path("version").asLong()).isEqualTo(1);
    assertThat(d.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    assertThat(problem(reserve(s),409)).isEqualTo("RESERVATION_CLOSED");
    assertThat(reserved("SKU-A")).isZero();assertThat(ledger("RESERVE")).isZero();assertThat(outbox("INVENTORY_RELEASED")).isEqualTo(1);
    assertThat(data(release(s,"reserve timeout"),200).path("status").asString()).isEqualTo("RELEASED");
  }
  @Test void releaseAfterCommitRequiresRestoreWithoutTouchingStock() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",2);data(reserve(s),201);data(commit(s.orderId()),200);
    var d=data(release(s,"cancel"),200);
    assertThat(d.path("status").asString()).isEqualTo("COMMITTED");assertThat(d.path("restore_required").asBoolean()).isTrue();
    assertThat(onHand("SKU-A")).isEqualTo(3);assertThat(ledger("RELEASE")).isZero();
  }
  @Test void releaseOfExpiredDeadlineSnapshotIsAccepted() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(-60),"SKU-A",1);
    assertThat(data(release(s,"late"),200).path("status").asString()).isEqualTo("RELEASED");
  }
  @Test void differentSnapshotForStoredReservationIsConflict() {
    seedStock("SKU-A",5,0);UUID order=UUID.randomUUID();data(reserve(snapshot(order,in(15),"SKU-A",1)),201);
    assertThat(problem(release(snapshot(order,in(15),"SKU-A",2),"cancel"),409)).isEqualTo("RESERVATION_CONFLICT");
    assertThat(reserved("SKU-A")).isEqualTo(1);
  }
  @Test void tombstoneWithUnknownSkuIs400() {
    var r=release(snapshot(UUID.randomUUID(),in(15),"NOPE",1),"cancel");
    assertThat(problem(r,400)).isEqualTo("INVALID_INPUT");
    assertThat(json(r).path("errors").get(0).path("field").asString()).isEqualTo("original_reservation.items[NOPE]");
    assertThat(jdbc.queryForObject("select count(*) from stock_reservations",Integer.class)).isZero();
  }
  @Test void hashMismatchAndBlankReasonAre400() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);
    assertThat(problem(release(s," "),400)).isEqualTo("INVALID_INPUT");
    var body=new java.util.HashMap<String,Object>(java.util.Map.of("order_id",s.orderId().toString(),"reason","x",
        "original_request_hash","0".repeat(64),"original_reservation",java.util.Map.of("order_no",s.orderNo(),
        "items",java.util.List.of(java.util.Map.of("sku","SKU-A","quantity",1)),"expires_at",ReservationSnapshot.time(s.expiresAt())))));
    assertThat(problem(post("release",body),400)).isEqualTo("REQUEST_HASH_MISMATCH");
  }
}
```

- [ ] **Step 2: Chạy, xác nhận fail**

Run: `./mvnw -B -pl services/inventory-service -am test -Dtest=ReleaseIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `/release` chưa có.

- [ ] **Step 3: Service** — thêm vào `ReservationService`:

```java
  public Outcome release(ReservationRequests.ReleaseCommand c,String traceId) {
    var s=c.snapshot();
    return tx.execute(status->{
      var existing=reservations.lock(c.orderId());
      if(existing.isEmpty()) {
        var known=new HashSet<>(stocks.lockForUpdate(s.skus()).keySet());
        var unknown=s.skus().stream().filter(sku->!known.contains(sku))
            .map(sku->new Api.FieldError("original_reservation.items["+sku+"]","unknown sku")).toList();
        if(!unknown.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","INVALID_INPUT",unknown);
        UUID id=UUID.randomUUID();
        if(reservations.insertIfAbsent(id,s,c.originalRequestHash(),"RELEASED",c.reason())) {
          reservations.insertItems(id,s.items());
          var tombstone=reservations.lock(c.orderId()).orElseThrow();
          events.append("INVENTORY_RELEASED",tombstone,s.items(),traceId);
          return new Outcome(200,null,ReservationView.of(tombstone,s.items(),false));
        }
        existing=reservations.lock(c.orderId());
      }
      var row=existing.orElseThrow();
      if(!row.requestHash().equals(c.originalRequestHash())) throw conflict("RESERVATION_CONFLICT");
      return switch(row.status()) {
        case "ACTIVE" -> new Outcome(200,null,view(finish(row,"RELEASED",c.reason(),traceId),false));
        case "COMMITTED" -> new Outcome(200,null,view(row,true));
        default -> new Outcome(200,null,view(row,false));
      };
    });
  }
```

Ghi chú: tombstone khóa các SKU tồn tại (`lockForUpdate`) chỉ để kiểm tồn tại theo cùng thứ tự khóa; không cập nhật `stock_items`.

Controller — thêm:

```java
  @PostMapping("/release")
  public ResponseEntity<?> release(@RequestHeader(name="Authorization",required=false) String auth,
      @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request) {
    var meta=authorize(auth,key);
    return respond(service.release(ReservationRequests.release(body(request)),meta.traceId()),meta);
  }
```

- [ ] **Step 4: Chạy lại** — lệnh Step 2 rồi `-Dtest='Reserv*Test,Commit*Test,Release*Test'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/inventory-service/src
git commit -m "feat(INV-02): release reservations and record tombstones"
```

---

### Task 7: Job hết hạn, race T05 và metric

**Files:**
- Create: `.../reservation/ReservationSweep.java`
- Modify: `.../reservation/ReservationService.java` (`expireDue`), `services/inventory-service/src/main/resources/application.yaml`
- Test: `.../reservation/ReservationSweepIntegrationTest.java`

**Interfaces:**
- Consumes: `ReservationRepository.dueIds/lockDue/overdueCount`, `ReservationService.finish`.
- Produces: `ReservationService.expireDue(int batch) → int` (số reservation đã EXPIRED); bean `ReservationSweep` (counter/gauge); cấu hình `fashion.inventory.reservation-sweep.enabled` (mặc định `true` ở app, không đặt trong test → tắt), `fashion.inventory.reservation-sweep.interval` (mặc định `5s`), `fashion.inventory.reservation-sweep.batch` (mặc định `100`).

- [ ] **Step 1: Test thất bại**

```java
package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationSweepIntegrationTest extends ReservationTestSupport {
  @Autowired ReservationService service;
  @Autowired MeterRegistry meters;
  ReservationSnapshot due(String sku,int q) {
    seedStock(sku,5,0);var s=snapshot(UUID.randomUUID(),in(15),sku,q);data(reserve(s),201);
    jdbc.update("update stock_reservations set expires_at=now()-interval '1 second' where order_id=?",s.orderId());return s;
  }
  @Test void expiresDueReservationsOnly() {
    var old=due("SKU-A",2);seedStock("SKU-B",5,0);var fresh=snapshot(UUID.randomUUID(),in(15),"SKU-B",1);data(reserve(fresh),201);
    assertThat(meters.get("inventory.reservations.overdue").gauge().value()).isEqualTo(1.0);
    assertThat(service.expireDue(100)).isEqualTo(1);
    assertThat(data(get(old.orderId()),200).path("status").asString()).isEqualTo("EXPIRED");
    assertThat(data(get(fresh.orderId()),200).path("status").asString()).isEqualTo("ACTIVE");
    assertThat(reserved("SKU-A")).isZero();assertThat(reserved("SKU-B")).isEqualTo(1);
    assertThat(service.expireDue(100)).isZero();assertThat(ledger("RELEASE")).isEqualTo(1);
    assertThat(meters.get("inventory.reservations.overdue").gauge().value()).isZero();
    assertThat(meters.get("inventory.reservations").tags("operation","expire","outcome","expired").counter().count()).isEqualTo(1.0);
  }
  @Test void t05CommitAndSweepRaceHasOneOutcome() throws Exception {
    for(int round=0;round<10;round++) {
      clearInventory();var s=due("SKU-A",2);var gate=new CountDownLatch(1);
      try(var pool=Executors.newFixedThreadPool(2)) {
        var commit=pool.submit(()->{gate.await();return commit(s.orderId()).statusCode();});
        var sweep=pool.submit(()->{gate.await();return service.expireDue(100);});
        gate.countDown();
        assertThat(commit.get(30,TimeUnit.SECONDS)).isEqualTo(409);sweep.get(30,TimeUnit.SECONDS);
      }
      assertThat(jdbc.queryForObject("select status from stock_reservations",String.class)).isEqualTo("EXPIRED");
      assertThat(ledger("RELEASE")).isEqualTo(1);assertThat(ledger("COMMIT")).isZero();
      assertThat(reserved("SKU-A")).isZero();assertThat(onHand("SKU-A")).isEqualTo(5);
    }
  }
  @Test void sweepSkipsRowHeldByCommitInFlight() throws Exception {
    var s=due("SKU-A",1);
    try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),"postgres","postgres")) {
      c.setAutoCommit(false);
      try(var st=c.prepareStatement("select id from stock_reservations where order_id=? for update")) { st.setObject(1,s.orderId());st.executeQuery(); }
      assertThat(CompletableFuture.supplyAsync(()->service.expireDue(100)).get(10,TimeUnit.SECONDS)).isZero();
      c.rollback();
    }
    assertThat(service.expireDue(100)).isEqualTo(1);
  }
  @Test void commitBeforeDeadlineBeatsSweep() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);data(reserve(s),201);
    data(commit(s.orderId()),200);
    jdbc.update("update stock_reservations set expires_at=now()-interval '1 second'");
    assertThat(service.expireDue(100)).isZero();assertThat(onHand("SKU-A")).isEqualTo(4);
  }
}
```

- [ ] **Step 2: Chạy, xác nhận fail**

Run: `./mvnw -B -pl services/inventory-service -am test -Dtest=ReservationSweepIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL biên dịch — `expireDue` chưa có.

- [ ] **Step 3: Service + sweep**

Thêm vào `ReservationService` (import `org.slf4j.*`):

```java
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReservationService.class);
  /** One transaction per reservation; SKIP LOCKED leaves rows a commit holds, so commit and expiry never both win. */
  public int expireDue(int batch) {
    int expired=0;
    for(UUID id:reservations.dueIds(batch)) {
      try {
        Boolean done=tx.execute(status->reservations.lockDue(id)
            .map(row->{finish(row,"EXPIRED","expired","reservation-sweep-"+UUID.randomUUID());return true;}).orElse(false));
        if(Boolean.TRUE.equals(done)) expired++;
      } catch(RuntimeException e) { LOG.warn("Reservation expiry failed for reservation_id={}",id,e); }
    }
    return expired;
  }
```

Counter (đếm request theo kết quả, kể cả khi transaction rollback): thêm field + tham số constructor + helper:

```java
  private final io.micrometer.core.instrument.MeterRegistry meters;
  public ReservationService(JdbcClient jdbc,TransactionTemplate tx,ReservationRepository reservations,StockAdminService stocks,
      StockEvents stockEvents,ReservationEvents events,io.micrometer.core.instrument.MeterRegistry meters) {
    this.jdbc=jdbc;this.tx=tx;this.reservations=reservations;this.stocks=stocks;this.stockEvents=stockEvents;this.events=events;
    this.meters=meters;
  }
  void count(String operation,String outcome) {
    meters.counter("inventory.reservations","operation",operation,"outcome",outcome).increment();
  }
```

Chèn lời gọi (mỗi dòng ngay trước `return`/`throw` tương ứng):

```java
// reserve: trước throw OUT_OF_STOCK
count("reserve","out_of_stock");
// reserve: trước return new Outcome(201,...)
count("reserve","created");
// replay: trước throw RESERVATION_CONFLICT / return 200 / throw RESERVATION_CLOSED
count("reserve","conflict");   count("reserve","replayed");   count("reserve","closed");
// commit: case COMMITTED / case EXPIRED và nhánh due / case RELEASED / trước return 200 cuối
count("commit","replayed");    count("commit","expired");     count("commit","closed");   count("commit","committed");
// release: nhánh tombstone / ACTIVE / COMMITTED / default
count("release","tombstone");  count("release","released");   count("release","restore_required");   count("release","noop");
// expireDue: trong if(Boolean.TRUE.equals(done))
count("expire","expired");
```

Với `switch` biểu thức trong `release`, đổi thành khối có lệnh: `case "ACTIVE" -> { count("release","released"); yield new Outcome(...); }` (tương tự cho hai nhánh còn lại).

`ReservationSweep.java`:

```java
package vn.fashion.inventory.reservation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Overdue gauge always; the trigger only when enabled (@EnableScheduling is app-wide via the relay, so the
 * @Scheduled method lives on a conditional bean, as in catalog MediaConfig). Tests call expireDue directly. */
@Configuration
public class ReservationSweep {
  @Bean Gauge overdueReservations(MeterRegistry meters,ReservationRepository reservations) {
    return Gauge.builder("inventory.reservations.overdue",reservations,r->r.overdueCount())
        .description("ACTIVE reservations past expires_at not yet swept").register(meters);
  }
  @Configuration @EnableScheduling @ConditionalOnBooleanProperty("fashion.inventory.reservation-sweep.enabled")
  static class Trigger {
    private final ReservationService service;
    private final int batch;
    Trigger(ReservationService service,@Value("${fashion.inventory.reservation-sweep.batch:100}") int batch) {
      this.service=service;this.batch=batch;
    }
    @Scheduled(fixedDelayString="${fashion.inventory.reservation-sweep.interval:5s}") void sweep() { service.expireDue(batch); }
  }
}
```

`application.yaml` dưới `fashion.inventory`:

```yaml
    reservation-sweep:
      enabled: ${INVENTORY_RESERVATION_SWEEP_ENABLED:true}
      interval: ${INVENTORY_RESERVATION_SWEEP_INTERVAL:5s}
      batch: ${INVENTORY_RESERVATION_SWEEP_BATCH:100}
```

Trong `InventoryTestSupport` đổi annotation để job không chạy nền trong test:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"fashion.messaging.relay.enabled=false", "fashion.inventory.reservation-sweep.enabled=false"})
```

- [ ] **Step 4: Chạy lại** — lệnh Step 2, rồi toàn module `./mvnw -B -pl services/inventory-service -am test`. Expected: PASS toàn bộ (52 test INV-01 + test mới).

- [ ] **Step 5: Commit**

```bash
git add services/inventory-service/src
git commit -m "feat(INV-02): expire overdue reservations with skip-locked sweep"
```

---

### Task 8: Gateway boundary, event relay, mutation, tài liệu và verify

**Files:**
- Modify: `services/gateway/src/test/java/vn/fashion/gateway/security/GatewayBoundaryTest.java`
- Test: `.../reservation/ReservationEventRelayIntegrationTest.java`
- Modify docs: `docs/design/03_interfaces.md` (§4 bảng internal inventory: 4 route ghi "INV-02 implemented local", §1.3 thêm quy tắc `request_hash` reservation), `docs/design/05_database_design.md` (§8 bảng `stock_reservations` thêm `version`, `release_reason`, V002, quyền runtime), `docs/design/06_service_flows.md` (§6.1 ghi trạng thái hiện thực, metric), `docs/delivery/10_backlog.md` (dòng INV-02 In progress/Review), `docs/engineering/12_engineering_guide.md` (lệnh test reservation), `services/inventory-service/README.md` (API, sweep config, metric, giới hạn), `infra/local/.env.example` (comment `INVENTORY_INTERNAL_CALLERS` thêm `order-service:<base64>`), `docs/superpowers/specs/2026-10-16-inv-02-reservations-design.md` (trạng thái), `docs/superpowers/plans/README.md` (dòng plan)
- Create: `docs/evidence/inv-02-local-2026-10-16.md`, `docs/superpowers/plans/2026-10-16-inv-02-handoff.md`

- [ ] **Step 1: Gateway test thất bại trước khi sửa?** — Gateway đã chặn mọi `/internal/**`, nên test mới sẽ PASS ngay; đây là regression guard. Thêm vào `GatewayBoundaryTest`:

```java
  @Test
  void inventoryReservationInternalIsNotRouted() {
    client.get().uri("/internal/api/v1/inventory/reservations/4c8a1f2e-0000-4000-8000-000000000010")
        .exchange().expectStatus().isNotFound();
    assertThat(CATALOG.requestCount()).isZero();
  }
```

Run: `./mvnw -B -pl services/gateway -am test -Dtest=GatewayBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS.

- [ ] **Step 2: Relay đến Kafka** — test dùng relay thật (bật cho class này):

```java
package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class ReservationEventRelayIntegrationTest extends ReservationTestSupport {
  // Dynamic properties outrank @SpringBootTest(properties) of the base class, so only this class runs the relay.
  @DynamicPropertySource static void relay(DynamicPropertyRegistry registry) {
    registry.add("fashion.messaging.relay.enabled",()->"true");
  }
  @Test void reservedEventReachesKafkaKeyedByOrderNo() {
    seedStock("SKU-A",5,0);var s=snapshot(UUID.randomUUID(),in(15),"SKU-A",1);data(reserve(s),201);
    var props=Map.<String,Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG,"inv02-"+UUID.randomUUID(),ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
    try(var consumer=new KafkaConsumer<String,String>(props)) {
      consumer.subscribe(List.of("inventory.events"));
      long deadline=System.currentTimeMillis()+20_000;
      while(System.currentTimeMillis()<deadline) {
        for(var record:consumer.poll(Duration.ofMillis(500)))
          if(record.value().contains("INVENTORY_RESERVED")) {
            assertThat(record.key()).isEqualTo(s.orderNo());
            var event=mapper.readTree(record.value());
            assertThat(event.path("payload").path("order_id").asString()).isEqualTo(s.orderId().toString());
            assertThat(event.path("aggregate_version").asLong()).isEqualTo(1);
            return;
          }
      }
      fail("INVENTORY_RESERVED not relayed within 20s");
    }
  }
}
```

Run: `./mvnw -B -pl services/inventory-service -am test -Dtest=ReservationEventRelayIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS. Nếu topic `inventory.events` chưa tồn tại trong Testcontainers Kafka, test `VariantCreatedConsumerIntegrationTest.inventoryRelayPublishesSnapshot` là mẫu: đọc cách nó tạo topic và làm theo y như vậy.

- [ ] **Step 3: Mutation** — với mỗi mutant: sửa tạm, chạy test chỉ định, ghi số assertion failure, rồi `git checkout -- <file>` và kiểm `git diff --exit-code` sạch.

| Mutant (file `ReservationService.java` trừ khi ghi khác) | Test phải fail |
|---|---|
| Trong `commit`, xóa nhánh `if(row.due()) {...}` | `CommitIntegrationTest.commitAfterDeadlineExpiresAndPersists` |
| Trong `ReservationRepository.lockDue`, bỏ `and status='ACTIVE'` | `ReservationSweepIntegrationTest.commitBeforeDeadlineBeatsSweep` |
| Trong `replay`, đổi `!row.requestHash().equals(hash)` thành `false` | `ReserveIntegrationTest.sameOrderDifferentHashIsConflict` |
| Trong `reserve`, đổi `if(!shortages.isEmpty())` thành `if(false)` | `ReserveIntegrationTest.t02OneShortSkuRollsBackEverything` |
| `ReservationSnapshot.canonical`: bỏ `.replace("|","\\|")` | `ReservationRequestsTest.escapingPreventsDelimiterCollision` |
| Trong `release`, nhánh `"COMMITTED"` trả `view(row,false)` | `ReleaseIntegrationTest.releaseAfterCommitRequiresRestoreWithoutTouchingStock` |

Expected: 6/6 mutant bị bắt (assertion failure, không phải lỗi biên dịch).

- [ ] **Step 4: Tài liệu** — cập nhật các file ở mục Files theo nội dung đã hiện thực:
  - 03 §4: 4 dòng route inventory ghi `INV-02 implemented local: /internal/api/v1/inventory/...`, caller order-service, no-store, mã lỗi `RESERVATION_CONFLICT/RESERVATION_CLOSED/RESERVATION_EXPIRED/RESERVATION_NOT_FOUND/REQUEST_HASH_MISMATCH`, OUT_OF_STOCK `errors[items[SKU]]`; §1.3: công thức `request_hash` reservation đúng như Global Constraints.
  - 05 §8: thêm `version bigint >= 1`, `release_reason text?`, CHECK status/timestamp, `V002__stock_reservations.sql`, runtime không DELETE; bỏ câu "Các bảng reservation/return/subscription vẫn thuộc task sau" cho phần reservation (return/subscription vẫn sau).
  - 06 §6.1: dòng "INV-02 hiện thực local 2026-10-16: sweep 5 s SKIP LOCKED, commit tự kiểm hạn; metric `inventory_reservations_total`, gauge `inventory_reservations_overdue`".
  - 10: cập nhật trạng thái INV-02 (In progress → Review sau verify), REQ, dependency, evidence link; không tự đánh Done.
  - 12 §1: thêm `./mvnw -B -pl services/inventory-service -am test -Dtest='Reserv*Test,Commit*Test,Release*Test'`.
  - README inventory-service: mục Internal reservations (route, caller, hash, giới hạn), cấu hình `INVENTORY_RESERVATION_SWEEP_*`, metric, ngoài phạm vi (INV-03).
  - `.env.example`: comment `INVENTORY_INTERNAL_CALLERS=caller:base64-X.509,...; order-service cần cho reservation (ORD-02)`.
  - Spec: trạng thái "đã duyệt 2026-10-16; hiện thực local, xem evidence"; plans README: thêm dòng plan INV-02 đánh dấu **Mới nhất**, bỏ chữ "Mới nhất" ở dòng INV-01.
  - Evidence: lệnh đã chạy, số test từng module, mutation 6/6, giới hạn (chưa có caller thật, smoke chưa gọi reservation, Kafka staging chưa kiểm).
  - Handoff: commit theo task, trạng thái, bước tiếp (ORD-01/ORD-02 dùng reservation, INV-03 return).

- [ ] **Step 5: Verify toàn bộ**

```bash
./mvnw -B verify
python3 -B -m unittest discover -s tests/contracts -p 'test_*.py' -v
bash scripts/validate-contracts.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
bash scripts/local-up.sh && bash scripts/smoke-local.sh
git diff --check
bash scripts/scan-secrets.sh
```

Expected: BUILD SUCCESS, 0 failure/error/skip; contracts/docs PASS; smoke `S1-local smoke PASS`; gitleaks không có finding. Ghi số thực vào evidence.

- [ ] **Step 6: Commit**

```bash
git add services/gateway/src/test docs infra/local/.env.example services/inventory-service
git commit -m "docs(INV-02): contracts, evidence and docs for stock reservations"
```
