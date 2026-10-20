# Nghiệm thu task local — 2026-10-20

## TASK:SHP-02 — shipping SELF events, COD, cancel

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:SHP-02` sau khi merge PR #30. **Không độc lập:** SHP-02 do Claude Code viết.
Review gồm một subagent reviewer toàn nhánh (Critical/Important đã sửa ở `96c00f9`) và một agent review riêng trên PR #30
(1 Important + 4 Minor, sửa ở `eb0b7c1`); re-review trên PR kết luận **đủ điều kiện merge** và nêu một Minor không chặn,
đã sửa sau merge (dưới). Merge do chủ dự án. Phạm vi: acceptance ở [10](../delivery/10_backlog.md) dòng SHP-02 theo
[spec đã duyệt](../superpowers/specs/2026-10-20-shp-02-self-events-cancel-design.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `eb0b7c1`, PR #30 đã merge `main` `03b3aa6`. Application CI (pull_request) SUCCESS
  ([run 39297751605](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39297751605)): backend toàn
  reactor **795 test** (shipping-service 45), frontend typecheck/unit 21/build, contract **74 test**, local stack + smoke
  (`SHP-02 self-event route PASS`, `SHP-01 shipping estimate PASS`, `S1-local smoke PASS`), browser smoke Playwright 8 test.
  Documentation CI SUCCESS ([run 39297751607](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39297751607)).
- Local: shipping 45 test; mutation (nhánh CANCELLED giữ bằng chứng, thứ tự `occurred_at`) bị test bắt. Chi tiết:
  [evidence SHP-02](shp-02-local-2026-10-20.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 SHP-02 / spec) | Bằng chứng | Kết quả |
|---|---|---|
| SELF evidence events (gán shipper, bàn giao, giao, thất bại, giao lại, hoàn) có actor/evidence, version, idempotency | `SelfEventIntegrationTest` (bảng cạnh, parse strict, VERSION_CONFLICT, INVALID_TRANSITION, replay), quyền `shipping.self_event`; smoke route qua Gateway | Pass |
| Không SHIPPING ngay khi CREATED: `handed_over_at` chỉ đặt khi bàn giao | Cạnh HANDED_OVER chỉ từ PICKING; CHECK `shipments_handed_over_check` (V004) | Pass |
| COD collected/remitted payload (event tài chính riêng) | `fullSelfPathWithCodAndEvents`: số tiền phải khớp, một lần; payload khớp tập field schema; fixture + contract test | Pass |
| Cancel tombstone (T15–T17): hủy trước bàn giao, hủy-trước-tạo, 409 sau bàn giao | `ShipmentCancelIntegrationTest` (6, gồm song song cancel/create và cancel/bàn giao) | Pass |
| Bằng chứng vật lý sau hủy không bị mất (06 §17.2) | History `applied=false`, 409 `CANCELLED_EVIDENCE_RECORDED`; mốc giờ cũ hơn vẫn được ghi (sửa sau merge) | Pass |
| Chuyển MANUAL + incident khi có bằng chứng trên CANCELLED | Chưa làm: câu hỏi mở cho chủ dự án ở [08](../design/08_decisions.md) | **Ngoại lệ (chờ quyết định)** |

**`TASK:SHP-02`: Done (local, trước G0) — ngoại lệ MANUAL + incident chờ quyết định ở 08**; chủ dự án đã merge PR #30 vào
`main` (`03b3aa6`, 2026-10-20), CI trên `eb0b7c1` PASS trước merge.

### Sửa sau merge

Re-review: trên CANCELLED, `occurred_at` bị kiểm thứ tự nên bằng chứng có mốc sớm hơn dòng đã ghi bị 400. Đã bỏ kiểm thứ tự
trên CANCELLED (giữ 60 giây và mốc tạo), thêm assert vào `evidenceOnACancelledShipmentIsKeptNotDropped`; shipping 45 test PASS
local. Sửa này đi cùng PR kế tiếp từ `dev`.

### Ngoại lệ và việc còn lại (không chặn Done local)

- Quyết định MANUAL + incident và báo order khi có bằng chứng trên CANCELLED: chốt trước G1/G2 (liên quan kho và tiền COD).
- Consumer của `SHIPMENT_STATUS_UPDATED`/`COD_*` (ORD-03/04, PAY-01) và route internal cancel từ order-service chưa có;
  chỉ có integration test và outbox row.
- Upload ảnh bằng chứng, nộp COD nhiều đợt, `NOTIFY_SHIPMENT_STATUS` (NOT-01), carrier (SHP-03) chưa làm.
- O04 (phí mẫu) vẫn mở từ SHP-01.

## TASK:PAY-01 — payment-service COD

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:PAY-01` trước khi merge PR #31. **Không độc lập:** PAY-01 do Claude Code viết.
Review gồm một subagent reviewer toàn nhánh (1 Important + Minor, sửa ở `2d8ede8`) và một agent review riêng trên PR #31
(2 Important + 6 Minor, sửa ở `50fef8c` và `b5be015`); re-review trên PR kết luận **đồng ý merge**, không thấy lỗi mới.
Merge do chủ dự án. Phạm vi: acceptance ở [10](../delivery/10_backlog.md) dòng PAY-01 theo
[spec đã duyệt](../superpowers/specs/2026-10-20-pay-01-payment-cod-design.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `b5be015`. Application CI (pull_request) SUCCESS
  ([run 39375350512](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39375350512)): backend toàn
  reactor **824 test** (payment-service 28), frontend typecheck/unit 21/build, contract **79 test**, local stack + smoke
  (`PAY-01 payment admin route PASS`, `SHP-02 self-event route PASS`, `S1-local smoke PASS`), browser smoke Playwright 8 test.
  Documentation CI SUCCESS ([run 39375350570](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39375350570)).
- Local: payment-service 28 test; mutation (lock theo order, hash snapshot, kiểm số tiền COD, Σgross ≤ amount) bị test bắt.
  Chi tiết: [evidence PAY-01](pay-01-local-2026-10-20.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 PAY-01 / spec) | Bằng chứng | Kết quả |
|---|---|---|
| Payment/COD schema, một payment mỗi đơn, `provider_request_id = order_no` (PAY-07) | `PaymentSchemaIntegrationTest` (CHECK, REVOKE), `PaymentCreateIntegrationTest` (song song một payment, replay, 409 conflict/duplicate) | Pass |
| Close + `closed_at`, close-before-create ngăn tạo mới (PAY-08) | `PaymentCloseIntegrationTest` (tombstone, song song close/create, retry trả hiện trạng) | Pass |
| Late success vẫn ghi tiền | `lateCollectionOnAClosedPaymentIsRecordedAndKeepsClosedAt`, `settlementAfterALateCollectionOnAClosedPaymentStillCompletes` | Pass |
| T27: carrier đã nộp chưa phải SUCCESS | `COD_REMITTED` chỉ là bằng chứng; SUCCESS chỉ khi FINANCE settle đủ | Pass |
| Settlement gross = fee + net nhiều đợt (PAY-03) | `SettlementIntegrationTest` (CHECK DB, ref một lần, Σgross ≤ amount, VERSION_CONFLICT, `PAYMENT_COMPLETED` một lần) | Pass |
| Bằng chứng COD lệch/trùng không tự ép khớp | Reconciliation item `COD_AMOUNT_MISMATCH`/`COD_METHOD_MISMATCH`/`COD_DUPLICATE_COLLECTION`; payload sai → DLT | Pass |
| Query + quyền FINANCE, internal không public | `PaymentAdminIntegrationTest`, `GatewayBoundaryTest.paymentAdminIsRoutedAndInternalIsNot`, smoke route | Pass |
| Gọi thật từ order-service | Chưa có order-service: internal chỉ có integration test, `PAYMENT_INTERNAL_CALLERS` trống | **Ngoại lệ (ORD-01/02)** |

**`TASK:PAY-01`: Done (local, trước G0) — internal route chờ order-service (ORD-01/02)**; CI trên `b5be015` PASS; chủ dự án
đã merge PR #31 vào `main` (`7412f01`, 2026-10-20).

### Ngoại lệ và việc còn lại (không chặn Done local)

- VNPay/MoMo (PAY-02/03), refund (PAY-04), resolve đối soát/batch và alert COD outstanding (PAY-05), consumer `payment.events`
  (ORD-02/03), `NOTIFY_PAYMENT_SUCCESS` (NOT-01), alert trên `messaging_dlt_total` (PLT-05).
- Minor M4 (review nội bộ): create replay trước khi close trả trạng thái đã lưu; order nên đọc `GET by-order` khi cần hiện trạng.
- PR #31 còn mang: sửa SHP-02 sau merge, O06 (chủ dự án xác nhận) và quyết định guest checkout cho ORD-01 (`fd5e5fc`, 08/03).

## TASK:ORD-01 — order-service quote/create/read, guest checkout token

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:ORD-01` sau khi CI PR #32 xanh. **Không độc lập:** ORD-01 do Claude Code viết.
Review gồm một subagent reviewer toàn nhánh (2 Important + 9 Minor, sửa ở `002c7c3`) và một agent review riêng trên PR #32
(2 Important + 6 Minor, sửa hết ở `8a4f7f5`). Sau bản sửa chưa có re-review xác nhận trên PR. Merge do chủ dự án. Phạm vi:
acceptance ở [10](../delivery/10_backlog.md) dòng ORD-01 theo
[spec đã duyệt](../superpowers/specs/2026-10-20-ord-01-order-quote-create-design.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `8a4f7f5`. Application CI (pull_request) SUCCESS
  ([run 39394985552](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39394985552)): backend toàn
  reactor **853 test** (order-service 26, cart-service 94), frontend typecheck/unit 21/build, contract **83 test**, local stack
  + smoke (`ORD-01 guest quote/create/read PASS`, `CART-01`, `SHP-01`, `PAY-01`, `S1-local smoke PASS`), browser smoke
  Playwright 8 test. Documentation CI SUCCESS
  ([run 39394985577](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39394985577)).
- Local: mutation 5 guard (lookup key trước kiểm quote, so giá, UNIQUE giỏ/version, ownership đọc, checkout token gắn giỏ)
  đều bị test bắt. Chi tiết: [evidence ORD-01](ord-01-local-2026-10-20.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 ORD-01 / 02 REQ / spec) | Bằng chứng | Kết quả |
|---|---|---|
| Quote từ server, hạn 2 phút, gắn actor/cart/version/address/method (REQ ORD-01) | `QuoteIntegrationTest` (giá catalog, phí SELF + COD, hạn 120 s, token gắn actor), `QuoteTokensTest` (sửa 1 byte/khóa khác → INVALID_QUOTE) | Pass |
| Checkout lưu snapshot bất biến | `aMemberOrderIsDurablePendingWithItsSnapshot` (items/giá/phí/địa chỉ/shipping quote, history, saga, outbox một TX); CHECK tổng tiền | Pass |
| Idempotency lookup trước quote expiry; 202 + status URL; retry cùng key cùng đơn, khác body 409 (REQ ORD-07) | `aRetryWithTheSameKey...` (sau 5 phút, giá đổi, không gọi catalog), `parallelSubmitsMakeOneOrder`, smoke 202 qua Gateway | Pass |
| PRICE_CHANGED contract (T24) | `changedPricesOrCartsAreRefusedAndNothingIsWritten` (409 old/new, 0 row) | Pass |
| Ownership: member chỉ đơn mình, guest credential đúng đơn, cursor (REQ ORD-05, T21) | `OrderReadIntegrationTest` (404 đồng nhất, cookie đúng đơn, hạn 90 ngày ở server, `mine` cursor), `aGuestQuotesWithItsCheckoutToken`, smoke đọc bằng cookie / không cookie 404 | Pass |
| Guest checkout xác minh bằng checkout token (08 CART-03) | `CheckoutTokenIntegrationTest`, `badCredentialsAreRefused`, race/reload guest (`parallelGuestSubmits...`, `aGuestWhoLostItsKey...`) | Pass |
| Đơn tiến tới CONFIRMED/WAITING_PAYMENT (201) | Chưa có saga: đơn dừng PENDING/PROCESSING | **Ngoại lệ (ORD-02)** |

**`TASK:ORD-01`: Done (local, trước G0) — đơn dừng ở PENDING/PROCESSING tới ORD-02**; CI trên `8a4f7f5` PASS, chờ re-review
(nếu chủ dự án muốn) và chủ dự án merge PR #32 vào `main`.

### Ngoại lệ và việc còn lại (không chặn Done local)

- ORD-02: reserve/payment/cleanup giỏ; bump version giỏ khi cleanup/compensate (UNIQUE giỏ/version); guest mất response + token
  hết hạn + giỏ đã dọn không lấy lại được đơn (ràng buộc thiết kế).
- Chưa có rate limit checkout, job ẩn danh PII đơn và dọn credential guest hết hạn (O06, role migration/owner), admin order
  (ORD-03/ADM-02), UI checkout (WEB-03).
- Minor để sau ở evidence (secret quote thiếu không chặn khởi động, xoay khóa quote, kiểm tràn phí shipping, log lỗi CHECK có
  thể chứa PII, vài test timeout).
