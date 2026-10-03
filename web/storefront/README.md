# Storefront shell

`TASK:PLT-01` · `REQ:XCT-01`, `REQ:XCT-05`. Đây là shell local của Sprint 1,
không hoàn thành `WEB-01`: chưa có collection, filter, detail, ảnh/size guide, nội dung
VI/EN thật, cart, auth hay checkout.

## Chạy local

Từ repository root, cần chạy Catalog và Gateway trước. Gateway là public boundary; frontend
chỉ gọi same-origin `/api/v1/catalog/products?limit=1`, không gọi Catalog trực tiếp.

```bash
# Terminal 1: PostgreSQL và Catalog (xem README của catalog-service để thiết lập .env local)
docker compose --env-file infra/local/.env -f infra/local/compose.yaml up -d postgres
./mvnw -pl services/catalog-service spring-boot:run

# Terminal 2: Gateway
CATALOG_BASE_URL=http://localhost:8081 ./mvnw -pl services/gateway spring-boot:run

# Terminal 3: Vite SPA
npm run dev --workspace @fashion/storefront
```

Cách chạy đầy đủ không cần 3 terminal: `bash scripts/local-up.sh` build `dist` và phục vụ bằng
nginx 1.30.5 (`Dockerfile`, `nginx.conf`) tại `http://localhost:4173`. nginx proxy `/api/` sang
Gateway, trả 404 cho asset thiếu dưới `/assets/`, và chỉ fallback `index.html` cho route UI.

Với Vite dev, mở `http://localhost:5173/` hoặc reload `http://localhost:5173/products`. Vite fallback
phục vụ route UI; `vite.config.ts` proxy riêng `/api` sang `VITE_GATEWAY_BASE_URL`
(mặc định `http://localhost:8080`) để API miss vẫn do Gateway trả HTTP 404, không bị rewrite
thành `index.html`.

## Kiểm thử

```bash
npm run typecheck --workspace @fashion/storefront
npm test --workspace @fashion/storefront -- --run
npm run build --workspace @fashion/storefront
npx playwright install chromium
npx playwright test tests/e2e/s1-local.spec.ts
```

Playwright mặc định chạy vào stack tĩnh `http://127.0.0.1:4173` (đặt `PLAYWRIGHT_BASE_URL` để
chạy vào Vite dev). Test không intercept: catalog đi qua Gateway → catalog → PostgreSQL thật và
chấp nhận cả empty state lẫn danh sách, không chấp nhận error state. Test chạy Chromium ở desktop và
viewport 360px. State loading, empty, error và retry được render trong `CatalogPage`; retry chỉ
lặp GET an toàn. Shell không lưu token, amount, secret hay internal identity header.
