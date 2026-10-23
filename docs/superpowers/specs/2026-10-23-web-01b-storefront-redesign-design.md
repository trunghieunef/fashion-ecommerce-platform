# WEB-01b — Làm lại giao diện và UX storefront (style "Grotesk kiểu Thụy Sĩ", Tailwind CSS)

`TASK:WEB-01b` · `REQ:CAT-03/04/05/06`, `REQ:XCT-01/05` · dependency WEB-01 (Done local, xem
[nghiệm thu 2026-10-19](../../evidence/acceptance-2026-10-19.md)).
Trạng thái: **đã duyệt** 2026-10-23 (chủ dự án). Các lựa chọn bên dưới chủ dự án đã chốt khi brainstorm ngày 2026-10-23, nghiên cứu thiết kế bằng
skill ui-ux-pro-max và mockup trên visual companion.

Đây là "task thẩm mỹ riêng" mà spec [WEB-01](2026-10-18-web-01-storefront-design.md) đã hẹn. WEB-01 giữ trạng thái Done;
WEB-01b thay giao diện và một phần UX của 5 màn WEB-01, **không đổi contract hay backend**.

## 1. Mục tiêu

Storefront theo hướng **tối giản cao cấp**, style **C — Grotesk kiểu Thụy Sĩ** (gần Zara/Uniqlo): một font sans grotesk,
chữ in hoa đậm cho nhãn và tiêu đề, khoảng cách chặt, chỉ dùng đen/trắng/xám, ảnh là trọng tâm.

- **Header + điều hướng:** mega-menu danh mục trên desktop, drawer trên mobile, ô tìm kiếm phủ header, nút VI/EN.
- **Home kiểu editorial:** hero là collection đang chạy, các collection khác xếp ô 2 cột, dải "Hàng mới về" cuộn ngang.
- **Listing/search:** thanh filter trên desktop, drawer filter trên mobile, chip cho filter đang bật, chọn lưới 2 hoặc 4 cột.
- **Detail:** gallery dạng lưới cùng panel thông tin dính trên desktop, ảnh vuốt ngang trên mobile, ô màu bằng thumbnail,
  bảng size trong drawer.
- Giữ mọi yêu cầu chất lượng của WEB-01: loading/empty/error + retry, keyboard, focus thấy được, label, alt, 360px và desktop.

Không làm: đổi contract `catalog.yaml` hoặc catalog-service; đổi ảnh khi hover card (`ProductSummary` chỉ có một ảnh);
mã màu hex cho variant; nút "Thêm vào giỏ", giỏ hàng, login (WEB-02); checkout (WEB-03); dark mode; SEO SSR; content thật
O10 (fixture vẫn ghi rõ là mẫu).

Anti-goals: tính giá ở client; hiển thị "còn hàng" như một cam kết; trang lẫn kết quả của filter cũ; gọi service không
qua Gateway; thêm component library (shadcn, Radix…), state library, i18n library hoặc thư viện icon; gọi font hay CDN
bên thứ ba lúc runtime.

## 2. Hệ thiết kế

Nguồn: `search.py --design-system` của ui-ux-pro-max (style Minimalism & Swiss, dials variance 3 / motion 3 / density 3),
tra thêm font hỗ trợ tiếng Việt, sau đó chủ dự án chọn phương án C trên mockup.

| Token | Giá trị | Dùng cho |
|---|---|---|
| `--color-fg` | `#000000` | chữ, viền nhấn, nút chính, chip |
| `--color-bg` | `#FFFFFF` | nền |
| `--color-muted` | `#555555` | chữ phụ, breadcrumb (tương phản 7.4:1 trên trắng) |
| `--color-line` | `#D4D4D4` | viền phụ, size đã hết |
| `--color-soft` | `#F2F2F2` | nền ảnh placeholder, skeleton |
| `--color-danger` | `#B3261E` | lỗi form |
| `--color-focus` | `#005FCC` | focus ring 2px, offset 2px |
| `--font-sans` | Hanken Grotesk Variable (`wght` 100–900, subset vietnamese), fallback `system-ui` | toàn bộ |

- Chữ: body 16px/1.5 (không nhỏ hơn 12px). Nhãn/nav in hoa, weight 600, tracking 0.02em. Tiêu đề màn weight 700,
  tracking −0.03em. Hero 40–72px in hoa, line-height 0.9.
- Spacing theo thang 4px của Tailwind. Container tối đa `max-w-screen-2xl`, gutter 16px (mobile) và 24–32px (desktop).
- Ảnh có tỉ lệ cố định 3:4 (card, gallery) để tránh layout shift; ảnh ngoài màn hình dùng `loading="lazy"`.
- Chuyển động: hover 200ms, zoom ảnh card scale 1.03; drawer trượt 250ms. Tất cả tắt khi `prefers-reduced-motion: reduce`.
- Icon (menu, tìm kiếm, đóng, mũi tên) là SVG inline có `aria-hidden`; nút chỉ có icon phải có `aria-label`.
- Vùng chạm tối thiểu 44×44px; khoảng cách giữa các control ít nhất 8px.

## 3. Màn hình và hành vi

Routes, API và nguồn dữ liệu giữ nguyên như WEB-01 §2; header gọi thêm `GET /categories` (đã có trong contract).

### 3.1 Header (mọi màn)

- Desktop (≥ 1024px): logo bên trái; danh mục cấp 1 từ `/categories` ở giữa, kèm link "Bộ sưu tập" và "Hàng mới"
  (`/products?sort=NEWEST`); bên phải là nút tìm kiếm và VI/EN. Header dính khi cuộn.
- Mega-menu: hover hoặc focus vào danh mục cấp 1 mở panel liệt kê `children`, link `/products?category_id=…`. Panel đóng
  khi Esc (focus trả về mục cha), khi rời chuột hoặc focus ra ngoài, và khi điều hướng. Mục cha có `aria-expanded`.
  Danh mục không có `children` là một link thường.
- "Bộ sưu tập" cũng là một mục mega-menu (app không có route danh sách collection): lần mở đầu tiên mới gọi
  `GET /collections?limit=6`, sau đó liệt kê link `/collections/{slug}`. Đang tải thì hiện "Đang tải…"; lỗi thì hiện một
  dòng thông báo kèm "Thử lại", không chặn phần còn lại của header. Trên mobile, mục này là một accordion trong drawer.
- Mobile (< 1024px): nút menu mở Drawer toàn màn hình, danh mục con mở bằng accordion (`<details>`), VI/EN nằm cuối drawer.
- Tìm kiếm: bấm nút tìm kiếm mở ô nhập phủ header, focus sẵn vào ô; Enter chuyển tới `/search?q=…` (trim, tối đa 100 ký
  tự như hiện tại); Esc đóng ô.
- `/categories` đang tải hoặc lỗi: header vẫn hiện "Sản phẩm" (`/products`) và mọi chức năng khác; không hiện màn lỗi.
- Có link "Bỏ qua tới nội dung chính" (skip link) trỏ tới `<main id="main">`.

### 3.2 Home `/`

- Hero: collection đầu tiên trong `GET /collections?limit=6`. Có `cover_asset_id` thì hiện ảnh full-bleed với tên in hoa
  và nút "Khám phá" (`/collections/{slug}`). Không có ảnh thì hero chỉ có chữ lớn trên nền `--color-soft`. Không có
  collection nào thì bỏ hero, trang bắt đầu bằng "Hàng mới về".
- Các collection còn lại: lưới ô ảnh 2 cột trên desktop, 1 cột trên mobile; nhãn in hoa kèm mũi tên.
- "Hàng mới về": `GET /products?sort=NEWEST&limit=8` hiển thị thành dải cuộn ngang bằng CSS scroll-snap. Desktop có nút
  trước/sau (cuộn bằng `scrollBy`, ẩn khi hết hướng), mobile vuốt. Có link "Xem tất cả" tới `/products?sort=NEWEST`.
- Loading dùng skeleton đúng kích thước, empty và error + retry riêng cho từng khối, như WEB-01.

### 3.3 Listing `/products` và search `/search`

- Tiêu đề: tên danh mục đang lọc (lấy từ cây `/categories`), từ khóa tìm kiếm, hoặc "Tất cả sản phẩm".
- Thanh công cụ: nút "Lọc (n)" (n là số filter đang bật), "Sắp xếp" (select native, 4 giá trị như WEB-01), và chọn lưới
  2/4 cột (chỉ desktop; mobile cố định 2 cột). Lựa chọn số cột lưu `localStorage` (try/catch, mặc định 4), không đưa vào URL.
- Bấm "Lọc" mở Drawer (bên phải trên desktop, toàn màn hình trên mobile) gồm danh mục, cỡ, màu, thương hiệu và khoảng giá.
  **Chọn là áp dụng ngay**: mỗi thay đổi ghi vào URL, nên logic WEB-01 (bỏ cursor, abort request cũ, không trộn trang)
  áp dụng nguyên vẹn. Cuối drawer có "Xóa tất cả" và nút "Xem kết quả" (chỉ đóng drawer).
  Khoảng giá là hai ô số có label, áp dụng khi rời ô hoặc Enter. Min > max thì báo lỗi ngay dưới ô và không ghi URL.
- Chip: mỗi filter đang bật là một chip "nhãn ✕" (`aria-label` "Bỏ lọc …"); bấm thì xóa tham số đó khỏi URL. Có thêm
  "Xóa tất cả". Chip xuống dòng khi hết chỗ, không cắt chữ.
- Card: ảnh 3:4, tên in hoa, giá hoặc khoảng giá VND từ server. Hover hoặc focus thì ảnh zoom 1.03. Toàn bộ card là một link.
- Giữ nút "Tải thêm" theo `next_cursor` và các trạng thái loading/empty/no results/error + retry của WEB-01.

### 3.4 Collection `/collections/:slug`

Cùng ngôn ngữ thiết kế: hero tên collection (ảnh cover nếu có), lookbook dạng lưới ảnh có caption, sau đó lưới sản phẩm
dùng chung card và "Tải thêm". Không có filter. 404 hiện màn "không tìm thấy".

### 3.5 Detail `/products/:id`

- Desktop (≥ 1024px): bên trái là ảnh xếp lưới 2 cột theo `sort_order`; bên phải là panel `sticky` gồm breadcrumb
  (danh mục), tên, giá, chọn màu, chọn cỡ, trạng thái tồn, mô tả.
- Mobile: gallery cuộn ngang bằng scroll-snap, có chỉ báo vị trí ("2 / 5", đọc được bằng screen reader); panel nằm dưới.
- Bấm ảnh mở zoom bằng `<dialog>` như WEB-01 (Esc hoặc nút đóng, focus trả về ảnh).
- **Ô màu**: với mỗi màu của variant ACTIVE, nếu có ảnh với `variant_color` trùng tên màu thì ô màu là thumbnail của ảnh
  đó (`kind=thumb`, alt là tên màu); không có thì là pill chữ. Vẫn là radio group như WEB-01, có nhãn "Màu: {tên}".
  Chọn màu thì gallery cuộn tới ảnh đầu tiên của màu đó (nếu có).
- **Cỡ**: nút radio; tổ hợp không có variant ACTIVE thì disabled và gạch ngang. Nút "Bảng size" mở Drawer bên phải
  chứa nội dung size guide của WEB-01 (`table` + `guideline_html` đã sanitize ở server); size guide `null` thì ẩn nút.
- Tồn kho: 3 nhánh như WEB-01 (`available > 0`, `0`, `null`) kèm chú thích "tồn kho chỉ để tham khảo, hàng được giữ khi
  đặt đơn".
- Mô tả: dùng `<details>`, mở sẵn trên desktop.
- Không có nút "Thêm vào giỏ" (WEB-02 thêm vào panel này).

### 3.6 Drawer dùng chung

Một component duy nhất `Drawer` bọc `<dialog>` native và mở bằng `showModal()`, nên có sẵn focus trap, Esc, inert nền và
trả focus. Nhận `side` (`right` | `full`), `title` (gắn `aria-labelledby`) và nút đóng có label. Dùng cho menu mobile,
filter và bảng size. Bấm backdrop thì đóng.

## 4. Cấu trúc code

- `web/storefront/package.json`: thêm devDependency `tailwindcss@4.3.3`, `@tailwindcss/vite@4.3.3` (pin chính xác, bản
  phát hành 2026-07-16, peer `vite ^8`) và dependency `@fontsource-variable/hanken-grotesk@5.3.0`. Cập nhật lockfile;
  `scripts/verify-toolchain.sh` vẫn phải PASS.
- `vite.config.ts`: thêm plugin `tailwindcss()`.
- `src/app/styles.css` viết lại thành `@import "tailwindcss";`, `@import "@fontsource-variable/hanken-grotesk";`, `@theme`
  chứa token ở §2 và vài `@layer base` (focus ring, reduced motion). Bỏ toàn bộ class CSS cũ.
- `src/app/Header.tsx` (mới, tách từ `App.tsx`): header, mega-menu, drawer menu, ô tìm kiếm.
- `src/ui/Drawer.tsx`, `src/ui/icons.tsx` (mới): Drawer ở §3.6 và các SVG icon.
- `src/catalog/HomePage.tsx`, `ListingPage.tsx`, `CollectionPage.tsx`, `ProductPage.tsx`, `ui.tsx`: đổi markup sang
  class Tailwind theo §3. `api.ts`, `useLoad`, `usePaged` và `i18n.tsx` giữ nguyên logic; từ điển thêm chuỗi mới VI/EN.
- Tách thêm component nhỏ khi file quá dài (ví dụ `FilterDrawer.tsx`, `Gallery.tsx`), mỗi file một nhiệm vụ.

## 5. Kiểm thử

- **Vitest (jsdom, harness và fixture hiện có)**: 21 test WEB-01 giữ nguyên ý nghĩa. Selector theo class cũ (khoảng 42
  chỗ `querySelector`/`className`) đổi sang role, label hoặc `data-testid` để test không lệ thuộc class Tailwind.
  Test mới:
  - header: danh mục từ `/categories` hiện đúng; mở panel bằng focus hoặc hover; Esc đóng và trả focus;
    `/categories` lỗi thì header vẫn có link "Sản phẩm".
  - listing: chọn cỡ trong drawer thì URL có `size` và cursor bị bỏ; chip ✕ xóa đúng tham số; "Xóa tất cả";
    min > max báo lỗi và không ghi URL; chọn 2/4 cột lưu `localStorage` và chạy được khi `localStorage` ném lỗi.
  - detail: ô màu dạng thumbnail khi có ảnh `variant_color` và dạng pill khi không có; "Bảng size" mở drawer và ẩn khi
    size guide `null`.
  - home: hero dùng cover; không có cover thì hero chữ; không có collection thì không hiện hero.
  - jsdom chưa hỗ trợ đủ `HTMLDialogElement.showModal`, nên harness thêm polyfill tối thiểu (đặt `open`) chỉ trong test.
- **Contract**: fixture không đổi; `tests/contracts/test_storefront_fixtures.py` vẫn phải PASS.
- **Playwright** `tests/e2e/web-01.spec.ts`: cập nhật theo bố cục mới, ở cả desktop và 360px: home → mở mega-menu →
  listing → mở drawer lọc, chọn cỡ, thấy chip → detail, chọn màu/cỡ bằng bàn phím, mở bảng size → đổi EN. Kiểm tra không
  có horizontal scroll ở 360px. Chạy trên CI (máy local 7.4 GB RAM không dựng nổi stack).
- `npm run typecheck`, `npm test`, `npm run build`, `bash scripts/validate-contracts.sh` và kiểm tra docs ở AGENTS.md.
- Chụp ảnh 360px và desktop cho 5 màn để đưa vào evidence (từ Playwright trên CI hoặc `vite preview` với fixture).

## 6. Tài liệu

- `web/storefront/README.md`: hệ thiết kế, Tailwind, font, cách test.
- [14 §2](../../design/14_frontend_behavior.md): hành vi header/mega-menu, drawer filter + chip, gallery, bảng size.
- [08](../../design/08_decisions.md): mục "WEB-01b — quyết định" (§7 bên dưới).
- [17](../../engineering/17_tech_stack.md): thêm Tailwind 4.3.3 và font tự host.
- [10](../../delivery/10_backlog.md): thêm dòng WEB-01b; WEB-01 giữ Done.
- Evidence `docs/evidence/web-01b-local-2026-10-23.md` (lệnh đã chạy, kết quả, ảnh chụp nếu có).

## 7. Quyết định (chủ dự án đã chốt 2026-10-23)

1. Phạm vi: làm lại cả giao diện và UX của 5 màn WEB-01, được sửa hành vi đã chốt trong spec cũ; mã task mới là WEB-01b.
2. Định vị "tối giản cao cấp", style C — Grotesk kiểu Thụy Sĩ (Hanken Grotesk, đơn sắc, không màu nhấn).
3. Dùng **Tailwind CSS**, không dùng shadcn/Radix. Spec WEB-01 bỏ anti-goal "không thêm UI framework" cho phần CSS;
   vẫn không thêm component, state, i18n hay icon library.
4. Chỉ sửa frontend, không đổi contract: không có ảnh thứ 2 khi hover, ô màu dùng thumbnail hoặc pill chữ.
5. Đưa vào cả 4 nhóm UX: header + mega-menu, home editorial, filter drawer + chip, detail gallery + panel dính.
6. Font tự host qua `@fontsource-variable` (không dùng Google Fonts lúc runtime) — agent đề xuất, đã trình bày trong
   thiết kế.
7. Giữ nguyên: "Tải thêm" thay cho infinite scroll; link sản phẩm theo `id`; ngôn ngữ lưu `localStorage`, không vào URL.
