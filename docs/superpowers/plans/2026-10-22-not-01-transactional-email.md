# NOT-01 Transactional email Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 6 email giao dịch VI/EN (đặt đơn, đang chuẩn bị, giao xong, hủy, hoàn về, đặt lại mật khẩu) qua notification-service
mới, gửi SMTP vào mail sink Mailpit local; mỗi sự việc tối đa một email, không lộ token/ghi chú OPS.

**Architecture:** order ghi outbox `NOTIFY_ORDER_*` tới `notification.events` trong cùng TX với `OrderTransitions.move`;
user-service bật relay để `NOTIFY_RESET_PASSWORD` đã có lên Kafka. notification-service: consumer inbox → dòng `notifications`
`PENDING` đã render (Mustache, escape) → worker lease gửi SMTP ngoài TX → `SENT`/retry/`FAILED`/`UNKNOWN`/`EXPIRED`.
Reset: lưu marker, lấy secret từ user internal lúc gửi, render trong bộ nhớ.

**Tech Stack:** Java 21, Spring Boot 4.0.8 (`spring-boot-starter-mail`, `spring-boot-starter-mustache` từ Boot BOM — dependency
mới, chỉ ở notification-service), PostgreSQL 17.11 + Kafka Testcontainers, `platform-durability`, `platform-messaging`,
`platform-security` (service token), Mailpit (compose local).

**Spec:** [NOT-01 design](../specs/2026-10-22-not-01-transactional-email-design.md)

## Global Constraints

- Event (schema `notification-events.schema.json`, `additionalProperties: false`): `NOTIFY_ORDER_CONFIRMED`, `NOTIFY_ORDER_PREPARING`, `NOTIFY_ORDER_DELIVERED`, `NOTIFY_ORDER_CANCELLED`, `NOTIFY_ORDER_RETURNED`; topic `notification.events`, `aggregate_id` `order_id`, `partition_key` `dedupe_key`.
- Trigger (trong `OrderTransitions.move`, đơn `payment_method = COD` và `contact_email` khác null): `→CONFIRMED` (từ PENDING), `CONFIRMED→PACKING`, `→DELIVERED`, `→CANCELLED`, `→RETURNED`. dedupe_key `order:{order_id}:{confirmed|preparing|delivered|cancelled|returned}`; template_key `ORDER_CONFIRMED|ORDER_PREPARING|ORDER_DELIVERED|ORDER_CANCELLED|ORDER_RETURNED`.
- `data`: `order_no`, `placed_at`, `items[] {name, size, color, quantity, line_total, image_url}`, `subtotal`, `shipping_fee`, `total`, `payment_method`; chỉ CONFIRMED thêm `ship_to {name, phone_masked, address}` (`recipient_name`, phone che, `address_line`); chỉ CANCELLED thêm `cancel_category`.
- `phone_masked`: giữ 3 số đầu và 3 số cuối, giữa là `****` (`0901234123` → `090****123`); không bao giờ đưa `reason` tự do/history vào event.
- `cancel_category` từ `orders.cancel_reason`: `CUSTOMER_CANCELLED`→`CUSTOMER`; `OPS_CANCELLED`→`SHOP`; `EXPIRED`, `ORDER_EXPIRED`, `RESERVATION_EXPIRED`→`EXPIRED`; `OUT_OF_STOCK`→`OUT_OF_STOCK`; còn lại→`OTHER`.
- Câu lý do (vi): CUSTOMER "Bạn đã hủy đơn."; SHOP "Shop đã hủy đơn, vui lòng liên hệ hotline nếu cần hỗ trợ."; EXPIRED "Đơn quá hạn xác nhận."; OUT_OF_STOCK "Sản phẩm đã hết hàng."; OTHER "Đơn không thể xử lý, vui lòng liên hệ hotline." (en dịch tương đương).
- Email 2 chỉ "đơn đang được chuẩn bị", không hứa ngày giao. Email 1: "Shop sẽ gọi điện xác nhận trong vòng 24 giờ". Email 6: "link có hiệu lực 30 phút", "không phải bạn thì bỏ qua".
- Link: `{storefront_base_url}/orders/{order_no}` (không token); reset `{storefront_base_url}/reset-password#token={secret}`. Ảnh: `image_url` tuyệt đối giữ nguyên, tương đối thì ghép `{public_asset_base_url}`; null → ô trống.
- Tiền: vi `887.000 ₫`, en `887,000 VND`. Thiếu template en → vi.
- Trạng thái `notifications`: PENDING/SENDING/SENT/FAILED/UNKNOWN/EXPIRED/SUPPRESSED (05). UNIQUE `(dedupe_key, channel, recipient)`.
- Gửi: lease 60 s; SMTP connect/read/write timeout 10 s; trả lời 4xx hoặc lỗi kết nối (chưa gửi) → retry, backoff 1/2/4/8/16 phút ±20 %; lần thất bại thứ 6 → `FAILED`; trả lời 5xx → `FAILED` ngay; timeout/IO sau khi đã kết nối mà không có mã trả lời → `UNKNOWN`; `SENDING` hết lease → `UNKNOWN`, không gửi lại. `Message-ID` `<{notification_id}@{message_id_domain}>`.
- Reset: `rendered_body` chứa marker `@@RESET_URL@@`, không bao giờ chứa token; quá `expires_at` hoặc user 404 → `EXPIRED`; user 5xx/timeout → retry như lỗi chắc chắn.
- Metric: `notification.sent`, `notification.failed{template}`, `notification.unknown`, `notification.expired`. Log chỉ `notification_id`, `dedupe_key`, template, status — không email, nội dung, token.
- Cấu hình (giá trị mẫu): `NOTIFICATION_SHOP_NAME`, `NOTIFICATION_HOTLINE`, `NOTIFICATION_MAIL_FROM`, `NOTIFICATION_STOREFRONT_BASE_URL`, `NOTIFICATION_PUBLIC_ASSET_BASE_URL`, `NOTIFICATION_MESSAGE_ID_DOMAIN`, `SPRING_MAIL_HOST/PORT`.
- Không `notification.results`, không job dọn 90 ngày, không email đơn online, không API public/route Gateway.
- Không stage 10 file `.sh` đang M và `.gitattributes` (stage theo path). Docs tiếng Việt, comment code tiếng Anh. Test tắt worker/listener, gọi `runOnce()`.

## Review Focus

- Đơn CANCELLED qua nhiều đường (khách hủy, OPS hủy, hết hạn, hết hàng lúc reserve) → đúng một event với đúng `cancel_category`, không bao giờ chữ OPS nhập — Task 1 `everyCancelPathSendsOneFixedReason`.
- Tên sản phẩm/ghi chú có `<script>`/`{{` → email hiện chữ thường, không chạy, không vỡ template — Task 2 `renderedDataIsEscaped`.
- Worker chết sau khi SMTP đã nhận nhưng trước khi ghi SENT → lần sau `UNKNOWN`, không email thứ hai — Task 3 `aSendingRowWithAnExpiredLeaseBecomesUnknown`.
- Hai worker cùng chạy → mỗi email đúng một lần — Task 3 `twoWorkersSendEachEmailOnce`.
- Link reset hết hạn trước khi gửi (Kafka chậm/user 404) → không email, `EXPIRED`; token không có trong DB/log — Task 4 `anExpiredResetIsNeverSent`, `theResetTokenIsNeverStored`.

---

### Task 1: order phát NOTIFY_ORDER_* + contract

**Files:** `contracts/events/notification-events.schema.json`, `contracts/events/registry.json`; create
`services/order-service/src/main/java/vn/fashion/order/orders/OrderNotifications.java`; modify `saga/OrderTransitions.java`;
`tests/contracts/test_event_payloads.py`, `test_event_routing.py`; create `OrderNotificationsIntegrationTest`.

**Interfaces — Produces:** `OrderNotifications.onTransition(UUID orderId, String from, String to)` (cùng TX, gọi cuối
`OrderTransitions.move`); schema `$defs`: `NotifyOrder` (chung) với `data` `OrderEmailData`.

- [ ] Schema + registry 5 event (Global Constraints); `dedupe_key` pattern `^order:[0-9a-f-]{36}:(confirmed|preparing|delivered|cancelled|returned)$`; contract test: ví dụ hợp lệ mỗi loại; thiếu `ship_to` ở CONFIRMED, có `ship_to` ở loại khác, `cancel_category` lạ, field thừa → invalid; routing đúng topic/key.
- [ ] Test `eachTransitionWritesOneNotifyEvent`: PENDING→CONFIRMED, CONFIRMED→PACKING, SHIPPING→DELIVERED, →CANCELLED, RETURNING→RETURNED mỗi cái đúng một dòng outbox topic `notification.events` đúng type/dedupe/locale/recipient; payload validate theo schema (JSON Schema từ classpath như test outbox hiện có).
- [ ] Test `confirmedCarriesMaskedShipToOnly`: `phone_masked` = `091****678` cho `0912345678`, không có số đầy đủ ở bất cứ đâu trong payload; loại khác không có `ship_to`.
- [ ] Test `everyCancelPathSendsOneFixedReason`: khách hủy → CUSTOMER; OPS hủy với reason "khach bom hang" → SHOP và payload không chứa chuỗi đó; hết hạn → EXPIRED; reserve hết hàng → OUT_OF_STOCK; `RESERVE_FAILED` → OTHER.
- [ ] Test `noEmailNoOnlineNoEvent`: `contact_email` null → 0 event; method VNPAY (seed trực tiếp) → 0 event; transition rollback → 0 event; →RETURNING, →SHIPPING, →CANCELLING → 0 event.
- [ ] Chạy test fail, implement, chạy `./mvnw -B -pl services/order-service verify` PASS; contract test qua docker python (lệnh ở evidence ORD-04).
- [ ] Commit `feat(NOT-01): order emits NOTIFY_ORDER_* to notification.events`.

### Task 2: notification-service — schema, template, nhận event

**Files:** create module `services/notification-service` (pom theo payment-service + `spring-boot-starter-mail`,
`spring-boot-starter-mustache`; `Dockerfile`; `application.yaml` port 8087, DB `notification`, group `notification-service`,
relay tắt — service không có outbox); root `pom.xml` `<module>`; `db/migration/V001__notification.sql` (`notification_templates`,
`notifications` theo 05 §11.2 + `processed_events` từ `durability.sql`); `V002__seed_templates.sql` (12 dòng);
`src/main/resources/templates/email/layout.mustache`; `NotificationIntake.java`, `NotificationEventsListener.java`,
`EmailRenderer.java`; test `NotificationTestSupport`, `NotificationIntakeIntegrationTest`, `notification-test-init.sql`.

**Interfaces — Produces:** `EmailRenderer.render(String templateKey, String locale, JsonNode data) -> Rendered(UUID templateId,
int version, String subject, String html)` (layout bọc body, model đã format tiền/ảnh/link/câu lý do, shop/hotline từ config);
`NotificationIntake.accept(EventEnvelope e)` (gọi trong `InboxGuard.applyOnce`); bảng `notifications` cột theo 05.

- [ ] V001/V002 + test schema: UNIQUE `(dedupe_key, channel, recipient)`, CHECK status 7 giá trị, seed đủ 6 key × vi/en.
- [ ] Test `anOrderEventBecomesOnePendingEmail`: envelope `NOTIFY_ORDER_CONFIRMED` (vi) → một dòng PENDING, recipient, template_version, subject chứa mã đơn, html chứa `887.000 ₫`, `090****123`, ảnh ghép base URL, link `/orders/{order_no}`, "24 giờ".
- [ ] Test `theSameEventTwiceOrTheSameDedupeKeyIsOneRow`: cùng event_id hai lần, và event_id khác cùng dedupe → 1 dòng.
- [ ] Test `englishFallsBackToVietnameseWhenMissing` (xóa dòng en trong test) và `englishUsesEnglish` (`887,000 VND`).
- [ ] Test `renderedDataIsEscaped`: tên sản phẩm `<script>x</script>{{y}}` → html chứa `&lt;script&gt;`, không chứa `<script>`.
- [ ] Test `cancelledShowsTheFixedSentence`: mỗi `cancel_category` → đúng câu ở Global Constraints.
- [ ] Test `anInvalidEventIsPermanent`: thiếu `recipient` → `PermanentEventException` (DLT), 0 dòng.
- [ ] Listener `notification.events` theo mẫu `ShippingEventsListener` (map 6 type, kể cả `NOTIFY_RESET_PASSWORD` — Task 4 điền nhánh).
- [ ] `./mvnw -B -pl services/notification-service -am verify` PASS; commit `feat(NOT-01): notification-service intake and templates`.

### Task 3: worker gửi SMTP

**Files:** create `NotificationSender.java` (claim/gửi/ghi kết quả), `NotificationSweeper` (hoặc chung sender) cho SENDING hết
lease; test `NotificationSendIntegrationTest`, test helper `FakeSmtp` (ServerSocket kịch bản: OK, 451 ở MAIL FROM, 550 ở RCPT,
im lặng sau `.` của DATA, cổng đóng).

**Interfaces — Consumes:** dòng `notifications` Task 2. **Produces:** `NotificationSender.runOnce() -> int` (số dòng xử lý);
`@Scheduled` fixedDelay 2 s, batch 20, tắt bằng `fashion.notification.worker.enabled=false` trong test.

- [ ] Claim SQL một câu: chỉ `PENDING` có `next_retry_at <= now()` (không bao giờ `UNKNOWN`) → `SENDING`, `lease_token`, `lease_until = now()+60s` (`for update skip locked`); ghi kết quả chỉ khi `lease_token` khớp và `lease_until > now()`.
- [ ] Test `aPendingEmailIsSentOnce`: FakeSmtp OK → `SENT`, `sent_at`, một message, `Message-ID` đúng, metric `notification.sent` +1; `runOnce()` lần nữa → 0 message mới.
- [ ] Test `aTemporaryRefusalRetriesThenFails`: 451 → PENDING `retry_count` 1, `next_retry_at` trong [0.8, 1.2] phút; lặp tới lần thứ 6 → `FAILED`, metric `notification.failed{template}`.
- [ ] Test `aClosedPortRetries` (lỗi kết nối) như trên lần đầu.
- [ ] Test `aPermanentRefusalFailsAtOnce`: 550 → `FAILED`, `retry_count` 0 → không gửi lại.
- [ ] Test `silenceAfterDataIsUnknown`: im lặng sau DATA (timeout 10 s; test cấu hình 1 s) → `UNKNOWN`, metric, không retry.
- [ ] Test `aSendingRowWithAnExpiredLeaseBecomesUnknown`: seed SENDING lease quá hạn → `runOnce()` → `UNKNOWN`, 0 message.
- [ ] Test `twoWorkersSendEachEmailOnce`: 10 dòng, hai luồng `runOnce()` song song → 10 message, mỗi dòng SENT một lần.
- [ ] Test `logsCarryNoRecipientOrBody` (OutputCapture): không có email người nhận/subject trong log.
- [ ] verify PASS; commit `feat(NOT-01): SMTP sender with lease, retry and UNKNOWN`.

### Task 4: email đặt lại mật khẩu + relay user-service

**Files:** notification: `ResetPasswordSecrets.java` (gọi `GET /internal/api/v1/users/notification-secrets/{challenge_id}`,
service token `aud=user-service`, caller `notification-service`), nhánh reset trong `NotificationIntake` và sender; user-service:
`pom.xml` (+`platform-messaging`), `application.yaml` (Kafka bootstrap, `fashion.messaging.relay.enabled: ${USER_RELAY_ENABLED:true}`,
test tắt); test `ResetPasswordEmailIntegrationTest` (stub user bằng JDK `HttpServer` như order `StubServers`), user test hiện có vẫn PASS.

- [ ] Test `aResetEmailCarriesTheLinkOnlyAtSendTime`: intake → `rendered_body` chứa `@@RESET_URL@@`, không có secret; gửi → message chứa `/reset-password#token=<secret>` (escape), DB vẫn không chứa secret.
- [ ] Test `anExpiredResetIsNeverSent`: `expires_at` quá → `EXPIRED` không gọi user; user 404 `SECRET_NOT_FOUND` → `EXPIRED`; metric `notification.expired`.
- [ ] Test `aUserOutageRetries`: user 503 → PENDING retry_count 1, 0 message.
- [ ] Test `theResetTokenIsNeverStored`: sau gửi, `select` mọi cột text của `notifications` không chứa secret; log không chứa secret.
- [ ] user-service: relay bật trong app, tắt trong test support; `./mvnw -B -pl services/user-service,services/notification-service -am verify` PASS.
- [ ] Commit `feat(NOT-01): reset password email; user-service outbox relay`.

### Task 5: local stack, smoke, mutation, tài liệu

**Files:** `infra/local/postgres-init/08-notification-db.sh` (theo `06-payment-db.sh`); `infra/local/compose.yaml` (mount init,
service `notification` port 8087 nội bộ, `mailpit` image pin tag + digest ≥ 2 tuần, SMTP 1025 nội bộ, UI 8025 chỉ
`127.0.0.1`; user thêm Kafka env + depends_on kafka); `infra/local/kafka-init/create-topics.sh` (+`user.events`,
`notification.events` và DLT); `scripts/local-up.sh` (`service_key NOTIFICATION notification-service USER_INTERNAL_CALLERS`,
mật khẩu DB notification, build JAR); `scripts/smoke-local.sh`; `.github/workflows` nếu liệt kê module/JAR; docs.

- [ ] Smoke (đọc Mailpit API `GET /api/v1/search?query=...`): đơn COD → email "đặt đơn" có mã đơn; OPS confirm → `PACKING` → email "đang chuẩn bị"; đơn khách hủy → email hủy có "Bạn đã hủy đơn."; `forgot` → email reset → lấy token từ link → `POST /api/v1/auth/password/reset` 2xx → login mật khẩu mới OK. In `NOT-01 order emails PASS`, `NOT-01 reset email PASS`.
- [ ] Mutation (sửa tạm, chạy test, `git checkout --`): bỏ UNIQUE dedupe; lưu secret thay marker; timeout → `SENT`; sweeper gửi lại SENDING hết lease. Mỗi cái phải có test fail; ghi kết quả vào evidence.
- [ ] Docs: 03 §5 (5 event, producer email giao hàng đổi sang order, payload), 05 §11.2 (V001/V002 thực tế), 06 §10 ("NOT-01 hiện thực", email 2 ở `PACKING`), 08 (§11 spec + email 2 chủ dự án chọn), 12 §1 (lệnh), 13 (log/secret), 14 (link `/orders/{order_no}`, `/reset-password#token=`), 15 (Mailpit thay provider tới O07), README gốc, `services/notification-service/README.md` (chạy/test/config/migration/topic/health/metric/giới hạn), `services/user-service/README.md` (relay bật), `services/order-service/README.md`, CLAUDE.md, backlog 10 (NOT-01 In review local), evidence `docs/evidence/not-01-local-2026-10-22.md`.
- [ ] `./mvnw -B verify` (reactor) PASS; contract test; `local-up` + `smoke-local` PASS; `check_docs`, unittest scripts, `git diff --check`.
- [ ] Commit `docs(NOT-01): local stack, smoke, docs and evidence`.
