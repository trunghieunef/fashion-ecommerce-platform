# WEB-02b — Storefront: giỏ hàng guest/member và gộp giỏ

`TASK:WEB-02` (phần b) · `REQ:CART-01–04` · dependency [WEB-02a](2026-10-23-web-02a-session-account-design.md) (phiên đăng
nhập, `authFetch`, `onSignedIn`, `src/ui/form.tsx`), CART-01, CART-02 (Done local).
Trạng thái: **đã duyệt** 2026-10-23 (chủ dự án). Chủ dự án chọn tách WEB-02 thành a/b (2026-10-23); theo quy ước làm việc, agent tự chốt các
mặc định ở §7, chủ dự án duyệt hoặc sửa trước khi viết plan. Plan của phần b viết sau khi phần a xong.

## 1. Mục tiêu

SF-05 trên storefront (giao diện WEB-01b): thêm sản phẩm vào giỏ từ trang chi tiết, xem giỏ, đổi số lượng, xóa dòng, xóa giỏ;
giỏ guest (cookie) và giỏ member. Đăng nhập khi đã có giỏ guest thì giỏ được gộp vào giỏ member, báo rõ dòng nào bị điều chỉnh.

Acceptance (10 dòng WEB-02, 14 SF-05): 409 version làm mới giỏ và báo điều chỉnh; **mất credential hay xung đột version
không làm mất item**; gộp giỏ không âm thầm bỏ item.

Không làm: checkout/quote/đặt đơn (WEB-03: nút "Thanh toán" hiển thị nhưng disabled kèm ghi chú), ước tính phí ship trong giỏ
(contract chỉ có `shipping_fee_status: NOT_CALCULATED`), lịch sử đơn (WEB-04). Không đổi contract hay backend.

Anti-goals: tính tiền ở client (giá trong giỏ là preview server trả về); tự tạo giỏ cho người chỉ xem hàng; sinh
`Idempotency-Key` mới khi retry cùng một thao tác; client tự xóa item ở local; thêm thư viện.

## 2. Giao diện

Theo WEB-01b và các thành phần form/thông báo của WEB-02a.

- Header: nút giỏ có số lượng (`aria-label` "Giỏ hàng, N sản phẩm"); menu mobile thêm mục giỏ.
- Chi tiết sản phẩm: nút "Thêm vào giỏ" trong panel dính, disabled cho tới khi chọn đủ màu + size của một variant (hết hàng
  tham khảo vẫn cho thêm — tồn chỉ là tham khảo, server quyết). Thêm thành công → Drawer "Đã thêm vào giỏ" có dòng vừa thêm,
  nút "Xem giỏ" và "Tiếp tục mua"; Drawer không tự đóng.
- Route `/cart`: §3.

Nguyên tắc UX (skill ui-ux-pro-max, `--domain ux`):

| Khuyến nghị | Áp dụng |
|---|---|
| Contextual Live Badge Updates (High) | Badge giỏ không tự đọc số; một vùng `role="status"` duy nhất đọc "Đã thêm vào giỏ. Giỏ hàng có N sản phẩm" |
| Confirmation Dialogs (High) | Xác nhận trước khi "Xóa giỏ" (nút hủy được focus mặc định); xóa một dòng không hỏi lại nhưng có thông báo trạng thái |
| Input Types / Mobile Keyboards | Ô số lượng `inputmode="numeric"`, có label (ẩn trực quan nhưng đọc được) |
| Submit Feedback (High) | Nút "Thêm vào giỏ" hiện trạng thái đang thêm, sau đó Drawer xác nhận hoặc lỗi gần nút |

## 3. Giỏ hàng

- **Danh tính**: guest dùng cookie `cart_token` (HttpOnly, `Path=/api/v1/carts`) do server đặt; member dùng Bearer qua
  `authFetch` của WEB-02a.
- **Tạo giỏ lười**: không tạo giỏ khi chỉ xem hàng. Lần "Thêm vào giỏ" đầu tiên gọi `POST /carts` (guest: tạo + Set-Cookie;
  member: lấy hoặc tạo giỏ ACTIVE). Guest lưu `cart_id` vào `localStorage['storefront.cart']` (chỉ là gợi ý để đọc lại;
  credential là cookie). Khởi động: guest có gợi ý → `GET /carts/{id}`; 404 → xóa gợi ý, giỏ rỗng. Member → `GET /carts/mine`
  (404 = chưa có giỏ).
- **Version**: mọi lệnh ghi gửi `expected_version` của giỏ đang hiển thị; response trả giỏ mới và thay state.
  409 `VERSION_CONFLICT` → tải lại giỏ, hiện thông báo "Giỏ hàng vừa thay đổi ở nơi khác, vui lòng kiểm tra lại", **không** tự
  gửi lại thao tác. Không có trường hợp nào client xóa item ở local.
- **Idempotency-Key** (`POST …/items`, merge): `crypto.randomUUID()` sinh một lần cho mỗi thao tác người dùng, giữ nguyên
  qua retry do lỗi mạng/503/timeout; thao tác mới mới sinh key mới.
- **Màn `/cart`**: mỗi dòng ảnh, tên, màu/size, giá preview, bộ số lượng (1–99, `PUT` số tuyệt đối, debounce 400 ms; ô số
  cho phép gõ), xóa dòng; nút "Xóa giỏ" (xác nhận). `preview_status`: `OK` → tạm tính `subtotal_preview`; `UNAVAILABLE` →
  đánh dấu dòng không còn bán/không đủ hàng tham khảo, không hiện tạm tính; `EMPTY` → trạng thái giỏ trống + link mua sắm.
  Luôn kèm ghi chú: giá và tồn chỉ tham khảo, phí ship tính ở bước đặt hàng. Nút "Thanh toán" disabled (WEB-03).
- Giá mỗi dòng và tạm tính lấy nguyên từ server (`Price` của WEB-01); client không nhân/cộng.

## 4. Gộp giỏ (CART-02)

- Gắn vào `onSignedIn` của WEB-02a: sau đăng nhập/đăng ký thành công **khi có gợi ý giỏ guest**. Gọi `POST /carts/merge`
  (Bearer + cookie guest) với `guest_cart_id`, `guest_expected_version`, `member_expected_version` (đọc ngay trước khi gộp;
  member chưa có giỏ → `POST /carts` trước).
- `Idempotency-Key` của lần gộp lưu `sessionStorage['storefront.merge']` cho tới khi có kết quả cuối, để reload giữa chừng
  vẫn gửi lại đúng key.
- 200 → thay bằng giỏ member, xóa gợi ý guest, nếu `adjustments` khác rỗng thì hiện Drawer liệt kê từng điều chỉnh
  (giảm số lượng/bỏ dòng, lý do) — không âm thầm. `already_merged` → coi như xong.
- 409 version → đọc lại hai giỏ, thử lại **một lần** với key mới; vẫn 409 → giữ nguyên giỏ guest, báo "Chưa gộp được giỏ,
  sẽ thử lại" và thử lại ở lần khởi động sau. 404 giỏ guest → xóa gợi ý (giỏ đã hết hạn/đã gộp). 503/mạng → giữ key, thử lại
  sau. Trong mọi lỗi, giỏ guest và giỏ member trên server không bị client xóa.

## 5. Cấu trúc code và kiểm thử

```
src/cart/api.ts             typed theo cart.yaml
src/cart/CartProvider.tsx   giỏ hiện tại, add/update/remove/clear, merge (gắn onSignedIn)
src/cart/CartPage.tsx, AddToCart.tsx, AddedDrawer.tsx, MergeNotice.tsx
```

- **Vitest + harness** (mở rộng `mockFetch` cho `/carts`; fixture được `test_storefront_fixtures.py` validate với
  `cart.yaml`): không gọi `/carts` khi chỉ xem hàng; add đầu tiên tạo giỏ và lưu gợi ý; retry cùng key sau lỗi mạng; 409 →
  tải lại + thông báo, item vẫn còn; số lượng PUT tuyệt đối; UNAVAILABLE/EMPTY; nút Thanh toán disabled; badge + status
  message; merge sau login với key lưu `sessionStorage`; adjustments hiện đủ; 409 → một lần thử lại; 404 → xóa gợi ý; 503 →
  giữ giỏ guest.
- **Playwright** (`tests/e2e/web-02.spec.ts` mở rộng): chuỗi thật — thêm vào giỏ khi là guest (nếu có sản phẩm public) →
  đăng nhập → giỏ được gộp; fixture — merge có adjustments, 409.
- Tài liệu: README storefront (giỏ), 14 SF-05, 10, 08, evidence `docs/evidence/web-02b-local-<ngày>.md`.

## 6. Ràng buộc từ WEB-02a

Dùng nguyên `authFetch`, `AuthProvider.status`/`onSignedIn`, `src/http.ts`, `src/ui/form.tsx`. Nếu khi viết plan phần b thấy
cần đổi các interface này, ghi rõ trong plan và không sửa ngầm.

## 7. Quyết định agent tự chốt (cần chủ dự án duyệt)

1. Tạo giỏ lười ở lần "Thêm vào giỏ" đầu tiên; `cart_id` guest lưu `localStorage` chỉ làm gợi ý.
2. 409 version: tải lại và báo, không tự gửi lại thao tác (trừ merge: thử lại một lần).
3. Sau khi thêm vào giỏ: mở Drawer xác nhận, không chuyển trang.
4. Checkout và ước tính phí ship không thuộc WEB-02; nút "Thanh toán" disabled.
5. Không thêm thư viện.
