# WEB-03 — Storefront: checkout COD, báo giá server, đặt đơn và theo dõi đơn

`TASK:WEB-03` · `REQ:ORD-01/05/07, CART-05` · dependency WEB-02 (Done local 2026-10-25), ORD-01/02/03 (Done local).
Trạng thái: **đã duyệt** 2026-10-25 (chủ dự án). Theo quy ước làm việc, agent tự chốt các mặc định ở §8; chủ dự án duyệt hoặc sửa trước
khi viết plan. Giao diện theo WEB-01b (style C "Grotesk kiểu Thụy Sĩ", Tailwind) và các form của WEB-02a; hướng dẫn UX lấy từ
skill ui-ux-pro-max (§2.4).

## 1. Mục tiêu

SF-06 và SF-07 trên storefront: từ giỏ hàng, khách (guest hoặc member) nhập liên hệ và địa chỉ giao, chọn COD, xem **tổng tiền
do server báo giá**, xác nhận và đặt đơn. Sau khi đặt, trang đơn hiện trạng thái đang chuẩn bị (202 + poll) tới khi shop nhận
đơn (CONFIRMED) hoặc đơn bị hủy (hết hàng...). Member xem được danh sách đơn của mình.

Acceptance (10 dòng WEB-03, 14 §3):

- Khách xác nhận tổng do server tính; giá/phí đổi thì phải xác nhận lại; **không bao giờ tự đặt đơn**.
- Một lần xác nhận dùng một `Idempotency-Key`; mất response thì gửi lại **cùng key và body**, không tạo đơn thứ hai.
- 202 → poll theo `retry_after_seconds`; không coi redirect hay timeout là thành công hay thất bại.
- Lịch sử đơn của member; trình duyệt bị gián đoạn (tải lại, đóng tab, mất mạng) rồi quay lại vẫn tìm đúng đơn (test với mock).

Không làm:

- VNPay/MoMo (WEB-04, PAY-02/03).
- Trang chi tiết đơn đầy đủ: lịch sử trạng thái, tracking, nút "Hủy đơn", refund (SF-08 → WEB-04). Trang đơn ở WEB-03 chỉ
  hiện trạng thái hiện tại và snapshot (§5).
- Voucher, ước tính phí ship trong giỏ (§8, D3).
- Không đổi contract hay backend.

Anti-goals: tính tiền ở client; đặt đơn tự động khi báo giá hết hạn hoặc giá đổi; sinh key mới khi retry cùng thao tác; lưu
`checkout_token` vào storage/URL; thêm thư viện.

## 2. Giao diện

### 2.1 Route

| Route | Nội dung |
|---|---|
| `/checkout` | Một trang: liên hệ → địa chỉ giao → phương thức → tổng tiền + "Đặt hàng". Giỏ trống → về `/cart`. |
| `/orders/:orderNo` | Trạng thái đơn và snapshot (§5). Trùng link trong email NOT-01 (`{storefront}/orders/{order_no}`). |
| `/account/orders` | Danh sách đơn của member (`RequireAuth`), mới nhất trước, "Xem thêm" theo cursor. |

Trang giỏ: nút "Thanh toán" bật khi giỏ có ít nhất một dòng (server quyết dòng nào không bán được, §3.3), dẫn tới `/checkout`.
Header tài khoản: thêm mục "Đơn hàng của tôi".

### 2.2 Bố cục `/checkout`

- **Một trang, không wizard nhiều bước** (D1). Desktop (≥ `lg`): form bên trái, tóm tắt đơn bên phải dính (`sticky`) giống
  panel trang sản phẩm. Mobile (< `lg`, 360px): tóm tắt đầy đủ (dòng hàng, bảng tiền, nút chính) nằm **cuối trang** sau form,
  không có thanh dính (chủ dự án chọn phương án B trên mockup, 2026-10-25; D10).
- Guest: dòng đầu trang "Đã có tài khoản? Đăng nhập" (`/login?next=/checkout`; WEB-02b gộp giỏ sau đăng nhập).
- Các nhóm, mỗi nhóm là một `fieldset` có `legend`:
  1. **Liên hệ**: số điện thoại (bắt buộc), email (bắt buộc với guest; member điền sẵn email tài khoản).
  2. **Địa chỉ giao**: member có sổ địa chỉ → danh sách radio các địa chỉ đã lưu (địa chỉ mặc định chọn sẵn) + "Giao tới địa
     chỉ khác"; guest hoặc "địa chỉ khác" → form cùng trường với `AddressForm` (người nhận, điện thoại, tỉnh, phường, địa chỉ
     chi tiết; danh mục tỉnh/phường mẫu O04). Địa chỉ nhập ở checkout không tự lưu vào sổ (D5).
  3. **Phương thức**: chỉ COD ("Thanh toán khi nhận hàng"), chọn sẵn; ghi chú VNPay/MoMo sắp có (không hiện radio disabled).
  4. **Ghi chú** cho shop (tùy chọn, ≤ 500 ký tự).
- **Tóm tắt**: dòng hàng (ảnh, tên, màu · size, số lượng, thành tiền) và bảng tiền **chỉ từ báo giá server**: tạm tính, phí
  giao, phí COD, (giảm giá nếu > 0), **Tổng cộng**. Trước khi có báo giá: dòng hàng từ giỏ (giá tham khảo) và "Tổng tiền sẽ được
  tính khi bạn bấm Xem tổng tiền".

### 2.3 Hai nút, không tự đặt

- Nút chính đổi theo trạng thái: **"Xem tổng tiền"** (gọi quote) → khi có báo giá còn hạn: **"Đặt hàng · {tổng}"**.
- Sửa bất kỳ trường nào sau khi đã có báo giá → báo giá cũ bị bỏ, nút trở lại "Xem tổng tiền" (giá trị nhập giữ nguyên).
- Báo giá có hạn 2 phút: tóm tắt ghi "Giá giữ đến 10:42" (giờ tuyệt đối, không đếm ngược từng giây, không live region). Hết hạn
  → nút trở lại "Xem tổng tiền" và một câu `role="status"` "Báo giá đã hết hạn, bấm Xem tổng tiền để cập nhật." (D4).
- Nút bận (`aria-busy`, chặn bấm lặp bằng ref như WEB-02b) trong lúc gọi quote/đặt đơn.

### 2.4 UX (ui-ux-pro-max, domain `ux`)

Áp dụng các mục khớp từ cơ sở dữ liệu của skill:

- *Autofill Support*: `autocomplete` đúng (`tel`, `email`, `name`, `address-line1`; tỉnh/phường là select danh mục nên không
  autofill).
- *Form Labels*, *Error Placement*: label hiện rõ, lỗi dưới từng trường nối `aria-describedby` (`Field` của WEB-02a).
- *Focusable Error Summary*: submit lỗi → `ErrorSummary` đầu form nhận focus, link tới từng trường; giữ lỗi inline.
- *Loading Buttons*, *Submit Feedback*: nút bận + trạng thái kết quả rõ ràng (§3, §4).
- *Progress Indicators*: trang đơn hiện các mốc "Đã nhận đơn → Shop xác nhận → Đang giao → Đã giao" với mốc hiện tại.
- Giới hạn thời gian báo giá: không có mục khớp trong cơ sở dữ liệu của skill (đã thử lại với từ khóa khác); dùng nguyên tắc
  chung WCAG 2.2.1 (người dùng tự gia hạn được): hết hạn không mất dữ liệu nhập, một nút để lấy báo giá mới.
- Chạm ≥ 44×44 px, focus ring hiện rõ, không cuộn ngang ở 360px, không dùng màu làm tín hiệu duy nhất.

## 3. Báo giá (quote)

### 3.1 Credential

- Member: Bearer qua `authRequest`.
- Guest: trước lần quote đầu `POST /api/v1/carts/{cart_id}/checkout-token` (cookie `cart_token`), giữ `checkout_token` và
  `expires_at` **chỉ trong bộ nhớ** (module state của checkout), gửi header `Checkout-Token` cho quote và tạo đơn. Token còn
  < 30 s hoặc server trả `401 INVALID_CHECKOUT_TOKEN` → lấy token mới rồi gửi lại cùng request một lần.

### 3.2 Request

`POST /api/v1/orders/quote` với `cart_id`, `cart_version` của giỏ đang hiển thị, `method: COD`, `shipping_address`,
`contact` (`email: null` khi member bỏ trống), `note`; header `Accept-Language` theo ngôn ngữ đang chọn.

### 3.3 Kết quả

| Trả về | Hiển thị |
|---|---|
| 200 | Tóm tắt dùng `items`/`totals` của quote; lưu `quote_token`, `expires_at`, `cart_version` trong state |
| 400 `INVALID_FIELDS` | Lỗi gắn field (map `errors[].field` về trường form) + ErrorSummary |
| 409 `VERSION_CONFLICT` | Giỏ đổi ở nơi khác: tải lại giỏ (CartProvider), câu "Giỏ hàng vừa thay đổi, vui lòng kiểm tra lại", không tự quote lại |
| 409 `ITEM_UNAVAILABLE` | Liệt kê dòng không còn bán (theo `errors[].field = variant_id`), link "Sửa giỏ hàng" |
| 422 `CART_EMPTY` | Về `/cart` |
| 422 `NO_SHIPPING_RULE` | "Chưa giao được tới khu vực này" gắn nhóm địa chỉ |
| 422 khác (`PAYMENT_AMOUNT_UNSUPPORTED`, `AMOUNT_OUT_OF_RANGE`) | Câu cố định "Không đặt được đơn này, vui lòng liên hệ shop" |
| 404 `CART_NOT_FOUND`, 401 sau khi đã lấy lại token | Giỏ mất: tải lại giỏ, quay về `/cart` với thông báo |
| 503 / mạng | "Chưa tính được tổng tiền, vui lòng thử lại"; quote là thao tác đọc, bấm lại an toàn |

## 4. Đặt đơn

### 4.1 Một lần xác nhận = một key

- Bấm "Đặt hàng" → tạo `Idempotency-Key` (UUID) và body `{cart_id, cart_version, quote_token}`, lưu cặp này vào
  `sessionStorage['storefront.checkout']` **trước** khi gửi (body không có secret: `quote_token` không phải secret theo
  contract; D6).
- `POST /api/v1/orders` với key đó. Lỗi mạng/503/timeout → **không biết kết quả**: hiện "Chưa xác nhận được đơn, đang thử
  lại…" và tự gửi lại **cùng key + body** với backoff (1 s, 2 s, 4 s… tối đa 30 s, tối đa 5 lần), sau đó nút "Thử lại" (cùng
  key). Không bao giờ sinh key mới cho cùng body.
- Tải lại trang khi đang có bản ghi trong sessionStorage → `/checkout` gửi lại ngay cùng key + body (server trả đúng đơn cũ,
  kể cả khi quote đã hết hạn). Bản ghi bị xóa khi có kết quả xác định (202, hoặc 4xx ở bảng dưới).

### 4.2 Kết quả

| Trả về | Xử lý |
|---|---|
| 202 | Xóa bản ghi; tải lại giỏ (order dọn giỏ ở background); chuyển `/orders/{order_no}` (`replace`) |
| 409 `PRICE_CHANGED` | Xóa bản ghi; tự quote lại (không tự đặt), tóm tắt hiện "Giá đã thay đổi" kèm giá cũ → mới theo `errors` (`old=..,new=..`); khách bấm "Đặt hàng" lần nữa (key mới vì body mới) |
| 409 `QUOTE_EXPIRED` | Xóa bản ghi; nút về "Xem tổng tiền" + câu hết hạn như §2.3 |
| 409 `VERSION_CONFLICT`, `ITEM_UNAVAILABLE` | Như §3.3 |
| 409 `CART_ALREADY_ORDERED` | Đơn đã tồn tại cho đúng trạng thái giỏ này: chuyển `/orders/{errors[0].message}` (guest có cookie mới) |
| 409 `IDEMPOTENCY_KEY_REUSED` | Lỗi client (key cũ với body khác): xóa bản ghi, báo lỗi chung, cho bấm lại |
| 400 `INVALID_QUOTE` | Xóa bản ghi; báo giá không hợp lệ → quote lại (không tự đặt) |
| 401 `INVALID_CHECKOUT_TOKEN` | Token mới, gửi lại cùng key + body |

## 5. Trang đơn `/orders/:orderNo`

- `GET /api/v1/orders/{order_no}`: member dùng Bearer; guest dựa vào cookie `order_access` (HttpOnly, server đặt). 404 →
  "Không xem được đơn này. Nếu bạn đặt bằng thiết bị khác, hãy đăng nhập hoặc liên hệ shop" (không có tra cứu bằng số điện
  thoại).
- Nội dung: mã đơn, mốc tiến độ (§2.4), câu trạng thái theo bảng 14 §4 ("Đang chuẩn bị đơn", "Đã tiếp nhận, chờ shop xác nhận",
  "Shop đã hủy đơn", "Đơn bị hủy vì hết hàng — giỏ hàng vẫn còn, hãy sửa giỏ rồi đặt lại"...), dòng hàng, bảng tiền,
  địa chỉ, liên hệ, phương thức COD, hạn đơn chờ (`expires_at`). Không hiện từ saga/lease/UNKNOWN.
- **Poll** chỉ khi `processing_status = PROCESSING`: chờ `retry_after_seconds` (mặc định 3 s nếu null), tạm dừng khi tab ẩn,
  fetch ngay khi tab hiện lại; lỗi mạng → backoff tới 30 s. Sau **2 phút** vẫn `PROCESSING` → dừng, hiện "Đơn đang được xử lý,
  shop sẽ liên hệ với bạn" + nút "Kiểm tra lại" (D7). `READY`/`FINISHED`/`ACTION_REQUIRED` → dừng.
- Thay đổi trạng thái được đọc qua một `role="status"`; focus vào `h1` khi trang mở.

## 6. Danh sách đơn `/account/orders`

`GET /api/v1/orders/mine?limit=20` (Bearer): mã đơn, ngày đặt, số sản phẩm, tổng, nhãn trạng thái; mỗi dòng link tới
`/orders/{order_no}`. "Xem thêm" dùng `next_cursor`. Trống → "Bạn chưa có đơn hàng nào" + link mua sắm. Guest không có trang
này (đơn guest xem qua link trong email, còn cookie).

## 7. Cấu trúc code và kiểm thử

- `src/checkout/api.ts`: `checkoutToken(cartId)`, `quote(...)`, `createOrder(key, body)`, `getOrder(no)`, `myOrders(cursor)`;
  types theo `order.yaml`. Guest gửi `Checkout-Token`, member `authRequest`.
- `src/checkout/CheckoutPage.tsx` (form + tóm tắt), `src/checkout/submit.ts` (key, sessionStorage, retry/backoff — tách để
  test không cần React), `src/orders/OrderPage.tsx` (+ hook poll), `src/orders/OrdersPage.tsx`.
- Fixture theo contract: `quote.json`, `order-processing.json`, `order-confirmed.json`, `order-cancelled.json`,
  `orders-mine.json`, `checkout-token.json`; thêm vào registry `tests/contracts/test_storefront_fixtures.py`.
- Vitest (TDD, harness hiện có):
  - quote: guest lấy token rồi gửi header, member không; mỗi lỗi ở §3.3; sửa trường làm mất báo giá; hết hạn không tự đặt.
  - đặt đơn: cùng key + body khi mất mạng/503 và khi tải lại trang; key mới sau `PRICE_CHANGED`; `CART_ALREADY_ORDERED` →
    đúng đơn; token hết hạn → token mới, cùng key.
  - poll: dừng ở `READY`/`FINISHED`, dừng sau 2 phút, tạm dừng khi tab ẩn (fake timers + `visibilitychange`).
- Playwright:
  - fixture: checkout guest → quote → giá đổi → xác nhận lại → 202 → trang đơn poll tới CONFIRMED (desktop + 360px).
  - fixture: mất response lần đặt đầu, tải lại trang → cùng key, đúng một đơn.
  - chuỗi thật (CI): guest thêm hàng → checkout COD → trang đơn CONFIRMED; member thấy đơn trong `/account/orders`.

## 8. Quyết định agent tự chốt (cần chủ dự án duyệt)

| # | Quyết định | Lý do / hệ quả |
|---|---|---|
| D1 | Checkout **một trang**, không wizard | Chỉ có COD và ít trường |
| D2 | Guest checkout được phép, gợi ý đăng nhập | Contract ORD-01 hỗ trợ guest qua checkout token; guest bắt buộc email |
| D3 | Không làm ước tính phí ship trong giỏ | Phí thật có ở báo giá checkout; ước tính trong giỏ cần chọn tỉnh ngay ở giỏ. Làm sau nếu PO cần (SF-05 ghi "estimate fee") |
| D4 | Lấy báo giá bằng nút "Xem tổng tiền", không tự quote khi gõ | Tránh gọi quote liên tục và tổng nhảy khi đang nhập; hết hạn chỉ cần bấm lại |
| D5 | Địa chỉ nhập ở checkout không tự lưu vào sổ địa chỉ | Tránh ghi PII ngoài ý muốn; member vẫn quản lý sổ ở `/account/addresses` |
| D6 | Lưu `{key, cart_id, cart_version, quote_token}` trong `sessionStorage` tới khi có kết quả | Tải lại trang vẫn gửi lại đúng đơn; theo contract `quote_token` không phải secret; không lưu `checkout_token` |
| D7 | Poll tối đa 2 phút rồi dừng với nút "Kiểm tra lại" | 14 §3 (saga MANUAL giữ `PROCESSING` không giới hạn) |
| D8 | Trang đơn ở WEB-03 tối giản (trạng thái + snapshot); hủy đơn, lịch sử trạng thái để WEB-04 | Phạm vi SF-08 thuộc WEB-04 |
| D9 | Không thêm thư viện | fetch, React, storage có sẵn |
| D10 | Mobile: tóm tắt đầy đủ ở cuối trang, không thanh dính (**chủ dự án chọn**, mockup 2026-10-25) | Đơn giản hơn; khách cuộn hết form mới thấy tổng. Phương án A (tóm tắt thu gọn đầu trang + thanh dưới dính) không chọn |
