# notification-service

`TASK:NOT-01` · `REQ:NOT-01`, `REQ:NOT-05` (seed), `REQ:NOT-06`. Email giao dịch VI/EN: đặt đơn, đang chuẩn bị, giao thành công,
hủy, hoàn về shop (order phát) và đặt lại mật khẩu (user phát). [Spec](../../docs/superpowers/specs/2026-10-22-not-01-transactional-email-design.md) ·
[contract event](../../contracts/events/notification-events.schema.json) ·
[evidence](../../docs/evidence/not-01-local-2026-10-22.md) · quyết định ở [08 NOT-01](../../docs/design/08_decisions.md#not-01--quyết-định-2026-10-22).

Trạng thái: **Done (local, trước G0)** — [nghiệm thu](../../docs/evidence/acceptance-2026-10-22.md#tasknot-01--email-giao-dịch-vien). Gửi qua SMTP; local là Mailpit. Chưa có provider thật (O07).

## Chạy và test

```bash
./mvnw -pl services/notification-service -am test   # Docker: PostgreSQL + Kafka Testcontainers; SMTP là FakeSmtp; worker/listener tắt, test gọi runOnce()
bash scripts/local-up.sh && bash scripts/smoke-local.sh   # smoke: "NOT-01 order emails PASS", "NOT-01 reset email PASS"
```

Port 8087 trong container compose (không publish ra host; order-service cũng 8087 trong container của nó, hai container không đụng nhau).
Không có route Gateway, không API public. Xem email local: Mailpit UI/API `http://127.0.0.1:8025` (SMTP 1025 chỉ nội bộ compose).

## Luồng

- Consumer `notification.events` (group `notification-service`, inbox `processed_events`, ACK sau commit): kiểm tra các trường bắt buộc theo schema (viết tay, chưa có validator JSON Schema; người nhận phải là đúng một địa chỉ strict, không danh sách/tên hiển thị) → một TX ghi
  `notifications` `PENDING` với snapshot template + `rendered_subject`/`rendered_body` đã escape (Mustache, layout chung
  `templates/email/layout.mustache`). Trùng `(dedupe_key, channel, recipient)` → bỏ qua; thiếu/sai trường bắt buộc → DLT. Thiếu bản `en` → `vi`.
- Worker (poll 2 s, lease 120 s — timeout SMTP 10 s là theo từng bước socket) gửi SMTP ngoài TX, `Message-ID` `<{notification_id}@{domain}>`:

| Kết quả SMTP | Trạng thái |
|---|---|
| Nhận (250 sau DATA) | `SENT` (SMTP nhận, không có nghĩa khách đã đọc) |
| Không kết nối được, lỗi trước `MAIL FROM` (chào 421, rớt sau EHLO, AUTH), 4xx | `PENDING` + `retry_count + 1`, backoff 1/2/4/8/16 phút ±20 %; lần thất bại thứ 6 → `FAILED` |
| 5xx | `FAILED` ngay |
| Timeout/đứt kết nối sau khi đã kết nối, không có mã trả lời (kể cả rớt ở RCPT/DATA) | `UNKNOWN` (không gửi lại; đánh đổi có chủ ý, review PR #36 M2) |
| Địa chỉ người nhận không phải đúng một địa chỉ hợp lệ (lỗi khi dựng thư, chưa gọi SMTP) | `FAILED` |
| `SENDING` hết lease (worker chết) | `UNKNOWN`, không gửi lại mù (T22) |
| Quá `expires_at`, hoặc user 404 (reset) | `EXPIRED` |

- Reset password: `rendered_body` chỉ có marker `@@RESET_URL@@`. Lúc gửi gọi `GET /internal/api/v1/users/notification-secrets/{challenge_id}`
  (service token ADR-19), thay marker bằng `{storefront}/reset-password#token={secret}` trong bộ nhớ; token không vào DB, log, metric.

## Cấu hình

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `NOTIFICATION_DB_URL`, `NOTIFICATION_DB_PASSWORD` | local | runtime role `notification_runtime` |
| `NOTIFICATION_MIGRATION_DB_URL`, `NOTIFICATION_MIGRATION_DB_PASSWORD` | local | Flyway role `notification_migration` |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | `localhost`, `1025` | SMTP (compose: `mailpit`) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:19092` | consumer `notification.events` |
| `NOTIFICATION_SHOP_NAME`, `NOTIFICATION_HOTLINE`, `NOTIFICATION_MAIL_FROM` | giá trị mẫu | khung email; chủ dự án điền giá trị thật |
| `NOTIFICATION_STOREFRONT_BASE_URL`, `NOTIFICATION_PUBLIC_ASSET_BASE_URL` | `http://localhost:4173`, `http://localhost:8080` | link "Xem đơn hàng", reset; ghép ảnh sản phẩm tương đối |
| `NOTIFICATION_MESSAGE_ID_DOMAIN` | `example.invalid` | domain của `Message-ID` |
| `NOTIFICATION_USER_BASE_URL` | `http://localhost:8082` | user-service (internal secrets) |
| `NOTIFICATION_SERVICE_PRIVATE_KEY`, `NOTIFICATION_SERVICE_PUBLIC_KEY` | trống | ADR-19 caller `notification-service` (local-up sinh, thêm vào `USER_INTERNAL_CALLERS`) |
| `fashion.notification.worker.enabled` | `true` | tắt worker trong test |

## Database và topic

`V001__notification.sql` (`notification_templates`, `notifications`, `processed_events`), `V002__seed_templates.sql` (12 template EMAIL:
6 key × vi/en). Runtime không cần outbox (không phát `notification.results`). Local DB/role: `infra/local/postgres-init/08-notification-db.sh`.
Tiêu thụ `notification.events` (DLT `notification.events.DLT`); `user.events` do user-service phát (relay bật từ NOT-01).

## Health, observability, giới hạn

Readiness `readinessState,db` (health của mail không nằm trong readiness nên Mailpit tắt không làm service not-ready); liveness
`livenessState`; log JSON ECS có redaction, chỉ `notification_id`, `dedupe_key`, template, status — không email người nhận, nội dung hay token.
Metric `notification.sent`, `notification.failed{template}`, `notification.unknown`, `notification.expired` (`/actuator/prometheus`).

Giới hạn: chỉ email HTML (không plain-text); chưa `notification.results`; chưa job dọn recipient/nội dung 90 ngày (O06, trước G2); chưa
email đơn online (chờ PAY-02/03); chưa SMS/OTP/marketing/unsubscribe (NOT-02/03, USR-03); chưa UI/alert cho `FAILED`/`UNKNOWN` (ADM-02, PLT-05;
hiện chỉ metric/log/DB); `UNKNOWN` có thể nghĩa là khách không nhận email, gửi lại thủ công cần key mới + audit (chưa có API); địa chỉ giao
trong email 1 chỉ gồm `address_line` (chưa có danh mục tên tỉnh/phường); provider thật và domain gửi chờ O07; topic `notification.events`, `notification.events.DLT`, `user.events`, `user.events.DLT` phải có ở mọi môi trường (chỉ `infra/local/kafka-init` tạo; follow-up PLT-04) vì thiếu chúng chặn cả `order.events` cùng đơn.
