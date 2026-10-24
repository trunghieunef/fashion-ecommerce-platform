# WEB-02b — Cart and cart merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Storefront có giỏ hàng guest/member (thêm từ trang chi tiết, trang `/cart`, badge trên header) và gộp giỏ guest vào
giỏ member khi đăng nhập, theo giao diện WEB-01b và nền phiên đăng nhập WEB-02a.

**Architecture:** `src/cart/api.ts` gọi cart API (guest qua `request` + cookie, member qua `authRequest`); `CartProvider`
giữ giỏ hiện tại, tạo giỏ lười, giữ `Idempotency-Key` cho mỗi thao tác, xử lý 409 bằng tải lại + thông báo, và gắn việc
gộp giỏ vào `onSignedIn` của WEB-02a. Tên/ảnh/màu/size của dòng giỏ lấy từ dữ liệu client lưu lúc thêm (`display.ts`).

**Tech Stack:** React 19.2.8, React Router 8.4.0, Tailwind 4.3.3, Vitest 5 + jsdom (harness), Playwright 1.63.

**Spec:** [WEB-02b](../specs/2026-10-23-web-02b-cart-merge-design.md) (đã duyệt 2026-10-23; §3 "Thông tin hiển thị" và
§7.6 bổ sung và được duyệt cùng plan này 2026-10-24). Nền: [WEB-02a](../specs/2026-10-23-web-02a-session-account-design.md).

## Global Constraints

- Không thêm dependency; không đổi `contracts/` hay service. Fixture mới qua `tests/contracts/test_storefront_fixtures.py`.
- Giá luôn từ server (`Price` của WEB-01): giá dòng = `preview.effective_price` nếu có, không thì `price_snapshot`; tạm tính =
  `subtotal_preview` chỉ khi `preview_status === 'OK'`. Client không nhân/cộng tiền.
- Số lượng 1–99 (`Quantity`); giỏ tối đa 100 dòng (server quyết `CART_ITEM_LIMIT`).
- `Idempotency-Key` (`crypto.randomUUID()`) cho `POST …/items` và `POST /carts/merge`: một key cho mỗi thao tác người dùng,
  giữ nguyên khi thử lại thao tác đó sau lỗi mạng/503; thao tác mới → key mới.
- `localStorage`: `storefront.cart` (gợi ý `cart_id` guest), `storefront.cartDisplay` (`{ [variant_id]: { product_id, name_vi,
  name_en, asset_id, color, size } }`); `sessionStorage`: `storefront.merge` (`{ guest_cart_id, key }`). Mọi đọc/ghi bọc try/catch.
- Không bao giờ xóa item ở local; mọi thay đổi giỏ là state server trả về.
- Câu cố định (VI / EN):
  - 409 version: "Giỏ hàng vừa thay đổi ở nơi khác, vui lòng kiểm tra lại." / "Your cart changed elsewhere, please check it."
  - gộp chưa được: "Chưa gộp được giỏ, sẽ thử lại." / "Could not merge your cart yet, will try again."
  - thông báo sau thêm: "Đã thêm vào giỏ. Giỏ hàng có {n} sản phẩm." / "Added to cart. Your cart has {n} items."
  - ghi chú giỏ: "Giá và tồn kho chỉ để tham khảo; phí giao hàng được tính ở bước đặt hàng." /
    "Prices and stock are for reference; shipping is calculated at checkout."
- Giao diện theo WEB-01b/02a (`LABEL`, `BUTTON`, `BUTTON_OUTLINE`, `PAGE`, `Drawer`, `ui/form.tsx`); vùng chạm ≥ 44px.
- File mới mở đầu bằng `// TASK:WEB-02b: …`. Ledger `.superpowers/sdd/2026-10-24-web-02b-cart-merge/progress.md`.
- Máy local không dựng stack: e2e chuỗi thật để CI; e2e fixture chạy trên `vite preview`.

## Review Focus

1. Bấm "Thêm vào giỏ" hai lần liên tiếp → một request, nút bận → test `double click adds once` (Task 4).
2. Giỏ guest hết hạn/cookie mất (`GET /carts/{gợi ý}` → 404) → giỏ rỗng, gợi ý bị xóa, không báo lỗi
   → test `an expired guest cart hint is dropped quietly` (Task 3).
3. Thêm vào giỏ lỗi mạng rồi bấm lại → cùng `Idempotency-Key` → test `retrying a failed add reuses its key` (Task 3).
4. Gõ số lượng ngoài 1–99 hoặc không phải số → không gửi PUT sai, ô trở về số hiện tại
   → test `an out-of-range quantity is not sent` (Task 5).
5. Gộp giỏ 409 hai lần → giữ giỏ guest và gợi ý, báo "sẽ thử lại", lần khởi động sau thử lại
   → test `a second merge conflict keeps the guest cart for later` (Task 6).

---

## File map (`src/` = `web/storefront/src/`)

| File | Trách nhiệm | Task |
|---|---|---|
| `src/auth/AuthProvider.tsx` (+ test) | bọc từng callback `onSignedIn` | 1 |
| `src/cart/api.ts`, fixtures `cart-guest.json`, `cart-member.json`, `cart-empty.json`, `merge.json`, `tests/contracts/test_storefront_fixtures.py`, `src/test/harness.tsx` | API giỏ có kiểu, fixture có contract, fallback `/carts/mine` 404 | 1 |
| `src/cart/display.ts` (+ test) | lưu/đọc thông tin hiển thị theo `variant_id` | 2 |
| `src/cart/CartProvider.tsx` (+ test), `src/app/App.tsx` | giỏ hiện tại, thao tác, 409, key | 3 |
| `src/cart/AddToCart.tsx`, `src/cart/AddedDrawer.tsx`, `src/catalog/ProductPage.tsx`, `src/app/Header.tsx` (+ test) | nút thêm, drawer xác nhận, badge, live region | 4 |
| `src/cart/CartPage.tsx` (+ test) | trang `/cart` | 5 |
| `src/cart/merge.ts`, `src/cart/MergeNotice.tsx` (+ test) | gộp giỏ sau đăng nhập | 6 |
| `tests/e2e/web-02.spec.ts`, docs, evidence | e2e + tài liệu | 7 |

---

### Task 1: An toàn `onSignedIn`, API giỏ, fixture, harness

**Files:** như File map dòng 1–2.

**Interfaces:**
- `AuthProvider.begin`: gọi từng callback trong `try/catch` riêng; lỗi một callback không làm `signIn`/`register` reject và
  không chặn callback khác (WEB-02a deferred minor).
- `src/cart/api.ts` — types theo `cart.yaml`: `Preview`, `CartItem`, `Cart`, `Adjustment`, `MergeResult = { cart: Cart;
  merged_from: string; already_merged: boolean; adjustments: Adjustment[] }`. Hàm (tham số `member: boolean` chọn
  `authRequest` hay `request`):
  `createCart(member): Promise<Cart>` (POST `/api/v1/carts`), `getCart(member, cartId)`, `getMyCart(): Promise<Cart>`
  (authRequest GET `/carts/mine`), `addItem(member, cartId, { variant_id, quantity, expected_version }, key)` (header
  `Idempotency-Key`), `setQuantity(member, cartId, itemId, quantity, expectedVersion)`, `removeItem(member, cartId, itemId,
  expectedVersion)` và `clearCart(member, cartId, expectedVersion)` (DELETE `?expected_version=`), `mergeCarts({ guest_cart_id,
  guest_expected_version, member_expected_version }, key): Promise<MergeResult>` (authRequest; cookie guest tự đi kèm).
- Harness fallback: `GET /api/v1/carts/mine` → 404 `NOT_FOUND`/`CART_NOT_FOUND` (test cũ ở trạng thái member không lỗi).
- Fixture (synthetic, variant id từ `product.json`): `cart-guest.json` (GUEST, 2 dòng: `…551` M/Blue preview OK, `…553` M/Red
  preview `active:false` → `preview_status: UNAVAILABLE`, `subtotal_preview: null`), `cart-member.json` (MEMBER, 1 dòng OK,
  `preview_status: OK`, `subtotal_preview` = giá × số lượng), `cart-empty.json` (GUEST, `items: []`, `EMPTY`), `merge.json`
  (`MergeResponse`, 1 adjustment `QUANTITY_CAPPED` requested 5 applied 3). Contract test map `cart.yaml#/components/schemas/…`.

- [ ] **Step 1: Tests** — `AuthProvider.test.tsx`: `a throwing onSignedIn callback does not fail the sign-in and others still run`;
  `src/cart/api.test.ts`: `guest calls carry no Authorization, member calls carry Bearer`, `addItem sends the Idempotency-Key header`,
  `remove and clear send expected_version as a query`, `mergeCarts posts the three versions`.
- [ ] **Step 2:** chạy → FAIL. Thêm fixture + map → `validate-contracts` FAIL cho tới khi đúng; phản chứng một fixture hỏng → FAIL.
- [ ] **Step 3:** Implement. **Step 4:** `npm test`, `validate-contracts`, `typecheck` PASS.
- [ ] **Step 5: Commit** — `feat(WEB-02b): typed cart API, contract-checked cart fixtures, isolated onSignedIn callbacks`

### Task 2: Thông tin hiển thị dòng giỏ

**Files:** Create `src/cart/display.ts`, `src/cart/display.test.ts`.

**Interfaces:**
- `type ItemDisplay = { product_id: string; name_vi: string; name_en: string; asset_id: string | null; color: string; size: string }`.
- `rememberDisplay(variantId: string, info: ItemDisplay): void`; `displayOf(variantId: string): ItemDisplay | null`. Lưu
  `localStorage['storefront.cartDisplay']`, giữ tối đa 200 variant gần nhất (bỏ cái cũ nhất), mọi lỗi storage → no-op/null.

- [ ] **Step 1: Tests**: `remembers and reads back`, `unknown variant is null`, `keeps at most 200 entries`,
  `a throwing or corrupt localStorage never throws` (getItem ném lỗi; giá trị không phải JSON).
- [ ] **Step 2–4:** FAIL → implement → PASS. **Step 5: Commit** — `feat(WEB-02b): client-side display data for cart lines`

### Task 3: `CartProvider`

**Files:** Create `src/cart/CartProvider.tsx`, `src/cart/CartProvider.test.tsx`; Modify `src/app/App.tsx` (CartProvider bọc
trong AuthProvider), `src/i18n.tsx`.

**Interfaces:**
- `useCart(): { cart: Cart | null; count: number; loading: boolean; notice: string | null; clearNotice(): void;
  add(variantId: string, quantity: number): Promise<'added' | 'conflict' | 'failed'>; setQuantity(itemId: string, quantity: number):
  Promise<void>; remove(itemId: string): Promise<void>; clear(): Promise<void>; reload(): Promise<void> }`.
  `count` = tổng `quantity`.
- Khởi động/đổi trạng thái auth: `guest` + có gợi ý → `getCart(false, hint)` (404 → xóa gợi ý, `cart = null`); `guest`
  không gợi ý → không gọi API; `member` → `getMyCart()` (404 → `cart = null`); member → guest → `cart = null`.
- `add`: chưa có giỏ → `createCart(member)` (guest lưu gợi ý) rồi `addItem`. Key: nếu lần `add` trước cùng
  `variantId+quantity` thất bại vì mạng/503 thì dùng lại key đó, ngược lại sinh key mới; thành công/409 → xóa key giữ lại.
  409 `VERSION_CONFLICT` → `reload()` + `notice` câu 409, trả `'conflict'`; lỗi khác → `'failed'`.
- `setQuantity`/`remove`/`clear`: gửi `expected_version` hiện tại; 409 → `reload()` + notice; response → thay `cart`.

- [ ] **Step 1: Tests**: `a visitor who only browses never calls /carts`; `the first add creates the cart and stores the hint`;
  `an expired guest cart hint is dropped quietly` (Review Focus 2); `a member loads /carts/mine`; `retrying a failed add reuses its key`
  (Review Focus 3: lần 1 → 503, lần 2 cùng variant/số lượng → cùng header `Idempotency-Key`; lần 3 khác số lượng → key khác);
  `a version conflict reloads and keeps every item` (sau 409, `cart.items` = response GET mới, notice câu 409);
  `signing out drops the member cart from view`.
- [ ] **Step 2–4:** FAIL → implement → PASS + `npm test`. **Step 5: Commit** — `feat(WEB-02b): cart provider with lazy cart, retry keys and conflict reload`

### Task 4: Nút "Thêm vào giỏ", drawer xác nhận, badge

**Files:** Create `src/cart/AddToCart.tsx`, `src/cart/AddedDrawer.tsx`, `src/cart/AddToCart.test.tsx`; Modify
`src/catalog/ProductPage.tsx` (nút trong `product-info` sau trạng thái tồn), `src/app/Header.tsx`, `src/ui/icons.tsx`
(`BagIcon`), `src/i18n.tsx`.

**Interfaces:**
- `AddToCart({ product, variant }: { product: Product; variant: Variant | undefined })`: disabled khi `!variant`; bận khi đang
  gửi (`aria-busy`, chặn bấm lặp); thành công → `rememberDisplay(variant.id, …)` (ảnh = ảnh đầu tiên cùng `variant_color`,
  không có thì ảnh đầu) và mở `AddedDrawer`; `'conflict'`/`'failed'` → thông báo ngay dưới nút (`role="alert"`).
- `AddedDrawer`: tên, màu/size, giá dòng vừa thêm; nút "Xem giỏ" (`/cart`) và "Tiếp tục mua" (đóng); không tự đóng.
- Header: link `/cart` (`BagIcon`, `aria-label` "Giỏ hàng, {n} sản phẩm"; số trên badge `aria-hidden`); menu mobile có mục giỏ.
  Một `<p role="status" className="sr-only">` toàn app (trong CartProvider) đọc câu "Đã thêm vào giỏ…" sau mỗi lần thêm.

- [ ] **Step 1: Tests**: `the button waits for a full variant`; `adding opens the drawer and announces the count`;
  `double click adds once` (Review Focus 1); `the header badge label counts items`; `a failed add shows the reason by the button`.
- [ ] **Step 2–4:** FAIL → implement → PASS + `npm test`. **Step 5: Commit** — `feat(WEB-02b): add to cart, confirmation drawer and header badge`

### Task 5: Trang `/cart`

**Files:** Create `src/cart/CartPage.tsx`, `src/cart/CartPage.test.tsx`; Modify `src/app/App.tsx`, `src/i18n.tsx`.

**Interfaces:** Route `/cart` (không cần đăng nhập). Mỗi dòng: ảnh/tên/màu/size từ `displayOf`, thiếu → placeholder + SKU;
giá dòng (Global Constraints); bộ số lượng: nút −/+ và ô `inputmode="numeric"` có label ẩn; PUT số tuyệt đối sau 400 ms
không đổi (debounce); giá trị ngoài 1–99/không phải số → không gửi, ô trở về số hiện tại; nút "Xóa" từng dòng (status
message); "Xóa giỏ" mở Drawer xác nhận (Hủy nhận focus). Dòng có `preview === null`, `!preview.active` hoặc
`preview.available < quantity` → nhãn "Không còn bán" / "Không đủ hàng (tham khảo)". `preview_status === 'OK'` → tạm tính
`subtotal_preview`; luôn có ghi chú giỏ; nút "Thanh toán" disabled kèm "Sắp có". Giỏ rỗng/không có giỏ → trạng thái trống +
link `/products`.

- [ ] **Step 1: Tests** (fake timers cho debounce): `shows lines with display data or SKU`; `quantity changes are sent once after 400 ms`;
  `an out-of-range quantity is not sent` (Review Focus 4: `0`, `100`, `abc`); `unavailable lines are marked and no subtotal is shown`;
  `subtotal shows only when preview_status is OK`; `clearing the cart asks first and focuses Cancel`; `checkout is disabled`;
  `an empty cart links to products`.
- [ ] **Step 2–4:** FAIL → implement → PASS + `npm test`. **Step 5: Commit** — `feat(WEB-02b): cart page`

### Task 6: Gộp giỏ sau đăng nhập

**Files:** Create `src/cart/merge.ts`, `src/cart/MergeNotice.tsx`, `src/cart/merge.test.tsx`; Modify `src/cart/CartProvider.tsx`, `src/i18n.tsx`.

**Interfaces:**
- `mergeGuestCart(): Promise<{ outcome: 'merged' | 'none' | 'later'; result?: MergeResult }>`: không có gợi ý → `'none'`.
  Đọc giỏ guest (`getCart(false, hint)`; 404 → xóa gợi ý, `'none'`), giỏ member (`getMyCart`, 404 → `createCart(true)`), lấy
  key từ `sessionStorage['storefront.merge']` nếu cùng `guest_cart_id`, không thì sinh mới và lưu; `mergeCarts`. 200 →
  xóa gợi ý + key, `'merged'`. 409 → đọc lại hai giỏ, thử lại **một lần** với key mới; vẫn 409 → `'later'` (giữ gợi ý).
  404 → xóa gợi ý, `'none'`. Mạng/503 → giữ gợi ý + key, `'later'`.
- `CartProvider`: đăng ký `onSignedIn(() => run merge)`; khi khởi động ở trạng thái member mà còn gợi ý → chạy merge.
  `'merged'` → `cart = result.cart`; `adjustments.length > 0` → mở `MergeNotice` (Drawer liệt kê SKU/tên, lý do, số yêu cầu →
  số áp dụng); `'later'` → notice "Chưa gộp được giỏ, sẽ thử lại."; lỗi trong merge không làm đăng nhập lỗi (Task 1).

- [ ] **Step 1: Tests**: `signing in with a guest cart merges it and shows the adjustments`; `already_merged is treated as done`;
  `a merge retried after reload sends the same key`; `a first conflict retries once with a new key`;
  `a second merge conflict keeps the guest cart for later` (Review Focus 5); `a missing guest cart clears the hint`;
  `no guest cart, no merge call`.
- [ ] **Step 2–4:** FAIL → implement → PASS + `npm test`. **Step 5: Commit** — `feat(WEB-02b): merge the guest cart on sign-in`

### Task 7: E2E, tài liệu, evidence

**Files:** Modify `tests/e2e/web-02.spec.ts`, `web/storefront/README.md`, `docs/design/14_frontend_behavior.md` (SF-05),
`docs/delivery/10_backlog.md`, `docs/design/08_decisions.md` (mục "WEB-02b — quyết định"), `docs/superpowers/plans/README.md`;
Create `docs/evidence/web-02b-local-<ngày>.md`.

- [ ] **Step 1: E2E**:
  - chuỗi thật (localhost): khách mở sản phẩm public đầu tiên (nếu có), chọn variant, thêm vào giỏ → badge 1 → đăng ký tài
    khoản mới → giỏ được gộp (badge vẫn ≥ 1, `/cart` có dòng) → đổi số lượng → xóa giỏ; không có sản phẩm public → kiểm `/cart`
    rỗng và dừng.
  - fixture (`page.route`): thêm vào giỏ từ chi tiết → drawer → `/cart` → đổi số lượng → 409 hiện câu cố định; đăng nhập
    với giỏ guest → MergeNotice hiện điều chỉnh.
- [ ] **Step 2:** `npm run build` + `vite preview` + `PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174 npx playwright test tests/e2e/web-02.spec.ts -g fixture` → PASS.
- [ ] **Step 3: Docs** — README (giỏ, khóa storage, giới hạn hiển thị), 14 SF-05, 10 (WEB-02b), 08 (6 quyết định spec §7),
  plans README; evidence (lệnh, kết quả, phần chưa chạy).
- [ ] **Step 4: Verify** — `npm run typecheck && npm test && npm run build && bash scripts/validate-contracts.sh && python3 -B -m unittest discover -s scripts -p 'test_*.py' && python3 -B scripts/check_docs.py && git diff --check` → PASS.
- [ ] **Step 5: Commit** — `docs(WEB-02b): e2e, behaviour docs, decisions and local evidence`

Sau Task 7: review toàn nhánh bằng subagent, sửa Critical/Important (RED→GREEN). Push `dev` và PR (tiếng Anh) khi chủ dự án yêu cầu.
