# Handoff CAT-02a — 2026-10-11

`TASK:CAT-02` phần 02a · `REQ:CAT-04`, hỗ trợ `REQ:CAT-03/05`.
Thực thi trên `dev`, base Task 7 `e141af5`; local commits được cho phép,
chưa push/PR/merge main. [Spec](../specs/2026-10-11-cat-02a-public-browse-design.md),
[plan](2026-10-11-cat-02a-public-browse.md),
[evidence và lệnh tái hiện](../../evidence/cat-02a-local-2026-10-11.md).

8 public reads + internal cart/order variant quote đã hiện thực local: V007 unaccent,
PostgreSQL query-time min/max, public visibility, bốn sort/keyset/cursor filter/domain,
localized detail/taxonomy/guide, collection [start,end) và caption-only lookbook.
Quote giữ thứ tự/found/active, NON_NULL chỉ ở QuoteItem, no-store/security kể cả lỗi trước
controller; Gateway internal404. Contract có đủ operation/nullable shapes/filter bounds,
smoke có browse/detail/search và card shape mới.

Verification ngày 2026-10-11: full reactor BUILD SUCCESS 453 tests
(durability36/security38/user65/catalog282/gateway32), 0 failures/errors/skips;
contracts43 + Redocly và scripts14 PASS; local-up/smoke PASS. Sáu required source
mutants đều killed với 1 assertion failure/0 errors, source phục hồi nguyên byte.
Hoàn tất tài liệu ngày 2026-10-13: docs58/diff checks và pinned Gitleaks staged patch
PASS; history scan101 commits không có leaks. Full suite/mutation/smoke không rerun
trong lượt hoàn tất Task 7 docs tại `d18b7fd`; đây là bằng chứng lịch sử trước final fixes,
log thực và counts nằm trong evidence.

Commit Task1–6: `a260312`, `8ae8fcf`, `57ce012`, `086b8a5`, `97fdef9`, `e141af5`.
Task7 có subject `docs(CAT-02): contract, smoke and docs for CAT-02a public browse`;
hash local nằm trong task-7-report.md và `git log` (tránh hash tự tham chiếu trong commit).
Raw evidence sẽ archive ở `.superpowers/cat-02a-verification-2026-10-11/` sau final
whole-branch review; controller copy rồi chỉ xóa plan SDD workspace, giữ ignored archive.

Final review ngày 2026-10-13: F1 search normalization và F2 detail snapshot là Important;
final fix wave sửa SQL lower/unaccent trước escape cho names/slug và read-only REPEATABLE_READ
cho product/collection detail. F4 V007 filename, F5 năm public contract test methods và F6
phạm vi exception advice đã đồng bộ. Đây là technical corrections theo ledger, không thêm
quyết định nghiệp vụ. Trong wave này trước chỉ đạo bổ sung, F3 Minor còn chờ owner,
runtime bỏ qua unknown fields; trạng thái lịch sử được cập nhật bởi F3 follow-up bên dưới.

Fresh final fix verification ngày 2026-10-13: full reactor BUILD SUCCESS **457 tests**
(durability36/security38/user65/catalog286/gateway32), 0 failures/errors/skips; connected48,
contracts43/Redocly, scripts14, docs58/diff PASS. RED4 assertion failures/no errors và SQL percent
mutant1 assertion failure/no errors; restored bytes/SHA và recompilation được xác minh trước
full tests. Pinned Gitleaks history102/staged patch không leaks. Raw `final-fix-*` logs/report
tách khỏi Task7Oct11; không rerun smoke/local-up hoặc năm mutants unchanged ở wave này.
Fix subject `fix(CAT-02): normalize search and keep detail snapshots consistent`; local hash
nằm trong report/git log. Scoped re-review và archive còn do controller chốt.

F3 follow-up theo chỉ đạo owner 2026-10-13 đã hiện thực/kiểm local: QuoteRequest áp dụng
quy tắc any-setter hiện có tại03 quanh277, giữ `variant_ids` và không thêm request items.
Verifier/allowlist trước đọc/bind/validate body; missing/invalid401 và forbidden403 kể cả
JSON lỗi/sai kiểu. Unknown →400 field tên key/message cố định, không echo/log value.
Collection ItemRequest đã có rule; thêm `items[0]` regression, giữ `items[1]`.
Fresh full461 (catalog290), targeted31, contracts43/Redocly, scripts14, docs58/diff,
history103/staged secret scans PASS; RED unknown3/1fail và auth-binding1/1fail,
final unknown-check mutant1/1fail, tất cả0 errors; bytes restore/compile44/hash/XML checked.
Raw `f3-*` logs/report giữ riêng; interim460 trước auth-binding correction và historical457
không thay proof cuối. Sau full chỉ rename test/comment, assertions và executable logic không đổi.
Subject `fix(CAT-02): reject unknown quote fields after service authentication`, local hash
trong f3-report/git log. F3 không còn pending owner; controller scoped re-review/archive còn mở.

F3 fix round1 ngày2026-10-15 sửa Important415 mất Api envelope/metadata do consumes
reject trước HandlerMethod: kiểm media trong controller sau auth, trước body, dùng ErrorResponse
advice hiện có giữ generic415/errors/metadata/correlation/no-store/Accept. JSON/+json,406/405
giữ nguyên; malformed media415, media sai với missing/invalid401 và forbidden403 đã kiểm.
Minor hai nhãn policy03/spec sửa §3. Fresh full462 =36/38/65/291/32, targeted32,
contracts43/scripts14/docs58/diff/history104/staged scans PASS; RED2/2 assertion failures,
same unknown mutant1/1 assertion failure,0 errors, bytes/hash/compile44/XML verified.
Raw `f3-r1-*`/report tách Oct13 historical461; source/test không đổi sau full.
Subject `fix(CAT-02): preserve quote metadata for unsupported media`, hash trong f3-report/git log;
controller scoped re-review73a1e96..newHEAD/archive còn mở, không whole-branch review/smoke rerun.

CAT-02 **In progress**, CAT-02b pending: Redis cache/invalidation durable (CAT-09),
replay-safe ORDER_COMPLETED sold_quantity, inventory observation và public HTTP cache
headers. Chưa load benchmark, remote CI/staging hoặc UAT/nghiệm thu parent/toàn bộ MVP.
Chưa sinh key cart/order thật; local smoke dùng synthetic OPS token, không chứng minh
login→OPS issuance. Existing local `.env`, credentials, volumes/data giữ nguyên; smoke để
lại fixture synthetic theo policy hiện có.

Bước tiếp theo: controller scoped re-review final fixes/diff/evidence trước lưu archive; parent vẫn mở.
Push/PR/merge hoặc triển khai 02b chỉ khi chủ dự án yêu cầu; không tự provision AWS.

PR19 review fixes ngày2026-10-15, base `38012fd`: findings từ chỉ đạo chính xác của chủ dự án,
đã kiểm code, không viện dẫn GitHub review/comment chưa có. Shared public predicate + quote
kiểm category và parent nếu có đều ACTIVE, giữ found=true/active=false cho variant ẩn.
Regression covers products/search/detail404/filter parent, mixed/sole-hidden collections,
no-parent và ACTIVE-parent. PublicLimit parser dùng chung products/search/collection list/detail;
0/abc đều Api.Problem→Api.Error INVALID_LIMIT, field/message chung không rejected_value,
metadata/correlation đầy đủ. Xóa product error/success wrappers riêng, dùng Api.ok giữ successful JSON.
Catch-all log `Catalog request failed`, giữ exception/redaction và4xx; public POST products405.

Public reads bỏ qua unknown query; products/search/collection list/detail chấp nhận repeated,
lấy giá trị đầu tiên và vẫn validate format/domain; size-guide locale binding không đổi trong task này.
Old HEAD HTTP products repeated200/collections repeated400/search repeated200/unknown200;
sau rebuild cả bốn200. Spec §2/§3,03,OpenAPI/service README đồng bộ phạm vi này.

Fresh verification: RED5/4 assertion failures/0 errors; connected GREEN80; explicit compile rồi
bare `./mvnw -B test` BUILD SUCCESS466 = durability36/security38/user65/catalog295/gateway32,
0 failures/errors/skips,49 fresh XML checked; source/tests before/after full hash khớp.
`bash scripts/validate-contracts.sh` Redocly+43, scripts14, docs58/working+staged diff,
pinned Gitleaks history105/staged patch PASS. `bash scripts/local-up.sh` rồi
`bash scripts/smoke-local.sh` exit0 ngay lượt mới, CAT-02a/CAT-03/CAT-01b/S1-local PASS.
`.env` hash khớp, giữ credentials/volumes; smoke thêm synthetic fixtures. Smoke cũ initial empty-log
exit1/retry PASS giữ lịch sử, không coi lỗi cũ đã resolved chỉ vì lượt mới xanh.
Npm source-map-js high advisory/JVM/compiler/LoadBalancer/Compose warnings ngoài scope, giữ nguyên.

Exact commands/selectors/logs/report mới tại `.superpowers/pr19-review-fixes-2026-10-15/`,
chi tiết trong [evidence](../../evidence/cat-02a-local-2026-10-11.md); không ghi đè historical reports.
Một local commit subject `fix(CAT-02): align public visibility and validation after PR19 review`;
controller review rồi push dev/chờ latest s1-local GREEN theo owner authorization, chưa báo CI GREEN.
CAT-02 **In progress**, CAT-02b pending; không push/PR/merge/AWS/provider trong worker task.
