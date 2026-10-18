# WEB-01 — Storefront browse: home, listing/search, collection, product detail (web/storefront)

`TASK:WEB-01` · `REQ:CAT-03/04/05/06`, `REQ:XCT-01/05` · dependency PLT-02 (xem §7.1).
Trạng thái: **đã duyệt** 2026-10-18 — chủ dự án: UI/UX đơn giản trước, tinh chỉnh thẩm mỹ sau (task riêng). Chủ dự án giao agent tự chốt mặc định; lựa chọn của agent ghi ở §7.
Mục tiêu dài hạn: đóng Phase 1A (sau WEB-01 là WEB-02, ADM-01, QA-01, mỗi task một spec riêng).

## 1. Mục tiêu

Khách duyệt được shop trên điện thoại 360px và desktop, bằng VI hoặc EN, chỉ qua Gateway (`/api/v1/catalog/**`):

- SF-01 Home + collection: collection đang chạy, lookbook, ảnh có alt/fallback, link đúng slug.
- SF-02 Listing/search: filter/sort/cursor nằm trong URL; đổi filter reset cursor; không trộn trang của filter cũ.
- SF-03 Product detail: chọn size/màu, ảnh + zoom, giá VND từ server, size guide, tồn tham khảo
  (`available`/`observed_at`; chưa quan sát = "chưa rõ"), không cam kết đã giữ hàng.
- Mỗi màn có loading / empty / error + retry; keyboard, focus thấy được, label, alt.

Không làm: thêm vào giỏ, giỏ, login (WEB-02), checkout (WEB-03), review/wishlist (Phase 2), SEO SSR (SPA đã được
chấp nhận), content thật O10 (fixture đánh dấu rõ là mẫu).

Anti-goals: tính giá ở client; hiển thị "còn hàng" như cam kết; trang lẫn kết quả filter cũ; gọi service trực tiếp
không qua Gateway; thêm UI framework/state library/i18n library.

## 2. Routes (React Router 8 có sẵn)

| Path | Màn | API |
|---|---|---|
| `/` | Home: collection đang chạy + sản phẩm mới | `GET /collections?limit=6`, `GET /products?sort=NEWEST&limit=8` |
| `/products` | Listing + filter | `GET /products?…` + `GET /categories`, `GET /brands` cho bộ lọc |
| `/search?q=` | Kết quả tìm kiếm (cùng filter) | `GET /search?q=…` |
| `/collections/:slug` | Collection + lookbook + sản phẩm | `GET /collections/{slug}` |
| `/products/:id` | Detail | `GET /products/{id}` (size guide nằm trong detail) |
| `*` | 404 UI | — |

Ảnh: `<img src="/api/v1/catalog/images/{asset_id}?kind=thumb|image">` (Gateway route public đã có, cache 300 s);
lỗi tải ảnh → khung placeholder giữ alt. Product link dùng `id` (API detail nhận UUID), slug chỉ để hiển thị.

## 3. Hành vi chính

- **URL là nguồn của filter**: `q, category_id, brand_id, size, color, tag, min_price, max_price, sort` (sort ∈ NEWEST/BEST_SELLING/PRICE_ASC/PRICE_DESC). Đổi bất kỳ filter →
  bỏ cursor, abort request đang chạy (`AbortController`), xóa danh sách cũ trước khi render trang mới. "Tải thêm" nối
  trang theo `next_cursor` chỉ khi filter không đổi; response của filter cũ đến muộn bị bỏ qua.
- **Tiền**: `Intl.NumberFormat('vi-VN', {style:'currency', currency:'VND'})` trên số nguyên từ server; khoảng giá
  `min_price`–`max_price`; không cộng/trừ ở client.
- **Variant**: nút chọn size/màu (radio group, keyboard được). Tổ hợp không có variant ACTIVE → disabled.
  Tồn của variant đã chọn: `available > 0` "Còn hàng (tham khảo)", `0` "Hết hàng", `null` "Chưa rõ tồn kho";
  luôn kèm chú thích tồn chỉ tham khảo, giữ hàng khi đặt đơn.
- **Zoom**: bấm ảnh mở `<dialog>` ảnh `kind=image`, Esc/nút đóng, focus trả về ảnh.
- **Size guide**: bảng `table` + `guideline_html` đã sanitize phía server, render trong vùng riêng; `null` → ẩn mục.
- **VI/EN**: nút chuyển ngôn ngữ trên header; lưu `localStorage` (try/catch, mặc định `vi`), gửi `Accept-Language`
  cho detail/size guide; tên/alt chọn `*_vi`/`*_en` (EN rỗng → fallback VI). Chuỗi UI trong một file từ điển nhỏ.
- **Lỗi**: 404 detail/collection → màn "không tìm thấy"; 5xx/mạng → thông báo + "Thử lại" (chỉ lặp GET).

## 4. Cấu trúc code

Theo pattern hiện có (`catalog/catalogClient.ts`, component + test cạnh nhau): `src/catalog/api.ts` (fetch typed,
envelope, signal), `src/i18n.ts`, `src/catalog/{HomePage,ListingPage,CollectionPage,ProductPage}.tsx`, component nhỏ
dùng chung (`ProductCard`, `Price`, `CatalogImage`, `States`). `CatalogPage` cũ được thay bằng `ListingPage`.
CSS thuần trong `app/styles.css` (mobile-first, biến màu, `:focus-visible`). Không thêm dependency.

## 5. Kiểm thử

- **Unit (Vitest + jsdom, mock `fetch`)**: listing — đổi filter reset cursor + bỏ response cũ, tải thêm nối trang,
  empty/no results, error + retry; detail — chọn variant, trạng thái tồn ba nhánh, 404, giá VND; collection — lookbook,
  ảnh lỗi giữ alt; i18n — đổi EN dùng `name_en`, fallback VI.
- **Fixture có contract**: response mock lưu ở `web/storefront/src/test/fixtures/*.json`; `tests/contracts/test_storefront_fixtures.py`
  validate từng fixture với schema response trong `catalog.yaml` (mock không lệch contract).
- **Playwright** (`tests/e2e/`, desktop + 360px, stack thật như s1-local): đi home → listing → filter → detail, chọn
  variant bằng bàn phím, đổi EN, không có error state; dữ liệu smoke catalog hiện có.
- `npm run typecheck`, `npm test`, `npm run build`; Application CI chạy sẵn các bước frontend + browser smoke.

## 6. Tài liệu

`web/storefront/README.md` (routes, cách test), 14 §2 (SF-01/02/03 implemented), 10 backlog, 08 (quyết định §7),
evidence `docs/evidence/web-01-local-2026-10-18.md` (kèm ảnh chụp 360px/desktop nếu chụp được).

## 7. Quyết định của agent (cần duyệt)

1. **PLT-02**: coi contract đã implement (`catalog.yaml`) là đủ cho WEB-01; **không** đánh dấu PLT-02 Done.
2. Không thêm thư viện (UI kit, i18n, state, testing-library); React Router + CSS thuần + từ điển tự viết.
3. Link sản phẩm theo `id` (`/products/:id`), vì API detail nhận UUID; chưa có route theo slug.
4. Ngôn ngữ lưu `localStorage`, mặc định VI; không đưa locale vào URL.
5. "Tải thêm" (nút) thay cho infinite scroll; cursor không đưa vào URL (reload về trang đầu của filter).
6. Không có nút "Thêm vào giỏ" ở WEB-01; WEB-02 thêm sau khi có giỏ UI.
7. Mock có contract: fixture JSON được validate bằng Python contract test.
