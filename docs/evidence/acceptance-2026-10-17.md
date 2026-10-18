# Nghiệm thu task local — 2026-10-17

Chủ dự án yêu cầu Claude Code (agent) nghiệm thu `TASK:INV-02` ngày 2026-10-17, thay reviewer
người/TL, theo tiền lệ [nghiệm thu 2026-10-16](acceptance-2026-10-16.md).
**Không độc lập:** INV-02 do agent (Codex) viết, review PR #22 và sửa sau review do Claude Code
thực hiện; đợt nghiệm thu này không thay review bởi người khác tác giả.

Phạm vi: acceptance tối thiểu ở [10](../delivery/10_backlog.md) + REQ INV-02/03/06 ở
[02](../design/02_prd.md) + DoD ở [09 §6](../delivery/09_delivery_plan.md), mức local trước G0.
Không nghiệm thu G0–G2, staging/AWS hay production.

## Bằng chứng chung

- Code trên `dev` `e73ec5d`; merge vào `main` qua PR #22 (`abd5cfb`, 2026-10-17), tree trùng `dev` `635aba9`. Review PR #22 đăng 7 điểm, cả 7 đã
  sửa trong `e73ec5d`; chi tiết ở [evidence INV-02](inv-02-local-2026-10-16.md).
- Application CI (pull_request) trên `e73ec5d` SUCCESS (run 39025098727: `./mvnw -B test`,
  `validate-contracts.sh` gồm unittest contract Python, smoke local/auth/browser); Documentation
  CI SUCCESS (39025098752, 39025096594).
- Chạy lại ngày 2026-10-17 trên `e73ec5d`, Windows + Git Bash, Docker Desktop:
  `./mvnw -B verify` BUILD SUCCESS **587 test**, 0 failure/error/skip (durability 36, security 38,
  messaging 14, user 65, catalog 300, inventory 98, Gateway 36); integration dùng PostgreSQL/Kafka
  thật qua Testcontainers.
- `bash scripts/local-up.sh` exit 0, stack healthy, không reset volume; `bash scripts/smoke-local.sh`
  exit 0 `S1-local smoke PASS`; `bash scripts/scan-secrets.sh` exit 0, no leaks found.
- Bằng chứng tác giả (mutation 6/6 guard, relay Kafka, contract) ở [evidence INV-02](inv-02-local-2026-10-16.md);
  mutant bổ sung cho nhánh replay quá hạn bị bắt (1 failure) ngày 2026-10-17.

## Kết quả theo tiêu chí

| Tiêu chí | Bằng chứng (`services/inventory-service`, package `reservation`) | Kết quả |
|---|---|---|
| Reserve đa SKU một transaction; thiếu một SKU rollback toàn bộ (REQ INV-02, T02) | `reservesAllSkusWithLedgerAndEvents`, `t02OneShortSkuRollsBackEverything`, `manyItemsAreStoredInOneReservation` | Pass |
| Tranh SKU cuối: đúng một reservation, stock không âm (T01, REQ INV-06) | `t01LastUnitGoesToExactlyOneOrder`; constraint `reserved ≤ on_hand` từ INV-01 | Pass |
| Retry cùng hash một lần; khác hash 409 RESERVATION_CONFLICT; hash sai snapshot 400 REQUEST_HASH_MISMATCH (T03) | `t03RetrySameHashReturnsExistingOnce`, `sameOrderDifferentHashIsConflict`, `differentSnapshotForStoredReservationIsConflict`, `hashMismatchIs400`, `hashMismatchAndBlankReasonAre400` | Pass |
| Tombstone: release trước reserve, reserve muộn 409, release lặp no-op (T04) | `t04TombstoneBlocksLateReserve`, `releaseWaitsForReservationInsertBeforeTakingSkuLock`, `tombstoneWithUnknownSkuIs400` | Pass |
| Commit/sweep đồng thời khi hết hạn: một kết quả, EXPIRED được lưu (T05) | `t05CommitAndSweepRaceHasOneOutcome`, `commitAfterDeadlineExpiresAndPersists`, `sweepSkipsRowHeldByCommitInFlight`, `commitBeforeDeadlineBeatsSweep`, `expiresDueReservationsOnly`, `replayAfterDeadlineExpiresUnsweptReservation` | Pass |
| Commit lặp không trừ thêm, query COMMITTED (T09) | `commitDeductsOnceAndRepeatIsIdempotent`, `t09QueryAfterCommitShowsCommitted`, `committedReplayAfterDeadlineStaysCommitted` | Pass |
| Commit giảm on_hand/reserved, release chỉ reserved, terminal không mở lại (REQ INV-03) | `commitDeductsOnceAndRepeatIsIdempotent`, `releaseActiveFreesStockAndRepeatIsNoop`, `releaseAfterCommitRequiresRestoreWithoutTouchingStock`, `releaseExistingExpiredIsNoop` | Pass |
| Chỉ order-service gọi; auth trước body; route internal không public | `authCallerAndKeyAreCheckedBeforeBody`, `malformedBodyIs400OnlyAfterAuth`; Gateway boundary 404 | Pass |
| Lock ordering, không deadlock với adjust/import | `reserveConcurrentWithAdjustDoesNotDeadlock` | Pass |
| Schema/constraint, runtime không xóa reservation | `orderIdIsUnique`, `statusAndTimestampsAreConsistent`, `itemQuantityAndSkuAreConstrained`, `runtimeCannotDeleteReservations` | Pass |
| Outbox đúng loại/key và tới Kafka thật | `reservesAllSkusWithLedgerAndEvents` (aggregate/partition), `reservedEventReachesKafkaKeyedByOrderNo` | Pass |

**`TASK:INV-02`: Done (local, trước G0).**

## Ngoại lệ và việc còn lại (không chặn Done local)

- Chưa có caller ORD-01/ORD-02 thật; smoke S1-local không gọi reservation, integration dùng
  synthetic service token.
- `GET` reservation chỉ đọc trạng thái đã lưu: row ACTIVE quá hạn hiển thị ACTIVE tới khi sweep
  (mặc định 5 s) xử lý; reserve/commit đều tự expire.
- Chưa có fault-injection test hai expiry candidate khi outbox lỗi (Minor follow-up Task 7).
- Runtime vẫn có UPDATE trên `stock_reservation_items` qua default privileges; service không dùng.
- Kafka local single-node không auth; SASL/TLS/HA và staging thuộc PLT-04/05. Return/restore là INV-03.

---

# Nghiệm thu `TASK:CAT-02` (02a + 02b) — 2026-10-17

Chủ dự án yêu cầu Claude Code nghiệm thu CAT-02 sau khi mở PR #23. **Không độc lập:** 02b do
Claude Code viết (02a do agent trước viết); review toàn nhánh 02b do một reviewer subagent mới
thực hiện, không thay review bởi người khác tác giả. Phạm vi: acceptance ở [10](../delivery/10_backlog.md)
dòng CAT-02 + `REQ:CAT-03/04/05/09` ở [02](../design/02_prd.md), mức local trước G0.

## Bằng chứng chung

- 02a merge vào `main` qua PR #19 (`edfd003`), [evidence 02a](cat-02a-local-2026-10-11.md).
- 02b trên `dev` `da6d3af`, merge vào `main` qua PR #23 (`0a6928e`, 2026-10-17), tree trùng `dev` `39708c6`. Application CI (pull_request) trên `da6d3af`
  SUCCESS (run 39037570831: `./mvnw -B test`, `validate-contracts.sh`, smoke local/auth/browser);
  Documentation CI SUCCESS.
- Local trên `da6d3af`: catalog 336 test PASS; `./mvnw -B verify` 619 test PASS trước fix review
  (module khác không đổi); local-up + smoke PASS (`CAT-02b cache invalidation PASS`); 6/6 mutant;
  scan-secrets sạch. Chi tiết: [evidence 02b](cat-02b-local-2026-10-17.md).

## Kết quả theo tiêu chí

| Tiêu chí | Bằng chứng (catalog-service tests) | Kết quả |
|---|---|---|
| Browse/filter/sort/search/cursor (REQ CAT-04) | `ProductBrowseIntegrationTest`: `sizeAndColorMustMatchTheSameVariant`, `brandTagAndPriceRangeFilterOnMinPrice`, `searchIgnoresAccentsAndEscapesWildcards`, `everySortPagesWithoutDuplicatesOrGaps`, `cursorFromOtherFiltersOrSortIsRejected` | Pass |
| Cursor ổn định cùng giá | `everySortPagesWithoutDuplicatesOrGaps` (tie-break id) | Pass |
| Batch quote | `VariantQuoteIntegrationTest` (12 test, auth/ACTIVE/giá/no-store) | Pass |
| Chỉ product ACTIVE; collection ACTIVE trong [start,end), ≥1 product | `hidesNonPublicProducts`, `nonPublicProductIs404`, `CollectionPublicIntegrationTest`: `futureStartIsHidden`, `inactiveAndDraftCollectionsAreHidden`, `emptyCollectionIsHidden` | Pass |
| Test biên thời gian, unpublish product ACTIVE cuối | `startBoundaryShowsAndEndBoundaryHides`, `exactDatabaseTransactionStartIncludesAndEndExcludesEquality`, `unpublishingLastActiveProductHidesCollection` | Pass |
| Durable invalidation + TTL (REQ CAT-09) | `CacheInvalidationIntegrationTest` (6 test: thay đổi admin hiện sau worker, replay/409 không enqueue, Redis treo giữ task rồi phục hồi, MANUAL + metric); `PublicCacheIntegrationTest` TTL 300/30 s; smoke | Pass |
| Inventory version cũ không ghi đè; tồn có timestamp, không giữ hàng (REQ CAT-05/09) | `StockObservationsIntegrationTest`: `newerVersionUpdatesAndOlderIsIgnored`, `detailShowsPerVariantStockAndOldestObservedAt`; `stockIsNotCached` | Pass |
| BEST_SELLING từ ORDER_COMPLETED, replay không cộng lại | `SoldQuantitiesIntegrationTest`: `replaySameOrderDoesNotAddTwice`, `duplicateVariantLinesSum`, `bestSellingOrdersBySoldQuantity`; DLT `completedOrderWithUnknownVariantGoesToDlt` (Kafka thật) | Pass (event tổng hợp) |
| Redis lỗi không làm hỏng public read | `PublicCacheRedisDownIntegrationTest`, `pausedRedisFallsBackWithinBudget`, `undecodableValueIsMiss`, `oldSchemaValueIsMiss` | Pass |
| Public HTTP cache header, lỗi no-store | `PublicCacheHeadersIntegrationTest`, `PublicServerErrorHeadersIntegrationTest` | Pass |

**`TASK:CAT-02`: Done (local, trước G0).**

## Ngoại lệ và việc còn lại (không chặn Done local)

- Chưa có producer ORDER_COMPLETED thật: kiểm E2E khi có ORD-03.
- Request đầu sau khi catalog khởi động có thể fallback PostgreSQL; `background_tasks` DONE chưa purge.
- Staging chưa có Redis/ACL cho catalog (PLT-04).
- Minor để sau: ngưỡng test Redis pause 1 s, cộng `available` bằng long, smoke kiểm metric hit.
