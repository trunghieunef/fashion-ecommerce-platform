# PAY-01 Payment COD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** payment-service với payment COD một-đơn-một-payment, close/tombstone, bằng chứng COD từ `shipping.events`,
FINANCE settlement gross-fee-net và admin read.

**Architecture:** Service mới theo mẫu shipping-service (JdbcClient, Flyway, `Api` envelope + `replay`/`body`,
`ApiExceptionHandler`, no-store, `InternalCallers`, `AdminAuth`) + consumer inbox như inventory `CatalogEventsListener`
(`InboxListenerSupport`) + outbox relay platform-messaging. Mọi ghi trong một TX dưới advisory lock theo `order_id`;
không HTTP trong TX.

**Tech Stack:** Java 21, Spring Boot 4.0, PostgreSQL 17.11, Kafka, Testcontainers.

**Spec:** [PAY-01 design](../specs/2026-10-20-pay-01-payment-cod-design.md)

## Global Constraints

- Port 8086, DB `payment` (`payment_migration`/`payment_runtime`); Gateway chỉ `/admin/api/v1/payments/**`; `/internal` không route.
- Internal callers `PAYMENT_INTERNAL_CALLERS` (`order-service`); permissions `payment.read` (list/detail), `payment.settle` (settlement).
- Method `COD|VNPAY|MOMO`; PAY-01 chỉ COD, VNPAY/MOMO → 422 `PAYMENT_METHOD_UNAVAILABLE`; amount 1..10^12, ≤ 0 → 422
  `PAYMENT_AMOUNT_UNSUPPORTED`; currency chỉ `VND`. `provider_request_id = order_no`.
- Lỗi 409: `PAYMENT_CONFLICT`, `PAYMENT_CLOSED`, `PAYMENT_NOT_SETTLEABLE`, `SETTLEMENT_EXCEEDS_AMOUNT`,
  `SETTLEMENT_REF_REUSED`, `VERSION_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`; 404 `PAYMENT_NOT_FOUND`.
- Topic phát `payment.events` (key `order_no`), tiêu thụ `shipping.events` group `payment-service`.
- Không stage 10 file `.sh` đang M và `.gitattributes`; file mới `infra/local/postgres-init/06-payment-db.sh` được phép.
- Verify theo module, tắt stack compose khi chạy full verify (máy ít RAM).

## Review Focus

- Close song song create cùng order → hoặc payment COD_PENDING rồi FAILED đã close, hoặc tombstone + create 409; không bao giờ payment mở sau close — Task 3.
- Hai settlement song song cùng payment, mỗi cái gross = amount → một SUCCESS, cái kia 409 `PAYMENT_NOT_SETTLEABLE`/`SETTLEMENT_EXCEEDS_AMOUNT`; Σgross không vượt amount — Task 4.
- `COD_COLLECTED` đến trước khi payment tồn tại → retry (không ACK, không tạo payment); sau khi create thì áp dụng — Task 4.
- Cùng `COD_COLLECTED` giao hai lần với `event_id` khác nhưng cùng `event_ref` → một transaction, một lần chuyển trạng thái — Task 4.
- Settlement sau late collection trên payment đã close → SUCCESS, `closed_at` giữ, `PAYMENT_COMPLETED` phát — Task 4.

---

### Task 1: Khung service + schema + hạ tầng local

- [ ] `services/payment-service`: pom (copy shipping, bỏ catalog client), Dockerfile, `application.yaml` (port 8086,
  Kafka consumer như inventory), `PaymentApplication`, `PaymentBeans`, `web/` (Api, ApiExceptionHandler, NoStoreFilter,
  AdminAuth — copy shipping), `internal/InternalCallers`.
- [ ] `V001__payment.sql`: `payments` (05 + `snapshot jsonb`, `created_at`, `updated_at`, CHECK spec §3),
  `payment_transactions` (source thêm CLOSE/SETTLEMENT), `cod_settlements`, `reconciliation_items`, `idempotency_requests`,
  `outbox_events`, `processed_events`; grant runtime: không DELETE, không UPDATE `payment_transactions`/`cod_settlements`.
- [ ] Root pom module; `06-payment-db.sh` (mẫu `05-shipping-db.sh`); compose service + env; local-up `service_key PAYMENT
  payment-service` (không thêm vào caller nào), giữ credential role qua restart, topic `payment.events`; Gateway route
  `payment-admin`; `.env.example`.
- [ ] Test `PaymentSchemaIntegrationTest`: CHECK `gross = fee + net`, `refunded + reserved <= amount`, UNIQUE order_id;
  runtime DELETE `payments` và UPDATE `cod_settlements` → lỗi quyền.
- [ ] `./mvnw -B -pl services/payment-service verify` PASS; commit `feat(PAY-01): payment-service skeleton and schema`.

### Task 2: Create + by-order (internal)

**Interfaces — Produces:** `PaymentService.create(String key, Snapshot s, String corr) -> PaymentView`,
`PaymentService.byOrder(UUID) -> PaymentView`, `record Snapshot(UUID orderId, String orderNo, UUID userId, String method,
long amount, String currency, Instant expiresAt)`, `Snapshot.hash() -> String` (SHA-256 TreeMap canonical),
`static Snapshot PaymentService.parse(JsonNode)` (strict: field lạ/thiếu → 400), `PaymentEvents.created/failed/completed(view, corr)`,
`PaymentService.lockOrder(UUID orderId)` (`pg_advisory_xact_lock(hashtextextended(order_id::text,0))`).

- [ ] Test `PaymentCreateIntegrationTest`: COD → 200 `status=COD_PENDING`, `provider_request_id=order_no`, 1 transaction
  `CREATE`, 1 outbox `PAYMENT_CREATED`; replay cùng key → cùng `payment_id`, không thêm outbox; key khác cùng body → cùng
  payment; khác amount → 409 `PAYMENT_CONFLICT`; 8 create song song cùng order → 1 row; amount 0 → 422; VNPAY → 422
  `PAYMENT_METHOD_UNAVAILABLE`; currency `USD` → 400; token `cart-service` → 403 trước body hỏng; by-order chưa có → 404.
- [ ] Chạy test → FAIL; implement `PaymentController` (internal) + `PaymentService` + `PaymentEvents` + event schema
  `contracts/events/payment-events.schema.json`, registry 3 type, fixtures valid + invalid; chạy lại → PASS; commit.

### Task 3: Close + tombstone

**Interfaces — Consumes:** Task 2. **Produces:** `PaymentService.close(String key, UUID pathOrderId, Snapshot s, String reason, String corr) -> PaymentView`.

- [ ] Test `PaymentCloseIntegrationTest`: close COD_PENDING → FAILED + `closed_at` + transaction `CLOSE` (reason) + outbox
  `PAYMENT_FAILED` reason `CLOSED`; close trước create → tombstone FAILED (snapshot, hash, closed_at), create sau → 409
  `PAYMENT_CLOSED`; close lặp (key khác) → 200 hiện trạng, không thêm outbox; path ≠ body → 400; snapshot khác hash → 409
  `PAYMENT_CONFLICT`; thiếu snapshot → 400; reason rỗng/>500 → 400; close sau COD_COLLECTED → status giữ, `closed_at` set,
  không outbox; close/create song song ×4 (Review Focus 1).
- [ ] FAIL → implement route `POST /internal/api/v1/payments/by-order/{order_id}/close` → PASS; commit.

### Task 4: Bằng chứng COD + settlement + admin

**Interfaces — Consumes:** Task 2–3. **Produces:** `CodEvidence.apply(JsonNode envelope)` (gọi từ
`ShippingEventsListener` qua `InboxListenerSupport`, map `COD_COLLECTED`/`COD_REMITTED`), `SettlementService.settle(...)`.

- [ ] Test `CodEvidenceIntegrationTest` (gọi `apply` trực tiếp + một test listener với Kafka Testcontainers như inventory):
  collected khớp → COD_COLLECTED, transaction `COD` key `event_ref`; lặp cùng `event_ref` → no-op; gross lệch → status giữ,
  1 `reconciliation_items` `COD_AMOUNT_MISMATCH` (expected/observed); payment không phải COD → `COD_METHOD_MISMATCH`;
  payment đã close → COD_COLLECTED, `closed_at` giữ; REMITTED → transaction `REMITTED`, status giữ (**T27**); payment chưa
  có → ném lỗi retryable, không ghi gì.
- [ ] Test `SettlementIntegrationTest`: gross ≠ fee+net → 400; chưa COD_COLLECTED → 409 `PAYMENT_NOT_SETTLEABLE`; hai
  đợt 60%+40% → SUCCESS + `paid_at` + 1 `PAYMENT_COMPLETED`; đợt vượt → 409 `SETTLEMENT_EXCEEDS_AMOUNT`; ref dùng lại →
  409 `SETTLEMENT_REF_REUSED`; version sai → 409; replay key → cùng kết quả; song song (Review Focus 2); payment đã close
  (Review Focus 5); quyền `payment.read` không settle được → 403.
- [ ] Test `PaymentAdminIntegrationTest`: list filter status + cursor; detail có transactions, settlements, reconciliation
  items; không quyền → 403; id lạ → 404.
- [ ] FAIL → implement `ShippingEventsListener`, `CodEvidence`, `SettlementService`, `PaymentAdminController` → PASS; commit.

### Task 5: Contract + docs + verify + PR

- [ ] `contracts/openapi/payment.yaml` + `payment-internal.yaml`, `tests/contracts/test_payment.py`; Redocly lint; contract
  test (docker python offline wheels).
- [ ] Gateway test `paymentRoutesAreForwardedAndInternalIsNot`; smoke-local bước `PAY-01 payment admin route PASS`
  (403 không quyền, 404 id lạ có quyền FINANCE).
- [ ] Docs: README service, 03 (§3/§4 payment implemented, path close by-order), 05 (cột `snapshot`, source CLOSE/SETTLEMENT),
  06 §7.2, 08 (quyết định §9 spec), 10 (In review), 12 (lệnh), CLAUDE.md, root README, evidence `pay-01-local-2026-10-20.md`.
- [ ] Mutation guard chính (lock order, hash check, Σgross ≤ amount, kiểm amount COD) bị test bắt.
- [ ] Verify module (tắt compose), gateway test, contracts, docs check, `git diff --check`; review subagent toàn nhánh, sửa
  Critical/Important; push dev, PR → main (không tự merge).
