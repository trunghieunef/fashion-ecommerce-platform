# Storefront

`TASK:WEB-01` · `REQ:CAT-03/04/05/06`, `REQ:XCT-01/05` (trên shell `TASK:PLT-01`).
[Spec](../../docs/superpowers/specs/2026-10-18-web-01-storefront-design.md) ·
[evidence](../../docs/evidence/web-01-local-2026-10-18.md). React 19 SPA (Vite, React Router), không UI kit/i18n/state library.
UI hiện cố ý đơn giản; tinh chỉnh thẩm mỹ là task sau. Chưa có giỏ, login (WEB-02) hay checkout (WEB-03).

## Màn hình

| Path | Màn | API (same-origin qua Gateway) |
|---|---|---|
| `/` | Home: bộ sưu tập đang chạy + hàng mới | `GET /api/v1/catalog/collections?limit=6`, `/products?sort=NEWEST&limit=8` |
| `/products` | Listing + bộ lọc (URL) | `/products?…&limit=24`, `/categories`, `/brands` |
| `/search?q=` | Tìm kiếm (cùng bộ lọc) | `/search?q=…` |
| `/collections/:slug` | Collection + lookbook | `/collections/{slug}` (trang đầu) |
| `/products/:id` | Chi tiết: size/màu, giá, tồn tham khảo, zoom, bảng size | `/products/{id}` + `Accept-Language` |
| `*` | Không tìm thấy | — |

- Bộ lọc nằm trong query string (`q, category_id, brand_id, size, color, tag, min_price, max_price, sort`); đổi bộ lọc
  bỏ cursor, hủy request cũ và bỏ qua trang "Tải thêm" của bộ lọc cũ.
- Tiền VND từ server, định dạng `vi-VN`; client không tính giá. Tồn kho: còn hàng (tham khảo) / hết hàng / chưa rõ,
  kèm chú thích hàng chỉ được giữ khi đặt đơn.
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
```

Fixture mock ở `src/test/fixtures/*.json` được `tests/contracts/test_storefront_fixtures.py` validate với schema response
của `contracts/openapi/catalog.yaml`, nên mock không lệch contract. `tests/e2e/web-01.spec.ts` đi home → listing → lọc →
chi tiết (chọn size bằng bàn phím) → đổi EN, không có error state và không cuộn ngang; phần chi tiết chỉ chạy khi stack có
sản phẩm public.

## Giới hạn

Collection chỉ hiển thị trang sản phẩm đầu (`ponytail:` trong code); cursor không lưu trong URL (reload về trang đầu của bộ
lọc); nội dung là fixture/smoke, chưa có content O10 thật; chưa đo accessibility tự động (axe) — kiểm thủ công + e2e bàn phím.
