# Nghiệm thu task local — 2026-10-19

## TASK:WEB-01 — storefront browse (bản thô)

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:WEB-01` sau khi Application CI của PR #28 PASS, với ghi chú: **mới xong phần
thô, chưa hoàn thiện chi tiết UI**. **Không độc lập:** WEB-01 do Claude Code viết. Review gồm một subagent reviewer
(0 Critical, 3 Important đã sửa) và một agent review riêng trên PR #28 (3 finding P2 đã sửa ở `c024084`); quyết định đồng ý
merge của reviewer sau lượt sửa được chủ dự án xác nhận trong phiên làm việc (chưa có comment mới trên PR tại lúc ghi). Không
thay review bởi người khác tác giả. Phạm vi: acceptance ở [10](../delivery/10_backlog.md) dòng WEB-01 + SF-01/02/03 ở
[14](../design/14_frontend_behavior.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `c024084`, PR #28 (chưa merge). Application CI (pull_request) SUCCESS
  ([run 39150569066](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39150569066)): backend toàn
  reactor 748 test, frontend typecheck/unit/build, contract **68 test** (gồm `test_storefront_fixtures.py`), local stack +
  smoke (`S1-local smoke PASS`), browser smoke Playwright **8 test** (s1-local + web-01, desktop + 360px). Documentation CI SUCCESS.
- Local: Vitest 21 test, typecheck, build PASS; Playwright web-01 6/6 trên stack thật + smoke; mutation guard trang cũ bị bắt.
  Chi tiết: [evidence WEB-01](web-01-local-2026-10-18.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 WEB-01 / 14) | Bằng chứng | Kết quả |
|---|---|---|
| Shell/list/collection/detail (SF-01/02/03) | Routes `/`, `/products`, `/search`, `/collections/:slug`, `/products/:id`; `CollectionPage.test.tsx`, `ListingPage.test.tsx`, `ProductPage.test.tsx`; e2e web-01 | Pass |
| Filter URL, đổi filter reset cursor, không trộn trang cũ | `reads filters from the URL…`, `a filter change resets the cursor…`, `drops a late load-more page…`, `keeps category and brand…`, `keeps tag…`; mutation guard | Pass |
| Detail: chọn variant, out-of-stock/unknown rõ, không cam kết giữ hàng | `asks for a variant…three stock states`, `partial size/colour matrix never gets stuck`; e2e chọn bằng bàn phím | Pass |
| Zoom, nhiều ảnh, size guide | `opens the zoom dialog…`, `every image is reachable…`, `renders the size guide table…` | Pass |
| Responsive VI/EN, `<html lang>` | e2e desktop + 360px không cuộn ngang; `…refetching in English`, fallback caption VI | Pass (mức cơ bản, xem ngoại lệ) |
| Loading/empty/error/retry, 400 không lặp retry, 404 | `shows the empty state…`, `offers a retry…`, `a 400 filter error names the field…`, `unknown collection and unknown route…` | Pass |
| Mock có contract | `tests/contracts/test_storefront_fixtures.py` (8 fixture khớp `catalog.yaml`, đã bắt một fixture sai) | Pass |
| Keyboard, mobile 360px | e2e `detail with contract fixtures` (Space/Arrow trên radio, 360px) | Pass |

**`TASK:WEB-01`: Done (local, trước G0) — bản thô, chưa hoàn thiện chi tiết UI**; còn bước merge PR #28 vào `main` do chủ dự án.

### Ngoại lệ và việc còn lại (không chặn Done bản thô)

- **Chi tiết UI chưa làm** (theo chủ dự án): thẩm mỹ/design system; trên phone: bộ lọc luôn mở chiếm màn đầu (nên thu gọn),
  vùng chạm size/màu ~34px (< 44px khuyến nghị), header chiếm 2–3 dòng, chưa kiểm trực quan/thiết bị thật.
- Đổi ngôn ngữ ở trang chi tiết tải lại sản phẩm (mất lựa chọn); `observed_at` chưa hiển thị; cursor không lưu URL.
- Chưa đo accessibility tự động (axe); content O10 thật chưa có (fixture/demo).
- PLT-02 vẫn In progress (WEB-01 dùng contract đã implement, 08).
- Phase 1A còn WEB-02, ADM-01, QA-01.
