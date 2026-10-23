# WEB-01b — Storefront redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Làm lại giao diện và UX 5 màn storefront WEB-01 theo style "Grotesk kiểu Thụy Sĩ" bằng Tailwind CSS, không đổi
contract hay backend.

**Architecture:** Giữ nguyên lớp dữ liệu (`api.ts`, `useLoad`, `usePaged`, `i18n.tsx`); viết lại markup sang class Tailwind,
thêm `Header` (mega-menu), `Drawer` dùng chung trên `<dialog>` native, `FilterDrawer` + chip cho listing và `Gallery` +
panel dính cho detail. Filter vẫn nằm trong URL nên logic abort/reset cursor của WEB-01 không đổi.

**Tech Stack:** React 19.2.8, React Router 8.4.0, Vite 8.3.0, Tailwind CSS 4.3.3 (`@tailwindcss/vite`),
`@fontsource-variable/hanken-grotesk` 5.3.0, Vitest 5 + jsdom, Playwright 1.63.

**Spec:** [WEB-01b design](../specs/2026-10-23-web-01b-storefront-redesign-design.md) (đã duyệt 2026-10-23). Đọc kèm
[spec WEB-01](../specs/2026-10-18-web-01-storefront-design.md) cho các hành vi được giữ nguyên.

## Global Constraints

- Pin chính xác: `tailwindcss@4.3.3`, `@tailwindcss/vite@4.3.3` (devDependencies), `@fontsource-variable/hanken-grotesk@5.3.0`
  (dependency). Không thêm dependency nào khác: không component, state, i18n, icon library, testing-library.
- Không đổi `contracts/`, không đổi service nào; fixture trong `web/storefront/src/test/fixtures/` giữ nguyên.
- Token đúng spec §2: `--color-fg #000000`, `--color-bg #FFFFFF`, `--color-muted #555555`, `--color-line #D4D4D4`,
  `--color-soft #F2F2F2`, `--color-danger #B3261E`, `--color-focus #005FCC`, `--font-sans` Hanken Grotesk Variable, fallback `system-ui`.
- Breakpoint desktop = `lg` (1024px). Body 16px/1.5, chữ không nhỏ hơn 12px; vùng chạm ≥ 44×44px; focus ring 2px offset 2px.
- Mọi chuyển động tắt khi `prefers-reduced-motion: reduce`; ảnh tỉ lệ 3:4, `loading="lazy"` ngoài màn hình đầu.
- Mọi chuỗi UI mới có cả VI và EN trong `src/i18n.tsx`; nội dung chọn qua `pick()` như WEB-01.
- Tiền chỉ format số nguyên từ server (`Price`), không tính ở client. Không có nút "Thêm vào giỏ".
- Mỗi file mới mở đầu bằng comment `// TASK:WEB-01b: …` như pattern hiện có.
- Ledger thực thi ở `.superpowers/sdd/2026-10-23-web-01b-storefront-redesign/progress.md` (gitignored).
- Máy local 7.4 GB RAM: không chạy `local-up`/smoke; Playwright chỉ chạy test dùng fixture qua `vite preview` (Task 8),
  test chuỗi thật để CI chạy.

## Review Focus

1. Tên danh mục/collection tiếng Việt dài (ví dụ "Đầm & chân váy dự tiệc") trong mega-menu, chip và tiêu đề ở 360px:
   phải xuống dòng, không cắt chữ, không tạo horizontal scroll → test chip wrap ở Task 5, kiểm tra scrollWidth ở Task 8.
2. Mở drawer rồi điều hướng (bấm link danh mục trong menu mobile, Back của trình duyệt): drawer phải đóng, nền không
   kẹt `inert`/scroll-lock → test "navigating closes the drawer" ở Task 3.
3. `localStorage` ném lỗi (private mode) khi đọc/ghi số cột lưới → mặc định 4 cột, không crash → test ở Task 5.
4. Màu variant có ký tự đặc biệt hoặc khác hoa/thường so với `variant_color` của ảnh ("Đen" và "đen"): so khớp
   chính xác như WEB-01 (không tự chuẩn hóa), màu không khớp thì hiện pill → test ở Task 7.
5. Response `/categories` đến sau khi người dùng đã mở menu, hoặc lỗi: header không nhảy layout, vẫn có link "Sản phẩm"
   → test ở Task 3.

---

## File map

| File | Trách nhiệm | Task |
|---|---|---|
| `web/storefront/package.json`, `package-lock.json` (root) | dependency mới | 1 |
| `web/storefront/vite.config.ts` | plugin `tailwindcss()` | 1 |
| `web/storefront/src/app/styles.css` | `@import "tailwindcss"`, font, `@theme`, `@layer base` | 1 |
| `web/storefront/src/test/harness.tsx` | polyfill `<dialog>`, default route cho `/categories` | 1 |
| `web/storefront/src/ui/icons.tsx` | SVG: `MenuIcon`, `SearchIcon`, `CloseIcon`, `ChevronLeft`, `ChevronRight`, `ChevronDown` | 2 |
| `web/storefront/src/ui/Drawer.tsx` (+ `.test.tsx`) | Drawer dùng chung | 2 |
| `web/storefront/src/app/Header.tsx` (+ `.test.tsx`) | header, mega-menu, search overlay, menu mobile | 3 |
| `web/storefront/src/app/App.tsx` | dùng `Header`, skip link, `<main id="main">` | 3 |
| `web/storefront/src/catalog/ui.tsx` | card, giá, ảnh, skeleton, states theo style mới | 4 |
| `web/storefront/src/catalog/HomePage.tsx` (+ `HomePage.test.tsx`) | hero, ô collection, rail hàng mới | 4 |
| `web/storefront/src/catalog/FilterDrawer.tsx`, `ListingPage.tsx` (+ test) | toolbar, drawer lọc, chip, 2/4 cột | 5 |
| `web/storefront/src/catalog/CollectionPage.tsx` (+ test) | hero collection, lookbook, lưới | 6 |
| `web/storefront/src/catalog/Gallery.tsx`, `ProductPage.tsx` (+ test) | gallery, panel dính, ô màu, bảng size | 7 |
| `tests/e2e/web-01.spec.ts`, docs | e2e + tài liệu + evidence | 8 |

---

### Task 1: Tailwind, font, token và harness

**Files:**
- Modify: `web/storefront/package.json`, `package-lock.json`, `web/storefront/vite.config.ts`, `web/storefront/src/app/styles.css`
- Modify: `web/storefront/src/test/harness.tsx`

**Interfaces:**
- Produces: utility class theo token, ví dụ `text-fg`, `bg-soft`, `border-line`, `text-muted`, `text-danger`,
  `font-sans` (từ `@theme { --color-fg: …; --font-sans: … }`). Harness: `mockFetch(handler, honourAbort?)` giờ chấp nhận
  handler trả `null`/`undefined` và khi đó trả fixture mặc định cho `/categories` (`categories.json`), `/brands`
  (`brands.json`), `/collections?limit=6` (`collections.json`); polyfill `HTMLDialogElement.prototype.showModal`/`close`
  (đặt/bỏ `open`, `close()` phát event `close`) chỉ khi jsdom chưa có.

- [ ] **Step 1: Cài dependency**

Run: `npm install -w web/storefront -D -E tailwindcss@4.3.3 @tailwindcss/vite@4.3.3 && npm install -w web/storefront -E @fontsource-variable/hanken-grotesk@5.3.0`
Expected: `package.json` có đúng 3 version không dấu `^`; `git diff package-lock.json` chỉ thêm các gói này và transitive deps.

- [ ] **Step 2: Cấu hình** — thêm `tailwindcss()` từ `@tailwindcss/vite` vào `plugins` trước `react()`. Viết lại
  `styles.css`: `@import "tailwindcss";`, `@import "@fontsource-variable/hanken-grotesk";`, `@theme` với 7 màu + `--font-sans`
  ở Global Constraints, `@layer base` gồm `body` (bg, fg, 16px/1.5, `font-sans`), `:focus-visible`
  (`outline: 2px solid var(--color-focus); outline-offset: 2px`) và
  `@media (prefers-reduced-motion: reduce) { *, ::before, ::after { transition-duration: 0s !important; animation-duration: 0s !important; scroll-behavior: auto !important; } }`.
  Giữ class `.visually-hidden` cũ cho tới khi các task sau thay bằng `sr-only`, sau đó xóa.

- [ ] **Step 3: Harness** — default route và polyfill như Interfaces. Header (Task 3) sẽ gọi `/categories` trên mọi màn,
  nên các test có handler "bắt tất cả" (`mockFetch(() => json(product))`) phải được sửa để chỉ trả cho path của nó và trả
  `null` cho path khác; các assertion `calls[0]`/`calls.at(-1)` đổi sang lọc theo path (ví dụ
  `calls.filter((c) => c.url.includes('/products/')).at(-1)`).

- [ ] **Step 4: Verify**

Run: `npm run typecheck && npm test && npm run build`
Expected: 21/21 PASS; `dist/assets/*.css` chứa `Hanken Grotesk` và file `.woff2` subset `vietnamese` nằm trong `dist/assets/`.
Run: `bash scripts/verify-toolchain.sh` (với PATH toolchain) → EXIT 0.

- [ ] **Step 5: Commit** — `build(WEB-01b): add Tailwind 4.3.3, self-hosted Hanken Grotesk and design tokens`

### Task 2: Drawer và icons

**Files:**
- Create: `web/storefront/src/ui/icons.tsx`, `web/storefront/src/ui/Drawer.tsx`, `web/storefront/src/ui/Drawer.test.tsx`

**Interfaces:**
- Produces:
  - `icons.tsx`: `MenuIcon`, `SearchIcon`, `CloseIcon`, `ChevronLeft`, `ChevronRight`, `ChevronDown`, mỗi cái
    `(props: { className?: string }) => JSX.Element`, SVG 24×24 `stroke="currentColor"` `aria-hidden="true"` `focusable="false"`.
  - `Drawer({ open, onClose, title, side = 'right', children }: { open: boolean; onClose: () => void; title: string; side?: 'right' | 'full'; children: ReactNode })`.
    Bọc `<dialog aria-labelledby>`; `open` true → `showModal()`, false → `close()`; event `close` (Esc) và bấm backdrop
    (target là chính dialog) gọi `onClose`. Có tiêu đề `<h2 id>` và nút đóng `aria-label={t('close')}` dùng `CloseIcon`.
    `side='right'`: rộng `w-full max-w-md` sát phải; `side='full'`: toàn màn hình. Trượt vào 250ms (`transition` + `translate-x`).
    Khi đóng, `<dialog>` native tự trả focus về phần tử đã mở nó.

- [ ] **Step 1: Test** `Drawer.test.tsx` (render Drawer trong một component nhỏ có nút "Mở"):
  - `opens as a modal dialog with its title and a labelled close button`: bấm "Mở" → `dialog.open === true`,
    `dialog.getAttribute('aria-labelledby')` trỏ tới `h2` có text title, có `button[aria-label="Đóng"]`.
  - `close button, Escape (close event) and backdrop click all call onClose`: 3 cách, mỗi cách `onClose` được gọi 1 lần.
- [ ] **Step 2:** `npm test -w web/storefront -- Drawer` → FAIL (module chưa có).
- [ ] **Step 3:** Implement `icons.tsx` và `Drawer.tsx` theo Interfaces.
- [ ] **Step 4:** `npm test -w web/storefront -- Drawer` → PASS; `npm run typecheck` PASS.
- [ ] **Step 5: Commit** — `feat(WEB-01b): shared dialog-based Drawer and SVG icons`

### Task 3: Header, mega-menu, tìm kiếm, menu mobile

**Files:**
- Create: `web/storefront/src/app/Header.tsx`, `web/storefront/src/app/Header.test.tsx`
- Modify: `web/storefront/src/app/App.tsx` (bỏ `Header` cũ, thêm skip link và `id="main"` trên mọi `<main>` qua các page),
  `web/storefront/src/i18n.tsx`

**Interfaces:**
- Consumes: `Drawer`, icons (Task 2); `listCategories`, `listCollections` (`api.ts`); `useLoad` (`catalog/ui.tsx`).
- Produces: `Header()` (không props). Chuỗi i18n mới: `skipToContent` ("Bỏ qua tới nội dung chính" / "Skip to main content"),
  `menu` ("Menu" / "Menu"), `openSearch` ("Mở tìm kiếm" / "Open search"), `closeSearch` ("Đóng tìm kiếm" / "Close search"),
  `allProducts` ("Tất cả sản phẩm" / "All products"), `viewAll` ("Xem tất cả" / "View all"). Đổi `store` thành tên hiển
  thị logo "Fashion Store" (giữ nguyên giá trị).

Hành vi theo spec §3.1. Chi tiết đã quyết:
- Desktop (`hidden lg:flex`): logo `Link /`; `<nav aria-label={t('nav')}>` gồm mỗi danh mục cấp 1 (có `children` → nút
  `aria-expanded`/`aria-controls` mở panel; không có → `Link /products?category_id=`), mục "Bộ sưu tập" (nút, panel),
  link "Hàng mới" `/products?sort=NEWEST`, link "Sản phẩm" `/products`. Một panel mở tại một thời điểm (state
  `openMenu: string | null`). Mở bằng `onPointerEnter` hoặc focus/click nút; đóng bằng `onPointerLeave` của cả vùng
  nav+panel, Esc (focus về nút), `focusout` ra ngoài vùng, và mọi thay đổi `location`.
- Panel "Bộ sưu tập" gọi `listCollections` lần đầu mở (state riêng, không dùng `useLoad` để tránh gọi lúc mount); lỗi →
  thông báo `loadError` + nút `retry`.
- Search: nút `aria-label={t('openSearch')}` → thay thanh header bằng `<form role="search">` có input `type="search"`
  `maxLength={100}` tự focus; submit trim, rỗng thì không đi; Esc hoặc nút đóng thì quay lại.
- Mobile (`lg:hidden`): nút `MenuIcon` `aria-label={t('menu')}` mở `Drawer side="full"`, bên trong là `<details>` cho mỗi
  danh mục có con, link "Bộ sưu tập" dạng `<details>` (lazy load như desktop), "Hàng mới", "Sản phẩm", nút VI/EN.
  Mọi thay đổi `location` đóng drawer.
- Header `sticky top-0 z-40 bg-bg border-b border-fg`. Nút VI/EN giữ text `switchLang` như WEB-01 (e2e dùng).

- [ ] **Step 1: Tests** `Header.test.tsx` (render qua `render('/')` của harness, fixture `categories.json` có "Áo" →
  "Áo thun"):
  - `lists top-level categories from /categories and opens the panel by focus`: nút "Áo" `aria-expanded="false"`; focus →
    `"true"`, panel có link `/products?category_id=44444444-4444-4444-8444-444444444445` text "Áo thun".
  - `Escape closes the panel and returns focus to its button`.
  - `the collections panel loads only when first opened`: chưa mở → không có call `/collections` nào ngoài của HomePage
    (render ở `/products` để tách); mở → có call `/collections?limit=6` và link `/collections/he-2026`.
  - `keeps a Products link when /categories fails` (handler trả 503 cho `/categories`): có link "Sản phẩm" `/products`,
    không có text `loadError` trong `header`.
  - `search submits a trimmed query and ignores an empty one`: mở search, nhập "  áo  ", submit → route `/search?q=%C3%A1o`
    (kiểm `h1` "Tìm kiếm: áo"); nhập "   " → vẫn ở màn cũ.
  - `navigating closes the mobile drawer` (Review Focus 2): mở menu, bấm link "Hàng mới" → `dialog.open === false`.
  - `the skip link targets main`: `a[href="#main"]` tồn tại và `document.getElementById('main')` có sau render.
- [ ] **Step 2:** `npm test -w web/storefront -- Header` → FAIL.
- [ ] **Step 3:** Implement `Header.tsx`, sửa `App.tsx`, thêm chuỗi i18n.
- [ ] **Step 4:** `npm test` (toàn bộ) → PASS; `npm run typecheck` PASS.
- [ ] **Step 5: Commit** — `feat(WEB-01b): header with mega-menu, search overlay and mobile menu`

### Task 4: Thành phần dùng chung và Home editorial

**Files:**
- Modify: `web/storefront/src/catalog/ui.tsx`, `web/storefront/src/catalog/HomePage.tsx`, `web/storefront/src/i18n.tsx`
- Create: `web/storefront/src/catalog/HomePage.test.tsx` (chuyển 2 test home từ `CollectionPage.test.tsx` sang)

**Interfaces:**
- Consumes: icons (Task 2).
- Produces (giữ tên/chữ ký cũ để page khác dùng): `ProductCard`, `ProductGrid({ products, label, columns? }: { …; columns?: 2 | 4 })`
  (`ul` vẫn có `aria-label={label}`; mobile luôn 2 cột, `lg` theo `columns`, mặc định 4), `Price` (thêm
  `data-testid="price"`), `CatalogImage` (khung `aspect-[3/4] bg-soft`, placeholder giữ `role="img"` + `aria-label`),
  `LoadMore`, `Loading` (skeleton lưới có `role="status"` và text `sr-only` `t('loading')`), `NotFound`, `Failed`.
  Mới: `ProductRail({ products, label }: { products: ProductSummary[]; label: string })` — `ul` cuộn ngang
  `snap-x snap-mandatory overflow-x-auto` có `aria-label`, nút trước/sau (`ChevronLeft/Right`, `aria-label`
  `t('previous')`/`t('next')`, `hidden lg:inline-flex`) gọi `scrollBy({ left: ±clientWidth * 0.8 })` và bị `disabled` khi
  hết hướng (tính từ `scrollLeft`/`scrollWidth`/`clientWidth` trong handler `scroll`).
  Chuỗi mới: `previous` ("Trước" / "Previous"), `next` ("Sau" / "Next"), `explore` ("Khám phá" / "Explore").
- Card: link bao toàn card; tên `uppercase text-xs font-semibold`; ảnh `group-hover:scale-[1.03] transition duration-200`.

Home theo spec §3.2: hero = `items[0]` (ảnh `kind=image` nếu có `cover_asset_id`, nếu không thì chữ lớn trên `bg-soft`),
`h1` là tên collection hero (khi có hero; không có hero thì `h1` `sr-only` `t('store')`); ô collection = `items.slice(1)`
lưới `grid-cols-1 lg:grid-cols-2`; khối "Hàng mới" dùng `ProductRail` với tiêu đề `h2` `t('newArrivals')` và link
`t('viewAll')` → `/products?sort=NEWEST`. Hai khối vẫn tải/lỗi độc lập.

- [ ] **Step 1: Tests** `HomePage.test.tsx`:
  - giữ 2 test cũ (link `/collections/he-2026` có text "Hè 2026 (mẫu)"; call `/products?sort=NEWEST&limit=8` và
    `ul[aria-label="Hàng mới"] li` có 2).
  - `hero uses the first collection cover`: fixture collections với `cover_asset_id` = asset id hợp lệ →
    `img[src="/api/v1/catalog/images/<id>?kind=image"]` trong hero; `h1` = "Hè 2026 (mẫu)"; link "Khám phá" → `/collections/he-2026`.
  - `hero without a cover is text only`: fixture gốc (`cover_asset_id: null`) → hero không có `img`, `h1` "Hè 2026 (mẫu)".
  - `no collections: no hero, new arrivals still render`: `items: []` → không có link "Khám phá", rail vẫn có 2 `li`.
  - `rail buttons are disabled at the ends`: jsdom có `scrollWidth`=`clientWidth`=0 nên cả hai nút `disabled`.
- [ ] **Step 2:** `npm test -w web/storefront -- HomePage` → FAIL ở 4 test mới.
- [ ] **Step 3:** Implement `ui.tsx` + `HomePage.tsx`; xóa 2 test home khỏi `CollectionPage.test.tsx`.
- [ ] **Step 4:** `npm test` → PASS (các test listing/collection/product dùng `.price`, `.gallery`… sẽ sửa ở Task 5–7;
  nếu chúng hỏng vì `ui.tsx` thì sửa selector ngay trong task này sang `[data-testid="price"]`/role).
- [ ] **Step 5: Commit** — `feat(WEB-01b): editorial home with hero, collection tiles and new-arrivals rail`

### Task 5: Listing — toolbar, drawer lọc, chip, 2/4 cột

**Files:**
- Create: `web/storefront/src/catalog/FilterDrawer.tsx`
- Modify: `web/storefront/src/catalog/ListingPage.tsx`, `web/storefront/src/catalog/ListingPage.test.tsx`, `web/storefront/src/i18n.tsx`

**Interfaces:**
- Consumes: `Drawer` (Task 2), `ProductGrid columns` (Task 4), `FILTER_KEYS`, `Filters`, `listCategories`, `listBrands`.
- Produces:
  - `FilterDrawer({ open, onClose, filters, onChange }: { open: boolean; onClose: () => void; filters: Filters; onChange: (next: Filters) => void })`.
    Field có `name` = key URL: `category_id` (select, cây phẳng như `flat()` hiện tại), `brand_id` (select), `size`,
    `color` (text input — API không có danh sách cỡ/màu), `min_price`, `max_price` (number, `inputMode="numeric"`).
    Select gọi `onChange` khi `change`; text/number khi `blur` hoặc Enter (chỉ khi giá trị đổi). Min > max: hiện
    `role="alert"` `t('priceOrder')` dưới ô, `aria-invalid`, không gọi `onChange`. Giữ quy tắc WEB-01: danh sách
    category/brand lỗi hoặc đang tải thì giá trị URL được giữ (không mất `category_id`/`brand_id`). Cuối drawer:
    nút `t('clear')` (gọi `onChange` chỉ giữ `q`) và nút `t('showResults')` (chỉ `onClose`).
  - `ListingPage`: `onChange(next)` → `setParams(next)` (giữ `q`, `tag`, `sort` hiện có trừ khi bị đổi). Tiêu đề `h1`:
    `Tìm kiếm: {q}` (giữ đúng chuỗi WEB-01), tên danh mục từ cây khi có `category_id`, nếu không `t('allProducts')`.
    Toolbar: nút `t('filters')` + ` (n)` khi n > 0 (n = số key khác `q`/`sort` đang có); `<select name="sort">` native
    (đổi → URL ngay); nhóm 2 nút radio `aria-label={t('columns')}` (`lg` trở lên) lưu `localStorage['storefront.grid']`
    `'2' | '4'` trong try/catch, mặc định 4.
  - Chip: `<ul aria-label={t('activeFilters')}>` `flex flex-wrap`; mỗi key khác `q`/`sort` là một nút
    `aria-label={`${t('removeFilter')} ${label}: ${display}`}` (display: tên category/brand nếu biết, còn lại giá trị thô;
    giá format VND), bấm thì xóa key khỏi URL. Có chip "Xóa tất cả" khi ≥ 1 chip.
  - Chuỗi mới: `showResults` ("Xem kết quả" / "Show results"), `columns` ("Số cột" / "Columns"), `activeFilters`
    ("Bộ lọc đang chọn" / "Active filters"), `removeFilter` ("Bỏ lọc" / "Remove filter"), `clearAll` ("Xóa tất cả" / "Clear all").
- Giữ nguyên: trạng thái 400 (`invalidFilter` + nút `clear` trong `section` có `data-testid="invalid-filter"`), empty/no
  results, error + retry, `LoadMore`.

- [ ] **Step 1: Test helpers + migrate** — thay `submitFilters(form.filters)` bằng
  `setFilter(container, name, value)`: mở drawer qua nút "Bộ lọc", đặt `value`, phát `change` (select) hoặc `blur`
  (input) trong `act`, `flush()`. Sửa 10 test hiện có cho đúng tương tác mới, giữ nguyên ý nghĩa và assertion URL/text.
  Test 400 tìm nút qua `[data-testid="invalid-filter"] button`.
- [ ] **Step 2: Tests mới**:
  - `a chip removes only its filter`: `/products?size=M&color=Red` → 2 chip; bấm chip size → call `/products?` cuối có
    `color=Red`, không có `size=`, không có `cursor=`.
  - `clear all keeps the search query`: `/search?q=%C3%A1o&size=M` → bấm "Xóa tất cả" → URL call cuối là `/search?q=%C3%A1o&limit=24`.
  - `chips wrap instead of clipping` (Review Focus 1): `ul[aria-label="Bộ lọc đang chọn"]` có class `flex-wrap`.
  - `the title is the category name`: `/products?category_id=44444444-4444-4444-8444-444444444445` với categories fixture → `h1` "Áo thun".
  - `grid columns persist and survive a throwing localStorage` (Review Focus 3): chọn 2 cột → `localStorage['storefront.grid'] === '2'`;
    `vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); })` → render vẫn ra lưới
    (2 `li`), radio 4 cột `checked`.
  - `filter count on the toolbar button`: `/products?size=M&sort=PRICE_ASC` → nút có text "Bộ lọc (1)".
- [ ] **Step 3:** `npm test -w web/storefront -- ListingPage` → các test mới FAIL.
- [ ] **Step 4:** Implement `FilterDrawer.tsx`, `ListingPage.tsx`, chuỗi i18n.
- [ ] **Step 5:** `npm test` → PASS; `npm run typecheck` PASS.
- [ ] **Step 6: Commit** — `feat(WEB-01b): listing toolbar, filter drawer, chips and 2/4 column grid`

### Task 6: Collection page

**Files:**
- Modify: `web/storefront/src/catalog/CollectionPage.tsx`, `web/storefront/src/catalog/CollectionPage.test.tsx`

**Interfaces:**
- Consumes: `CatalogImage`, `ProductGrid`, `LoadMore`, `Loading`, `Failed` (Task 4).

Theo spec §3.4: hero tên collection (`h1` in hoa; ảnh `cover_asset_id` kind=image nếu có, không thì nền `bg-soft`);
lookbook `ul` `aria-label={t('lookbook')}` lưới `grid-cols-2 lg:grid-cols-3`, mỗi ảnh `figure` + `figcaption`; sau đó lưới
sản phẩm và `LoadMore`. Giữ heading `h2` "Lookbook".

- [ ] **Step 1:** Sửa selector trong test: `.lookbook img` → `ul[aria-label="Lookbook"] img`; giữ assertion caption,
  fallback VI, 404, cursor `c-2`. Thêm test `hero uses the collection cover when present` (fixture collection với
  `cover_asset_id` có giá trị → `img[src*="kind=image"]` đầu tiên trong `main` là ảnh cover).
- [ ] **Step 2:** `npm test -w web/storefront -- CollectionPage` → test mới FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `npm test` → PASS.
- [ ] **Step 5: Commit** — `feat(WEB-01b): collection page with cover hero and lookbook grid`

### Task 7: Product detail — gallery, panel dính, ô màu, bảng size

**Files:**
- Create: `web/storefront/src/catalog/Gallery.tsx`
- Modify: `web/storefront/src/catalog/ProductPage.tsx`, `web/storefront/src/catalog/ProductPage.test.tsx`, `web/storefront/src/i18n.tsx`

**Interfaces:**
- Consumes: `Drawer`, icons (Task 2), `CatalogImage`, `Price` (Task 4).
- Produces:
  - `Gallery({ images, fallbackAlt, focusColor }: { images: { assetId: string; alt: string; color: string | null }[]; fallbackAlt: string; focusColor: string })`.
    Desktop `lg:grid lg:grid-cols-2 gap-1`; mobile `flex snap-x snap-mandatory overflow-x-auto`, mỗi ảnh `w-full shrink-0`,
    chỉ báo `"{i} / {n}"` có `aria-live="polite"` cập nhật theo `scroll` (chỉ mobile, `lg:hidden`). Mỗi ảnh là
    `button` `aria-label={`${t('zoom')}: ${alt}`}` mở `<dialog>` zoom như WEB-01 (ảnh `kind=image`, nút đóng, focus trả về).
    Khi `focusColor` đổi và có ảnh màu đó: `scrollIntoView({ block: 'nearest', inline: 'start' })` ảnh đầu của màu
    (gọi qua optional chaining vì jsdom không có). Không còn hàng thumbnail; mọi ảnh luôn hiển thị (giữ yêu cầu PR #28).
  - Ô màu trong `ProductPage`: radio `name="color"` như WEB-01; nhãn là thumbnail (`CatalogImage kind="thumb"`, alt = tên
    màu) khi có ảnh `variant_color === color` (so khớp chính xác), nếu không là pill chữ. `legend`: `Màu: {color đã chọn}`
    (`t('color')`).
  - Cỡ: radio như WEB-01; cỡ không có variant ACTIVE với màu đã chọn hiển thị `line-through text-muted` nhưng vẫn chọn
    được (giữ hành vi "every size stays reachable" của review 1).
  - Bảng size: nút `t('sizeGuide')` (ẩn khi `size_guide === null`) mở `Drawer side="right" title={t('sizeGuide')}` chứa
    `guideline_html` + bảng như WEB-01.
  - Layout: `lg:grid lg:grid-cols-[1.4fr_1fr] gap-8`; panel `lg:sticky lg:top-20 self-start`; breadcrumb = `product.category.name`
    (link `/products?category_id=`); trạng thái tồn giữ `aria-live="polite"` thêm `data-testid="stock"`; mô tả trong
    `<details>` có `open` ban đầu = `window.matchMedia?.('(min-width: 1024px)').matches ?? true` (mở sẵn trên desktop
    theo spec §3.5), tiêu đề `t('description')`.
  - Lệch có chủ đích so với spec §3.5 "disabled": danh sách cỡ chỉ gồm cỡ của variant ACTIVE, nên một cỡ chỉ "hết" khi
    kết hợp với màu đang chọn. Giữ hành vi review 1 của WEB-01 (chọn được, màu không khớp bị bỏ chọn) và chỉ gạch ngang
    để báo hiệu; ghi rõ trong 14 §2.
  - Chuỗi mới: `description` ("Mô tả" / "Description").

- [ ] **Step 1: Migrate tests**: `.stock` → `[data-testid="stock"]`; `.product-info .price` → `article [data-testid="price"]`;
  `button.zoom` → `button[aria-label^="Phóng to ảnh"]`; `.gallery img`/`.gallery [role="img"]` →
  `[data-testid="gallery"] img`/`[role="img"]`; test "every image is reachable" đổi từ bấm thumbnail sang: mọi ảnh của
  fixture đều có trong gallery, bấm nút zoom của ảnh thứ 2/3/4 thì `dialog img` src đúng id `…662/663/664`. Size guide test:
  bấm nút "Bảng size" trước khi đọc `th`.
- [ ] **Step 2: Tests mới**:
  - `colour swatch is a thumbnail when an image has that variant_color, a text pill otherwise` (Review Focus 4): fixture
    sửa tại chỗ với ảnh `variant_color: 'Blue'` → nhãn radio Blue chứa `img[src*="kind=thumb"]` alt "Blue"; Red không
    có ảnh → nhãn chỉ có text "Red"; ảnh `variant_color: 'blue'` (thường) không làm Blue thành thumbnail.
  - `legend shows the chosen colour`: chọn Red → `legend` text "Màu: Red".
  - `size guide opens in a drawer and is hidden without a guide`: có guide → bấm nút → `dialog[open]` chứa `th` "Ngực (cm)";
    `size_guide: null` → không có nút "Bảng size".
  - `breadcrumb links to the category`: `a[href="/products?category_id=<category.id của fixture>"]`.
- [ ] **Step 3:** `npm test -w web/storefront -- ProductPage` → test mới FAIL.
- [ ] **Step 4:** Implement `Gallery.tsx`, `ProductPage.tsx`, chuỗi i18n. Xóa `.visually-hidden` khỏi `styles.css` nếu
  không còn dùng (`rg visually-hidden web/storefront/src` rỗng).
- [ ] **Step 5:** `npm test` → PASS; `npm run typecheck && npm run build` PASS.
- [ ] **Step 6: Commit** — `feat(WEB-01b): product gallery, sticky panel, colour swatches and size-guide drawer`

### Task 8: E2E, tài liệu, evidence

**Files:**
- Modify: `tests/e2e/web-01.spec.ts`, `web/storefront/README.md`, `docs/design/14_frontend_behavior.md` (§2),
  `docs/design/08_decisions.md` (mục mới "WEB-01b — quyết định 2026-10-23"), `docs/engineering/17_tech_stack.md`,
  `docs/delivery/10_backlog.md` (dòng Update + dòng bảng WEB-01b), `docs/superpowers/plans/README.md` (thêm plan này)
- Create: `docs/evidence/web-01b-local-2026-10-23.md`

**Interfaces:**
- Consumes: mọi label/role ở Task 3–7.

- [ ] **Step 1: Cập nhật e2e** (hai test hiện có, cả 2 project):
  - test chuỗi thật: home có `h2` "Hàng mới"; mở menu (desktop: nút danh mục đầu tiên trong `navigation "Điều hướng chính"`
    nếu có; mobile: nút "Menu") rồi đi tới "Sản phẩm"; mở "Bộ lọc", điền Size "M" + Tab → URL có `size=M`, có chip
    "Bỏ lọc Size: M"; phần detail và đổi EN giữ như cũ; giữ assert `scrollWidth <= innerWidth`.
  - test fixture: thêm mở "Bảng size" (dialog visible, Esc đóng) và ô màu radio vẫn chọn được bằng bàn phím; thêm
    `page.screenshot({ path: test.info().outputPath('<màn>.png'), fullPage: true })` cho home, listing, detail.
- [ ] **Step 2: Chạy e2e fixture cục bộ (không cần Docker)**

Run (từ repo root, PATH toolchain): `npm run build && (cd web/storefront && npx vite preview --port 4174 --strictPort &) && npx playwright install chromium && PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174 npx playwright test tests/e2e/web-01.spec.ts -g "contract fixtures"`
Expected: 2 passed (desktop + mobile). Test chuỗi thật không chạy local (cần stack); ghi rõ trong evidence.

- [ ] **Step 3: Docs** — README storefront (hệ thiết kế, Tailwind/font, lệnh test, e2e fixture cục bộ); 14 §2 (header/mega-menu,
  drawer lọc áp dụng ngay + chip, cột lưới lưu localStorage, gallery/ô màu/bảng size drawer); 08 (7 quyết định ở spec §7);
  17 (Tailwind 4.3.3, `@tailwindcss/vite` 4.3.3, `@fontsource-variable/hanken-grotesk` 5.3.0, lý do tự host); 10 (WEB-01b
  In progress → chờ nghiệm thu, WEB-01 giữ Done); plans README.
- [ ] **Step 4: Evidence** `docs/evidence/web-01b-local-2026-10-23.md`: lệnh đã chạy và kết quả (typecheck, test, build,
  validate-contracts, docs checker, e2e fixture), version thực, ảnh chụp (đường dẫn output Playwright, không commit ảnh nếu
  >1 MB), phần chưa chạy (e2e chuỗi thật → CI) và lý do (RAM).
- [ ] **Step 5: Verify toàn bộ**

Run: `npm run typecheck && npm test && npm run build && bash scripts/validate-contracts.sh && python3 -B -m unittest discover -s scripts -p 'test_*.py' && python3 -B scripts/check_docs.py && git diff --check`
Expected: tất cả PASS / EXIT 0.

- [ ] **Step 6: Commit** — `docs(WEB-01b): e2e, behaviour docs, decisions and local evidence`

Sau Task 8: review toàn nhánh bằng subagent, sửa mọi Critical/Important (mỗi lỗi có test RED→GREEN), push `dev`, mở PR
`dev` → `main` khi chủ dự án yêu cầu. Không tự merge.
