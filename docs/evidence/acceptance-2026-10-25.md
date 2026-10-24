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
    **browser smoke**. Browser smoke gồm `web-02.spec.ts` chuỗi thật: thêm vào giỏ → đăng ký gộp giỏ → tăng số lượng →
    xóa giỏ, trên desktop và 360px.
  - `docs-check`: SUCCESS.
  - Lần CI trên `a53bcce` fail ở e2e WEB-02a: `getByRole('status')` khớp hai vùng. Đã sửa ở `f81e1b4`.
- **Merge:** chủ dự án merge PR #39 vào `main` tại `b28059f` (2026-10-24). Documentation CI và Application CI trên
  `b28059f`: SUCCESS ([run 40115593521](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40115593521)).
- **Local** (Windows, 7.4 GB RAM): typecheck, **176 test** Vitest (54 mới so với 122), build, contract 95 OK, docs check.
  - Playwright fixture 10/10 (WEB-01 + WEB-02) trên `vite preview --host 127.0.0.1`.
  - Phản chứng: bỏ guard chống tải cũ → 4 test gộp giỏ FAIL.
  - Máy này không chạy được stack thật; bằng chứng chuỗi thật là CI `s1-local`.
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
| Chuỗi thật: thêm vào giỏ → đăng ký gộp giỏ → số lượng → xóa giỏ | e2e `web-02.spec.ts` "guest cart merges on registration…" trên CI ([run 40113986120](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40113986120), Browser smoke) | Pass |
| Docs đồng bộ (README, 14, 08, 10, plans README, evidence) | Commit `ec092cb`, `a53bcce`, `436368e` và commit nghiệm thu này (gồm `CLAUDE.md`) | Pass |

**WEB-02b: Done (local, trước G0).** CI trên `436368e` PASS. Chủ dự án đã merge PR #39 vào `main` (`b28059f`, 2026-10-24).
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
