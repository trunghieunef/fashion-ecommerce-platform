# Nghiệm thu task local — 2026-10-16

Chủ dự án yêu cầu Claude Code (agent) nghiệm thu `TASK:INV-01` ngày 2026-10-16, thay reviewer
người/TL, theo tiền lệ [nghiệm thu 2026-10-11](acceptance-2026-10-11.md).
**Không độc lập:** INV-01 cũng do Claude Code viết; PR #20 đã có review riêng nhưng đợt nghiệm thu
này không thay review bởi người khác tác giả.

Phạm vi: acceptance tối thiểu ở [10](../delivery/10_backlog.md) + REQ INV-01/08 ở
[02](../design/02_prd.md) + DoD ở [09 §6](../delivery/09_delivery_plan.md), mức local trước G0.
Không nghiệm thu G0–G2, staging/AWS hay production.

## Bằng chứng chung

- Code merge vào `main` qua PR #20 (`f7eb5ac`, 2026-10-16) và PR #21 (`a76442e`). Tree của `main`
  `a76442e` trùng `dev` `09e092c`.
- Application CI + Documentation CI (pull_request) trên `09e092c` SUCCESS (run 38856087842,
  38856087834). Documentation CI push trên `main` `a76442e` SUCCESS (38856241693). Application CI
  push trên `main` `a76442e` bị **cancelled** (38856241661), nên `main` không có run Application CI
  riêng; bằng chứng CI là run PR trên cùng tree.
- Chạy lại ngày 2026-10-16 trên `09e092c`, Windows + Git Bash, Docker Desktop:
  `./mvnw -B verify` BUILD SUCCESS **540 test**, 0 failure/error/skip (durability 36, security 38,
  messaging 14, user 65, catalog 300, inventory 52, Gateway 35); integration dùng PostgreSQL/Kafka
  thật qua Testcontainers.
- `bash scripts/local-up.sh` exit 0, stack healthy, không reset volume; `bash scripts/smoke-local.sh`
  exit 0 `S1-local smoke PASS`, gồm bước inventory: SKU mới đăng ký qua catalog relay → Kafka →
  inventory inbox trong deadline, rồi adjust/đọc qua Gateway admin.
- Bằng chứng tác giả (mutation 6/6, contract 48, F1–F18) ở [evidence INV-01](inv-01-local-2026-10-15.md).

## Kết quả theo tiêu chí

| Tiêu chí | Bằng chứng | Kết quả |
|---|---|---|
| Stock schema; 0 ≤ reserved ≤ on_hand; available = on_hand − reserved (REQ INV-01) | `StockSchemaIntegrationTest`: `reservedCannotExceedOnHand`, `onHandCannotBeNegative`, `ledgerTypeIsConstrained`, `operationKeyUniquePerSku`; internal read `returnsAvailableForAllowedCaller` | Pass |
| Mọi thay đổi có ledger, append-only | `positiveAdjustWritesLedgerAndRestocked`, `importAddsInboundForEachRow`, `ledgerIsAppendOnlyForRuntime` (quyền DB runtime) | Pass |
| Register SKU từ `VARIANT_CREATED` | `registersSkuWithZeroStockAndEmitsSnapshot`, `lowercaseSkuIsCanonicalized`, smoke Kafka thật | Pass |
| Duplicate SKU event không reset tồn | `newEventSameVariantDoesNotResetStock`, `duplicateEventIdIsIgnored`, `sameVariantDifferentSkuGoesToDlt` | Pass |
| CSV lỗi không ghi một phần; ≤ 1.000 dòng, validate trước ghi (REQ INV-08) | `invalidCsvRollsBackEveryRow`, `overflowInLaterRowRollsBackEverything`, `rejects1001Rows`, `reportsEveryBadLine`, `rejectsDuplicateSku` | Pass |
| Adjustment dưới reserved bị từ chối | `adjustmentCannotMoveOnHandBelowReserved` | Pass |
| Lý do/key/audit; replay không cộng lại (REQ INV-08) | `zeroDeltaAndBlankReasonAreRejected`, `requiresAdjustPermissionAndKey`, `replaySameKeyReturnsSameResponse`, `sameKeyDifferentBodyIsConflict`, `replayDoesNotAddTwice`; audit là ledger append-only có `actor_id`/`reason` theo spec đã duyệt | Pass |
| Đồng thời và quyền | `concurrentAdjustsDoNotLoseUpdates`, `readPermissionCannotAdjust`, `missingTokenIs401AndOtherCallerIs403BeforeValidation` | Pass |

**`TASK:INV-01`: Done (local, trước G0).**

## Ngoại lệ và việc còn lại (không chặn Done local)

- Follow-up từ review PR20 (handoff INV-01): cảnh báo consumer lag và rà lỗi permanent→DLT, tái sử dụng
  AdminClient cho Kafka health, import set-based, relay theo batch, gom AdminCommands.
- Internal availability chỉ có integration test; caller thật đầu tiên là CART-01.
- Kafka local single-node không auth; SASL/TLS/HA, DLT replay tool và staging thuộc PLT-04/05.
- Chưa có UI admin (ADM-01); INV-02 reserve/commit/release, INV-03 return ngoài phạm vi.
