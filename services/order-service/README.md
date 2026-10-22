# order-service

`TASK:ORD-01` · `REQ:ORD-01`, `REQ:ORD-05`, `REQ:ORD-07`. Quote từ server (ký, 2 phút), tạo đơn `PENDING` idempotent với
snapshot bất biến, đọc đơn theo ownership; guest checkout bằng checkout token của cart-service.
[Spec](../../docs/superpowers/specs/2026-10-20-ord-01-order-quote-create-design.md) ·
[contract](../../contracts/openapi/order.yaml) · [evidence](../../docs/evidence/ord-01-local-2026-10-20.md).
`TASK:ORD-02` · `REQ:ORD-02`, `REQ:ORD-09`: saga checkout COD bền vững (worker nền, lease + CAS), bù trừ, hết hạn, dọn giỏ,
inbox `payment.events`. [Spec](../../docs/superpowers/specs/2026-10-20-ord-02-checkout-saga-design.md) ·
[evidence](../../docs/evidence/ord-02-local-2026-10-20.md).
`TASK:ORD-03` · `REQ:ORD-03`, `REQ:ORD-06`, `REQ:PAY-03` (COD: thu có bằng chứng → FINANCE xác nhận → `SUCCESS`) kiểm end-to-end qua smoke: admin orders cho OPS/FINANCE, OPS xác nhận/hủy
đơn COD chờ OPS, saga commit kho → `PACKING` → vận đơn SELF, consumer `shipping.events` → `SHIPPING`/`DELIVERED` +
`ORDER_COMPLETED`. [Spec](../../docs/superpowers/specs/2026-10-21-ord-03-cod-confirm-fulfil-design.md) ·
[evidence](../../docs/evidence/ord-03-local-2026-10-21.md) (Done local).
`TASK:ORD-04` · `REQ:ORD-04`, `REQ:ORD-10`, `REQ:SHP-06`: khách tự hủy đơn chờ OPS, OPS hủy tới trước bàn giao (bù trừ vận đơn → kho → payment), hoàn hàng theo `shipping.events`, phiếu kiểm đếm OPS cộng kho đúng một lần, trả hàng sau giao. [Spec](../../docs/superpowers/specs/2026-10-21-ord-04-cancel-return-design.md) · [evidence](../../docs/evidence/ord-04-local-2026-10-21.md) (Done local, trước G0; [nghiệm thu](../../docs/evidence/acceptance-2026-10-21.md#taskord-04--khách-tự-hủy-ops-hủy-sau-xác-nhận-hoàn-hàng-và-kiểm-đếm)).

Chưa có: refund tiền đã thu (đơn đã thu → `MANUAL PAID_NEEDS_REFUND`, PAY-04); shipment `UNKNOWN` của carrier ngoài; khách yêu cầu trả hàng tự phục vụ; `pack` đơn online (PAY-02/03); màn hình admin (ADM-01/02); VNPay/MoMo (PAY-02/03); rate limit checkout.

## Chạy và test

```bash
./mvnw -pl services/order-service -am test   # Docker: PostgreSQL 17.11 Testcontainers; cart/catalog/shipping/inventory/payment là HTTP stub; relay và worker saga tắt
bash scripts/local-up.sh && bash scripts/smoke-local.sh   # smoke: "ORD-01 guest quote/create/read PASS", "ORD-02 saga confirm PASS", "ORD-03 COD end-to-end PASS", "ORD-04 cancel after confirm PASS", "ORD-04 return PASS", "ORD-04 customer cancel PASS"
```

Port 8087 (không publish ra host). Qua Gateway: `/api/v1/orders/**` và `/admin/api/v1/orders/**` (route `order-admin`); không có route `/internal`.

## API

| Method / path | Ai | Ghi chú |
|---|---|---|
| `POST /api/v1/orders/quote` | member (Bearer) hoặc guest (`Checkout-Token`) | cart_id, cart_version, method, shipping_address, contact, note → quote_token + totals, hạn 2 phút |
| `POST /api/v1/orders` | như trên + `Idempotency-Key` | cart_id, cart_version, quote_token → `202` PENDING/PROCESSING, `Location`, `Retry-After: 3`; guest nhận cookie `order_access` |
| `GET /api/v1/orders/{order_no}` | member owner hoặc cookie `order_access` của đơn | Không thuộc actor → 404 `ORDER_NOT_FOUND` (T21); `processing_status` PROCESSING (PENDING/CANCELLING)/READY (CONFIRMED, RETURNING, CANCELLING chờ hàng đã bàn giao về — saga `WAIT_RETURN`)/FINISHED (CANCELLED, DELIVERED, RETURNED), `reason_code` khi hủy (thêm `CUSTOMER_CANCELLED`), `allowed_actions` `["CANCEL"]` khi `CONFIRMED` + `WAIT_OPS` |
| `POST /api/v1/orders/{order_no}/cancel` | như GET + `Idempotency-Key` | `{expected_version, reason?}` → `202` `CANCELLING` (`cancel_reason = CUSTOMER_CANCELLED`, history `CUSTOMER`, không `audit_logs`); `PENDING`/đã xác nhận → 409 `INVALID_TRANSITION` |
| `GET /api/v1/orders/mine` | member | `status`, `cursor`, `limit` 1..100 (mặc định 20), mới nhất trước |
| `GET /admin/api/v1/orders` | Bearer `order.read` | `status`, `q` (chính xác `order_no` hoặc `contact_phone`; phone cần `order.fulfill`, else 403), keyset `cursor`, `limit` 1..100 |
| `GET /admin/api/v1/orders/{order_no}` | `order.read` | đơn + items (kèm `order_item_id`) + `returns` (phiếu kiểm đếm) + history (actor, reason) + `expires_at`/`shipped_at` + saga (step, status, last_error) |
| `POST /admin/api/v1/orders/{order_no}/confirm-cod` | `order.fulfill` + `Idempotency-Key` | `{expected_version, note?}` → `202`, đơn vẫn `CONFIRMED` (PROCESSING), saga `COMMIT_STOCK` |
| `POST /admin/api/v1/orders/{order_no}/cancel` | `order.cancel` + `Idempotency-Key` | `{expected_version, reason}` → `202` `CANCELLING`, `cancel_reason = OPS_CANCELLED`; ORD-04: nhận `CONFIRMED` (`WAIT_OPS`/`COMMIT_STOCK`) và `PACKING` (`CREATE_SHIPMENT`/`DONE`), mọi trạng thái saga trừ lease sống (`SAGA_BUSY`) |
| `POST /admin/api/v1/orders/{order_no}/returns` | `order.return` + `Idempotency-Key` | `{expected_version, reason, evidence_ref, items:[{order_item_id, received_quantity, restock_quantity, condition?}]}` → `202` + đơn + `return` (`REQUESTED`); đơn `RETURNING`/`RETURNED`/`DELIVERED`; Σ received ≤ quantity (409 `RETURN_EXCEEDS_ORDERED`) |
| `POST /admin/api/v1/orders/{order_no}/returns/{return_id}/void` | `order.return` | Key + `{expected_version, reason}`; chỉ phiếu `MANUAL` của đơn (khác → 409 `RETURN_NOT_MANUAL`, không có → 404 `RETURN_NOT_FOUND`); phiếu `VOIDED` không còn tính vào Σ received để OPS đếm lại; `audit_logs` `ORDER_RETURN_VOID` (review PR #35 I2) |

- **Admin (ORD-03/04)**: `AdminAuth` kiểm Bearer + permission tại service (cùng `ORDER_JWT_PUBLIC_KEYS`). Không có
  `order.fulfill` (FINANCE) → bỏ key `contact_phone`/`contact_email`/`shipping_address`/`note` (cả response confirm/cancel:
  bỏ `contact`/`shipping_address`/`note`), history `reason` null. Confirm/cancel: auth → key → body, rồi một transaction khóa
  `order_sagas` rồi `orders` (thứ tự chung toàn service): version (409 `VERSION_CONFLICT`) → CONFIRMED + saga `WAIT_OPS`
  (+ COD cho confirm; cancel từ ORD-04 nhận thêm `COMMIT_STOCK` và `PACKING` `CREATE_SHIPMENT`/`DONE`, `SHIPPING`+ bị từ chối) (409 `INVALID_TRANSITION`, errors `status current=S`) → confirm chưa quá `expires_at`
  (409 `ORDER_EXPIRED`; cancel vẫn được) → không có worker giữ lease (409 `SAGA_BUSY`: đang xử lý, tải lại sau). Ghi chú
  cuộc gọi/lý do hủy lưu nguyên văn ở history và `audit_logs`: OPS không nhập số điện thoại/địa chỉ khách vào đó. Cả hai tăng `orders.version`, ghi `audit_logs` (`ORDER_CONFIRM_COD`/`ORDER_CANCEL`)
  và lưu response `202` trong `idempotency_requests` (actor `admin:{user_id}`); 409 nghiệp vụ rollback, không lưu (retry cùng
  key được đánh giá lại); khác body → 409 `IDEMPOTENCY_KEY_REUSED`.

- **Actor**: Bearer quyết định một mình (sai → 401, không fallback). Không Bearer: `Checkout-Token` (ES256 do cart ký,
  `aud=order-service`, `sub=guest:<sha256 cookie giỏ>`, `cart_id`, 15 phút); `cart_id` khác token → 404.
- **Quote**: cart snapshot đúng `cart_version` (khác → 409 `VERSION_CONFLICT`; rỗng → 422 `CART_EMPTY`), giá =
  `effective_price` của catalog (thiếu/không active → 409 `ITEM_UNAVAILABLE`), phí SELF + phí COD từ quote shipping lưu
  sẵn (422 `NO_SHIPPING_RULE`). `total = subtotal + shipping_fee + cod_fee`; tràn → 422 `AMOUNT_OUT_OF_RANGE`; tổng 0 →
  422 `PAYMENT_AMOUNT_UNSUPPORTED`. Guest bắt buộc `contact.email`. Không ghi DB, không gọi inventory.
- **quote_token**: `base64url({kid, quote}).base64url(HMAC-SHA256)`; chứa địa chỉ/liên hệ của chính khách (chỉ base64), không
  log.
- **Create**: lookup `(actor_key, Idempotency-Key)` trước mọi kiểm quote → cùng body trả đơn cũ (kể cả quote hết hạn/giá đã
  đổi), khác body 409 `IDEMPOTENCY_KEY_REUSED`. Sau đó: quote sai actor/cart/version → 400 `INVALID_QUOTE`; hết hạn → 409
  `QUOTE_EXPIRED`; giỏ khác dòng đã quote → 409 `VERSION_CONFLICT`; giá catalog khác → 409 `PRICE_CHANGED` (errors
  `variant_id`, `old=..,new=..`). Một transaction: `orders` + `order_items` + `order_status_history` + `order_sagas`
  (FULFILL/RESERVE/RUNNING cho ORD-02) + outbox `ORDER_CREATED`. Key khác cùng giỏ/version → 409 `CART_ALREADY_ORDERED`
  (order_no chỉ lộ cho cùng actor). COD `expires_at = +24h`. `order_no = ORD + yyyyMM (giờ VN) + 6 số sequence`.
- **Guest credential**: cookie `order_access` (HttpOnly, Secure, SameSite=Lax, `Path=/api/v1/orders/{order_no}`, 90 ngày —
  O06, server kiểm `created_at` của credential, không chỉ `Max-Age`); DB chỉ lưu sha256 (bảng `order_guest_access`). Retry
  create cùng key cấp thêm cookie mới, cookie cũ vẫn hợp lệ (hai response đua nhau không làm guest mất đơn), tối đa 10
  credential mỗi đơn (`ACCESS_CAPPED`); guest cùng actor nhận `409 CART_ALREADY_ORDERED` cũng kèm cookie của đơn đó. Log
  audit `ACCESS_REISSUED`. Dọn credential hết hạn (O06) cần role migration/owner — task retention riêng.
- **Tìm lại đơn (ORD-02)**: trước kiểm quote, đơn chưa hủy của cùng actor với `(cart_id, cart_version)` → 409
  `CART_ALREADY_ORDERED` + order_no (guest kèm cookie), để guest mất response/key vẫn lấy lại được đơn.

## Saga (ORD-02)

`SagaWorker.runOnce()` mỗi `ORDER_SAGA_POLL`: claim ≤ 10 saga đến hạn (`FOR UPDATE SKIP LOCKED`, lease 30 s). Mỗi bước ghi
intent (`*_state = UNKNOWN`) dưới lease, gọi HTTP ngoài transaction, ghi kết quả bằng CAS lease token; mất lease → bỏ kết quả.

| Step | Gọi (key) | OK | Từ chối nghiệp vụ | Còn lại (400/404/`*_CONFLICT`/401/403/5xx/timeout, exception) |
|---|---|---|---|---|
| `RESERVE` | inventory reserve (`reserve:{order_id}`, hash 03 §4) | HELD → `CREATE_PAYMENT` | `OUT_OF_STOCK` → CANCEL `OUT_OF_STOCK`; `RESERVATION_CLOSED` → `RESERVE_FAILED` | retry cùng key |
| `CREATE_PAYMENT` | payment create (`payment:{order_id}`) | đơn `CONFIRMED`, saga `WAIT_OPS` tới `expires_at`, enqueue `CART_CLEANUP` | `PAYMENT_CLOSED`/`PAYMENT_METHOD_UNAVAILABLE`/`PAYMENT_AMOUNT_UNSUPPORTED` → CANCEL `PAYMENT_FAILED` | retry cùng key |
| `COMPENSATE` | (ORD-04) vận đơn khi cần (`shipment-cancel:{order_id}`), release (`release:{order_id}`, snapshot + hash gốc) trừ khi RELEASED/RESTORED (NONE + 400 `INVALID_INPUT` = SKU lạ, không giữ gì), `/returns` khi `restore_required` (`cancel:{order_id}`), rồi luôn close (`close:{order_id}`) | `CANCELLED` | `MANUAL` (tiền đã thu hoặc đã ghi `PAYMENT_COMPLETED` → `PAID_NEEDS_REFUND`; từ chối vận đơn khác `SHIPMENT_HANDED_OVER`; release 200 sau khi đã ghi COMMITTED mà không `restore_required`) | retry |
| `WAIT_RETURN` (ORD-04) | không gọi | đỗ `next_retry_at = now + 30 ngày`, `WAITING`; chỉ event shipping/phiếu chuyển tiếp | — | — |
| `CLOSE_PAYMENT` (ORD-04) | payment close (`close:{order_id}`) | đơn `RETURNING → RETURNED`, saga `DONE` | đã thu → `MANUAL PAID_NEEDS_REFUND` | retry |

| `COMMIT_STOCK` (ORD-03) | inventory commit (`commit:{order_id}`; intent giữ `inventory_state = HELD`) | `COMMITTED`, đơn `CONFIRMED → PACKING` (history SYSTEM, không outbox), kết thúc lượt | `RESERVATION_EXPIRED` → `RELEASED` + CANCEL `EXPIRED`; `RESERVATION_CLOSED` → `RELEASED` + CANCEL `RESERVE_FAILED` | retry cùng key |
| `CREATE_SHIPMENT` (ORD-03) | shipping create (`shipment:{order_id}`, body snapshot: `quote_id`, `cod_amount = total_amount`, `to_address`) | lưu `shipment_id`, saga `DONE` | mọi từ chối → `MANUAL`, đơn giữ `PACKING` | retry cùng key/body |

Quá `expires_at` (COD 24 h) ở bước `RESERVE`/`CREATE_PAYMENT`/`WAIT_OPS`, kể cả trong lúc gọi payment → CANCEL `EXPIRED`, không `CONFIRMED` muộn; từ `COMMIT_STOCK` (đã OPS xác nhận) hạn của order không áp dụng, inventory quyết định. Intent tính một lần thử + backoff trước mỗi lời gọi xuôi; claim saga đã hết ngân sách → `MANUAL`. Backoff `min(2^n s, 5 phút)` ±20 %, 30 lần → `MANUAL`
(metric `order_saga_manual_total{step}` — tag `step` từ ORD-04 —, log `last_error`; `order_saga_handed_over_total` đếm hủy gặp vận đơn đã bàn giao), nghĩa vụ giữ nguyên. Chuyển trạng thái đơn ghi history (`SYSTEM`) +
outbox `ORDER_CONFIRMED`/`ORDER_CANCELLED` cùng transaction.

- **Hủy sau xác nhận (ORD-04)**: thứ tự cố định vận đơn → kho → payment, intent ghi trước mỗi HTTP (tính lần thử + backoff, không đổi `inventory_state`); vận đơn: `200` → `shipment_state CANCELLED`, `409 SHIPMENT_HANDED_OVER` → `HANDED_OVER` + saga `WAIT_RETURN` (không trả kho; metric `order.saga.handed_over` + log cảnh báo), chưa rõ → retry cùng key, không sang kho (T16). `reason` gửi shipping/inventory khi hủy là mã `cancel_reason` (hash idempotency ổn định); lời người nhập chỉ ở history/audit/phiếu.
- **Phiếu kiểm đếm (ORD-04)**: `OrderReturns` ghi `order_returns` + `order_return_items` + task `ORDER_RETURN` + `audit_logs` cùng TX dưới khóa `order_sagas` rồi `orders`; `OrderReturnWorker` gọi inventory `/returns` (`return:{return_id}`, chỉ SKU `restock > 0`) và gửi **lý do/evidence OPS đã lưu trên phiếu** (ổn định nên replay khớp; khác với lời gọi hủy/shipping dùng mã `cancel_reason`). Toàn bộ restock 0 → `COMPLETED` không gọi. `RETURN_EXCEEDS_COMMITTED`/`RESERVATION_NOT_COMMITTED` → phiếu và task `MANUAL`. Phiếu `COMPLETED` trên đơn `RETURNING` đặt saga `CLOSE_PAYMENT` trong cùng TX. Hash idempotency của phiếu có thứ tự cố định (không `Map.of`).
- **Dọn giỏ** (`CartCleanupWorker`): task `CART_CLEANUP` (business key order_id) gọi cart checkout-cleanup
  (`Idempotency-Key = order_id`); đơn `CANCELLING`/`CANCELLED` → bỏ qua; lỗi chưa rõ retry tối đa 30 lần rồi `MANUAL`, không
  đổi trạng thái đơn.
- **`payment.events`** (group `order-service`, inbox `processed_events`, ACK sau commit, retry/DLT platform-messaging):
  `PAYMENT_COMPLETED` ghi `paid_at` + `payment_state = PAID`, không đổi `status`; đơn lạ ACK + log; event hỏng → DLT.
- **`shipping.events`** (ORD-03, cùng group/inbox; khóa `order_sagas` rồi `orders`): `shipment_version` ≤
  `order_sagas.shipment_version` bỏ qua; `SHIPMENT_STATUS_UPDATED` `DELIVERING` → `SHIPPING`
  (`shipped_at` = `handed_over_at`, thiếu thì `provider_occurred_at`, rồi giờ nhận); `DELIVERED` → (`SHIPPING` nếu còn `PACKING`) `DELIVERED` (`delivered_at`) + outbox `ORDER_COMPLETED`; trạng
  thái khác chỉ ghi version; `SHIPMENT_CREATED` ghi `shipment_id` nếu saga chưa có; `COD_COLLECTED`/`COD_REMITTED` chỉ ACK. ORD-04: event có `shipment_id` khác saga bị bỏ + log; `RETURNING` (hoặc `RETURNED` đến trước) trên `SHIPPING`/`CANCELLING`/`PACKING` → `RETURNING` (`PACKING` đi qua `SHIPPING`); `RETURNED` khi đã `RETURNING` chỉ ghi version + log; `DELIVERED` trên `CANCELLING` → `DELIVERED` + `ORDER_COMPLETED` (log cảnh báo); `CANCELLED` do chính order hủy không cảnh báo.
  `processing_status`: CONFIRMED đã qua `WAIT_OPS` và PACKING chưa có shipment → PROCESSING; PACKING có shipment, SHIPPING →
  READY; DELIVERED → FINISHED.

## Email giao dịch (NOT-01)

`OrderNotifications` (gọi cuối `OrderTransitions.move`, cùng transaction) ghi outbox `notification.events` cho đơn COD có `contact_email`:
`NOTIFY_ORDER_CONFIRMED` (`PENDING → CONFIRMED`), `NOTIFY_ORDER_PREPARING` (`CONFIRMED → PACKING`, sau khi kho commit),
`NOTIFY_ORDER_DELIVERED`, `NOTIFY_ORDER_CANCELLED` (kèm `cancel_category` tính từ mã `cancel_reason`, không bao giờ từ `reason` tự do),
`NOTIFY_ORDER_RETURNED`. `dedupe_key` `order:{order_id}:{confirmed|preparing|delivered|cancelled|returned}` nên replay/retry không tạo email
thứ hai. Payload mang đủ dữ liệu render (`shipping_fee` = total − subtotal, `ship_to` chỉ ở CONFIRMED với số điện thoại che `090****123`);
đơn online và đơn không có email liên hệ không phát. Contract: `contracts/events/notification-events.schema.json`, 03 §5.2.

## Cấu hình

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `ORDER_DB_URL`, `ORDER_DB_PASSWORD` | local | runtime role `order_runtime` |
| `ORDER_MIGRATION_DB_URL`, `ORDER_MIGRATION_DB_PASSWORD` | local | Flyway role `order_migration` |
| `ORDER_JWT_PUBLIC_KEYS` | — | verifier access token member |
| `ORDER_SERVICE_PRIVATE_KEY`, `ORDER_SERVICE_PUBLIC_KEY` | trống | ADR-19 caller `order-service` tới cart/catalog/shipping/inventory/payment (local-up sinh, thêm vào `*_INTERNAL_CALLERS`) |
| `ORDER_CART_URL`, `ORDER_CATALOG_URL`, `ORDER_SHIPPING_URL`, `ORDER_DEPENDENCY_TIMEOUT` | localhost, `2s` | phụ thuộc; lỗi/timeout → 503, không ghi gì |
| `ORDER_INVENTORY_URL`, `ORDER_PAYMENT_URL` | `localhost:8083`, `:8086` | saga ORD-02 (cùng timeout) |
| `ORDER_SAGA_ENABLED`, `ORDER_SAGA_POLL`, `ORDER_SAGA_MAX_RETRIES` | `true`, `1s`, `30` | worker saga + dọn giỏ |
| `ORDER_CHECKOUT_PUBLIC_KEYS` | trống | `kid:base64 X.509` của checkout token cart (nhiều key để xoay); cart dùng `CART_CHECKOUT_PRIVATE_KEY`/`CART_CHECKOUT_KID` |
| `ORDER_QUOTE_SECRET`, `ORDER_QUOTE_KID` | trống, `q1` | HMAC quote_token (base64 ≥ 32 byte) |
| `ORDER_PAYMENT_METHODS` | `COD` | phương thức được quote |
| `ORDER_RELAY_ENABLED`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `true`, `localhost:19092` | outbox relay `order.events`; consumer `payment.events`, `shipping.events` (ORD-03) |

## Database

`V001__order.sql`: `orders` (05 §7 + `created_at`/`updated_at`, UNIQUE `(actor_key, checkout_key)` và `(cart_id, cart_version)`,
CHECK tổng tiền, guest ⇔ `guest_access_hash`), `order_guest_access` (mọi access hash đã cấp cho guest), `order_items`, `order_status_history`, `order_sagas`, `outbox_events`,
sequence `order_no_seq`. Runtime không DELETE/TRUNCATE đơn/items/history, không UPDATE history. Local DB/role:
`infra/local/postgres-init/07-order-db.sh`. `V002__order_saga.sql` (ORD-02): `order_sagas.payment_state`, UNIQUE giỏ/version
thành unique index một phần `WHERE status <> 'CANCELLED'`, `processed_events`, `background_tasks`. `V003__order_ops.sql`
(ORD-03): `audit_logs` (REVOKE UPDATE/DELETE/TRUNCATE khỏi runtime), `idempotency_requests`, `orders.shipped_at`,
`order_sagas.shipment_version`. `V004__order_returns.sql` (ORD-04): `order_returns`, `order_return_items` (runtime không DELETE, items không UPDATE), `order_sagas.shipment_state`. Retention: PII đơn ẩn danh sau 12 tháng từ khi đơn kết thúc (O06) — job chưa làm.

## Health, observability, giới hạn

Readiness `readinessState,db`; log JSON ECS có redaction (token, cookie không được log); trace OTLP khi đặt endpoint;
`/actuator/prometheus`. Event `ORDER_CREATED` không chứa địa chỉ/liên hệ. Giới hạn: chưa có rate limit checkout 10 rpm
(03 §1.2) — chưa có limiter chung; quote_token có thể dài tới ~20 KB với 100 dòng. Saga: `MANUAL` chưa có UI/alert (ADM-02/PLT-05), chỉ metric + log; nhiều
instance dựa vào `SKIP LOCKED` + lease, chưa đo tải; online `WAIT_PAYMENT` chưa đạt được tới PAY-02/03. Admin: list đọc
saga từng dòng CONFIRMED (N+1), chưa index `contact_phone` (seq scan).
Admin `q` chỉ coi `ORD` + 12 chữ số (không phân biệt hoa thường) là order_no (`OrderAdminQueries.ORDER_NO`); `q` rỗng là
không lọc; chuỗi khác là số điện thoại, so theo chữ số với `84` đầu quy về `0` (`+84 912…` khớp `0912…`). Consumer shipping
cảnh báo log (`order_id`, status, không PII) khi shipment cần OPS (`FAILED`/`MANUAL`) hoặc lệch trạng thái đơn; `shipment_version` lưu theo đơn và `shipment_id` của event được đối chiếu với saga (ORD-04: chưa tạo vận đơn thứ hai cho một đơn).
Giới hạn ORD-04: `WAIT_RETURN` đỗ 30 ngày chờ event shipping (không có job quét đơn chờ hàng quá lâu); phiếu chỉ `MANUAL` khi inventory từ chối (không ghi kho; metric `order_return_manual_total` + log `order_id`/`return_id`/mã lỗi), lỗi hạ tầng được thử lại cùng key mãi (backoff tối đa 5 phút ±20 %, quá 30 lần thì metric `order_return_stalled_total` + log, tăng mỗi lần thử sau đó nên alert theo rate, không theo tổng); OPS thoát phiếu `MANUAL` bằng `POST .../returns/{return_id}/void` rồi lập phiếu mới (review PR #35 I2); chưa có UI (ADM-02); `allowed_actions` luôn là hành động của khách; đơn đã thu tiền không tự `CANCELLED`/`RETURNED` (chờ PAY-04).
