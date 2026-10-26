# Nghiệm thu task local — 2026-10-25

## TASK:WEB-02 phần b — giỏ hàng và gộp giỏ

Chủ dự án yêu cầu Claude Code nghiệm thu **WEB-02b** sau khi merge PR #39. **Không độc lập:** WEB-02b do Claude Code viết
(7 task inline, TDD). Hai vòng review:

- Review toàn nhánh bằng một subagent: 1 Critical, 3 Important, 6 Minor. Đã sửa Critical, Important và 2 Minor ở `a53bcce`.
- Review của chủ dự án trên PR #39 (head `a53bcce`): 2 Important, 8 Minor, cùng lỗi CI `s1-local`. Lỗi CI sửa ở `f81e1b4`;
  2 Important và 6 Minor sửa ở `436368e`.

Phạm vi: phần b của dòng WEB-02 ở [10](../delivery/10_backlog.md) (REQ CART-01–04) và SF-05 ở
[14](../design/14_frontend_behavior.md), theo [spec đã duyệt](../superpowers/specs/2026-10-23-web-02b-cart-merge-design.md)
(bổ sung §3/§7.6 ngày 2026-10-24), mức local trước G0. Chỉ frontend: contract, service và dependency không đổi.

### Bằng chứng chung

- **CI PR #39 trên `436368e`: SUCCESS.**
  - `s1-local` ([run 40113986120](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40113986120)):
    verify toolchain/lockfile, backend reactor, frontend typecheck/test/build, contract, local stack + smoke và
    **browser smoke**. Browser smoke pass, nhưng chuỗi thật `web-02.spec.ts` "guest cart merges on registration…" chỉ
    chạy nhánh "không có sản phẩm public" (chỉ kiểm giỏ trống), xem đính chính dưới.
  - `docs-check`: SUCCESS.
  - Lần CI trên `a53bcce` fail ở e2e WEB-02a: `getByRole('status')` khớp hai vùng. Đã sửa ở `f81e1b4`.
- **Merge:** chủ dự án merge PR #39 vào `main` tại `b28059f` (2026-10-24). Documentation CI và Application CI trên
  `b28059f`: SUCCESS ([run 40115593521](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40115593521)).
- **Local** (Windows, 7.4 GB RAM): typecheck, **176 test** Vitest (54 mới so với 122), build, contract 95 OK, docs check.
  - Playwright fixture 10/10 (WEB-01 + WEB-02) trên `vite preview --host 127.0.0.1`.
  - Phản chứng: bỏ guard chống tải cũ → 4 test gộp giỏ FAIL.
  - Máy này không chạy được stack thật; chuỗi thật chỉ có thể chứng minh trên CI `s1-local`.
  - Chi tiết: [evidence WEB-02b](web-02b-local-2026-10-24.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 WEB-02 phần b / 14 SF-05 / spec) | Bằng chứng | Kết quả |
|---|---|---|
| Không tạo giỏ khi chỉ xem; tạo ở lần "Thêm vào giỏ" đầu; gợi ý `cart_id` guest chỉ ở `localStorage`, credential là cookie | `CartProvider.test.tsx`: `a visitor who only browses never calls /carts`, `the first add creates the cart and stores the hint`, `an expired guest cart hint is dropped quietly` | Pass |
| Mọi lệnh ghi gửi `expected_version`; 409 version → tải lại và báo, không tự gửi lại, không xóa item ở client | `a version conflict reloads and keeps every item`, `remove and clear send the current version…`, `overlapping writes send the version from the previous answer`; e2e fixture xung đột trên `/cart` | Pass |
| Giữ `Idempotency-Key` khi thử lại cùng thao tác sau lỗi mạng/503; thao tác mới dùng key mới | `retrying a failed add reuses its key` | Pass |
| Mất credential/giỏ không làm hỏng giỏ: giỏ hết hạn bị bỏ rồi tải lại; đổi tài khoản tải giỏ của người mới | `a write on a cart that is gone drops it`, `signing in as someone else replaces the previous member cart`, `signing out drops the member cart from view` | Pass |
| Thêm vào giỏ: cần đủ màu + size, chặn bấm lặp, drawer xác nhận, badge header, một thông báo `role="status"`; lỗi nói rõ cạnh nút | `AddToCart.test.tsx`: `double click adds once`, `adding opens the drawer…announces the count`, `the header badge label counts items`, `out of stock says how many are left`, `a version conflict on add says so by the button` | Pass |
| `/cart`: số lượng 1–99 gửi một lần sau 400 ms; số bị từ chối trả về giá trị giỏ; đánh dấu dòng không bán/không đủ hàng; tạm tính chỉ khi `OK`; xóa có xác nhận; checkout disabled | `CartPage.test.tsx` (13) gồm `quantity changes are sent once after 400 ms`, `a quantity the server refused goes back…`, `a quantity over the limit says the limit`, `subtotal shows only when preview_status is OK`, `clearing the cart asks first and focuses Cancel` | Pass |
| Lệnh ghi và gộp giỏ trả `NOT_LOADED` → đọc lại giỏ, không lùi version, dòng chưa có preview không bị đánh dấu | `a write answered without a preview reads the cart again`, `a merged cart without a preview is read again`, `a line without a preview yet is not marked as not sold` | Pass |
| Gộp giỏ sau đăng nhập/đăng ký: key giữ qua reload, 409 thử lại một lần, thất bại giữ giỏ guest, không âm thầm bỏ item (drawer điều chỉnh) | `merge.test.tsx` (10): `…merges it and shows the adjustments`, `a merge retried after reload sends the same key`, `a first conflict retries once with a new key`, `a second merge conflict keeps the guest cart for later`, `a network failure keeps the key and the guest cart`; e2e fixture drawer "5 → 3" | Pass |
| Tải giỏ cũ không ghi đè kết quả gộp; lỗi gộp không làm đăng nhập lỗi | `a member cart load answered after the merge does not overwrite the merged cart` (có phản chứng); `AuthProvider.test.tsx` callback `onSignedIn` được cách ly | Pass |
| Fixture giỏ đúng `cart.yaml` | `tests/contracts/test_storefront_fixtures.py` (4 fixture giỏ), phản chứng enum `reason` sai → FAIL | Pass |
| Chuỗi thật: thêm vào giỏ → đăng ký gộp giỏ → số lượng → xóa giỏ | e2e `web-02.spec.ts` "guest cart merges on registration…" trên CI ([run 40113986120](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40113986120), Browser smoke) — chỉ nhánh không có sản phẩm public; CI PR #40 trên `c9a83a4` ([run 40165015692](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40165015692)): smoke để lại sản phẩm public có tồn kho, browser smoke 26/26 | Pass (2026-10-25, sau đính chính) |
| Docs đồng bộ (README, 14, 08, 10, plans README, evidence) | Commit `ec092cb`, `a53bcce`, `436368e` và commit nghiệm thu này (gồm `CLAUDE.md`) | Pass |

**Đính chính 2026-10-25:** `scripts/smoke-local.sh` gỡ public sản phẩm duy nhất nó tạo trước khi browser smoke chạy, nên trên
CI các chuỗi thật cần sản phẩm (WEB-02b gộp giỏ, WEB-03 checkout, nhánh chi tiết của WEB-01) chỉ chạy nhánh "không có sản
phẩm public". Từ PR #40 smoke publish lại sản phẩm đó kèm tồn kho ở cuối; CI PR #40 trên `c9a83a4` ([run 40165015692](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40165015692)) chạy đủ chuỗi thật,
browser smoke 26/26.

**WEB-02b: Done (local, trước G0)**; tiêu chí chuỗi thật đạt ở CI PR #40 (`c9a83a4`). CI trên `436368e` PASS. Chủ dự án đã merge PR #39 vào `main` (`b28059f`, 2026-10-24).
**`TASK:WEB-02`: Done (local, trước G0)**, vì cả phần a ([nghiệm thu 2026-10-24](acceptance-2026-10-24.md)) và phần b đã Done.

### Ngoại lệ và việc còn lại (không chặn Done local)

- **Chưa làm trong WEB-02b:**
  - Ước tính phí ship trong giỏ: làm ở WEB-03.
  - API giỏ chưa trả tên/ảnh/màu/size, nên dòng giỏ dùng cache client và hiện SKU khi thiếu: cần một task backend riêng.
- **Follow-up từ review:**
  - Giá dòng dùng `price_snapshot` trong lúc đọc lại giỏ, và giữ nguyên nếu đọc lại lỗi.
  - Tab nền nhận `login` từ tab khác cũng chạy gộp giỏ.
  - Đăng xuất giữa lúc gộp vẫn hiện "sẽ thử lại" hoặc drawer điều chỉnh.
  - Rời `/cart` trong 400 ms thì mất sửa số lượng đang chờ.
  - "Chưa gộp được giỏ" chỉ hiện ở `/cart`.
  - Xóa dòng/xóa giỏ làm mất focus; lần xóa thứ hai không được đọc lại.
- **Từ WEB-02a, vẫn còn:**
  - Form chặn submit lặp bằng state, nên hai lần submit trong cùng một tick có thể gửi hai lần.
  - Follow-up của PR #38.
- **Chưa đo:** accessibility tự động (axe).

## TASK:WEB-03 — checkout COD, trang đơn, đơn của member

Chủ dự án yêu cầu Claude Code merge PR #40 rồi nghiệm thu **WEB-03**. **Không độc lập:** WEB-03 do Claude Code viết (7 task
Native, TDD, skill ui-ux-pro-max cho UX). Hai vòng review:

- Review toàn nhánh bằng một subagent (`1a956e6..bf4e7a7`): 0 Critical, 4 Important, sửa ở `2d62bcf`.
- Review của chủ dự án trên PR #40 (head `e56f827`): 0 Critical, 4 Important (I1–I4), 7 Minor. I1–I4 sửa ở `6b2c68d`.

Phạm vi: dòng WEB-03 ở [10](../delivery/10_backlog.md) (REQ ORD-01/05/07, CART-05), SF-06/SF-07 ở
[14](../design/14_frontend_behavior.md), theo [spec đã duyệt](../superpowers/specs/2026-10-25-web-03-checkout-design.md)
(D10 mobile do chủ dự án chọn), mức local trước G0. Chỉ frontend và script smoke: contract, service và dependency không đổi.

### Bằng chứng chung

- **CI PR #40 trên `6b2c68d`: SUCCESS.** `s1-local` ([run 40198147916](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40198147916))
  gồm toolchain/lockfile, backend reactor, frontend typecheck/test/build, contract, local stack + smoke và **browser smoke**
  với chuỗi thật. Từ `c9a83a4` smoke để lại sản phẩm public có tồn kho nên chuỗi thật chạy hết (run 40165015692: 26/26,
  có member đặt COD → "Đã tiếp nhận, chờ shop xác nhận" → "Đơn hàng của tôi", desktop + 360px). `docs-check`: SUCCESS.
- **Merge:** Claude Code merge PR #40 vào `main` tại `3078ce8` (2026-10-25) theo yêu cầu của chủ dự án. Documentation CI và Application CI trên `3078ce8`: SUCCESS ([run 40199521088](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40199521088)).
- **Local** (Windows, 7.4 GB RAM) trên `6b2c68d`: typecheck, **233 test** Vitest (57 mới so với 176), build, contract 95 OK,
  docs check; Playwright fixture 14/14 (WEB-01, WEB-02, WEB-03; desktop + 360px) trên `vite preview --host 127.0.0.1`.
  Mỗi lỗi review có test FAIL trước khi sửa. Chi tiết: [evidence WEB-03](web-03-local-2026-10-25.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 WEB-03 / 14 SF-06, SF-07 / spec §1) | Bằng chứng | Kết quả |
|---|---|---|
| Tổng do server báo giá; client không tính tiền; quote gửi `cart_version`, `Accept-Language` | `See total sends the cart version and shows the server totals`, `quote sends Accept-Language`; `Summary.tsx` chỉ hiện `totals` của quote | Pass |
| Không bao giờ tự đặt đơn: sửa trường, hết hạn (115 s từ lúc nhận), `QUOTE_EXPIRED`, `INVALID_QUOTE` chỉ đưa về "Xem tổng tiền"/quote lại | `editing a field drops the quote`, `an expired quote asks for a new one`, `an expired quote on submit goes back to See total`, `an invalid quote is quoted again, not placed`, `a client clock running fast still gets a usable quote` | Pass |
| Giá/phí đổi phải xác nhận lại | `prices changed: a new quote is shown and nothing is placed`; e2e fixture "giá đổi → xác nhận lại" | Pass |
| Một lần xác nhận = một `Idempotency-Key`; mất response/503/tải lại → cùng key + body; không đơn thứ hai | `double click places once`, `a lost response is retried with the same key and says so`, `a reload with a pending order resends the same key and body`, `the main button resends the stuck order with the same key`, `submit.test.ts`; e2e fixture "mất response + tải lại → một đơn" | Pass |
| Đơn đã có cho giỏ (`CART_ALREADY_ORDERED`, `code: CONFLICT`) mở đúng đơn; fake lỗi trong test chỉ dùng mã của contract | `an order already placed for this cart opens it` (FAIL trước `6b2c68d`) | Pass |
| Bản ghi đang chờ không bị gửi lại dưới người khác; xóa khi đăng xuất (chứa địa chỉ/liên hệ trong `quote_token`) | `a pending order is stored with who placed it`, `a guest order still pending after signing in is not replayed as the member`, `signing out forgets the pending order` | Pass |
| Guest: checkout token chỉ trong bộ nhớ, làm mới trước 30 s, `INVALID_CHECKOUT_TOKEN` → token mới, gửi lại một lần cùng key; member dùng Bearer | `api.test.ts` (4), `an expired checkout token is renewed with the same key` | Pass |
| Lỗi quote nói rõ: trường bị từ chối, giỏ đổi, hàng không bán, không giao được, mất kết nối | `a field the server rejects…`, `a cart changed elsewhere…`, `unavailable items are listed…`, `no shipping rule…`, `a quote outage…`, `a resumed order with unavailable items…` | Pass |
| 202 → trang đơn poll theo `retry_after_seconds`; dừng khi tab ẩn, backoff tới 30 s, dừng sau 2 phút với "Kiểm tra lại"; không coi timeout là thành/bại | `poll.test.tsx` (5), `a status change is announced`, `a stalled order says the shop will contact…` | Pass |
| Trang đơn: trạng thái lời khách, mốc tiến độ, snapshot, hết hàng → link giỏ; giỏ đọc lại khi đơn rời `PROCESSING` | `OrderPage.test.tsx` (8) gồm `the cart is read again once the order leaves processing` (FAIL trước `6b2c68d`) | Pass |
| Lịch sử đơn member `/account/orders` (cursor), guest phải đăng nhập | `OrdersPage.test.tsx` (5) | Pass |
| Fixture đơn hàng đúng `order.yaml`/`cart.yaml` | `tests/contracts/test_storefront_fixtures.py` (6 fixture mới) | Pass |
| Chuỗi thật: member thêm vào giỏ → checkout COD → đơn được tiếp nhận → có trong "Đơn hàng của tôi" | e2e `web-03.spec.ts` trên CI (run 40165015692, 40198147916) | Pass |
| Mobile 360 px không cuộn ngang; tóm tắt cuối trang (D10) | e2e fixture chromium-mobile | Pass |
| Docs đồng bộ (README, 14, 08, 10, spec, plans README, `CLAUDE.md`, evidence) | `e56f827`, `6b2c68d` và commit nghiệm thu này | Pass |

**WEB-03: Done (local, trước G0).** CI trên `6b2c68d` PASS; PR #40 đã merge vào `main` (`3078ce8`, 2026-10-25).

### Ngoại lệ và việc còn lại (không chặn Done local)

- **Ngoài phạm vi (WEB-04):** VNPay/MoMo, hủy đơn, lịch sử trạng thái, tracking, refund. Ước tính phí ship trong giỏ không làm (D3).
- **Chuỗi thật guest checkout** chỉ có ở e2e fixture; chuỗi thật trên CI là member.
- **Minor từ review PR #40 (M1–M7):** hạn quote không kiểm lại lúc bấm sau khi máy ngủ; khi kẹt form vẫn sửa được nhưng
  "Thử lại" gửi body cũ; trang đơn/danh sách không tải lại khi đổi tài khoản; member mở đơn đặt lúc là guest nhận 404;
  vòng gửi lại không hủy được khi rời trang (~31 s); đường gửi lại sau reload hiện id biến thể khi `PRICE_CHANGED` và không
  quote lại; 401 của phiên member hết hạn báo "giỏ đã đổi".
- **Minor từ review subagent:** hết hạn quote lần hai không đọc lại câu; lỗi server cho địa chỉ đã lưu trỏ vào trường ẩn;
  sổ địa chỉ tải muộn có thể đổi lựa chọn; trang đơn chưa hiện `shipping_discount` (0 ở MVP); đổi `orderNo` trên trang đang
  mở còn hiện đơn cũ tới khi tải xong.
- **Hệ quả đã biết của I4:** phiên member hết hạn cũng xóa bản ghi đặt đơn, nên đơn member đang kẹt không tự gửi lại sau khi
  đăng nhập lại (không tạo đơn trùng; khách xem "Đơn hàng của tôi").
- **Chưa đo:** accessibility tự động (axe). Danh mục tỉnh/phường là mẫu (O04).
