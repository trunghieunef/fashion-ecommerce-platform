# 14 — Đặc tả hành vi Storefront và Admin

B1 · 2026-09-19 · Owner FE + PO. Tài liệu chức năng để chia task UI; chưa có mockup hoặc design system đã duyệt.

## 1. Quy tắc chung

Responsive 360px đến desktop, VI/EN, tiền VND từ server. UTC lưu, hiển thị Asia/Ho_Chi_Minh. Loading/empty/error/retry và permission state là phần acceptance mỗi màn. Keyboard, visible focus, label, error liên kết field, contrast và alt text được kiểm tra trong U07.

Public amount/tổng tiền là dữ liệu render; FE chỉ gửi lựa chọn/quantity/quote token. Refresh token ở cookie, access token in-memory. POST cookie có CSRF/origin protection. Double-click submit/cancel/refund phải dùng cùng idempotency key; disabled button chỉ hỗ trợ UX, không thay server dedupe.

## 2. Màn hình Storefront

| ID / màn | Phase | Thành phần / dữ liệu | Acceptance / task |
|---|---|---|---|
| SF-01 Home/collection | 1 | Collection active, lookbook, ảnh CDN, VI/EN | Ảnh lỗi có fallback/alt; link đúng slug; WEB-01 — `WEB-01` implemented local (`/`, `/collections/:slug`, "Tải thêm" theo cursor); `WEB-01b` implemented local: hero = collection đầu tiên (ảnh cover hoặc chữ), collection còn lại dạng ô, rail "Hàng mới"; header có mega-menu danh mục (desktop, mở bằng hover/focus, Esc đóng và trả focus), drawer menu (mobile), "Bộ sưu tập" chỉ tải khi mở |
| SF-02 Listing/search | 1 | Filters/sort/cursor, empty/no results | Giữ filter URL, đổi filter reset cursor; không trộn trang giá cũ; WEB-01 — `WEB-01` implemented local (`/products`, `/search`; nút "Tải thêm", cursor không lưu URL); `WEB-01b`: bộ lọc trong drawer áp dụng ngay qua URL (select khi đổi, ô chữ/số khi rời ô/Enter), chip xóa từng tham số, "Xóa tất cả" giữ `q`/`sort`, lưới 2/4 cột desktop lưu `localStorage` |
| SF-03 Product detail | 1 | Variant size/màu, ảnh/zoom, giá, size guide, available/observed_at | Chọn variant trước add; out-of-stock/unknown rõ; không cam kết hàng đã giữ; WEB-01 — `WEB-01` implemented local (`/products/:id`; nút thêm vào giỏ thuộc WEB-02); `WEB-01b`: gallery lưới (desktop) / vuốt (mobile), mọi ảnh mở zoom; panel dính; ô màu = thumbnail ảnh có `variant_color` trùng tên chính xác, không thì chữ; size không có trong màu đang chọn gạch ngang nhưng vẫn chọn được (không disabled, giữ sửa lỗi review 1 của WEB-01); bảng size trong drawer |
| SF-04 Account/auth/address | 1 | Login/register/forgot/reset/profile/address/default | Refresh single-flight, validation, không tiết lộ account qua forgot; WEB-02 — `WEB-02a` implemented local (Done local 2026-10-24): access token chỉ trong bộ nhớ, khôi phục phiên bằng một lần refresh, single-flight trong tab + Web Lock giữa tab, BroadcastChannel đồng bộ đăng nhập/xuất; `/login?next=` chỉ đường dẫn nội bộ; forgot luôn một câu; reset đọc token từ fragment rồi xóa; đổi mật khẩu buộc đăng nhập lại; địa chỉ dùng danh mục tỉnh/phường mẫu (O04) |
| SF-05 Cart | 1 | Items/qty, version, giá tham khảo, estimate fee | 409 refresh và báo điều chỉnh; merge không âm thầm bỏ item; WEB-02 — `WEB-02b` implemented local (Done local 2026-10-25): giỏ tạo lười ở lần thêm đầu; 409 tải lại + báo, không tự gửi lại, không xóa item ở client; key giữ qua retry; `/cart` số lượng 1–99 debounce 400 ms, đánh dấu dòng không bán/không đủ hàng, tạm tính chỉ khi `preview_status = OK`; gộp giỏ sau đăng nhập, 409 thử lại một lần, thất bại giữ giỏ guest; tên/ảnh dòng giỏ lưu ở client (API giỏ không trả), thiếu thì hiện SKU; ước tính phí ship chưa làm (WEB-03) |
| SF-06 Checkout quote | 1 | Contact/address/method, server quote và expiry | Khách xác nhận tổng; sai giá/fee phải confirm lại; không đặt tự động; WEB-03 — implemented local (chờ nghiệm thu): `/checkout` một trang, "Xem tổng tiền" lấy quote, sửa trường hoặc hết 2 phút → lấy lại, `PRICE_CHANGED` → quote mới + giá cũ → mới, khách bấm lại; checkout token guest chỉ trong bộ nhớ; ước tính phí trong giỏ không làm (D3) |
| SF-07 Preparing/payment | 1 | order_no, deadline, processing/status_url/URL | 202 đang xử lý; poll theo Retry-After; không tạo lại order khi timeout; WEB-03 — implemented local (chờ nghiệm thu): một key cho một lần xác nhận, lưu `sessionStorage` trước khi gửi, mạng/503/tải lại → cùng key + body; `/orders/:orderNo` poll khi `PROCESSING` (`retry_after_seconds`, dừng khi tab ẩn, backoff 30 s, dừng sau 2 phút); `/account/orders` |
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

Guest (ORD-01, 08 CART-03): trước quote gọi `POST /api/v1/carts/{cart_id}/checkout-token`, giữ `checkout_token` trong memory
(không localStorage/URL) và gửi header `Checkout-Token` cho quote và tạo đơn. `401 INVALID_CHECKOUT_TOKEN` (hết 15 phút): lấy
token mới rồi gửi lại **cùng** Idempotency-Key và body. Credential xem đơn của guest là cookie HttpOnly `order_access` do
server đặt (FE không đọc được); `409 CART_ALREADY_ORDERED` có `order_no` ở `errors[0].message` và với cùng guest kèm cookie
mới → chuyển tới order detail, không tạo đơn khác. Member dùng Bearer, không cần checkout token.

ORD-02: create luôn trả 202 (không có 201), FE poll GET đơn: `processing_status` `PROCESSING` (đang giữ hàng/tạo payment
hoặc đang hủy) → poll tiếp theo `retry_after_seconds`; `READY` (`CONFIRMED`, COD chờ shop xác nhận) và `FINISHED`
(`CANCELLED`, `reason_code` OUT_OF_STOCK/RESERVE_FAILED/PAYMENT_FAILED/EXPIRED) dừng poll. Hết hàng: giỏ được giữ nguyên, hướng
khách về giỏ để sửa rồi đặt lại. FE giữ `cart_id` + `cart_version` đã submit (sessionStorage được, không phải secret): mất
response và key, gửi lại nhận `409 CART_ALREADY_ORDERED` + `order_no` của đơn chưa hủy (08 ORD-02). Đơn saga `MANUAL` vẫn trả
`PROCESSING` không giới hạn: FE giới hạn poll (ví dụ dừng sau khoảng 2 phút), hiển thị "đơn đang được xử lý, shop sẽ liên hệ" và
link xem lại đơn, không tự tạo đơn mới.

ORD-03: sau khi OPS xác nhận, đơn `CONFIRMED` chuyển `processing_status` `PROCESSING` (shop đang trừ kho) rồi `PACKING`
(`PROCESSING` tới khi có vận đơn SELF, sau đó `READY`), `SHIPPING` `READY`, `DELIVERED` `FINISHED`; OPS hủy đơn chờ xác nhận →
`CANCELLED` với `reason_code` `OPS_CANCELLED` (hiển thị "shop đã hủy đơn", không lộ lý do nội bộ). Trang đơn của khách chỉ poll
khi `PROCESSING` (cùng giới hạn như trên); `READY`/`FINISHED` dừng poll, khách tải lại để xem tiến độ giao. `DELIVERED` không
có nghĩa shop đã nhận tiền COD (T27). Admin UI (ADM-01/02) gửi `expected_version` từ GET detail và giữ `Idempotency-Key` qua
retry; `409 VERSION_CONFLICT`/`INVALID_TRANSITION` → tải lại đơn (lỗi này không lưu theo key, retry cùng key được đánh giá
lại); `409 ORDER_EXPIRED` → đơn sắp bị hủy, không xác nhận được.

NOT-01: email đơn có nút `{storefront}/orders/{order_no}` (không token; member đăng nhập rồi xem, guest xem khi còn cookie `order_access`, không thì báo liên hệ shop — WEB-04 làm trang) và email reset có link `{storefront}/reset-password#token={secret}` (token trong fragment; WEB-02 đọc `location.hash`, xóa khỏi URL sau khi đọc, gửi `POST /api/v1/auth/password/reset`).

ORD-04: nút "Hủy đơn" của khách chỉ hiện khi `allowed_actions` chứa `CANCEL` (đơn `CONFIRMED` đang chờ OPS); FE gửi `expected_version` của GET, giữ `Idempotency-Key` qua retry, body `reason` tùy chọn. `202` → đơn `CANCELLING` (`PROCESSING`, poll như trên) rồi `CANCELLED` với `reason_code` `CUSTOMER_CANCELLED` ("bạn đã hủy đơn"). `409 INVALID_TRANSITION` (shop vừa xác nhận) → tải lại đơn và báo "đơn đã được shop xác nhận, vui lòng liên hệ shop để hủy"; `409 SAGA_BUSY`/`VERSION_CONFLICT` → tải lại rồi cho bấm lại; `404` → như mất credential. `RETURNING` (`READY`) hiển thị "đơn đang được hoàn về shop", không poll; `CANCELLING` với `READY` (hàng đã rời shop, đang chờ hoàn) hiển thị "đơn đang được hủy, hàng đang trên đường về shop", không poll; `RETURNED` (`FINISHED`) "shop đã nhận hàng hoàn" (tiền COD chưa thu nên không có hoàn tiền; đã thu thì đơn nằm ở xử lý của shop). Admin UI (ADM-01/02): phiếu kiểm đếm chọn `items[].order_item_id` từ GET detail, `restock_quantity` ≤ `received_quantity`, hiển thị lỗi `RETURN_EXCEEDS_ORDERED` theo `errors`; phiếu `MANUAL` có nút "Hủy phiếu" (`.../void`, bắt buộc lý do) rồi lập phiếu mới; `saga.step = WAIT_RETURN` (`WAITING`) nghĩa là hàng đã rời shop, đang chờ hàng về (không hứa hoàn kho).

409 PRICE_CHANGED/QUOTE_EXPIRED: highlight số cũ/mới nếu có, yêu cầu xác nhận lại. 409 VERSION_CONFLICT: reload cart, không ghi đè thay đổi thiết bị khác. 429: chờ; 503: thông báo thử lại nhưng giữ key của mutation chưa biết kết quả.

## 4. Trạng thái đơn, tiền và nội dung UI

| Backend state | Nội dung gợi ý | Thao tác khách |
|---|---|---|
| PENDING | Đang chuẩn bị đơn | Chờ, xem trạng thái; chưa mở thanh toán nếu URL chưa ready |
| WAITING_PAYMENT | Chờ thanh toán, hạn còn lại | Pay URL hợp lệ hoặc yêu cầu hủy theo allowed_actions |
| CONFIRMED (COD) | Đã tiếp nhận, chờ shop xác nhận | Hủy trước OPS confirm (nút hiện khi `allowed_actions` có `CANCEL`, ORD-04) |
| PAID | Đã thanh toán, chờ đóng gói | Xem đơn; cần hỗ trợ thì liên hệ shop |
| PACKING | Shop đang đóng gói | Xem đơn; không hiển thị đã giao hãng |
| SHIPPING | Đã bàn giao để vận chuyển | Tracking |
| DELIVERED | Đã giao | Review Phase 2/đổi trả qua OPS theo policy |
| CANCELLING | Đang xử lý hủy | Chờ; chưa cam kết kho/tiền đã xong |
| CANCELLED | Đơn đã hủy | Đặt đơn mới bằng hành động chủ động |
| REFUNDING | Đang hoàn tiền | Xem tiến độ; thời hạn hiển thị theo policy đã chốt |
| REFUNDED | Đã xác nhận hoàn tiền | Xem thông tin hoàn |
| RETURNING / RETURNED | Đang hoàn hàng / shop đã nhận hàng hoàn | Tiền hoàn hiển thị trạng thái riêng (ORD-04: `RETURNING` `READY` không poll, `RETURNED` `FINISHED`) |

Partial refund sau DELIVERED không đổi nhãn đơn thành đã hủy. COD: “đã giao”, “đã thu từ khách”, “tiền về shop” là ba mốc riêng; khách không cần thấy các từ saga/lease/UNKNOWN. FE hiển thị “đang xác minh” và support reference an toàn.

## 5. Admin theo vai trò

| ID / màn | Role | Nội dung và điều kiện | Task |
|---|---|---|---|
| AD-01 Catalog/media/collections | OPS | Draft/publish, variants/weight, image validation, size guides; version conflict; upload 409 UPLOAD_URL_EXPIRED khi mất upload_id thì tạo intent mới với Idempotency-Key mới, complete 429/409 PROCESSING thì retry/poll status; complete 504/timeout là UNKNOWN: poll GET status, không coi là thất bại (CAT-03) | ADM-01 |
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
