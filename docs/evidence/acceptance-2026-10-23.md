# Nghiệm thu task local — 2026-10-23

## TASK:WEB-01b — làm lại giao diện và UX storefront

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:WEB-01b` sau khi review trên PR #37 được sửa và chủ dự án merge.
**Không độc lập:** WEB-01b do Claude Code viết (8 task inline, TDD); review toàn nhánh bằng một subagent (0 Critical /
4 Important + 1 Minor nâng lên Important, sửa ở `4fad218`). Review của chủ dự án trên PR #37 (head `ad3af65`): 5 lỗi chặn
merge, đều kiểm lại đúng và sửa ở `114174b`; 4 follow-up không chặn merge để lại. Merge do chủ dự án. Phạm vi: acceptance ở
[10](../delivery/10_backlog.md) dòng WEB-01b, SF-01/02/03 ở [14](../design/14_frontend_behavior.md) theo
[spec đã duyệt](../superpowers/specs/2026-10-23-web-01b-storefront-redesign-design.md), mức local trước G0. Chỉ frontend:
contract, service và fixture không đổi. `TASK:WEB-01` giữ Done.

### Bằng chứng chung

- CI PR #37 trên `114174b` SUCCESS: `s1-local`
  ([run 39986067998](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39986067998), gồm verify
  toolchain/lockfile, backend reactor, frontend typecheck/test/build, contract, local stack + smoke, browser smoke
  `web-01.spec.ts`/`s1-local.spec.ts` ở desktop và 360px) và `docs-check`.
- CI trên `main` sau merge (`a55a30b`): Application CI SUCCESS ([run 39989069566](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39989069566)) và Documentation CI SUCCESS.
- Local (Windows, 7.4 GB RAM): typecheck, **56 test** Vitest, build, contract 95 OK, docs check; Playwright test fixture
  2/2 trên `vite preview` và Vite dev (StrictMode). Linux thật (WSL glibc): `npm ci`, `npm ls --all`, typecheck, test, build.
  Stack thật và smoke không chạy được trên máy này; bằng chứng là CI `s1-local`. Chi tiết:
  [evidence WEB-01b](web-01b-local-2026-10-23.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 WEB-01b / spec §1, §2) | Bằng chứng | Kết quả |
|---|---|---|
| Style "Grotesk kiểu Thụy Sĩ": một font (Hanken Grotesk tự host, subset vietnamese), đơn sắc, token trong `@theme` | `styles.css`; build có `.woff2` vietnamese; ảnh chụp desktop/360px trong evidence | Pass |
| Tailwind CSS 4.3.3, không thêm component/state/i18n/icon library | `package.json` (3 gói pin chính xác); 08 mục WEB-01b, 17 | Pass |
| Header: mega-menu (hover/focus, Esc đóng và trả focus), "Bộ sưu tập" chỉ tải khi mở, tìm kiếm phủ header, menu mobile, skip link | 9 test `Header.test.tsx` (gồm StrictMode); e2e mở mega-menu, rê chuột từ nút xuống panel (PR #37 lỗi 5), accordion mobile | Pass |
| Home editorial: hero collection (ảnh hoặc chữ), ô collection, rail "Hàng mới" | 7 test `HomePage.test.tsx`; e2e fixture | Pass |
| Listing/search: drawer lọc áp dụng ngay qua URL (quy tắc WEB-01 về cursor/abort giữ nguyên), chip, 2/4 cột, giá ngược bị chặn, tìm kiếm rỗng báo "không phù hợp" | 21 test `ListingPage.test.tsx` (10 test WEB-01 giữ ý nghĩa + PR #37 lỗi 1–4); e2e chuỗi thật drawer Size M → chip | Pass |
| Collection: hero cover, lookbook | 4 test `CollectionPage.test.tsx` | Pass |
| Detail: gallery + panel dính, ô màu thumbnail/chữ (khớp chính xác), size gạch ngang vẫn chọn được, bảng size trong drawer, tồn kho 3 trạng thái không cam kết giữ hàng | 13 test `ProductPage.test.tsx`; e2e fixture chọn màu/size bằng bàn phím, drawer bảng size | Pass |
| Accessibility: bàn phím, focus thấy được, nhãn nhóm radio, vùng chạm ≥ 44px, `prefers-reduced-motion`, không cuộn ngang ở 360px | Test focus (drawer lọc, Esc mega-menu), nhóm Size có `legend`; e2e `scrollWidth <= innerWidth` cả hai viewport | Pass (chưa đo axe) |
| Tiền chỉ từ server, không tính ở client; mọi màn có loading/empty/error + retry | `Price` không đổi; test empty/no results/error/400 | Pass |
| Docs đồng bộ: README storefront, 14 §2, 08, 17, 10, evidence | Commit `3201ab5`, `114174b` | Pass |

**`TASK:WEB-01b`: Done (local, trước G0)**; CI trên `114174b` PASS; chủ dự án review PR #37, đã sửa đủ 5 lỗi và merge vào
`main` (`a55a30b`, 2026-10-23).

### Ngoại lệ và việc còn lại (không chặn Done local)

- Follow-up review PR #37: chống gọi trùng `/collections` bằng ref; `ProductRail` đo lại khi resize; `/categories` gọi hai
  lần trên listing; trùng `LABEL`/`VND` và phần hero giữa Home/Collection.
- Minor từ review toàn nhánh (evidence): chuỗi dài không dấu cách có thể tràn ở 360px; header dịch khi `/categories` về;
  chưa khóa cuộn nền sau drawer; kéo chọn chữ thả lên backdrop đóng drawer; `aria-controls` trỏ panel chưa render; nút rail
  disabled thay vì ẩn; nút cuối drawer là "Xóa lọc"; caption lookbook đọc hai lần; ảnh chụp 3/5 màn.
- Theo contract hiện có: không có ảnh thứ hai khi hover, mã màu hex, tổng số sản phẩm; size/màu trong drawer là ô chữ.
- `npm audit`: 4 lỗ hổng có từ trước (vite 8.3.0, vitest 5.0.1, source-map-js) — cần task nâng version riêng.
- Content/ảnh thật (O10); chưa đo accessibility tự động (axe).
