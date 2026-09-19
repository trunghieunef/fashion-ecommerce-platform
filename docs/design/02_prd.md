# 02 — Product Requirements Document

B1 · 2026-09-19 · Chủ trì PO + TL. Baseline đồng bộ 03–08; giả định kinh doanh xem 08.

## 1. Phạm vi và quy ước

Sản phẩm một shop, một kho logic, VI/EN, VND, guest/member. Release 1 gồm MVP (Phase 1) và tính năng Phase 2. P0 = bắt buộc MVP; P1 = bắt buộc hoàn thiện Release 1; P2 = sau Release 1. Quy ước này thay cách dùng ưu tiên chưa nhất quán trong bản cũ.

Giữ mã yêu cầu cũ để traceability; mã mới bổ sung không đổi nghĩa mã cũ. API chỉ định nghĩa ở [03](03_interfaces.md), schema ở [05](05_database_design.md), state machine/transaction ở [06](06_service_flows.md), UI ở [14](14_frontend_behavior.md). Mã trong bảng dưới là REQ; mã task trong backlog có namespace TASK riêng.

## 2. Người dùng và hành trình

| Vai trò | Hành trình / quyền |
|---|---|
| Guest | Duyệt → giỏ có credential → quote → checkout → tra cứu/hủy đơn bằng credential đúng đơn |
| Member | Như guest; thêm đồng bộ giỏ, địa chỉ, lịch sử, wishlist/review/restock Phase 2 |
| OPS | CRUD catalog, nhập/điều chỉnh kho, đóng gói, SELF, hủy trước bàn giao, kiểm đếm hàng hoàn |
| FINANCE | Tra cứu tiền, refund, COD settlement, xử lý lệch; không tự sửa kho |
| MARKETING | Voucher/campaign, template và marketing consent |
| SUPER_ADMIN | Cấp quyền theo permission; không bỏ qua invariant nghiệp vụ |
| Dev/SRE | Deploy, monitoring, recovery theo runbook; không sửa tiền bằng SQL thủ công |

US-001 duyệt/lọc và US-002 chi tiết size/màu/ảnh thuộc P0; phần review của US-002 thuộc P1. US-003 guest checkout, US-004 merge/đồng bộ giỏ, US-006 xử lý đơn thuộc P0. US-005 restock và US-007 flash sale thuộc P1.

## 3. Yêu cầu chức năng

### 3.1 User

| REQ | Priority / phase | Acceptance nghiệp vụ |
|---|---|---|
| USR-01 | P0 / 1 | Email lowercase unique; password >= 8 ký tự, BCrypt cost >= 10 theo baseline, không log password; race email trả lỗi an toàn |
| USR-02 | P1 / 2 | OTP 6 số, 5 phút, tối đa 5 thử/challenge và 5 lần gửi/ngày/phone; rate limit IP; resend vô hiệu code cũ; không lưu plaintext bền vững |
| USR-03 | P0 / 1 | Email/password; sai 5 lần khóa 15 phút; reset 30 phút dùng một lần, thông báo không lộ email tồn tại |
| USR-04 | P1 / 2 | Google/Facebook kiểm tra state/nonce và provider token; không auto-merge chỉ vì email trùng |
| USR-05 | P0 / 1 | Access 15 phút, refresh 30 ngày; rotate atomic; replay revoke family; đổi password revoke sessions |
| USR-06 | P0 / 1 | Hồ sơ VI/EN; nhiều địa chỉ, đúng một mặc định khi có địa chỉ; shipping ánh xạ mã địa chỉ theo carrier |
| USR-07 | P0 / 1 | Guest có contact/địa chỉ và credential; biết order_no/cart_id không đủ truy cập |
| USR-08 | P0 / 1 | Seed roles/permissions, kiểm tra tại service, audit thay đổi; thu hồi admin áp dụng ở request nhạy cảm tiếp theo |
| USR-09 | P1 / 2 | Email verification gắn target_email, token một lần; cần trước voucher per-user nếu chưa có phone verified |

### 3.2 Catalog

| REQ | Priority / phase | Acceptance |
|---|---|---|
| CAT-01 | P0 / 1 | CRUD, publish/unpublish, HTML sanitize; không hard-delete sản phẩm/SKU đã công bố |
| CAT-02 | P0 / 1 | SKU bất biến/unique và product-size-color unique; override giá và weight > 0; VARIANT_CREATED tạo kho 0 một lần |
| CAT-03 | P0 / 1 | Collection nhiều sản phẩm, thứ tự, cover/lookbook VI/EN, khoảng hiệu lực |
| CAT-04 | P0 / 1 | Search ILIKE; lọc category/brand/size/color/giá/tag; cursor theo sort value + id; NEWEST/BEST_SELLING/PRICE_ASC/PRICE_DESC |
| CAT-05 | P0 / 1 | Ảnh nhiều góc, zoom, biến thể và tồn có timestamp; tồn hiển thị không giữ hàng; review UI bật Phase 2 |
| CAT-06 | P0 / 1 | Size guide theo category/locale, HTML và JSON được kiểm tra |
| CAT-07 | P1 / 2 | ORDER_COMPLETED cấp eligibility 30 ngày; 1 order × variant = 1 review; <= 5 ảnh, rating 1–5; edit 7 ngày đưa lại PENDING; OPS duyệt |
| CAT-08 | P1 / 2 | Link subscribe khi hết available; inventory sở hữu subscription |
| CAT-09 | P0 / 1; campaign P1 / 2 | Invalidate bền vững + TTL; inventory version cũ không ghi đè; BEST_SELLING từ ORDER_COMPLETED chống cộng lại từ MVP |
| CAT-10 | P1 / 2 | Member wishlist PUT/DELETE/list idempotent; không tự giữ hàng |
| CAT-11 | P0 / 1 | Upload giới hạn loại/kích thước, kiểm tra ownership object, ảnh alt VI/EN; không nhận URL nội bộ tùy ý để fetch |

### 3.3 Cart

| REQ | Priority / phase | Acceptance |
|---|---|---|
| CART-01 | P0 / 1 | Guest/member lưu PostgreSQL; Redis cache; guest token hash, 30 ngày từ lần hoạt động |
| CART-02 | P0 / 1 | Qty 1–99/SKU, kiểm tra catalog/tồn tham khảo; optimistic version chống lost update |
| CART-03 | P0 / 1 | Merge guest→member xác minh cả hai, lock thứ tự, chỉ cộng một lần; cap qty/tồn và báo các item bị điều chỉnh |
| CART-04 | P0 / 1 | Thiết bị đọc cùng active cart; version conflict yêu cầu refresh |
| CART-05 | P0 / 1; voucher P1 / 2 | Preview không giữ kho/quota; khi chưa tính được phí không hiện tổng như đã chốt |
| CART-06 | P0 / 1 | Cleanup theo snapshot/version/qty, key order_id; giữ item đã sửa; retry không trừ hai lần |

### 3.4 Order

| REQ | Priority / phase | Acceptance |
|---|---|---|
| ORD-01 | P0 / 1 | Quote từ server, hạn 2 phút theo baseline; gắn actor/cart/version/address/method; checkout lưu snapshot bất biến |
| ORD-02 | P0 / 1 | Durable saga intent/step/resource state/lease; chỉ chuyển cạnh 06; history cùng TX |
| ORD-03 | P0 / 1 | Online hạn 15 phút, COD 24 giờ từ tạo; retry không gia hạn; race commit/expire có một kết quả |
| ORD-04 | P0 / 1 | Khách hủy WAITING_PAYMENT hoặc CONFIRMED; OPS trước bàn giao; CANCELLING đến khi bù trừ đủ; sau bàn giao return/kiểm đếm |
| ORD-05 | P0 / 1 | Member chỉ đơn mình; guest credential đúng đơn; danh sách cursor, tracking và trạng thái refund riêng |
| ORD-06 | P0 / 1 | OPS xác nhận COD/packing; SHIPPING chỉ trên bằng chứng handed_over_at; không set status tùy ý |
| ORD-07 | P0 / 1 | 201 sẵn sàng, 202 có order/status URL khi đang chuẩn bị; retry cùng key trả cùng order; khác body 409 |
| ORD-08 | P0 / 1 | Tiền muộn sau đóng/hết reserve → refund toàn bộ; không mở lại đơn hoặc giữ kho lại |
| ORD-09 | P0 / 1 | Lưu durable cart cleanup độc lập saga fulfill; lỗi cleanup không hủy đơn |
| ORD-10 | P0 / 1 | Đổi trả sau giao qua OPS, evidence theo policy O05; giữ lịch sử DELIVERED; không tự refund khi nhận webhook return |

Tổng: subtotal - discount_amount + shipping_fee - shipping_discount + cod_fee = total_amount. Tiền nguyên VND, discount không vượt subtotal/fee; làm tròn giảm % xuống đồng; kiểm tra tràn số. Baseline chưa nhận đơn total <= 0 (O05). Giá campaign đã nằm trong unit_price thì không trừ lần hai. Client không gửi amount có quyền quyết định.

Giá niêm yết trong baseline là giá dùng tính tiền, chưa có dòng cộng thuế riêng. PO/FINANCE chốt cách công bố thuế và hóa đơn trong O05/O06 trước khóa ORD-01; yêu cầu xuất hóa đơn/tax breakdown cần cập nhật hợp đồng và task riêng, không mặc định một email xác nhận là hóa đơn hợp lệ.

### 3.5 Inventory

| REQ | Priority / phase | Acceptance |
|---|---|---|
| INV-01 | P0 / 1 | available = on_hand - reserved; 0 <= reserved <= on_hand; mọi thay đổi có ledger |
| INV-02 | P0 / 1 | Reserve tất cả SKU một TX inventory-db; thiếu một SKU rollback toàn bộ |
| INV-03 | P0 / 1 | Chỉ order gọi commit/release; commit giảm cả on_hand/reserved; release chỉ reserved; terminal không mở lại |
| INV-04 | P1 / 2 | available 0→dương phát restock; inventory fan-out, notification gửi, result cập nhật subscription; <= 1/user/SKU/ngày |
| INV-05 | P1 / 2 | Batch cảnh báo available <= min_stock với dedupe SKU/ngày |
| INV-06 | P0 / 1 | PostgreSQL atomic/row locks và constraints quyết định; Redis không xác nhận bán được |
| INV-07 | P0 / 1 | Return hàng đã commit có evidence, tổng trả <= đã commit, operation key ngăn cộng lại |
| INV-08 | P0 / 1 | Nhập/adjust có lý do/key/audit; <= 1.000 dòng CSV, validate trước ghi; không hạ on_hand dưới reserved |

### 3.6 Payment

| REQ | Priority / phase | Acceptance |
|---|---|---|
| PAY-01 | P0 / 1 | VNPay create/query/IPN/refund qua adapter xác minh ở 15; reference cố định |
| PAY-02 | P0 / 1 | MoMo create/query/notify/refund qua adapter xác minh ở 15; callback cùng hàm apply verified result |
| PAY-03 | P0 / 1 | COD_PENDING → COD_COLLECTED theo bằng chứng thu → SUCCESS khi FINANCE xác nhận tiền về; không suy từ DELIVERED |
| PAY-04 | P0 / 1 | Verify signature/merchant/reference/amount/currency; TX payment+transaction+outbox trước ACK; browser return chỉ đọc |
| PAY-05 | P0 / 1 | Refund full/partial; lock payment, reserved + refunded <= amount; UNKNOWN giữ hạn mức; lỗi không xóa nghĩa vụ |
| PAY-06 | P0 / 1 tối thiểu; P1 / 2 tự động | Query UNKNOWN có worker, manual discrepancy/settlement có audit từ MVP; batch provider hằng ngày hoàn thiện Phase 2 |
| PAY-07 | P0 / 1 | Một payment/order, business key suốt vòng đời; timeout không tạo reference khác |
| PAY-08 | P0 / 1 | Close-before-create lưu tombstone/closed_at; late success vẫn ghi nhận tiền và báo order refund; close không mở lại pay URL |

### 3.7 Promotion

Toàn bộ PRO-01–05 là P1 / Phase 2. MVP discount = 0, không hiển thị voucher khả dụng.

| REQ | Acceptance |
|---|---|
| PRO-01 | Voucher PERCENT/FIXED/SHIPPING, min subtotal, scope SKU, ALL/MEMBER, hạn thời gian và usage/per-user limit |
| PRO-02 | Quote preview; lock/commit/release cả promotion aggregate trong PostgreSQL; tombstone release-before-lock |
| PRO-03 | Flash sale giữ quota độc lập physical stock; reserved + sold <= limit; order bù trừ khi một bên fail |
| PRO-04 | Campaign price, PRICE_CHANGED start/end; API tự kiểm tra thời gian, không phụ thuộc scheduler đúng giờ |
| PRO-05 | Shipping discount <= shipping_fee; không giảm cod_fee ngầm; D11/D12 áp dụng |
| PRO-06 | Không campaign chồng lịch cùng SKU; không sửa giá đang có reservation; commit quota hợp lệ sau end nếu còn reserve |

### 3.8 Shipping

| REQ | Priority / phase | Acceptance |
|---|---|---|
| SHP-01 | P0 / 1 | SELF fee rules theo vùng/weight; quote có fee/cod_fee/carrier/expiry; fallback phải cấu hình và khách xác nhận |
| SHP-02 | P0 / 1 SELF; P1 / 2 carrier | Order tạo shipment khi PACKING và ready; một order một shipment; UNKNOWN query trước retry |
| SHP-03 | P1 / 2 | GHN/GHTK/VTPL adapter verify riêng; event key/sequence/timestamp và edge guards, không chỉ rank status |
| SHP-04 | P0 / 1 | SELF gán người giao, audit bằng chứng bàn giao/giao/thu COD; cùng invariant carrier |
| SHP-05 | P2 / 3+ | So sánh carrier thông minh khi có yêu cầu/metric |
| SHP-06 | P0 / 1 | Cancel-before-create tombstone; chỉ restore khi chứng minh chưa giao; RETURNED cần OPS kiểm đếm trước cộng kho |

### 3.9 Notification

| REQ | Priority / phase | Acceptance |
|---|---|---|
| NOT-01 | P0 / 1 | Email giao dịch từ notification.events, recipient/template snapshot, dedupe; retry lỗi chắc chắn tối đa 5 |
| NOT-02 | P1 / 2 | OTP queue ưu tiên; retry lỗi chắc chắn tối đa 3, không gửi expired, không plaintext Kafka/DB/log |
| NOT-03 | P1 / 2 | Marketing opt-in và unsubscribe scoped token idempotent; không tắt email giao dịch |
| NOT-04 | P2 / 3+ | In-app/websocket khi triển khai Phase 3 |
| NOT-05 | P0 / 1 seed; P1 / 2 editor | Template VI/EN, fallback vi, escape nội dung; admin preview/version |
| NOT-06 | P0 / 1 | Lease recovery SENDING, query/dedupe provider; UNKNOWN khi không xác minh; SENT nghĩa provider đã nhận |

### 3.10 Admin và xuyên suốt

| REQ | Priority / phase | Acceptance |
|---|---|---|
| ADM-01 / ADM-02 / ADM-03 | P0 / 1 | Catalog/đơn/kho, filter, phân trang, hành động theo quyền và trạng thái |
| ADM-04 | P1 / 2 | Voucher/campaign/flash sale qua MARKETING |
| ADM-05 | P1 / 2 | Dashboard đơn/doanh số/tồn và CSV; tách tiền đã thu, COD chưa về, refund; không join DB xuyên service |
| ADM-06 | P0 / 1 | Users/roles bởi SUPER_ADMIN; permission enforcement từ MVP |
| ADM-07 | P0 / 1 collection; P1 / 2 nội dung marketing | Lookbook/collection thuộc catalog; chưa thêm CMS service |
| ADM-08 / XCT-03 | P0 / 1 | Audit append-only admin và thao tác tiền/kho; before/after đã lọc PII |
| XCT-01 | P0 / 1 | VI/EN UI + template, UTC lưu trữ, Asia/Ho_Chi_Minh hiển thị |
| XCT-02 | P0 / 1 | Rate limiting, ownership, CSRF với cookie, service identity và route private |
| XCT-04 | P2 / 3+ | Funnel analytics/A/B; business metrics tối thiểu vẫn có Phase 2 |
| XCT-05 | P0 / 1 | Accessibility cơ bản: keyboard/focus/label/error; mobile 360px đến desktop; không lộ internal state nhạy cảm |
| XCT-06 | P0 / 1 | Secret/PII policy và incident/restore theo 13, O06 trước launch |

## 4. Hành trình giao dịch chuẩn

Online: quote → order PENDING durable → reserve → lock promotion nếu bật → create payment → WAITING_PAYMENT → verified payment → commit kho/promotion → PAID → OPS PACKING → carrier/SELF bàn giao SHIPPING → DELIVERED.

COD: quote → reserve + COD_PENDING → CONFIRMED chờ OPS → commit kho/promotion → PACKING → SHIPPING → DELIVERED. Thu tiền và tiền về là các bằng chứng riêng.

Hủy: ghi CANCEL intent → xác minh/chặn shipment → release hoặc restore kho đúng trạng thái → release promotion → close payment → CANCELLED khi không còn tiền cần hoàn, hoặc REFUNDING → REFUNDED. Sau bàn giao đi RETURNING/RETURNED. Chi tiết cạnh và race ở 06; không có state machine thứ hai tại PRD.

## 5. NFR và nghiệm thu

| ID | Mục tiêu | Phạm vi đo |
|---|---|---|
| NFR-01 | Availability >= 99.9%/tháng | Public critical APIs ở production; synthetic probe + server metrics |
| NFR-02 | Catalog p95 < 300 ms, cache hit >= 90% | Cache warm, bộ query đã thống nhất |
| NFR-03 | Checkout acceptance p95 < 2 s | HTTP vào gateway đến order durable 201/202; không tính thời gian khách trả tiền |
| NFR-04 | Payment readiness p95 <= 5 s trong test provider bình thường | Đo riêng; UNKNOWN không tính thành công giả; timeout budget theo adapter |
| NFR-05 | >= 99.5% checkout đủ điều kiện không lỗi hệ thống | Có đủ stock, valid quote, hợp lệ auth; báo riêng out-of-stock/khách bỏ/provider fail |
| NFR-06 | RPO <= 15 phút, RTO <= 1 giờ | Diễn tập restore + đối soát money/event, không chỉ DB startup |
| NFR-07 | Không oversell/over-refund/double side effect | Điều kiện bắt buộc mọi phase và mọi bộ tải |
| NFR-08 | LCP mục tiêu < 2.5 s, p75 | Thiết bị/mạng đại diện O09; nguồn dữ liệu và profile được ghi trong report |
| NFR-09 | Các API tương tác thông thường p99 < 1 s | Loại quote/checkout/provider jobs/upload/export khỏi nhóm; có metric riêng |
| NFR-10 | Định hướng 10k event/s, 10k concurrent sau scale | Phase 3, không phải cam kết MVP hoặc suy từ số replica |

Test profiles, exclusions, timeout và release gates ở 11. Không có kết quả load test hiện tại. Tính năng phụ lỗi không chặn checkout; pricing/voucher lỗi không được tự tăng tiền hoặc bỏ ưu đãi đã xác nhận. Kafka outage có thể làm chậm saga; ngưỡng backlog vượt budget phải dừng nhận checkout mới an toàn.

## 6. Kế hoạch và traceability

[09 Delivery](../delivery/09_delivery_plan.md) quản lý milestone/capacity, [10 Backlog](../delivery/10_backlog.md) quản lý việc cần giao, [11 Test](../quality/11_test_strategy.md) nối requirement → task → test. Phải chốt policy tương ứng trước khóa acceptance; có thể mock và làm phần không phụ thuộc ngay.

Business metrics: orders/day, AOV, GMV theo đơn giao, captured cash, COD outstanding, refunds và cancellation/return rate. Không coi ORDER_PAID hay DELIVERED là báo cáo kế toán đã đối soát; định nghĩa báo cáo ở task ADM-03.
