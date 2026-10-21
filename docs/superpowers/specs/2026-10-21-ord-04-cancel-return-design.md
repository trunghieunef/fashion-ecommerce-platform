# ORD-04 — hủy sau xác nhận, khách tự hủy, hoàn hàng và kiểm đếm trả kho

`TASK:ORD-04` · `REQ:ORD-04`, `REQ:ORD-10`, `REQ:SHP-06` · dependency ORD-03, INV-03 (Done local); T04, T16, T17.
Trạng thái: **đã duyệt** 2026-10-21 (chủ dự án). Chủ dự án chọn bỏ phần hỏi đáp và đã quyết 3 điểm ở §12; đã qua builder
check (subagent), các điểm sửa đánh dấu "(builder check)". Lựa chọn của agent ở §11.
Nguồn: 02 ORD-04/08/10, SHP-06; 06 §5.3 (state machine), §5.4 (hủy/phục hồi), §16, §17.2 (shipment); 03 §2 (customer cancel),
§3 (admin cancel/returns); contract inventory `/release`, `/returns`, `GET /reservations/{order_id}` (INV-02/03); shipping
`by-order/{order_id}/cancel`, self-events (SHP-02); 05 `order_returns`, `order_return_items`; 13 (`order.return`); spec
[ORD-02](2026-10-20-ord-02-checkout-saga-design.md), [ORD-03](2026-10-21-ord-03-cod-confirm-fulfil-design.md).

## 1. Vì sao

Sau ORD-03, đơn COD đi trọn đường thẳng: đặt → OPS xác nhận → trừ kho → giao → đối soát. Nhưng mọi nhánh rẽ đều kẹt:

- Khách đổi ý trước khi shop gọi: không có nút hủy cho khách.
- OPS cần dừng đơn đã xác nhận/đóng gói (hết hàng thật khi đóng, khách gọi lại hủy): 409, phải xử lý tay ngoài hệ thống.
- Shipper giao thất bại rồi mang hàng về: đơn đứng mãi ở `SHIPPING`, payment COD mở mãi, kho không được cộng lại.
- Khách trả hàng sau khi nhận: không có chứng từ, không cộng kho.

ORD-04 nối các nhánh đó để demo G1 không có đơn treo, và để kho/tiền luôn khớp với hàng thật.

## 2. Mục tiêu, ngoài phạm vi, anti-goals

Mục tiêu:
- **Khách tự hủy** đơn của mình khi shop chưa xác nhận (`CONFIRMED` + saga `WAIT_OPS`).
- **OPS hủy sau xác nhận, trước bàn giao** (`CONFIRMED` đang commit, `PACKING`, kể cả saga đang `MANUAL`): hủy vận đơn trước,
  rồi mới trả kho đã trừ, rồi đóng payment → `CANCELLED`. Không trả kho khi chưa chứng minh hàng chưa rời shop (T16).
- **Hoàn hàng sau bàn giao**: `SHIPPING`/`CANCELLING` → `RETURNING` theo `shipping.events`; hàng về, OPS kiểm đếm → inventory
  cộng đúng số kiểm đếm một lần (T17) → đóng payment → `RETURNED`.
- **Trả hàng sau giao** (REQ ORD-10): OPS ghi phiếu + kiểm đếm trên đơn `DELIVERED`, cộng kho phần nhập lại, đơn **giữ**
  `DELIVERED`.
- Follow-up đã hứa: bước bù trừ ghi intent (crash lặp vẫn vào MANUAL — ORD-02), đối chiếu `shipment_id` của event với saga,
  tag `step` cho metric saga `MANUAL` (ORD-03 review).

Ngoài phạm vi:
- Refund tiền (PAY-04). Đơn đã thu tiền mà phải hủy/hoàn → `MANUAL` `PAID_NEEDS_REFUND` như ORD-02 (chủ dự án quyết, §12.3).
- Online (`WAITING_PAYMENT`, `PAID`) và tiền đến muộn (REQ ORD-08) — chờ TASK PAY-02/03/04.
- Carrier ngoài; shipment `UNKNOWN` của carrier (SELF tạo đồng bộ — chỉ có "response bị mất", xử lý bằng tombstone §4).
- Chính sách đổi trả (hạn ngày, phí, điều kiện hàng — O05/D10 chưa chốt): chỉ làm cơ chế, không enforce (chủ dự án quyết, §12.1).
- Màn hình (ADM-02, WEB-04); khách yêu cầu trả hàng tự phục vụ (06 §5.3: chưa thuộc bản này).

Anti-goals: cộng kho khi hàng có thể đã rời shop; cộng kho hai lần cho một lần kiểm đếm; cộng kho quá số đã trừ; báo
`CANCELLED`/`RETURNED` khi còn nghĩa vụ (vận đơn còn sống, kho chưa trả, payment chưa đóng); khách hủy được đơn shop đã xác
nhận; sửa lịch sử `DELIVERED` thành chưa giao; đơn kẹt không có đường ra.

## 3. Khách tự hủy (`POST /api/v1/orders/{order_no}/cancel`)

- Xác thực như GET đơn (member Bearer / guest cookie `order_access` của đúng đơn; không thuộc actor → 404 `ORDER_NOT_FOUND`,
  T21). Body `{expected_version, reason?}` (reason ≤ 500, tùy chọn), `Idempotency-Key` bắt buộc; idempotency actor = actor key
  của đơn (`user:{id}` / `guest:{hash}`), operation `customer-cancel`.
- Chỉ khi `CONFIRMED` + saga `FULFILL`/`WAIT_OPS`, cùng khóa `order_sagas` → `orders` và thứ tự kiểm như OPS cancel ORD-03
  (version → trạng thái → lease). `cancel_reason = CUSTOMER_CANCELLED`; history actor `CUSTOMER` (actor_id = user_id hoặc null
  cho guest) + reason khách nhập. Không ghi `audit_logs` (audit là thao tác admin). Response là view khách (như GET).
- `PENDING` (saga đang giữ hàng) hoặc shop đã xác nhận → `409 INVALID_TRANSITION` (errors `status current=S`); FE báo liên hệ
  shop. Cập nhật 03 §2 ("202 CANCELLING hoặc trạng thái hiện hành" → 202 hoặc 409) (builder check).
- `GET` đơn của khách trả `allowed_actions: ["CANCEL"]` khi điều kiện trên đúng, còn lại `[]`.
- `OrderCommands` hiện cố định actor admin, key `admin:{id}`, audit và redact theo quyền admin: tách lõi "khóa + kiểm + chuyển"
  dùng chung, phần actor/idempotency/audit/view do từng lối vào (admin, khách) cung cấp (builder check).

## 4. OPS hủy sau xác nhận, trước bàn giao

`POST /admin/api/v1/orders/{order_no}/cancel` (ORD-03) mở rộng nhận:

| Đơn | Saga | Ghi chú |
|---|---|---|
| `CONFIRMED` | `WAIT_OPS` (như ORD-03), `COMMIT_STOCK` (RUNNING/WAITING/MANUAL) | commit có thể đang bay |
| `PACKING` | `CREATE_SHIPMENT` (RUNNING/MANUAL), `DONE` | vận đơn có thể đã có |

`SHIPPING` trở đi → `409 INVALID_TRANSITION` (đi hoàn hàng §5). Lease còn sống → `409 SAGA_BUSY`. Cùng transaction: saga
`intent = CANCEL`, `step = COMPENSATE`, `status = COMPENSATING`, `retry_count = 0`, xóa lease; đơn `{CONFIRMED|PACKING} →
CANCELLING` (`OPS_CANCELLED`, history ADMIN + lý do), `audit_logs`. OPS hủy được cả saga `MANUAL` ở các step trên: đây là
đường thoát cho đơn kẹt (builder check).

Bù trừ (`compensate`) theo thứ tự cố định, mỗi lượt một nghĩa vụ, intent ghi trước mỗi HTTP (§7):

1. **Vận đơn** — cần khi `inventory_state ∈ {COMMITTED, RESTORED}` hoặc `shipment_id` có hoặc `shipment_state ≠ NONE` (vận đơn
   chỉ được tạo sau commit, nên điều kiện này phủ cả create đang mất response). Shipping
   `POST /internal/api/v1/shipping/shipments/by-order/{order_id}/cancel`, key `shipment-cancel:{order_id}`, body = snapshot
   create (như `ShippingClient.create`) + `reason` = `cancel_reason` (mã cố định, không phải lời OPS nhập: hash idempotency
   của shipping gồm reason — builder check).
   - `200 CANCELLED` (kể cả tombstone khi chưa có shipment) → `shipment_state = CANCELLED`.
   - `409 SHIPMENT_HANDED_OVER` → hàng đã rời shop: **không trả kho**; `shipment_state = HANDED_OVER`, saga `WAITING` step
     `WAIT_RETURN` (không phải MANUAL: event shipping sẽ đưa tiếp, §5), metric + log cảnh báo cho OPS.
   - Từ chối khác (`QUOTE_*`, 404) → `MANUAL`; chưa rõ → retry cùng key, **không** sang bước 2 (T16).
2. **Kho** — luôn gọi release như ORD-02 (snapshot + hash gốc) trừ khi đã `RELEASED`/`RESTORED`:
   - `200` `restore_required = true` (reservation `COMMITTED`) → inventory `POST /internal/api/v1/inventory/returns`,
     `Idempotency-Key = operation_key = cancel:{order_id}`, mọi SKU đủ số đã commit, `reason` = `cancel_reason`,
     `evidence_ref = order-cancel:{order_id}` (cố định để replay khớp) → `inventory_state = RESTORED`. Thay nhánh
     `COMMITTED → MANUAL` của ORD-02; release 200 không còn ghi `RELEASED` khi `restore_required` (builder check).
   - `200` khác → `RELEASED` (như ORD-02). Intent ở bước này **không** đổi `inventory_state` sang `UNKNOWN` (builder check).
   - Cùng quy tắc phủ được commit đang bay (inventory phân xử release↔commit) nên không cần biết step trước (builder check).
3. **Payment** — close như ORD-02 (COD chưa thu → đóng; đã thu → `MANUAL PAID_NEEDS_REFUND`).
4. `CANCELLING → CANCELLED`, saga `DONE`.

## 5. Hoàn hàng sau bàn giao

- Shipping SELF chỉ đi `DELIVERING → FAILED → RETURNING → RETURNED` (`REDELIVER` quay lại `DELIVERING`) (builder check).
- `shipping.events` (consumer ORD-03), sau kiểm version/`shipment_id`:
  - `RETURNING` trên đơn `SHIPPING` hoặc `CANCELLING` (saga `WAIT_RETURN`) → đơn `RETURNING` (history SYSTEM); saga `intent =
    CANCEL`, `step = WAIT_RETURN`, `status = WAITING` (đơn `SHIPPING` chưa có saga CANCEL thì gán `cancel_reason = RETURNED`).
  - `DELIVERED` trên đơn `CANCELLING` (OPS hủy nhưng hàng vẫn tới tay khách): bằng chứng vật lý thắng → `CANCELLING →
    SHIPPING → DELIVERED` + `ORDER_COMPLETED`, saga `DONE`, log cảnh báo; cạnh mới `CANCELLING → SHIPPING` ghi vào 06/08
    (builder check — không để đơn kẹt). `DELIVERING` trên đơn `CANCELLING` chỉ ghi version.
  - `RETURNED` (hàng về shop) chỉ ghi version + log: **kho chưa cộng** cho tới khi OPS kiểm đếm.
  - `CANCELLED` trên đơn `CANCELLING`/`CANCELLED` là do chính order hủy: không cảnh báo.
- OPS kiểm đếm: `POST /admin/api/v1/orders/{order_no}/returns` (`order.return` — đã seed cho OPS ở user-service V003, chưa nối
  vào order-service — builder check), `Idempotency-Key`, body `{expected_version, reason, evidence_ref, items:
  [{order_item_id, received_quantity, restock_quantity, condition}]}`. Dưới khóa: item thuộc đơn; Σ received mọi phiếu ≤
  quantity; `restock ≤ received`; đơn `RETURNING`, `RETURNED` (hàng về muộn — builder check) hoặc `DELIVERED` (§6). Ghi
  `order_returns` (`REQUESTED`) + items cùng transaction, task nền `ORDER_RETURN` (business key `return_id`), `audit_logs`
  `ORDER_RETURN` → `202`.
- Task `ORDER_RETURN` (retry/MANUAL của `BackgroundTaskRepository`): gọi inventory `/returns`, `operation_key = return:{return_id}`,
  chỉ SKU có `restock_quantity > 0`, `reason`/`evidence_ref` của phiếu → phiếu `COMPLETED`. Toàn bộ restock 0 → `COMPLETED` không
  gọi. `RETURN_EXCEEDS_COMMITTED`/`RESERVATION_NOT_COMMITTED` → phiếu và task `MANUAL`. Replay cùng key không cộng lần hai (T17).
- Phiếu `COMPLETED` trên đơn `RETURNING`: cùng transaction đặt saga `step = CLOSE_PAYMENT`, `status = RUNNING` → saga close
  payment (key `close:{order_id}`, như ORD-02; đã thu → `MANUAL PAID_NEEDS_REFUND`) → `RETURNING → RETURNED`, saga `DONE`.
  Tiền và trạng thái đơn do saga giữ (một chủ, một ngân sách retry); task chỉ giữ việc cộng kho (builder check).
- `RETURNED`: thêm phiếu được (hàng về muộn), chỉ cộng kho, không đổi trạng thái.

## 6. Trả hàng sau giao (REQ ORD-10)

Cùng endpoint `returns` trên đơn `DELIVERED`: phiếu + task cộng kho như §5, đơn **giữ `DELIVERED`**, không đổi payment (tiền đã
đối soát; hoàn tiền là FINANCE/PAY-04), không phát lại `ORDER_COMPLETED`. Nhiều phiếu được; Σ received ≤ quantity. Không
enforce hạn/phí (O05/D10, §12.1).

## 7. Follow-up ORD-02/03

- Bù trừ ghi intent trước mỗi HTTP (tính lần thử + backoff, không đổi cột trạng thái tài nguyên), crash lặp đúng lúc
  shipment-cancel/release/return/close vẫn hết ngân sách → `MANUAL` (re-review ORD-02).
- `ShippingEvents`: saga đã có `shipment_id` khác của event → bỏ qua + log (ORD-03 M8). Chưa tạo vận đơn mới nên không đặt lại
  `shipment_version`.
- Metric `order.saga.manual` thêm tag `step`; `last_error` giữ mã.

## 8. Dữ liệu (migration V004, append-only)

- `order_returns` theo 05 (`id`, `order_id`, `operation_key` = Idempotency-Key, `reason`, `evidence_ref`, `actor_id`, `status`
  REQUESTED/PROCESSING/COMPLETED/MANUAL, `request_id`, `created_at`, `updated_at`; UNIQUE(`order_id`, `operation_key`)) và
  `order_return_items` (PK(`return_id`, `order_item_id`), `received_quantity > 0`, `restock_quantity >= 0`,
  `restock <= received`, `condition`). Runtime không DELETE; `order_return_items` không UPDATE.
- `order_sagas.shipment_state varchar(16) NOT NULL DEFAULT 'NONE'` CHECK (NONE/CREATED/CANCELLED/HANDED_OVER); ORD-03 lưu
  `shipment_id` thì đặt `CREATED`.
- Step mới: `WAIT_RETURN`, `CLOSE_PAYMENT` (cột text). `cancel_reason` thêm `CUSTOMER_CANCELLED`, `RETURNED`.

## 9. Contract và tài liệu

- `order.yaml`: path mới customer cancel (202/409/404) và admin returns (202/400/403/409); admin cancel mở rộng; `allowed_actions`;
  `reason_code` `CUSTOMER_CANCELLED`/`RETURNED`; `processing_status` `RETURNING` → `READY` (chờ hàng về, không poll), `RETURNED`
  → `FINISHED` (builder check: saga `DONE` khi `RETURNED`).
- Tài liệu: 03 (§2 customer cancel, §3 admin), 05 (V004), 06 §5.3 (cạnh `CANCELLING → SHIPPING`), §5.4 (đoạn "ORD-04 hiện
  thực"; key return thực tế `cancel:{order_id}` thay `cancel:{order_id}:{sku}` — builder check), 08 (bảng "ORD-04 — quyết định"),
  10, 12 (smoke), 13 (`order.return` đã dùng), 14 §3 (nút hủy, `allowed_actions`), README, CLAUDE.md, evidence.

## 10. Test (PostgreSQL Testcontainers; inventory/shipping/payment là stub, worker gọi tay)

- Khách hủy: member/guest đúng đơn 202 → CANCELLED `CUSTOMER_CANCELLED`, history CUSTOMER; đơn người khác 404; `PENDING` và sau
  OPS xác nhận 409; replay cùng key; `allowed_actions`.
- OPS hủy `PACKING` có vận đơn: shipment-cancel → release (`restore_required`) → returns đủ SKU (key `cancel:{id}`) → close →
  CANCELLED; thứ tự gọi đúng. OPS hủy saga `MANUAL` ở `CREATE_SHIPMENT` thoát được.
- **T16**: shipment-cancel timeout → không gọi release/returns; lượt sau OK → mới trả kho. `SHIPMENT_HANDED_OVER` → 0 returns,
  saga `WAIT_RETURN`; event `RETURNING` → đơn `RETURNING`; event `DELIVERED` thay vào đó → `DELIVERED` + `ORDER_COMPLETED`.
- Hủy lúc commit đang bay: release trả `ACTIVE`→released → `RELEASED`, 0 returns; trả `restore_required` → returns.
- **T17**: phiếu gửi lặp cùng key → một phiếu, inventory một lần hiệu lực; phiếu thứ hai vượt Σ quantity → 409, không ghi; inventory
  replay cùng `operation_key` không cộng thêm.
- Hoàn hàng: SHIPPING → FAILED → RETURNING (event) → phiếu (received 2, restock 1) → inventory restock 1 → saga close → RETURNED;
  phiếu muộn trên RETURNED chỉ cộng kho.
- Sau giao: phiếu trên DELIVERED → cộng kho, đơn vẫn DELIVERED, không `ORDER_COMPLETED` mới, payment không gọi.
- Tiền đã thu khi hủy/hoàn → MANUAL `PAID_NEEDS_REFUND`, không CANCELLED/RETURNED.
- Crash sau intent bù trừ lặp lại → hết ngân sách → MANUAL. Event `shipment_id` lạ bị bỏ. Event CANCELLED do chính order hủy không cảnh báo.
- Mutation: trả kho trước khi shipment-cancel xác nhận; bỏ guard Σ received; key returns ngẫu nhiên; `RELEASED` khi `restore_required`.
- Smoke: đơn A → OPS xác nhận → PACKING → OPS hủy → CANCELLED, tồn kho về như trước (`ORD-04 cancel after confirm PASS`); đơn B
  → giao → DELIVERY_FAILED → RETURN_STARTED → RETURNED → OPS kiểm đếm → RETURNED, tồn kho cộng đúng (`ORD-04 return PASS`).

## 10b. Rủi ro

- `MANUAL` (`PAID_NEEDS_REFUND`, từ chối shipping) vẫn chưa có UI (ADM-02) — chỉ metric/log + API detail.
- Phiếu kiểm đếm do OPS nhập tay: sai số lượng là sai kho thật; chỉ chặn vượt quantity, không chặn nhập thiếu.
- Đơn đã hủy mà hàng vẫn giao tới khách → `DELIVERED` theo bằng chứng; payment COD vẫn thu bình thường (close trước đó không chạy
  vì bước vận đơn dừng ở `HANDED_OVER`).

## 11. Lựa chọn mặc định của agent (chủ dự án duyệt cùng spec 2026-10-21)

1. OPS hủy được tới trước bàn giao (`CONFIRMED`/`PACKING`, kể cả saga `MANUAL`); từ `SHIPPING` chỉ còn đường hoàn hàng.
2. Thứ tự bù trừ cố định: vận đơn → kho → payment; chưa chắc vận đơn đã hủy thì không trả kho (T16).
3. Hủy mà shipping báo đã bàn giao → saga chờ (`WAIT_RETURN`), không MANUAL; event hoàn đưa `RETURNING`, event giao thành công
   đưa `DELIVERED` (cạnh mới `CANCELLING → SHIPPING`).
4. Trả kho khi hủy dùng release rồi `/returns` khi `restore_required`, không cần biết step trước.
5. Một phiếu kiểm đếm hoàn tất đưa `RETURNING → RETURNED`; phiếu sau (hàng về muộn) chỉ cộng kho.
6. Hàng hỏng: `restock_quantity < received_quantity`, phần hỏng không vào kho (không có kho "hàng hỏng").
7. Task nền chỉ cộng kho; đóng payment và chuyển `RETURNED` do saga (`CLOSE_PAYMENT`).
8. Key cố định: `shipment-cancel:{order_id}`, `cancel:{order_id}` (trả kho khi hủy), `return:{return_id}` (kiểm đếm), `close:{order_id}`.
9. `reason` gửi shipping/inventory là mã `cancel_reason` cố định (replay khớp hash), lời người nhập chỉ ở history/audit.
10. `processing_status`: `RETURNING` → `READY`, `RETURNED` → `FINISHED`.
11. Không thêm event mới cho hoàn hàng; thêm khi NOT-01 cần.

## 12. Chủ dự án đã quyết (2026-10-21)

1. Trả hàng sau giao (ORD-10) **làm luôn** phần cơ chế (phiếu + kiểm đếm + cộng kho); không enforce policy O05/D10.
2. Khách **không** được tự hủy sau khi shop đã xác nhận (hẹp hơn 02 ORD-04 "khách hủy WAITING_PAYMENT hoặc CONFIRMED": chỉ
   `CONFIRMED` chờ OPS; `WAITING_PAYMENT` online chưa có).
3. Đơn đã thu COD rồi phải hủy/hoàn: **`MANUAL PAID_NEEDS_REFUND` chờ PAY-04**, không ghi hoàn tay trong ORD-04.
