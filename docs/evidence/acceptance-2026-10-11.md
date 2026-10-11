# Nghiệm thu task local — 2026-10-11

Chủ dự án chỉ định Claude Code (agent) nghiệm thu "tất cả những gì đã làm được" ngày 2026-10-11,
thay reviewer người/TL cho đợt này, theo tiền lệ S1-local ngày 2026-10-04 (09 §6).
**Không độc lập:** các task dưới đây cũng do Claude Code viết; mỗi PR đã có review riêng
(Superpowers/subagent) nhưng đợt nghiệm thu này không thay review bởi người khác tác giả.

Phạm vi: acceptance tối thiểu ở [10](../delivery/10_backlog.md) + DoD ở
[09 §6](../delivery/09_delivery_plan.md), mức local trước G0 (DoD chỉ yêu cầu staging từ G0).
Không nghiệm thu gate G0–G4, staging/AWS hay production.

## Bằng chứng chung

- `main` tại `9ca0c83` (code bằng `5dbd088`/`e2008e6`; PR17 chỉ docs). Toàn bộ code đã merge qua PR #1–#17.
- Full `./mvnw -B test` trên `e2008e6`: 388 PASS, 0 failure/error/skip
  (durability 36, security 38, user 65, catalog 218, Gateway 31); integration dùng PostgreSQL/Kafka/Redis thật qua Testcontainers.
- Application CI + Documentation CI trên `main` `9ca0c83` SUCCESS (run 38131810327, 38131810337).
- `bash scripts/local-up.sh` + `bash scripts/smoke-local.sh` ngày 2026-10-11: exit 0, `S1-local smoke PASS`
  (auth, CAT-01b, CAT-03 upload → publish → public read/304 → unpublish).

## Kết quả theo task

| Task | Acceptance (10) | Bằng chứng | Kết quả |
|---|---|---|---|
| PLT-03 | Outbox/inbox/idempotency/lease/background-task; crash sau publish; lease hết hạn; cùng version không đảo sequence | `crashAfterPublishRedeliversSameEventAndConsumerAppliesItOnce` (Kafka thật), `expiredLeaseCannotMarkSentEvenWithoutCompetitor`, `expiredPredecessorIsReclaimedBeforeItsSuccessor`, `eventsAtTheSameVersionArriveInSequenceOrder`, `concurrentDuplicateDeliveryCommitsOneEffect`; dùng thật bởi user-service (`USER_CREATED`) và catalog (`VARIANT_CREATED`); PR #4 | **Done (local)** |
| USR-01 | Migrations; register/login/refresh/logout/password; reset secret handoff + notification contract; concurrent refresh/replay, email race, revoke family | `concurrentRefreshWithTheSameTokenSucceedsAtMostOnce`, `refreshRotatesAndReplayOfTheOldTokenRevokesTheFamily`, `concurrentCaseInsensitiveRegistrationCreatesOneAccount`, `onlyTheNotificationServiceCanFetchTheSecretAndNeverCached`, `NOTIFY_RESET_PASSWORD` trong registry; 1b-ii: `RateLimitIntegrationTest`, `RedisDownIntegrationTest`, `RedisOutageIntegrationTest`; PR #8, #11, #12; [evidence 1b-ii](usr-01b-ii-local-2026-10-06.md) | **Done (local)**, gồm 1b-ii |
| USR-02 | Profile/address; roles/permissions; bootstrap admin audit; default-address race; OPS không refund, FINANCE không adjust stock; revoked admin bị chặn | `concurrentRequestsToSetTheDefaultLeaveExactlyOneDefault`, `seededRolesGrantThePermissionMatrixOf13` (OPS không `payment.refund`, FINANCE không `inventory.adjust`), `bootstrapGrantsTheFirstSuperAdminOnceWithAnAudit`, token cũ sau đổi role/khóa → 401, `lockedOrInactiveAccountIsRejectedEvenWithAFreshToken`; PR #9, #10 | **Done (local)** |
| CAT-01 | Migration; CRUD/variant/collection/size-guide/admin; VARIANT_CREATED outbox; SKU immutable; publish validation + sanitize; không hard-delete lịch sử | `createVariantWritesExactlyOneOutboxEvent`, `failureAfterInsertRollsBackVariantAndEvent`, `skuSizeColorAndProductAreImmutable`, `PublishIntegrationTest`, `HtmlSanitizerTest`, `CollectionAdminIntegrationTest`, `SizeGuideAdminIntegrationTest`; không có route DELETE trong catalog (brand/category inactivate); PR #14, #15; [1a](cat-01a-local-2026-10-07.md), [1b](cat-01b-local-2026-10-07.md) | **Done (local)** |
| CAT-03 | Scoped upload/attach + object storage config; validate content/type/size/owner; image alt/thumb; object chưa kiểm tra không public | Upload owner-only, quarantine private, re-encode/thumb, `alt_vi`/`alt_en` bắt buộc (V005), visibility DB trước public read; review lại PR16 4/4 finding đã sửa, 4/4 mutation bị bắt; PR #16; [evidence](cat-03-local-2026-10-08.md) | **Done (local)**, có ngoại lệ dưới |

## Ngoại lệ và việc còn lại (không chặn Done local)

- CAT-03: health group `media` và metric latency/lỗi S3 theo thao tác — chủ dự án đã hoãn trước đợt này,
  giữ là follow-up mở. Chưa quan sát lifecycle 2 ngày, chưa đo heap re-encode, GC partial chưa backoff.
- CAT-01/USR-01: event được ghi outbox; chưa có relay chạy như process trong runtime local nên chưa publish
  Kafka end-to-end — thuộc service tiêu thụ/staging (NOT-01, CAT-02, PLT-04/05).
- USR-01: gửi email reset thật thuộc acceptance NOT-01. USR-02: UI admin thuộc ADM-01. CAT-01: public read thuộc CAT-02.
- Tất cả: chỉ local; staging, load, restore và production chưa kiểm.

## Chưa nghiệm thu được

| Task | Lý do | Cần gì |
|---|---|---|
| PLT-01 | FE/PO chưa xác nhận đánh đổi SEO của SPA; O02 ngoài S1 còn mở | Quyết định SEO của chủ dự án |
| PLT-02 | O02 Kafka/Redis, CVE/license, staging; producer/consumer review chưa đủ consumer | Chốt O02; có consumer thật |
| SEC-01 | O06 còn thiếu ngày hạn/chi tiết policy; cleanup/purge chưa làm | Chủ dự án chốt O06 |
| PLT-05 | Correlation qua Kafka, dashboard/alert, trace trên staging | PLT-04 staging |
| PLT-04 | Mới chuẩn bị offline | O01 account/ngân sách |
