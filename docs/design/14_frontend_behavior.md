# 14 — Đặc tả hành vi Storefront và Admin

B1 · 2026-09-19 · Owner FE + PO. Tài liệu chức năng để chia task UI; chưa có mockup hoặc design system đã duyệt.

## 1. Quy tắc chung

Responsive 360px đến desktop, VI/EN, tiền VND từ server. UTC lưu, hiển thị Asia/Ho_Chi_Minh. Loading/empty/error/retry và permission state là phần acceptance mỗi màn. Keyboard, visible focus, label, error liên kết field, contrast và alt text được kiểm tra trong U07.

Public amount/tổng tiền là dữ liệu render; FE chỉ gửi lựa chọn/quantity/quote token. Refresh token ở cookie, access token in-memory. POST cookie có CSRF/origin protection. Double-click submit/cancel/refund phải dùng cùng idempotency key; disabled button chỉ hỗ trợ UX, không thay server dedupe.

## 2. Màn hình Storefront

| ID / màn | Phase | Thành phần / dữ liệu | Acceptance / task |
|---|---|---|---|
| SF-01 Home/collection | 1 | Collection active, lookbook, ảnh CDN, VI/EN | Ảnh lỗi có fallback/alt; link đúng slug; WEB-01 |
| SF-02 Listing/search | 1 | Filters/sort/cursor, empty/no results | Giữ filter URL, đổi filter reset cursor; không trộn trang giá cũ; WEB-01 |
| SF-03 Product detail | 1 | Variant size/màu, ảnh/zoom, giá, size guide, available/observed_at | Chọn variant trước add; out-of-stock/unknown rõ; không cam kết hàng đã giữ; WEB-01 |
| SF-04 Account/auth/address | 1 | Login/register/forgot/reset/profile/address/default | Refresh single-flight, validation, không tiết lộ account qua forgot; WEB-02 |
| SF-05 Cart | 1 | Items/qty, version, giá tham khảo, estimate fee | 409 refresh và báo điều chỉnh; merge không âm thầm bỏ item; WEB-02 |
| SF-06 Checkout quote | 1 | Contact/address/method, server quote và expiry | Khách xác nhận tổng; sai giá/fee phải confirm lại; không đặt tự động; WEB-03 |
| SF-07 Preparing/payment | 1 | order_no, deadline, processing/status_url/URL | 202 đang xử lý; poll theo Retry-After; không tạo lại order khi timeout; WEB-03 |
| SF-08 Order detail/history | 1 | Status history, snapshot, tracking, allowed_actions, refund state | Guest cookie đúng đơn, member history; mất credential không expose phone lookup; WEB-04 |
| SF-09 Review/wishlist | 2 | Eligible items, upload <= 5 ảnh, edit deadline, moderation state | Chỉ member eligible; thay edit về PENDING; CAT-04 |
| SF-10 Restock | 2 | Subscription/generation, subscribe/unsubscribe | Không biến subscription thành reserve; NOT-02 |
| SF-11 Voucher/flash sale | 2 | Server time/end, quote price, eligibility/error | D11/D12 báo rõ, không tự bỏ voucher; PRO-02 |
| SF-12 OTP/social/verify | 2 | Challenge expiry, resend, social link confirmation | Không auto-merge email; USR-03/04 |
| SF-13 Unsubscribe | 2 | Scoped token submit và preference result | Không cần cấp quyền account; NOT-03 |

Lookbook/size guide và giá hiển thị có VI/EN nội dung từ O10. Nếu chưa có content thật, fixture phải đánh dấu rõ; release gate không chấp nhận dummy.

## 3. Checkout và khôi phục trình duyệt

1. Cart preview chỉ tham khảo. Gửi cart_id/version/address/contact/method lấy quote.
2. Render item snapshot và breakdown subtotal/discount/ship/shipping discount/COD fee/total. Chỉ cho xác nhận khi quote còn hạn; hết hạn lấy quote mới.
3. Giữ một Idempotency-Key cho một lần xác nhận. Submit quote_token/cart/version; khi response mất, retry cùng key/body.
4. 201 online có URL chuyển hướng allowlisted; 201 COD chuyển order detail. 202 hiện đang chuẩn bị, lưu order_no và poll. Không lưu secret vào localStorage/URL.
5. Trở lại từ provider luôn đọc GET order/payment. Callback query string không xác nhận payment success.
6. Khi tab hidden pause poll; khi resume fetch trạng thái; delay theo Retry-After, exponential backoff có cap khi network fail. Không tự sinh order mới khi countdown về 0.
7. Terminal hoặc ACTION_REQUIRED dừng poll dày, hiển thị hướng dẫn và link order. Guest cookie mất → hỗ trợ xác minh; không có public phone/order_no bypass.

409 PRICE_CHANGED/QUOTE_EXPIRED: highlight số cũ/mới nếu có, yêu cầu xác nhận lại. 409 VERSION_CONFLICT: reload cart, không ghi đè thay đổi thiết bị khác. 429: chờ; 503: thông báo thử lại nhưng giữ key của mutation chưa biết kết quả.

## 4. Trạng thái đơn, tiền và nội dung UI

| Backend state | Nội dung gợi ý | Thao tác khách |
|---|---|---|
| PENDING | Đang chuẩn bị đơn | Chờ, xem trạng thái; chưa mở thanh toán nếu URL chưa ready |
| WAITING_PAYMENT | Chờ thanh toán, hạn còn lại | Pay URL hợp lệ hoặc yêu cầu hủy theo allowed_actions |
| CONFIRMED (COD) | Đã tiếp nhận, chờ shop xác nhận | Hủy trước OPS confirm |
| PAID | Đã thanh toán, chờ đóng gói | Xem đơn; cần hỗ trợ thì liên hệ shop |
| PACKING | Shop đang đóng gói | Xem đơn; không hiển thị đã giao hãng |
| SHIPPING | Đã bàn giao để vận chuyển | Tracking |
| DELIVERED | Đã giao | Review Phase 2/đổi trả qua OPS theo policy |
| CANCELLING | Đang xử lý hủy | Chờ; chưa cam kết kho/tiền đã xong |
| CANCELLED | Đơn đã hủy | Đặt đơn mới bằng hành động chủ động |
| REFUNDING | Đang hoàn tiền | Xem tiến độ; thời hạn hiển thị theo policy đã chốt |
| REFUNDED | Đã xác nhận hoàn tiền | Xem thông tin hoàn |
| RETURNING / RETURNED | Đang hoàn hàng / shop đã nhận hàng hoàn | Tiền hoàn hiển thị trạng thái riêng |

Partial refund sau DELIVERED không đổi nhãn đơn thành đã hủy. COD: “đã giao”, “đã thu từ khách”, “tiền về shop” là ba mốc riêng; khách không cần thấy các từ saga/lease/UNKNOWN. FE hiển thị “đang xác minh” và support reference an toàn.

## 5. Admin theo vai trò

| ID / màn | Role | Nội dung và điều kiện | Task |
|---|---|---|---|
| AD-01 Catalog/media/collections | OPS | Draft/publish, variants/weight, image validation, size guides; version conflict | ADM-01 |
| AD-02 Stock/ledger/import | OPS | on_hand/reserved/available tách rõ; reason bắt buộc; CSV errors từng dòng | ADM-01 |
| AD-03 Order work queue | OPS | Filter status/time, COD confirm, online pack, cancel before handover | ADM-02 |
| AD-04 Shipping/SELF/return | OPS | Evidence handover/delivery, received/restock/damaged, không cộng kho từ webhook | ADM-02 |
| AD-05 Payments/refunds | FINANCE | Captured/refunded/reserved/available amount; key/reason/evidence; processing/unknown | ADM-02 |
| AD-06 COD/reconciliation | FINANCE | Gross, phí, net, statement ref, discrepancy; không force matched | ADM-02 |
| AD-07 Users/roles | SUPER_ADMIN | Least privilege, revoke effective, audit | ADM-01 |
| AD-08 Voucher/campaign | MARKETING | Limits, thời gian, D11/D12, overlap conflict | ADM-03 |
| AD-09 Review moderation | OPS | Eligibility/images, approve/reject reason | CAT-04 |
| AD-10 Template/marketing | MARKETING | Preview VI/EN, placeholders, consent audience và unsubscribe | NOT-03 |
| AD-11 Reports | Theo domain | Order GMV / cash / COD outstanding / refunds / stock tách biệt | ADM-03 |

Admin confirm mutation có reason/evidence khi cần, không có free-form set status dropdown. UI chỉ render actions được phép; server vẫn enforce. UNKNOWN có next step hỗ trợ, không có nút “đánh dấu thành công” bỏ qua evidence.

## 6. Acceptance bàn giao FE

Mỗi screen có route/component owner, API operations, permission, validation, fixtures loading/empty/success/error/stale/forbidden, browser/mobile evidence và Uxx test. PO review nội dung nghiệp vụ; backend owner review contract. Mockup/design system thực tế được tạo trong task FE khi O10 đủ dữ liệu, không coi bảng này là visual design đã duyệt.
