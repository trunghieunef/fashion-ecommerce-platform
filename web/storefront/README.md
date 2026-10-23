# Storefront

`TASK:WEB-01`, `TASK:WEB-01b` · `REQ:CAT-03/04/05/06`, `REQ:XCT-01/05` (trên shell `TASK:PLT-01`).
Spec [WEB-01](../../docs/superpowers/specs/2026-10-18-web-01-storefront-design.md) ·
[WEB-01b](../../docs/superpowers/specs/2026-10-23-web-01b-storefront-redesign-design.md) · evidence
[WEB-01](../../docs/evidence/web-01-local-2026-10-18.md), [WEB-01b](../../docs/evidence/web-01b-local-2026-10-23.md).
React 19 SPA (Vite, React Router) + Tailwind CSS 4; không component/i18n/state/icon library.
Trạng thái: WEB-01 **Done (local) — bản thô** ([nghiệm thu 2026-10-19](../../docs/evidence/acceptance-2026-10-19.md));
WEB-01b (làm lại giao diện/UX) **Done (local)** ([nghiệm thu 2026-10-23](../../docs/evidence/acceptance-2026-10-23.md)). Chưa có giỏ, login (WEB-02) hay checkout (WEB-03).

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

## Phiên đăng nhập và tài khoản (WEB-02a)

[Spec](../../docs/superpowers/specs/2026-10-23-web-02a-session-account-design.md) ·
[evidence](../../docs/evidence/web-02a-local-2026-10-24.md). Trạng thái: **chờ review/nghiệm thu**.

- Access token chỉ ở biến module `src/auth/session.ts` (không storage); tải trang → một lần `POST /auth/refresh` (cookie
  HttpOnly) để khôi phục phiên. Refresh single-flight: một Promise trong tab, bọc Web Lock `storefront.auth.refresh` giữa
  các tab (trình duyệt không có Web Locks → chỉ trong tab). `authRequest` gắn Bearer, refresh trước khi token còn < 30 s,
  sau 401 refresh một lần và thử lại một lần. Đăng nhập/đăng xuất đồng bộ giữa tab qua `BroadcastChannel('storefront.auth')`.
- Route: `/login?next=` (chỉ nhận đường dẫn nội bộ), `/register`, `/forgot-password`, `/reset-password#token=…` (đọc token
  rồi xóa fragment), `/account` và `/account/addresses` (bọc `RequireAuth`). Header có nút tài khoản.
- Form dùng `src/ui/form.tsx`: label luôn hiện, lỗi tại ô khi rời ô/submit, hộp tóm tắt lỗi nhận focus, nút hiện/ẩn mật
  khẩu (không chặn paste), nút submit có trạng thái đang gửi, thông báo trạng thái tự ẩn 5 s.
- Đăng xuất chỉ kết thúc phiên local sau khi `POST /auth/logout` thành công (cookie HttpOnly chỉ server xóa được); lỗi →
  báo "Chưa đăng xuất được" và vẫn đăng nhập. Đổi mật khẩu: server thu hồi mọi phiên, `leaveSession('/login', notice)`.
  Kết thúc phiên có chủ đích thì `RequireAuth` chuyển tới điểm đến đó; phiên mất ngoài ý muốn (tab khác đăng xuất, refresh
  bị từ chối) mới về `/login?next=`. Đăng nhập ở tab khác (kể cả tài khoản khác) làm tab này tải lại hồ sơ.
- Một lần refresh trả về sau khi phiên đã bắt đầu/kết thúc lại (epoch) bị bỏ qua, nên không "hồi sinh" phiên vừa đăng xuất.
- Tỉnh/phường lấy từ **danh mục mẫu** `src/account/geo.sample.json` (đánh dấu "mẫu") cho tới khi O04 chốt nguồn; mã ngoài
  danh mục hiển thị thô.
- Local: dùng **`http://localhost:4173`**. Gateway chỉ nhận request ghi có cookie từ origin trong `GATEWAY_ALLOWED_ORIGINS`
  (mặc định `http://localhost:4173`); mở bằng `127.0.0.1` thì refresh bị 403 và phiên không khôi phục được.

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

# Không cần Docker: test fixture chạy trên build + vite preview (WEB-01: chụp home/listing/detail vào test-results/;
# WEB-02a: tests/e2e/web-02.spec.ts -g fixture)
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
