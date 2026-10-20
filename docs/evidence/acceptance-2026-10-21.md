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

**`TASK:ORD-02`: Done (local, trước G0)**; CI trên `3b8ee5b` PASS, re-review PR #33 đồng ý merge, chờ chủ dự án merge vào
`main`.

### Ngoại lệ và việc còn lại (không chặn Done local)

- ORD-03: OPS xác nhận COD, commit kho, packing/shipment, race commit↔expire, T09. ORD-04: khách/OPS hủy, return/restore, refund
  khi `PAID_NEEDS_REFUND` (PAY-04). Online `WAIT_PAYMENT` chờ PAY-02/03.
- `MANUAL` chưa có UI/alert (ADM-02/PLT-05); FE giới hạn poll (14 §3) khi làm WEB-03.
- Minor để sau ở evidence: lease không gia hạn trong batch, cleanup MANUAL không metric, event payment không đối chiếu amount;
  bước bù trừ chưa ghi intent nên crash lặp đúng lúc release/close chưa vào MANUAL (ghi chú re-review, làm cùng ORD-03/04);
  grant bảng mới cho `order_runtime` ở `infra/environments` kiểm khi làm PLT-04.
