# Storefront

`TASK:WEB-01`, `TASK:WEB-01b` · `REQ:CAT-03/04/05/06`, `REQ:XCT-01/05` (trên shell `TASK:PLT-01`).
Spec [WEB-01](../../docs/superpowers/specs/2026-10-18-web-01-storefront-design.md) ·
[WEB-01b](../../docs/superpowers/specs/2026-10-23-web-01b-storefront-redesign-design.md) · evidence
[WEB-01](../../docs/evidence/web-01-local-2026-10-18.md), [WEB-01b](../../docs/evidence/web-01b-local-2026-10-23.md).
React 19 SPA (Vite, React Router) + Tailwind CSS 4; không component/i18n/state/icon library.
Trạng thái: WEB-01 **Done (local) — bản thô** ([nghiệm thu 2026-10-19](../../docs/evidence/acceptance-2026-10-19.md));
WEB-01b (làm lại giao diện/UX) **chờ nghiệm thu**. Chưa có giỏ, login (WEB-02) hay checkout (WEB-03).

## Hệ thiết kế (WEB-01b)

Style "Grotesk kiểu Thụy Sĩ": một font **Hanken Grotesk Variable** tự host qua `@fontsource-variable/hanken-grotesk`
(có subset vietnamese, không gọi Google Fonts lúc chạy), chỉ đen/trắng/xám, nhãn in hoa đậm. Token khai báo trong
`@theme` của `src/app/styles.css` (`fg`, `bg`, `muted`, `line`, `soft`, `danger`, `focus`) và dùng qua class Tailwind
(`text-fg`, `bg-soft`…); class dùng lại (`LABEL`, `BUTTON`, `BUTTON_OUTLINE`, `PAGE`) export từ `src/catalog/ui.tsx`.
Breakpoint desktop `lg` (1024px); vùng chạm ≥ 44px; focus ring 2px; mọi chuyển động tắt khi `prefers-reduced-motion`.
Drawer dùng chung (`src/ui/Drawer.tsx`) là `<dialog>` native (`showModal`): focus trap, Esc, trả focus không cần thư viện.

| Thành phần | File |
|---|---|
| Header: mega-menu danh mục (desktop), menu drawer (mobile), ô tìm kiếm phủ header, VI/EN, skip link | `src/app/Header.tsx`, `src/app/App.tsx` |
| Drawer, icon SVG | `src/ui/Drawer.tsx`, `src/ui/icons.tsx` |
| Card, lưới 2/4 cột, rail cuộn ngang, skeleton, trạng thái | `src/catalog/ui.tsx` |
| Drawer bộ lọc | `src/catalog/FilterDrawer.tsx` |
| Gallery + zoom | `src/catalog/Gallery.tsx` |

## Màn hình

| Path | Màn | API (same-origin qua Gateway) |
|---|---|---|
| `/` | Home: hero = collection đầu tiên (ảnh cover hoặc chữ), collection còn lại dạng ô, rail "Hàng mới" | `GET /api/v1/catalog/collections?limit=6`, `/products?sort=NEWEST&limit=8` |
| `/products` | Listing: toolbar (Bộ lọc → drawer, Sắp xếp, 2/4 cột), chip bộ lọc đang chọn | `/products?…&limit=24`, `/categories`, `/brands` |
| `/search?q=` | Tìm kiếm (cùng bộ lọc) | `/search?q=…` |
| `/collections/:slug` | Collection: hero cover, lưới lookbook, "Tải thêm" theo `next_cursor` | `/collections/{slug}?cursor=` |
| `/products/:id` | Chi tiết: gallery (desktop lưới, mobile vuốt + "i / n"), panel dính: breadcrumb, giá, ô màu (thumbnail ảnh có `variant_color` trùng tên, không thì chữ), size, tồn tham khảo, "Bảng size" mở drawer, mô tả | `/products/{id}` + `Accept-Language` |
| (header, mọi màn) | Danh mục cấp 1 + con; "Bộ sưu tập" chỉ tải khi mở lần đầu | `/categories`, `/collections?limit=6` |
| `*` | Không tìm thấy | — |

- Bộ lọc nằm trong query string (`q, category_id, brand_id, size, color, tag, min_price, max_price, sort`); đổi bộ lọc
  bỏ cursor, hủy request cũ và bỏ qua trang "Tải thêm" của bộ lọc cũ (hook dùng chung `usePaged` cho listing và collection).
  `q`/`tag` được giữ khi áp bộ lọc khác; `category_id`/`brand_id` cũng được giữ khi danh sách lựa chọn chưa tải hoặc lỗi. Giá từ > giá
  đến bị chặn ngay trong drawer. Lỗi 400 (bộ lọc sai trong URL chia sẻ) báo tên trường và chỉ cho "Xóa lọc", không lặp "Thử lại".
- WEB-01b: drawer bộ lọc **áp dụng ngay** (select khi đổi, ô chữ/số khi rời ô hoặc Enter); size/màu là ô chữ vì API không có
  danh sách. Chip "Bỏ lọc …" xóa đúng một tham số; "Xóa lọc"/"Xóa tất cả" giữ `q` và `sort`. Số cột (2/4, chỉ desktop) lưu
  `localStorage` `storefront.grid` (try/catch, mặc định 4).
- Tiền VND từ server, định dạng `vi-VN`; client không tính giá. Tồn kho: còn hàng (tham khảo) / hết hàng / chưa rõ,
  kèm chú thích hàng chỉ được giữ khi đặt đơn; `observed_at` chưa hiển thị (đơn giản hóa có chủ đích). Mọi size/màu đều chọn
  được; chọn một giá trị không có variant với lựa chọn kia thì lựa chọn kia bị bỏ (không bị kẹt). Size không có trong màu đang
  chọn được gạch ngang nhưng vẫn chọn được. Chọn màu cuộn gallery tới ảnh đầu tiên của màu đó (không đổi thứ tự ảnh).
- Ảnh `/api/v1/catalog/images/{asset_id}?kind=thumb|image`; lỗi ảnh → khung placeholder giữ alt.
- Mô tả và `guideline_html` render HTML đã được catalog-service sanitize (CAT-01); client không tự sanitize thêm.
- VI/EN: nút trên header, lưu `localStorage` (`storefront.lang`, bọc try/catch), `<html lang>` theo ngôn ngữ;
  nội dung EN rỗng (caption, mô tả) hiển thị VI. Chuỗi UI ở `src/i18n.tsx`.

## Chạy local

`bash scripts/local-up.sh` build `dist` và phục vụ bằng nginx 1.30.5 tại `http://localhost:4173` (proxy `/api/` sang
Gateway, chỉ fallback `index.html` cho route UI, asset thiếu dưới `/assets/` trả 404). Dev server:
`npm run dev --workspace @fashion/storefront` (proxy `/api` sang `VITE_GATEWAY_BASE_URL`, mặc định `http://localhost:8080`).

USR-01b-ii: nginx ghi đè `X-Forwarded-For` bằng `$remote_addr` và xóa `X-Client-IP`, `Forwarded`, `X-Real-IP` từ client.
Gateway chỉ tin header đó từ proxy allowlist (`storefront` trong Compose); Vite dev không thuộc allowlist.

## Kiểm thử

```bash
npm run typecheck --workspace @fashion/storefront
npm test --workspace @fashion/storefront -- --run      # Vitest + jsdom, fetch mock bằng fixtures
npm run build --workspace @fashion/storefront
npx playwright install chromium && npx playwright test  # stack thật, desktop + 360px

# Không cần Docker: test fixture chạy trên build + vite preview, chụp home/listing/detail vào test-results/
npm run build && (cd web/storefront && npx vite preview --port 4174 --strictPort &)
PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174 npx playwright test tests/e2e/web-01.spec.ts -g "contract fixtures"
```

Fixture mock ở `src/test/fixtures/*.json` được `tests/contracts/test_storefront_fixtures.py` validate với schema response
của `contracts/openapi/catalog.yaml`, nên mock không lệch contract. `tests/e2e/web-01.spec.ts` có ba test (desktop + 360px):
(1) stack thật: home → "Sản phẩm" (nav desktop hoặc nút Menu ở 360px) → drawer lọc Size M → chip → (chi tiết nếu có
sản phẩm public) → đổi EN, không error state, không cuộn ngang; (2) luồng fixture bằng `page.route`: home/listing/chi tiết,
chọn màu/size bằng Space/Arrow, tồn "còn hàng"/"chưa rõ", ảnh lỗi giữ alt, drawer bảng size (Esc đóng), EN, ảnh chụp;
(3) route/sản phẩm không tồn tại → "không tìm thấy" (cần Gateway thật để sản phẩm trả 404).

## Giới hạn

Cursor không lưu trong URL (reload về trang đầu của bộ
lọc); đổi ngôn ngữ ở trang chi tiết tải lại sản phẩm (mất lựa chọn size/màu); nội dung là fixture/smoke, chưa có content
O10 thật; chưa đo accessibility tự động (axe) — kiểm thủ công + e2e bàn phím. WEB-01b: không có ảnh thứ hai khi hover
card và không có mã màu hex (contract chỉ có một ảnh/summary và tên màu); không hiện tổng số sản phẩm (API không trả).
