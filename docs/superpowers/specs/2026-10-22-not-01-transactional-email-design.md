# NOT-01 — email giao dịch VI/EN (notification-service)

`TASK:NOT-01` · `REQ:NOT-01`, `REQ:NOT-05` (seed), `REQ:NOT-06` · dependency PLT-03, USR-01 (Done local); T22, U20.
Trạng thái: **đã duyệt** 2026-10-22 (chủ dự án). Nội dung email do chủ dự án chọn qua hỏi đáp và mockup (kiểu B); đã qua builder
check (subagent), các điểm sửa đánh dấu "(builder check)". Lựa chọn của
agent đánh dấu "(agent)", danh sách ở §11.
Nguồn: 02 NOT-01/05/06; 03 §5 (`notification.events`, NOTIFY_*), §internal user `notification-secrets`; 05 §11.2
(`notification_templates`, `notifications`); 06 §10 (notification-service), T22; 11 U20; 13 (retention 90 ngày, secret tạm);
15 (Email/NOT-01, O07); 08 ADR-09, O07; contract `contracts/events/notification-events.schema.json`, `user-internal.yaml`.

## 1. Vì sao

Demo G1 (COD) cần khách nhận email về đơn hàng; USR-01 đã phát `NOTIFY_RESET_PASSWORD` nhưng chưa ai gửi, nên "quên mật khẩu"
chưa dùng được. Chưa chọn nhà cung cấp email thật (O07), nên bản này gửi qua SMTP vào một mail sink chạy local; đổi sang
provider thật là đổi cấu hình/adapter, không đổi luồng.

## 2. Mục tiêu, ngoài phạm vi, anti-goals

Mục tiêu — 6 email giao dịch, VI/EN (chủ dự án chọn):

| # | Email | Khi nào | Producer |
|---|---|---|---|
| 1 | Đặt đơn thành công | Đơn COD vào `CONFIRMED` (saga tới `WAIT_OPS`) | order |
| 2 | Shop đã xác nhận, "đơn đang được chuẩn bị" (không hứa ngày giao) | Đơn `CONFIRMED → PACKING` (sau khi OPS xác nhận và kho đã trừ) | order |
| 3 | Giao hàng thành công | Đơn vào `DELIVERED` | order |
| 4 | Đơn bị hủy, kèm câu lý do cố định | Đơn vào `CANCELLED` | order |
| 5 | Đơn đã hoàn về shop | Đơn vào `RETURNED` | order |
| 6 | Đặt lại mật khẩu (link hạn 30 phút) | `NOTIFY_RESET_PASSWORD` (đã có, USR-01) | user |

Không gửi email "đang giao" (chủ dự án bỏ).

Ngoài phạm vi: email marketing/bộ sưu tập mới, opt-in, unsubscribe (NOT-03, Phase 2); OTP/SMS (NOT-02); editor template
(NOT-05 P1); email thanh toán online/refund (PAY-02–04); provider thật và domain gửi (O07); xem đơn guest từ thiết bị khác.

Anti-goals: gửi trùng một email cho cùng một sự việc; lưu hay log token reset/nội dung đã render có secret; lộ ghi chú nội
bộ OPS cho khách; link chứa credential đăng nhập/xem đơn; email lỗi làm chậm hay hỏng đơn hàng; báo SENT khi không biết
SMTP đã nhận; gửi lại mù khi không biết lần trước đã gửi chưa.

## 3. Nội dung và giao diện (chủ dự án duyệt mockup kiểu B)

- Khung chung: dải tên shop trên cùng, khối trắng nội dung, nút đen, chân email hotline + "email tự động, vui lòng không trả
  lời". Tên shop, hotline, địa chỉ gửi (`from`), tên miền storefront: **cấu hình, giá trị mẫu** (chủ dự án điền sau).
- Email 1 (đầy đủ nhất): "Cảm ơn bạn đã đặt hàng", mã đơn, ngày đặt, "Shop sẽ gọi điện xác nhận trong vòng 24 giờ"; mỗi sản
  phẩm một dòng có **ảnh nhỏ**, tên, size/màu, số lượng, thành tiền; tạm tính, phí giao, **tổng COD** in đậm; người nhận,
  **số điện thoại che** dạng `090****123`, địa chỉ giao; nút "Xem đơn hàng".
- Email 2–5: cùng khung, mã đơn, danh sách sản phẩm (ảnh, tên, size/màu, số lượng), nút "Xem đơn hàng"; không có địa chỉ và
  số điện thoại (agent: dữ liệu tối thiểu).
- Email 4 — câu lý do cố định theo loại hủy, không bao giờ in `reason` OPS nhập (chủ dự án đồng ý):
  - `CUSTOMER_CANCELLED` → "Bạn đã hủy đơn."
  - `OPS_CANCELLED` → "Shop đã hủy đơn, vui lòng liên hệ hotline nếu cần hỗ trợ."
  - hết hạn (`EXPIRED` và các mã hết hạn khác của order) → "Đơn quá hạn xác nhận."
  - `OUT_OF_STOCK` → "Sản phẩm đã hết hàng."
  - mã khác (`RESERVE_FAILED`, `PAYMENT_FAILED`, giá trị dự phòng `CANCELLED`…) → "Đơn không thể xử lý, vui lòng liên hệ
    hotline." (agent)
  Order tính `cancel_category` từ cột `orders.cancel_reason` (mã), không bao giờ từ `reason` tự do của
  `order_status_history`. `cancel_reason = 'RETURNED'` chỉ có trên đơn đi đường hoàn hàng, kết thúc ở `RETURNED` → email 5,
  không phải email 4 (builder check). Đơn COD chưa thu tiền nên email không nhắc hoàn tiền.
- Ảnh: `order_items.image_url` (snapshot lúc đặt) ghép với base URL public cấu hình; ảnh catalog đã publish phục vụ public qua
  Gateway `/api/v1/catalog/images/{asset_id}`. Thiếu ảnh → ô trống, chữ vẫn đủ (mail chặn ảnh vẫn đọc được).
- Tiền VND nguyên: vi `887.000 ₫`, en `887,000 VND` (agent).
- Link "Xem đơn hàng" = `{storefront_base_url}/orders/{order_no}`, **không token**. Member đăng nhập rồi xem; guest xem được
  khi còn cookie trên trình duyệt đã đặt, không thì trang báo liên hệ shop (03: không tra cứu bằng phone/email). Trang đích
  do WEB-04 làm.
- Email 6: "Bạn vừa yêu cầu đặt lại mật khẩu", nút "Đặt lại mật khẩu", "link có hiệu lực 30 phút", "không phải bạn thì bỏ qua".
  Link `{storefront_base_url}/reset-password#token={secret}` (agent: token trong fragment để không vào log server/Referer);
  WEB-02 làm trang này theo đúng dạng link.
- Ngôn ngữ: email đơn theo `orders.locale`; reset theo `locale` trong event. Thiếu bản en → dùng vi (06 §10).
- Email chỉ HTML, không phần plain-text (agent; thêm khi chọn provider O07 nếu cần cho deliverability).

## 4. Producer: order phát NOTIFY_* (thay đổi so với 03)

- Order ghi outbox `notification.events` **cùng transaction** với chuyển trạng thái, tại `OrderTransitions.move` (mọi đổi
  `orders.status` đi qua đây cùng history + outbox `order.events`). Email 2 gắn với `CONFIRMED → PACKING` (do `SagaWorker` gọi
  sau khi inventory commit), không gắn với lệnh `confirm-cod` — chủ dự án chọn 2026-10-22 để email luôn đúng: lệnh xác nhận chỉ
  đặt saga `COMMIT_STOCK`, đơn còn có thể bị hủy nếu commit thất bại (builder check). Event:
  `NOTIFY_ORDER_CONFIRMED` (email 1, tên giữ như 03), `NOTIFY_ORDER_PREPARING` (2), `NOTIFY_ORDER_DELIVERED` (3),
  `NOTIFY_ORDER_CANCELLED` (4), `NOTIFY_ORDER_RETURNED` (5).
- Outbox order đã định tuyến theo `registry.json` (topic, `aggregate_id`, `partition_key` theo từng event type), nên chỉ cần
  thêm 5 dòng registry: topic `notification.events`, `aggregate_id` = `order_id` (guest không có `user_id`), `partition_key` =
  `dedupe_key` như `NOTIFY_RESET_PASSWORD` (builder check).
- dedupe_key `order:{order_id}:{confirmed|preparing|delivered|cancelled|returned}` — mỗi sự việc tối đa một email dù transition
  chạy lại/replay.
- **Đổi so với 03:** 03 ghi `NOTIFY_SHIPMENT_STATUS` do shipping phát. Shipping không có email/locale khách; order đã nghe
  `shipping.events` và đã có cả hai, nên email "giao thành công" do order phát. `NOTIFY_SHIPMENT_STATUS` để dành cho carrier
  ngoài (SHP-03). Ghi vào 08 (agent).
- Payload (JSON Schema mới trong `notification-events.schema.json`, `additionalProperties: false`): `dedupe_key`, `user_id`
  nullable, `recipient.email`, `channel = EMAIL`, `locale`, `template_key`, `data`. `data` chứa đủ để render, vì notification
  không đọc DB order (06 §10): `order_no`, `placed_at`, `items[] {name, size, color, quantity, line_total, image_url}`,
  `subtotal`, `shipping_fee`, `total`, `payment_method`; chỉ email 1 thêm `ship_to {name, phone_masked, address}` —
  `name` = `shipping_address.recipient_name`, `phone_masked` từ `shipping_address.phone`, `address` =
  `shipping_address.address_line` — địa chỉ đơn chỉ có `province_code`/`ward_code`, chưa có danh mục tên hành chính (SHP-01),
  nên email không in mã tỉnh/phường (agent, sửa khi viết plan); email 4
  thêm `cancel_category` (`CUSTOMER`/`SHOP`/`EXPIRED`/`OUT_OF_STOCK`/`OTHER`). **Order che số điện thoại trước khi ghi event**,
  không gửi `reason` tự do (agent).
- Người nhận = `orders.contact_email`. Đơn không có email liên hệ → không ghi event (ô email lúc checkout không bắt buộc).
- Không ghi event cho đơn online (`WAITING_PAYMENT`/`PAID`) ở bản này — chờ PAY-02/03 (agent: tránh hứa "COD" sai).

## 5. notification-service (service mới)

Theo 06 §10 và 05 §11.2; Spring Boot như các service khác, DB `notification` riêng (migration/runtime role như
`07-order-db.sh`, file init mới `08-notification-db.sh`), dùng `platform-durability` + `platform-messaging`.

1. **Nhận:** consumer `notification.events` (inbox, ACK sau commit) → validate theo schema → một TX: `processed_events` +
   dòng `notifications` `PENDING` với snapshot recipient, template id/version, `rendered_subject`/`rendered_body` đã escape.
   Trùng `(dedupe_key, channel, recipient)` → bỏ qua (ACK). Event không hợp lệ → DLT, không gửi.
2. **Reset password:** không lưu secret. `rendered_body` lưu với **marker** thay link; lúc gửi gọi
   `GET /internal/api/v1/users/notification-secrets/{challenge_id}` (service token ADR-19), render link **trong bộ nhớ**, không
   log. Quá `expires_at` hoặc user trả 404 → `EXPIRED`, không gửi. User 5xx/timeout → thử lại như lỗi chắc chắn.
3. **Gửi:** worker claim theo lease (`lease_until`, `lease_token`, như saga order; lease 60 giây, timeout SMTP
   kết nối/đọc 10 giây — lease luôn dài hơn một lần gửi để gửi chậm mà thành công không thành `UNKNOWN`; agent), gửi SMTP **ngoài TX**, `Message-ID` =
   `<{notification_id}@{domain cấu hình}>`.
   - SMTP nhận (250 sau DATA) → `SENT` + `sent_at` (`SENT` = server SMTP nhận, không phải khách đã đọc).
   - Lỗi chắc chắn chưa gửi (không kết nối được, 4xx) → `retry_count + 1`, backoff có jitter; quá **5** lần → `FAILED`.
   - Bị từ chối vĩnh viễn (5xx, ví dụ địa chỉ sai) → `FAILED` ngay (agent).
   - Timeout/đứt kết nối sau khi đã gửi DATA → `UNKNOWN`, không gửi lại.
4. **T22/U20:** dòng `SENDING` hết lease (worker chết giữa chừng) → `UNKNOWN`, không gửi lại mù (SMTP không có API tra cứu).
   Retry dùng snapshot đã lưu nên đổi template giữa chừng không ảnh hưởng email đang chờ.
5. **Template:** seed migration 12 dòng (6 key × vi/en) trong `notification_templates`; engine template tự escape HTML
   (agent: Mustache qua starter Spring Boot có sẵn trong BOM — builder xác nhận version), layout chung để không chép khung 12
   lần (cách làm do builder). Dữ liệu từ event luôn được escape.
6. **Kết quả `notification.results`** (`NOTIFICATION_SENT/FAILED`): **không làm ở NOT-01** — chưa có consumer; NOT-02 (restock)
   thêm khi inventory cần (agent; ghi 08).
7. **Quan sát:** metric `notification.sent`, `notification.failed{template}`, `notification.unknown`, `notification.expired`; log
   có `notification_id`, `dedupe_key`, template, status — **không** email người nhận, nội dung hay token.
8. Không API public, không route Gateway. OPS xem email lỗi qua metric/log/DB ở bản này; UI thuộc ADM-02.
9. Retention 90 ngày cho recipient/nội dung (13): ghi giới hạn, job dọn chưa làm (trước G2, O06).

## 6. Hạ tầng local và cấu hình

- **user-service chưa có relay** (README: outbox `NOTIFY_RESET_PASSWORD`/`USER_CREATED` "chưa có relay"), nên email 6 cần bật
  relay `platform-messaging` cho user-service (dependency, Kafka bootstrap, `fashion.messaging.relay.enabled`), thêm topic
  `user.events` + DLT; outbox cũ đang chờ sẽ được phát khi bật (phát hiện khi viết plan).
- notification-service gọi user internal bằng service token: `local-up.sh` sinh khóa `NOTIFICATION` và thêm vào
  `USER_INTERNAL_CALLERS` như các caller khác.

- `infra/local/compose.yaml`: thêm `notification-service` và **Mailpit** (mail sink, image pin version, chỉ bind
  `127.0.0.1`; UI xem email local). Không mở ra ngoài.
- Topic `notification.events` **chưa** có trong `infra/local/kafka-init/create-topics.sh` (user-service ghi outbox nhưng chưa ai
  tạo topic/consumer — builder check): thêm `notification.events` và `notification.events.DLT` theo quy ước DLT hiện có, giữ
  script idempotent.
- Cấu hình (giá trị mẫu, không secret thật): SMTP host/port, `from`, tên shop, hotline, `storefront_base_url`,
  `public_asset_base_url`, domain `Message-ID`. `infra/environments` chỉ thêm placeholder khi cần cho render-staging; không
  provision.

## 7. Contract và tài liệu

- `notification-events.schema.json` + `registry.json`: 5 event order mới; contract test (tests/contracts).
- 03 §5 (producer email giao hàng đổi sang order, 5 event, payload), 05 §11.2 (migration thực tế), 06 §10 ("NOT-01 hiện thực"),
  08 (các quyết định agent ở §11), 13 (log/secret), 14 (link `/orders/{order_no}`, `/reset-password#token=`), 12 lệnh
  chạy/test, README gốc + `services/notification-service/README.md` + `services/order-service/README.md`, CLAUDE.md, backlog
  10, evidence.

## 8. Test (PostgreSQL + Kafka Testcontainers; SMTP: Mailpit container hoặc stub — builder chọn)

- Order: mỗi transition 1–5 ghi đúng một outbox NOTIFY cùng TX (rollback → không event); chạy lại transition/replay event
  shipping → không event thứ hai; không email liên hệ → không event; phone đã che, không có `reason` tự do; đơn online không event.
- Notification nhận: event trùng → một dòng; event sai schema → không dòng; thiếu bản en → vi; dữ liệu có `<script>` được escape.
- Gửi: thành công → `SENT` một email đúng người nhận/tiêu đề/ngôn ngữ; 4xx/không kết nối → retry, lần 6 → `FAILED`; 5xx → `FAILED`
  ngay; timeout sau DATA → `UNKNOWN`; `SENDING` hết lease → `UNKNOWN`, không gửi lần hai (T22).
- Reset: gửi được link đúng token; DB và log không chứa token; quá `expires_at` hoặc 404 → `EXPIRED`, 0 email.
- Hai worker song song → mỗi email gửi một lần (lease).
- Mutation: bỏ dedupe; lưu token vào `rendered_body`; coi timeout là `SENT`; gửi lại `SENDING` hết lease.
- Smoke local: đặt đơn COD → Mailpit có email 1 đúng mã đơn; OPS xác nhận → đơn `PACKING` → email 2; hủy một đơn → email 4 với câu cố định;
  `forgot` → email 6 có link reset dùng được (đặt lại mật khẩu thành công).

## 9. Rủi ro

- Payload `notification.events` mang tên/địa chỉ khách (email 1) trên Kafka: chấp nhận vì notification không được đọc DB order;
  giảm bằng che phone, chỉ email 1 có địa chỉ, retention topic theo 13.
- `UNKNOWN` sau crash có thể nghĩa là khách không nhận email: chấp nhận theo 06 (không hứa exactly-once); metric để OPS thấy.
- Link storefront `/orders/…`, `/reset-password` chưa có trang (WEB-02/04): smoke dùng API để kiểm link reset.

## 10. Phụ thuộc và nghiệm thu

Dependency PLT-03, USR-01 Done local. O07 chưa chốt → nghiệm thu mức local với mail sink (15: task mock có thể hoàn tất trước);
gửi qua provider thật/domain là việc sau O07, trước G2.

## 11. Lựa chọn của agent (chủ dự án xem khi duyệt)

1. Email "giao thành công" do order phát (`NOTIFY_ORDER_DELIVERED`), không phải shipping như 03.
2. Event mang dữ liệu render; order che phone; chỉ email 1 có địa chỉ.
3. Mã hủy lạ → câu chung "liên hệ hotline".
4. Link reset dạng `#token=` (fragment).
5. Chỉ HTML; Mustache; Mailpit làm mail sink.
6. 5xx SMTP → `FAILED` ngay; 4xx/không kết nối → retry tối đa 5.
7. Chưa phát `notification.results`; chưa job dọn 90 ngày.
8. Không gửi email cho đơn online ở bản này.
