# WEB-01 Storefront Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** home, listing/search, collection, product detail trên SPA hiện có; VI/EN; 360px–desktop; UI đơn giản.

**Architecture:** React Router 8 + fetch qua Gateway; `api.ts` typed theo `catalog.yaml`; hook `useLoad` (abort + retry);
URL search params là nguồn filter; context ngôn ngữ nhỏ; CSS thuần. Không thêm dependency.

**Tech Stack:** React 19, React Router 8, Vite 8, Vitest 5 (jsdom), Playwright 1.63.

**Spec:** [WEB-01 design](../specs/2026-10-18-web-01-storefront-design.md)

## Global Constraints

- Chỉ gọi `/api/v1/catalog/**` same-origin; ảnh `/api/v1/catalog/images/{asset_id}?kind=thumb|image`.
- Tiền: `Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'})`, không tính ở client.
- Giữ chuỗi `Sản phẩm` (heading `/products`), `Chưa có sản phẩm`, list `Danh sách sản phẩm` cho e2e s1-local.
- `localStorage` bọc try/catch; mặc định `vi`.

## Review Focus

- Filter đổi khi response trang cũ còn bay → trang mới không lẫn item cũ — test `staleResponseIgnored`.
- `name_en` rỗng → hiển thị VI — test i18n fallback.
- Variant `available: null` → "Chưa rõ tồn kho", không "Còn hàng" — test detail.
- Ảnh lỗi → placeholder giữ alt — test `CatalogImage`.

---

### Task 1: Nền tảng (api, i18n, useLoad, UI states, routes)

**Files:** Create `src/i18n.tsx`, `src/catalog/api.ts`, `src/catalog/ui.tsx`, `src/test/fixtures/*.json`; Modify `src/app/App.tsx`, `src/app/styles.css`; Delete `src/catalog/catalogClient.ts`, `CatalogPage.tsx` (thay bằng ListingPage).

- [ ] api: `listProducts(params,signal)`, `searchProducts`, `getProduct(id,lang,signal)`, `listCollections`, `getCollection(slug,cursor)`, `listCategories`, `listBrands`; lỗi `ApiError(status)`.
- [ ] i18n: `LangProvider`, `useLang() -> {lang,setLang,t,pick(vi,en)}`.
- [ ] ui: `Price`, `CatalogImage` (fallback), `ProductCard`, `Loading`, `ErrorState(retry)`, `NotFound`; `useLoad(fn,deps)`.

### Task 2: Màn hình

- [ ] `ListingPage` (products/search, filters URL, load more, stale-guard), `HomePage`, `CollectionPage`, `ProductPage` (variant, stock, zoom dialog, size guide).
- [ ] Unit tests từng màn (spec §5) với fixtures.

### Task 3: Contract fixtures + e2e + docs

- [ ] `tests/contracts/test_storefront_fixtures.py` validate fixtures với schema response.
- [ ] `tests/e2e/web-01.spec.ts` (desktop + 360px, stack thật, tolerant với dữ liệu smoke).
- [ ] README storefront, 14, 10, 08, evidence; typecheck/test/build; commit.
