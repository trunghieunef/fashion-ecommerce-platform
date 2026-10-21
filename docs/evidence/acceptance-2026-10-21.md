# Nghiệm thu task local — 2026-10-21

## TASK:ORD-02 — saga checkout COD, bù trừ, hết hạn, dọn giỏ

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:ORD-02` sau khi re-review trên PR #33 đồng ý merge. **Không độc lập:** ORD-02
do Claude Code viết. Review gồm một subagent reviewer toàn nhánh (3 Important + 7 Minor, sửa ở `6f684b4`), một review trên
PR #33 (3 Important + 8 Minor, sửa ở `3b8ee5b`) và re-review trên PR xác nhận đã sửa, "đồng ý merge". Merge do chủ dự án.
Phạm vi: acceptance ở [10](../delivery/10_backlog.md) dòng ORD-02 và REQ ORD-02/09 ở 02 theo
[spec đã duyệt](../superpowers/specs/2026-10-20-ord-02-checkout-saga-design.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `3b8ee5b`. Application CI (pull_request) SUCCESS
  ([run 39460383570](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39460383570)): backend toàn
  reactor **887 test** (order-service 60), frontend typecheck/unit 21/build, contract **83 test**, local stack + smoke
  (`ORD-02 saga confirm PASS`, `ORD-01`, `CART-01`, `SHP-01/02`, `PAY-01`, `S1-local smoke PASS`), browser smoke Playwright 8
  test. Documentation CI SUCCESS ([run 39460383627](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39460383627)).
- Local: `./mvnw -B -pl services/order-service verify` 60 test PASS; mutation 4 guard (CAS lease, key reserve cố định,
  release trước `CANCELLED`, không dọn giỏ khi hết hàng) và guard sau review (`PAID` khi bù trừ, SKU lạ) đều bị test bắt.
  Chi tiết: [evidence ORD-02](ord-02-local-2026-10-20.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 ORD-02 / 02 REQ / spec) | Bằng chứng | Kết quả |
|---|---|---|
| Durable saga intent/step/resource state; RESERVE → CREATE_PAYMENT → CONFIRMED COD (REQ ORD-02) | `aCodOrderIsReservedPaidForAndConfirmed` (key/hash đúng 03 §4, history 2 dòng, outbox `ORDER_CONFIRMED`), smoke `ORD-02 saga confirm PASS` qua Gateway với inventory/payment thật | Pass |
| Lease token, worker cũ không ghi đè | `aStaleWorkerCannotOverwriteTheNewOne`, `aWorkerThatLostItsLeaseDuringACallDropsItsResult`; mutation bỏ CAS bị bắt | Pass |
| Chỉ chuyển cạnh 06, history + outbox cùng TX (REQ ORD-02, T23) | `OrderTransitions.move` có điều kiện status cũ; `aFailedTransitionRollsBackTheSagaAndTheOutbox` | Pass |
| Không HTTP trong DB TX | Intent/kết quả là transaction riêng, HTTP giữa hai transaction (`SagaWorker`); xác nhận ở cả ba lượt review | Pass |
| Retry cùng key/hash, timeout = UNKNOWN (T03) | `aLostReserveResponseIsRetriedWithTheSameKey`, `aReserveStillInFlightCannotHoldStockForACancelledOrder`; mutation key ngẫu nhiên bị bắt | Pass |
| WAITING deadlines, hết hạn release + close trước `CANCELLED` | `anExpiredConfirmedOrderIsCancelledOnlyOnceEverythingIsReleased`, `anOrderExpiringWhileThePaymentIsUnknownStillClosesIt`, `aPaymentAnsweredAfterTheDeadlineNeverConfirms` | Pass |
| Lỗi chắc chắn → bù trừ; lỗi contract không hủy đơn; hết ngân sách → MANUAL giữ nghĩa vụ | `outOfStockCancelsWithoutTouchingTheCart`, `aRefusedPaymentReleasesTheReservation`, `contractConflictsAndMissingRoutesNeverCancelTheOrder`, `aContractErrorGoesManual…`, `retriesBackOffThenGoManual`, `aCrashAfterTheIntentStillSpendsTheBudget`, `aStepThatThrowsCountsAsARetryAndEndsManual`, `aReleaseThatFindsCommittedStockGoesManual`, `committedStockIsNeverTreatedAsReleased` | Pass |
| Payment success khi đơn còn PENDING ghi durable, saga tiếp tục (T07) | `aCompletedPaymentBeforeTheSagaIsKeptAndTheSagaStillConfirms`, `aCompletedPaymentIsRecordedFromKafka` (inbox, trùng một lần), `aPaymentCompletedDuringCompensationGoesManualForARefund` | Pass |
| Dọn giỏ durable, độc lập saga; lỗi cleanup không hủy đơn (REQ ORD-09) | `aConfirmedOrderCleansItsCartOnce`, `aFailingCartIsRetriedAndTheOrderStaysConfirmed`, `aCancelledOrderNeverEmptiesTheCart` | Pass |
| GET đơn phản ánh `processing_status`/`reason_code`; đặt lại sau hủy; guest tìm lại đơn | `processingStatusFollowsTheSaga`, `outOfStockCancels…` (đặt lại cùng giỏ, actor khác 409), `aGuestWhoseCartWasCleanedFindsItsOrderAgain`; contract `order.yaml` 83 test | Pass |
| Worker chết sau inventory commit (T09) | Không có commit kho trong ORD-02; `COMMITTED` khi bù trừ → MANUAL | **Ngoại lệ (ORD-03)** |

**`TASK:ORD-02`: Done (local, trước G0)**; CI trên `3b8ee5b` PASS, re-review PR #33 đồng ý merge; chủ dự án đã merge vào
`main` (`a11ec41`, 2026-10-21).

### Ngoại lệ và việc còn lại (không chặn Done local)

- ORD-03: OPS xác nhận COD, commit kho, packing/shipment, race commit↔expire, T09. ORD-04: khách/OPS hủy, return/restore, refund
  khi `PAID_NEEDS_REFUND` (PAY-04). Online `WAIT_PAYMENT` chờ PAY-02/03.
- `MANUAL` chưa có UI/alert (ADM-02/PLT-05); FE giới hạn poll (14 §3) khi làm WEB-03.
- Minor để sau ở evidence: lease không gia hạn trong batch, cleanup MANUAL không metric, event payment không đối chiếu amount;
  bước bù trừ chưa ghi intent nên crash lặp đúng lúc release/close chưa vào MANUAL (ghi chú re-review, làm cùng ORD-03/04);
  grant bảng mới cho `order_runtime` ở `infra/environments` kiểm khi làm PLT-04.

## TASK:ORD-03 — OPS xác nhận/hủy COD, commit kho, vận đơn SELF, giao hàng

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:ORD-03` sau khi quyết định merge PR #34. **Không độc lập:** ORD-03 do Claude
Code viết (6 task, mỗi task một subagent reviewer; review toàn nhánh 0 Critical / 1 Important sửa ở `61741ec`). Review trên
PR #34 (`a11ec41..7c66b34`): 0 Critical, 0 Important, 8 Minor, kết luận "có thể merge"; cả 8 minor sửa ở `ad54847` và đã
trả lời trên PR. Merge do chủ dự án. Phạm vi: acceptance ở [10](../delivery/10_backlog.md) dòng ORD-03, REQ ORD-03/06 và
PAY-03 (COD) ở 02 theo [spec đã duyệt](../superpowers/specs/2026-10-21-ord-03-cod-confirm-fulfil-design.md), mức local trước
G0.

### Bằng chứng chung

- Code trên `dev` `ad54847`. CI PR #34 trên `7c66b34` SUCCESS: `s1-local`
  ([run 39505115599](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39505115599/job/118911981664),
  gồm backend reactor, contract, local stack + smoke) và `docs-check`. CI trên `ad54847` (sửa review PR): `docs-check`
  SUCCESS; `s1-local` của `ad54847` bị hủy vì có push mới, chạy lại trên `e542f1a` (cùng code + acceptance) **SUCCESS** ([run 39509342216](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39509342216/job/118926690478)).
- Local: `./mvnw -B -pl services/order-service verify` **95 test** PASS sau sửa review PR; trước đó order-service +
  gateway 138 test; contract 86 test (docker Python 3.13); `local-up` + `smoke-local` → `ORD-03 COD end-to-end PASS`.
  Mutation 4 guard đều bị test bắt (một mutant sống lần đầu, đã thêm assert). Chi tiết: [evidence ORD-03](ord-03-local-2026-10-21.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 ORD-03 / 02 REQ / spec) | Bằng chứng | Kết quả |
|---|---|---|
| OPS xác nhận COD từng đơn, chỉ khi `CONFIRMED` chờ OPS, chưa hết hạn; không set status tùy ý (REQ ORD-06) | `anOpsConfirmHandsTheOrderToTheSaga`, `confirmRefusals` (version, transition, `ORDER_EXPIRED`, `SAGA_BUSY`, quyền), idempotency replay/reuse; contract `order.yaml` | Pass |
| OPS hủy đơn `CONFIRMED` có lý do → release + close → `CANCELLED OPS_CANCELLED` (phạm vi kéo từ ORD-04) | `anOpsCancelReleasesAndCloses`, cancel sau xác nhận → 409; `confirmAndCancelRaceHasOneWinner` | Pass |
| Commit kho trước `PACKING`; race commit↔hết hạn một kết quả (REQ ORD-03, T12) | `aConfirmedOrderIsCommittedAndPacked`, T12a `inventorySaysExpiredSoTheOrderIsCancelled`, T12b `aCommitAfterTheOrderDeadlineStillPacks`, T12c `aWorkerCancellingAtTheDeadlineBeatsALateConfirm`, T12d race; mutation "PACKING trước commit" bị 9/10 test bắt | Pass |
| Worker chết sau inventory commit (T09) | `aLostCommitResponseAfterTheDeadlineStillPacks` (cùng key, `HELD` giữ nguyên, 0 release); mutation key ngẫu nhiên bị bắt | Pass |
| Tạo vận đơn SELF một lần, body snapshot; từ chối không tự hủy | `aPackedOrderGetsItsSelfShipment`, `aLostShipmentResponseIsRetriedWithTheSameBody`, `aRefusedShipmentGoesManualAndKeepsTheStock` | Pass |
| `SHIPPING` chỉ trên bằng chứng `handed_over_at`; delivered event → `DELIVERED` + `ORDER_COMPLETED` một lần (REQ ORD-06) | `ShippingEventsIntegrationTest` (happy, `outOfOrderShippingEventsOnlyMoveForward`, trùng/cũ, CANCELLED); Kafka listener test | Pass |
| End-to-end qua SELF + FINANCE settlement; COD `SUCCESS` chỉ khi FINANCE xác nhận, `DELIVERED` không suy ra đã thu (REQ PAY-03, T27) | Smoke `ORD-03 COD end-to-end PASS` local và trong CI `s1-local` trên `7c66b34`: DELIVERED → `COD_COLLECTED` → settlement → `SUCCESS` → `payment_state = PAID` | Pass |
| Quyền và PII: kiểm tại service; FINANCE không thấy liên hệ/địa chỉ (13) | `financeSeesNoCustomerContact`, 401/403, gateway `orderAdminIsRouted`; smoke FINANCE detail không PII, FINANCE confirm 403 | Pass |
| Không HTTP trong DB TX; thứ tự khóa `order_sagas` → `orders` toàn service | `aWorkerClaimTakenBeforeTheConfirmLosesItsWrite`, `paymentEventsLockTheSagaBeforeTheOrder`; review toàn nhánh | Pass |

**`TASK:ORD-03`: Done (local, trước G0)**; CI trên `7c66b34` PASS, review PR #34 "có thể merge", 8 minor đã sửa ở
`ad54847`; CI trên `e542f1a` PASS; chủ dự án đã merge vào `main` (`13f7eec`, 2026-10-21).

### Ngoại lệ và việc còn lại (không chặn Done local)

- ORD-04: hủy sau xác nhận/khách hủy, giao thất bại/hoàn hàng, shipment `UNKNOWN`, restore kho; đối chiếu `shipment_id` và
  đặt lại `shipment_version` khi tạo vận đơn mới; alert/metric `MANUAL` cho `COMMIT_STOCK`/`CREATE_SHIPMENT`.
- `pack` đơn online chờ TASK PAY-02/03; màn hình admin và hint "không nhập liên hệ khách vào ghi chú" ở ADM-01/02.
- Minor để sau ở evidence: N+1 saga khi đọc danh sách, chưa index `contact_phone`, smoke chưa đọc trực tiếp `paid_at`.
- Sửa ghi nhầm khi nghiệm thu: `REQ:PAY-03` là COD settlement (đã kiểm qua smoke), không phải phần online.
