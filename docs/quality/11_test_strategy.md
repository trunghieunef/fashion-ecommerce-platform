# 11 — Chiến lược kiểm thử và nghiệm thu

B1 · 2026-09-19 · Owner QA + TL · Chưa có ứng dụng, các test dưới đây là kế hoạch, không phải kết quả đã chạy.

## 1. Mục tiêu và tầng kiểm thử

| Tầng | Phạm vi | Ai thực hiện | Khi chạy |
|---|---|---|---|
| Static/contract | Format/lint, OpenAPI, JSON Schema, compatibility, docs links | CI + tác giả | Mỗi PR |
| Unit | Tiền/rounding/state guards/validation, không mock để chứng minh DB locking | BE/FE | Mỗi PR |
| DB integration | Flyway, constraints, unique races, transaction rollback, leases | BE; QA review | PR service liên quan, PostgreSQL thật cùng major production |
| Provider adapter | Signature/encoding/ACK/result-code, duplicate/timeout/query fixtures | BE | PR adapter + sandbox trước release |
| Service integration | HTTP command + Kafka outbox/inbox + recovery | BE/QA | Merge và staging gate |
| Browser E2E | Guest/member/admin, VI/EN, responsive, ownership và UX UNKNOWN | FE/QA | Critical PR và nightly khi có CI |
| Resilience/load/security | Crash boundaries, hot SKU, authorization, restore và backpressure | QA/DEVOPS/TL | Trước G2/G3 và sau thay đổi có rủi ro |
| UAT | Shop kiểm tra giá/phí/COD/hủy/return/refund/reports | PO/OPS/FINANCE | G1/G2/G3 |

H2 hoặc mock repository không thay kiểm thử constraint/race PostgreSQL. Không dùng tiền thật cho load test; provider stub có latency/error distributions được ghi rõ; sandbox chạy bộ nhỏ để xác minh tích hợp.

## 2. Traceability yêu cầu → task → test

T01–T27 được định nghĩa chi tiết duy nhất tại [06 §13](../design/06_service_flows.md). Tên Uxx ở mục 3 bổ sung happy path/security/UX ngoài các ca race này.

| Nhóm REQ | Tasks trong 10 | Test tối thiểu | Gate |
|---|---|---|---|
| USR-01/03/05/06/07/08 | USR-01/02, WEB-02, SEC-01 | T20 phần refresh, T21, U01/U02/U03 | G2 |
| USR-02/04/09 | USR-03/04 | T20 social, T25 OTP, U04 | G3 |
| CAT-01–06/09/11 | CAT-01/02/03, WEB-01, ADM-01 | U05/U06/U07, T24 | G2 |
| CAT-07/08/10 | CAT-04, NOT-02 | U08/U09 | G3 |
| CART-01–06 | CART-01/02/03, WEB-02/03 | T18/T19/T21/T25, U10 | G2 |
| ORD-01–10 | ORD-01–05, WEB-03/04, ADM-02 | T03–T09/T12/T16–18/T21/T24, U11/U12/U13 | G2 |
| INV-01/02/03/06/07/08 | INV-01–03 | T01–T05/T09/T17/T25, U14 | G2 |
| INV-04/05 | NOT-02 | U09/U15 | G3 |
| PAY-01–08 | PAY-01–04, WEB-04, ADM-02 | T06–T11/T26/T27, U12/U13/U16 | G2 |
| PAY-06 tự động | PAY-05 | U17 | G3 |
| PRO-01–06 | PRO-01/02 | T13/T14/T24/T25, U18 | G3 |
| SHP-01/02/04/06 | SHP-01/02, ORD-03/04 | T11/T15–17/T27, U11 | G2 |
| SHP-02/03 carrier | SHP-03 | T11/T15–17/T27, U19 | G3 |
| NOT-01/05 seed/06 | NOT-01 | T22/T23, U20 | G2 |
| NOT-02/03/05 editor | USR-03, NOT-03 | U04/U21 | G3 |
| ADM-01/02/03/06/08, XCT-03 | USR-02, ADM-01/02, SEC-01 | U03/U22, T10/T17 | G2 |
| ADM-04/05/07 marketing | ADM-03 | U17/U18/U21/U23 | G3 |
| XCT-01/02/05/06 | SEC-01, WEB-01–04, QA-02, OPS-01/02 | T21/T25, U03/U07/U24/U25 | G2 |
| NFR-01–09 | PLT-04/05, QA-03, OPS-01 | Profile L1 + restore + observability proof | G2 |
| NFR-10, SHP-05, NOT-04, XCT-04 | Phase 3 epics | Profile L3/benchmark riêng khi có task | G4 |

ADM-07 collection ở MVP kiểm tra U05; không đợi marketing. QA report phải mở rộng các range thành từng REQ và ghi pass/fail/not applicable cùng lý do. P0 không được N/A để đạt gate.

## 3. Ca bổ sung có dữ liệu và kết quả

| ID | Given / When | Then |
|---|---|---|
| U01 | Email khác chữ hoa và hai register đồng thời | Một account; response an toàn; không log password |
| U02 | Hai request đặt default address hoặc xóa default | Không hai mặc định; còn địa chỉ thì chọn một mặc định |
| U03 | OPS gọi refund; FINANCE gọi adjust; spoof X-User-Roles; truy cập internal public; admin vừa bị revoke | 403/404 phù hợp; không mutation; audit/alert có redaction |
| U04 | OTP resend rồi code cũ; thử lần 6; expired; Redis down; social state sai | Không login; fail closed; secret không trong Kafka/log/DB |
| U05 | Publish thiếu ảnh/variant; SKU duplicate; collection hết hạn | Validation đúng; storefront chỉ active; đơn cũ vẫn giữ snapshot |
| U06 | Nhiều item cùng giá, pagination và filter đổi | Không duplicate ở cursor cùng dataset; cursor sai filters bị reject; giá filter theo effective price Phase 2 |
| U07 | 360px, keyboard-only, đổi VI/EN, ảnh lỗi, API error | Có label/focus/error/retry, tổng VND nhất quán; không scroll ngang flow checkout |
| U08 | Guest review, member chưa giao, review lần hai, edit ngày 8 | Reject đúng; review hợp lệ PENDING; APPROVED mới hiển thị |
| U09 | available 0→1 do restock/release, send event replay, subscribe generation mới | Tối đa một gửi/user/SKU/ngày; inventory nhận result; không gửi hết hạn/đã inactive |
| U10 | Guest cart 30 ngày inactivity; cache flush; item sửa thiết bị khác | DB giữ khi chưa expire; expired không khôi phục credential; version conflict hiện UI |
| U11 | Guest COD, phí SELF hợp lệ, OPS confirm/pack/handover/deliver/thu/settle | Kho commit một lần; SHIPPING sau bàn giao; tiền chỉ SUCCESS theo settlement |
| U12 | Online thanh toán mỗi cổng, return trước webhook hoặc đóng tab | UI đọc server, không success giả; eventual PAID sau commit; resume đúng order |
| U13 | Cancel khi PENDING chuẩn bị, WAITING_PAYMENT, CONFIRMED, PACKING, SHIPPING | Chỉ action được phép; PENDING khách chưa có cancel action B1; admin/cancel race theo flow; SHIPPING return |
| U14 | CSV có dòng sai, duplicate key import, adjust < reserved | Không ghi một phần hoặc ghi hai lần; lý do/audit đầy đủ |
| U15 | available <= min_stock, job daily chạy lặp | Một cảnh báo/SKU/ngày, không trùng notify |
| U16 | Signature/amount/merchant/currency sai, query và webhook đồng thời | Không SUCCESS với bằng chứng sai; một effect với bằng chứng đúng |
| U17 | COD gross 500k, fee 20k, net 480k; net thiếu; statement replay | Khớp nghĩa vụ gross khi đúng evidence; thiếu tiền discrepancy; không settle hai lần |
| U18 | Per-user voucher guest; campaign overlap; end campaign lúc đã lock; discount làm total 0 | D11/D12 guard; overlap bị chặn; commit lock hợp lệ; zero-total reject rõ |
| U19 | Carrier FAILED rồi delivery retry; event cũ; create UNKNOWN rồi cancel | Edge guards đúng; không rank-only; không restore khi chưa rõ bàn giao |
| U20 | Notification sender crash sau ACK, SENDING hết lease, template thay giữa retry | Query/dedupe hoặc UNKNOWN; snapshot cũ dùng nhất quán; không claim đã đến inbox |
| U21 | Marketing opt-out sau enqueue trước send; replay unsubscribe; transactional mail | Marketing SUPPRESSED; unsubscribe idempotent; transactional vẫn gửi; không lộ secret |
| U22 | Admin double-click refund/return/action, version stale | Một operation; 409 khi stale/body mismatch; không vượt kho/tiền |
| U23 | Report ngày theo Asia/Ho_Chi_Minh qua mốc UTC; COD chưa về, partial refund | Orders/cash/stock tách nguồn; không gọi GMV là cash; CSV escaped |
| U24 | Cookie request cross-origin, guessed order/cart, upload giả type hoặc người khác | CSRF/ownership/type guard; không XSS/object takeover |
| U25 | Restore DB khác thời điểm, replay event cũ, outstanding UNKNOWN | Đối soát resource/provider trước mở traffic; business keys chặn lặp; RPO/RTO có mốc đo |
| U26 | Retry POST orders sau quote expiry nhưng key đã tạo đơn; mới body cùng key | Đơn cũ được trả; khác body 409; không gia hạn reservation |
| U27 | Outbox hai event cùng version, worker chết/lease cũ ghi kết quả; WAITING đến deadline | aggregate_sequence ổn định; stale lease CAS fail; work đến hạn được tiếp tục |

U26/U27 thuộc ORD-01/02 + PLT-03, bắt buộc G2.

## 4. Fixture và test data

- Synthetic users: guest A/B, member A/B, OPS, FINANCE, MARKETING, SUPER_ADMIN và revoked admin. Không copy PII production.
- Catalog: active/inactive, override price, 2 size × 2 màu, weight khác nhau; SKU_LAST=1, SKU_EMPTY=0; cart ít nhất hai SKU.
- Money: giá nguyên đồng, % rounding boundary, large value overflow, full/partial concurrent refund, total=0 rejection.
- Time: quote trước/sau 2 phút, reservation trước/sau 15 phút/24 giờ, campaign [start,end), review ngày 30/edit ngày 7. Clock injectable ở application; DB expiry test dùng deadline phù hợp DB clock, không mock để bỏ row-lock race.
- Provider stub: success/failure/timeout sau effect/duplicate/late/out-of-order/wrong signature/wrong amount. Có merchant reference ổn định và query để quan sát actual effect.
- Faults: crash trước network, sau remote commit trước response, sau local commit trước Kafka ACK, sau publish trước SENT, worker cũ sau hết lease.
- Mỗi test đối chiếu ledger/reservation/payment/refund/outbox, không chỉ HTTP status.

## 5. Load profiles và SLO

| Profile | Khi dùng | Tải và dataset | Điều kiện |
|---|---|---|---|
| L1 | G2 | 100 concurrent, 10 phút warm + 30 phút đo; 70% browse/15% cart/10% checkout/5% callback; >= 1.000 SKU synthetic | Provider stub normal và kịch bản hot SKU 1 unit riêng; p95 acceptance < 2s, catalog < 300ms; invariant tuyệt đối |
| L2 | G3 | 1.000 concurrent, warm 10 + đo 30 phút, thêm voucher/campaign + carrier stub | Không oversell/quota leak, đo cùng NFR và saturation |
| L3 | G4 | Ramp tới 10.000 concurrent, workload/sizing được duyệt O09; soak >= 60 phút | Ghi throughput thực, error budget và cost; Kafka 10k event/s benchmark riêng |

Concurrency không quy đổi sẵn thành RPS. Report ghi think time, connection pools, CPU/RAM/DB sizing, replica/partition count, seed, lượng dữ liệu, cache condition, generator bottlenecks, provider latency. Nếu test máy nhỏ không đạt profile, ghi giới hạn và gate chưa pass, không suy production sẽ tự đạt.

Checkout acceptance đo từ gateway nhận POST đến commit durable order + response 201/202. Payment readiness đo từ durable order đến URL/COD ready, không tính thời gian khách thanh toán. Đếm cả đơn nhận 202 nhưng chưa ready quá ngưỡng; không bỏ chúng khỏi report để làm đẹp latency.

Success ratio: mẫu số các checkout hợp lệ, quote còn hạn và có đủ stock/quota ở kịch bản đủ hàng; tử số đạt trạng thái sẵn sàng trong deadline và không mất/nhân đôi order do lỗi hệ thống. Báo riêng valid attempts, hệ thống fail/timeout, business reject, provider decline, customer abandon. Hot-SKU có out-of-stock hợp lệ không được tính như oversell hoặc tính thành system success đủ hàng.

Availability theo 30 ngày production: good requests / eligible critical requests, có synthetic probes; 5xx và timeout tính bad, scheduled maintenance không tự loại. 4xx hợp lệ nghiệp vụ tách riêng. Staging load test không chứng minh 99.9% uptime tháng.

## 6. Gate và mức độ lỗi

Sev1: mất/nhân đôi tiền, oversell, lộ credential/PII, sai quyền tài chính. Sev2: không mua được/hủy được/phục hồi được, sai tổng, kho kẹt không có recovery. Sev3: lỗi phụ có workaround; Sev4: cosmetic. G2/G3 không có Sev1/Sev2 mở; Sev3 cần owner/deadline và PO chấp nhận.

G2: P0 coverage, T01–T12/T16–T19/T21–T24/T26/T27, T15 SELF, T20 refresh, T25 DB/cache/auth; Uxx phần MVP, provider sandbox, load L1 và restore pass. T13/T14, T20 social, T25 OTP/promotion, Uxx Phase 2 thuộc G3. Không xem test chưa applicable là đã pass.

Mẫu report: build/commit; env/version; REQ/TASK/test ID; fixture; steps; expected/actual; pass/fail; trace/log đã che; invariant query result; người chạy/ngày; defects/waivers. Coverage là khả năng truy vết, không chỉ % dòng code.
