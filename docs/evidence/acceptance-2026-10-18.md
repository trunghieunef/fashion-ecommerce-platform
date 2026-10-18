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
