# Nghiệm thu task local — 2026-10-18

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:CART-01` sau khi CI của PR #25 chạy xong.
**Không độc lập:** CART-01 do Claude Code viết; review toàn nhánh do một reviewer subagent mới thực
hiện, không thay review bởi người khác tác giả. Phạm vi: acceptance ở [10](../delivery/10_backlog.md)
dòng CART-01 + `REQ:CART-01/02/04` ở [02](../design/02_prd.md), mức local trước G0 (G0 hoãn tới sau G1
theo quyết định 2026-10-18 trong [08](../design/08_decisions.md)).

## Bằng chứng chung

- Code trên `dev` `5a7a0d5`, PR #25 (chưa merge). Application CI (pull_request) SUCCESS
  (run 39104418263: `./mvnw -B test` toàn reactor, `validate-contracts.sh`, smoke local/auth/browser);
  Documentation CI SUCCESS.
- Local: cart-service **53 test** PASS sau fix review; `./mvnw -B verify` 692 test PASS trước fix (lần sau
  fix bị dừng vì máy thiếu bộ nhớ, CI thay thế); local-up + smoke PASS (`CART-01 guest/member cart PASS`);
  mutation 4/4 + mutant khóa transaction; contract 60 test; scan-secrets sạch.
  Chi tiết: [evidence CART-01](cart-01-local-2026-10-18.md).

## Kết quả theo tiêu chí

| Tiêu chí | Bằng chứng (cart-service tests) | Kết quả |
|---|---|---|
| Giỏ guest/member lưu PostgreSQL (REQ CART-01) | `CartSchemaIntegrationTest` (một chủ sở hữu, một ACTIVE/member), `guestPostCreatesCartAndCookie`, `memberPostCreatesOneActiveCart` (5 POST song song → 1 giỏ) | Pass |
| Cookie credential, token hash, 30 ngày từ lần hoạt động | `guestPostCreatesCartAndCookie` (HttpOnly/Secure/SameSite=Lax, DB chỉ SHA-256), `guestReturnsWithCookieGetsSameCart` (cấp lại cookie), `expiredGuestCartIsGone`, `malformedCookieGetsNewCart`, mutant lưu token thô bị bắt | Pass |
| Qty 1–99, kiểm catalog/tồn tham khảo (REQ CART-02) | `sumOver99IsQuantityLimit`, `hundredFirstItemIsLimit`, `inactiveVariantIs409`, `unknownVariantIs409`, `outOfStockIs409WithAvailable`, `decreaseAllowedWhenVariantInactive`, `dependencyBadShapeOnWriteIs503` | Pass |
| Optimistic version chống lost update; nhiều thiết bị (REQ CART-02/04) | `staleVersionIs409WithCurrent`, `parallelSameVersionOneWins`, `missingVersionIs400`; member `/carts/mine` cùng một giỏ | Pass |
| Item API + retry không cộng lại | `addStoresSnapshotAndBumpsVersion`, `addSameVariantSums`, `removeAndClear`, `retrySameKeyDoesNotAddAgain`, `concurrentSameKeyAddsOnce`, `sameKeyDifferentBodyIs409` | Pass |
| Guessed cart_id → 404 | `CartOwnershipIntegrationTest` (7 test: id đoán, cookie khác, guest↔member, sai định dạng), mutant bỏ điều kiện hash bị bắt | Pass |
| Redis mất không mất giỏ | cart-service không dùng Redis (PostgreSQL là nguồn sự thật, quyết định spec §9.4); preview chịu lỗi dependency (`dependencyDownStillReturnsCart`) | Pass |
| Không gọi HTTP khi giữ lock | `noRowLockDuringDependencyCall` (DELETE không bị chặn; mutant đưa gọi catalog vào transaction làm test fail) | Pass |
| Gateway route, CSRF, không route internal | `GatewayBoundaryTest` `cartRoutesAreForwarded`, `cartCookieMutationNeedsAllowedOrigin`, `cartInternalIsNotRouted` | Pass |

**`TASK:CART-01`: Done (local, trước G0)**; còn bước merge PR #25 vào `main` do chủ dự án thực hiện.

## Ngoại lệ và việc còn lại (không chặn Done local)

- REQ CART-01 nhắc "Redis cache": chủ dự án duyệt không cache ở CART-01; thêm khi đo tải cần.
- Chưa merge guest→member (CART-02), snapshot/cleanup cho order (CART-03), job dọn giỏ guest hết hạn.
- Cookie `Secure` trên trình duyệt thật và CORS khi storefront khác origin: kiểm ở WEB-02.
- Minor để sau: `current_version` dạng chuỗi trong `errors`, `idempotency_requests` không hết hạn,
  trace không vào preview, PUT tăng không làm mới `price_snapshot`, body đọc như JSON bất kể `Content-Type`.

## TASK:CART-03 — snapshot/cleanup cho order

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:CART-03` sau khi Application CI của PR #26 PASS.
**Không độc lập:** CART-03 do Claude Code viết. Review gồm một subagent reviewer (0 Critical, 2 Important đã sửa)
và một agent review riêng trên PR #26 (1 finding P2 đã sửa ở `461395e`; quyết định "đồng ý merge về mặt code,
chờ Application CI PASS"), không thay review bởi người khác tác giả. Phạm vi: acceptance ở [10](../delivery/10_backlog.md)
dòng CART-03 + `REQ:CART-05/06` ở [02](../design/02_prd.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `461395e`, PR #26 (merge `main` tại `2f19354` sau nghiệm thu). Application CI (pull_request) SUCCESS
  ([run 39137396425](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39137396425)):
  `./mvnw -B test` toàn reactor **713 test** 0 failure (cart-service 72), contract validation 64 test
  (gồm 4 test `test_cart_internal`), infra validation, local stack + smoke (`CART-01 guest/member cart PASS`,
  `S1-local smoke PASS`), browser smoke. Documentation CI SUCCESS.
- Local: `./mvnw -B -pl services/cart-service verify` 72 test PASS; mutation 3/3 (guard version, guard quantity,
  replay idempotency); scan-secrets sạch. Chi tiết: [evidence CART-03](cart-03-local-2026-10-18.md).

### Kết quả theo tiêu chí

| Tiêu chí | Bằng chứng | Kết quả |
|---|---|---|
| Snapshot API cho order: actor đã xác minh + expected version → items/versions (03 §4) | `CartSnapshotIntegrationTest`: `snapshotReturnsItemsWithoutWrites`, `snapshotChecksVersion` (400/409 current), `snapshotIsOwnerOnly` (actor khác/guest hết hạn/id sai → 404), `snapshotRejectsBadActor`, `snapshotRequiresOrderServiceToken` (không token, sai audience → 401; caller khác → 403) | Pass |
| Cleanup theo snapshot/version/qty, key `order_id` (REQ CART-06) | `t18KeepsChangedAndNewItems`, `editedBackToSameQuantityIsRetained`, `quantityMismatchAtSameVersionIsRetained`, `keyMustEqualOrderId`; mutation bỏ guard version/quantity bị bắt | Pass |
| T18: giữ item mới/sửa, không xóa sạch giỏ | `t18KeepsChangedAndNewItems` (A sửa → retained, B xóa → missing, D mới còn, C removed, giỏ ACTIVE), `nothingRemovedKeepsVersion` | Pass |
| Cleanup retry không trừ lại | `retrySameOrderDoesNotDeductTwice` (kể cả sau khi khách thêm lại cùng variant), `parallelSameOrderRemovesOnce`, `sameOrderDifferentBodyIs409`, `notOwnedRollsBackKey`; mutation bỏ replay bị bắt | Pass |
| Preview không giữ kho/quota; chưa tính phí không hiện tổng như đã chốt (REQ CART-05) + fee-preview integration contract | `subtotalWhenPreviewOk`, `subtotalNullWhenUnavailableOrEmpty`, `subtotalNullOnOverflow`, `shipping_fee_status: NOT_CALCULATED` trong `cart.yaml`/03/06; preview chỉ đọc (`readDoesNotWrite`) | Pass |
| Validation ở ranh giới tin cậy, auth trước body | `validation` (gồm item_id sai/thiếu trước item hợp lệ → 400, finding PR #26), `authBeforeBody` | Pass |
| Route internal không public | `GatewayBoundaryTest#cartInternalIsNotRouted` (snapshot qua Gateway → 404) | Pass |
| Contract | `cart-internal.yaml` + `cart.yaml` Redocly lint, `test_cart_internal.py`/`test_cart.py` trên CI | Pass |

**`TASK:CART-03`: Done (local, trước G0)**; chủ dự án đã merge PR #26 vào `main` (`2f19354`), CI trên `7c0726f` PASS trước merge.

### Ngoại lệ và việc còn lại (không chặn Done local)

- Route internal chưa có smoke tích hợp thật: order-service chưa tồn tại; `CART_INTERNAL_CALLERS` trống mặc định.
  ORD-01 đăng ký public key và gọi qua `background_tasks`.
- Câu hỏi mở ORD-01/WEB-03 ([08](../design/08_decisions.md) mục CART-03): cookie `cart_token` `Path=/api/v1/carts`
  không tới `/api/v1/orders`; phải chốt cách xác minh guest cho `Cart-Actor` trước guest checkout.
- Snapshot REPEATABLE READ (review I1) chưa có test xác định chen commit giữa hai lần đọc.
- Khách thêm lại cùng variant sau snapshot → dòng gộp được giữ nguyên (quyết định spec §8.4); WEB-03 hiển thị `retained`.
- Fee estimate thật thuộc SHP-01; job dọn giỏ guest hết hạn chờ O06.


## TASK:INV-03 và TASK:CART-02 — PR #27

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:INV-03` và `TASK:CART-02` sau khi Application CI của PR #27 PASS.
**Không độc lập:** cả hai do Claude Code viết. Review gồm một subagent reviewer (0 Critical, 1 Important đã sửa) và một agent
review riêng trên PR #27 (P2 timeout Gateway + P3 created_at, đã sửa ở `21ade69`; quyết định "đồng ý merge về mặt code",
reviewer chạy lại kịch bản qua Gateway thật: 200 sau 1018 ms), không thay review bởi người khác tác giả. Phạm vi: acceptance ở
[10](../delivery/10_backlog.md) dòng INV-03/CART-02 + `REQ:INV-07`, `REQ:CART-03` ở [02](../design/02_prd.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `21ade69`, PR #27 (merge `main` tại `780b4cb` sau nghiệm thu). Application CI (pull_request) SUCCESS
  ([run 39145296419](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39145296419)): `./mvnw -B test`
  toàn reactor **748 test** 0 failure (inventory 116 gồm `ReturnIntegrationTest` 15, cart 92 gồm `CartMergeIntegrationTest` 20,
  Gateway 39), contract 66 test, infra validation, local stack + smoke (`CART-01 guest/member cart PASS`, `S1-local smoke PASS`),
  browser smoke. Documentation CI SUCCESS.
- Local: inventory 116, cart 92, Gateway 39 test PASS; mutation INV-03 2/2, CART-02 2/2; scan-secrets sạch.
  Chi tiết: [evidence INV-03](inv-03-local-2026-10-18.md), [evidence CART-02](cart-02-local-2026-10-18.md).

### INV-03 — kết quả theo tiêu chí

| Tiêu chí | Bằng chứng (`ReturnIntegrationTest`) | Kết quả |
|---|---|---|
| Return hàng đã commit có evidence (REQ INV-07) | `restoreAddsOnHandOnce` (on_hand +, reserved giữ, ledger RETURN, reason/evidence lưu), `restockedWhenAvailableWasZero`, `notCommittedIs409`, `unknownOrderIs404` | Pass |
| Tổng trả ≤ đã commit, cả khi đồng thời (cumulative guard dưới lock) | `cumulativeGuard`, `parallelReturnsNeverExceedCommit`; mutation bỏ guard bị bắt | Pass |
| Operation key ngăn cộng lại (T17) | `t17SameKeyReplaysWithoutAdding` (tuần tự + song song), `sameKeyDifferentItemsIs409`, `multiSkuReplayIgnoresCollationOrder`; mutation bỏ replay bị bắt | Pass |
| Ranh giới tin cậy | `authBeforeBody`, `validation`, `skuNotInReservationIs400`, `lowercaseSkuMatches`, `runtimeCannotRewriteReturns` | Pass |
| Order đọc lại sau UNKNOWN | `getShowsReturned` (`items[].returned`) | Pass |

**`TASK:INV-03`: Done (local, trước G0)**; PR #27 đã merge `main` (`780b4cb`).

### CART-02 — kết quả theo tiêu chí

| Tiêu chí | Bằng chứng (`CartMergeIntegrationTest`) | Kết quả |
|---|---|---|
| Xác minh cả hai credential (REQ CART-03) | `noBearerIs401`, `noCookieOrOtherGuestCartIs404`, `expiredGuestIs404` | Pass |
| Lock theo thứ tự, retry/song song chỉ cộng một lần (T19) | `parallelMergeAddsOnce`, `retrySameKeyReplays`, `retryOtherKeyAfterMerge`; mutation bỏ MERGED / bỏ khóa bị bắt | Pass |
| Merge locks/version | `staleVersionsAre409`, `validation` (IDEMPOTENCY_KEY_REUSED) | Pass |
| Cap qty/tồn và báo item bị điều chỉnh (adjustments UI contract) | `adjustments`, `quantityCappedAt99`, `memberQuantityNeverReduced`, `cartItemLimit`, `cappedLineKeepsOlderCreatedAt`; `cart.yaml` `Adjustment` | Pass |
| Gộp đúng dữ liệu | `mergeSumsAndKeepsOlderCreatedAt`, `newVariantAddsLineAndGuestIsMerged` (MERGED + merged_into, cookie xóa), `memberWithoutCartGetsOne`, `emptyGuestCart` | Pass |
| Không gọi HTTP khi giữ lock; dependency lỗi không ghi; trong budget Gateway | `noLockDuringDependencyCall`, `dependencyDownIs503NoWrite`, `multiSkuMergeFitsGatewayBudget` | Pass |

**`TASK:CART-02`: Done (local, trước G0)**; PR #27 đã merge `main` (`780b4cb`).

### Ngoại lệ và việc còn lại (không chặn Done local)

- Chưa có smoke tích hợp: order-service (ORD-04) gọi `/returns`, UI WEB-02 gọi merge sau login.
- CART-02 M2/M3 (guest sửa giữa quote và lock, giỏ member đổi sau khóa → 409) chưa có test xác định.
- Retry merge khi cookie đã bị xóa → 404; client đọc `GET /carts/mine` (ghi trong contract).
- Phase 1A chưa đóng: còn WEB-01/02, ADM-01, QA-01.
