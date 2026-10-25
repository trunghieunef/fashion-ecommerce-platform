# WEB-03 — Checkout COD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Storefront có `/checkout` (liên hệ, địa chỉ, COD, báo giá server, đặt đơn một key), `/orders/:orderNo` (trạng thái +
poll) và `/account/orders` (đơn của member), theo giao diện WEB-01b và nền phiên/giỏ của WEB-02.

**Architecture:** `src/checkout/api.ts` gọi order API (member `authRequest`, guest `request` + header `Checkout-Token` lấy từ
cart). `src/checkout/submit.ts` giữ bản ghi đặt đơn (key + body) trong `sessionStorage` và gửi lại cùng key khi chưa biết kết
quả. `CheckoutPage` giữ form, báo giá và trạng thái nút; `OrderPage` poll theo `retry_after_seconds`.

**Tech Stack:** React 19.2.8, React Router 8.4.0, Tailwind 4.3.3, Vitest 5 + jsdom (harness), Playwright 1.63.

**Spec:** [WEB-03](../specs/2026-10-25-web-03-checkout-design.md) (đã duyệt 2026-10-25, gồm D10 mobile phương án B).

## Global Constraints

- Không thêm dependency; không đổi `contracts/` hay service. Fixture mới qua `tests/contracts/test_storefront_fixtures.py`
  (prefix `ORDER`, `order.yaml`).
- Tiền chỉ từ quote/đơn server (`totals`, `items[].line_total`), hiển thị bằng `Price`; client không cộng/nhân tiền.
- Không bao giờ tự gọi `POST /api/v1/orders`: chỉ khi khách bấm "Đặt hàng", hoặc gửi lại **cùng key + body** của bản ghi đang
  chờ (§4.1). `PRICE_CHANGED`/`QUOTE_EXPIRED`/`INVALID_QUOTE` chỉ dẫn tới quote lại, không đặt.
- Storage: `sessionStorage['storefront.checkout']` = `{ key, body: { cart_id, cart_version, quote_token } }`, bọc try/catch.
  `checkout_token` chỉ trong bộ nhớ (biến module trong `checkout/api.ts`), không storage/URL/log.
- Retry đặt đơn khi mạng/503: chờ 1, 2, 4, 8, 16 s (cap 30 s), tối đa 5 lần tự động, sau đó nút "Thử lại" cùng key.
- Poll đơn: chỉ khi `processing_status === 'PROCESSING'`, chờ `retry_after_seconds ?? 3` s, dừng khi tab ẩn, fetch ngay khi hiện
  lại, lỗi mạng backoff tới 30 s, dừng sau 120 s.
- Câu cố định (VI / EN):
  - chưa có quote: "Tổng tiền (gồm phí giao, phí COD) sẽ được tính khi bạn bấm Xem tổng tiền." / "The total (shipping and COD
    fee included) is calculated when you press See total."
  - hạn quote: "Giá giữ đến {time}" / "Price held until {time}"
  - hết hạn: "Báo giá đã hết hạn, bấm Xem tổng tiền để cập nhật." / "The quote expired, press See total to update it."
  - giá đổi: "Giá đã thay đổi. Vui lòng kiểm tra rồi đặt lại." / "Prices changed. Please check and place the order again."
  - đang gửi lại: "Chưa xác nhận được đơn, đang thử lại…" / "Could not confirm the order yet, retrying…"
  - poll dừng: "Đơn đang được xử lý, shop sẽ liên hệ với bạn." / "Your order is being processed, the shop will contact you."
  - không xem được đơn: "Không xem được đơn này. Nếu bạn đặt bằng thiết bị khác, hãy đăng nhập hoặc liên hệ shop." / "This
    order cannot be shown. If you ordered on another device, sign in or contact the shop."
- Nhãn trạng thái theo 14 §4 (PENDING "Đang chuẩn bị đơn", CONFIRMED "Đã tiếp nhận, chờ shop xác nhận", PACKING "Shop đang
  đóng gói", SHIPPING "Đã bàn giao để vận chuyển", DELIVERED "Đã giao", CANCELLING "Đang xử lý hủy", CANCELLED "Đơn đã hủy" +
  câu theo `reason_code`). Không hiện saga/lease/UNKNOWN.
- Giao diện: `PAGE`, `LABEL`, `BUTTON`, `BUTTON_OUTLINE`, `ui/form.tsx` (`useForm`, `Field`, `SelectField`, `ErrorSummary`,
  `SubmitButton`, `StatusMessage`); vùng chạm ≥ 44px; `autocomplete` đúng; desktop tóm tắt `lg:sticky`, mobile tóm tắt ở
  cuối trang không thanh dính (D10).
- File mới mở đầu bằng `// TASK:WEB-03: …`. Ledger `.superpowers/sdd/2026-10-25-web-03-checkout/progress.md`.
- Máy local không dựng stack: e2e chuỗi thật để CI; e2e fixture chạy trên `vite preview --host 127.0.0.1 --port 4174`.

## Review Focus

1. Bấm "Đặt hàng" hai lần trong cùng một tick → một request (ref guard) → test `double click places once` (Task 4).
2. Mất response (mạng) rồi tải lại trang → `/checkout` gửi lại cùng key + body, không tạo đơn thứ hai
   → test `a reload with a pending order resends the same key and body` (Task 4).
3. Sửa địa chỉ sau khi đã có báo giá → nút về "Xem tổng tiền", không đặt với báo giá cũ
   → test `editing a field drops the quote` (Task 3).
4. Báo giá hết hạn khi khách đang đọc → không tự đặt, không tự quote → test `an expired quote asks for a new one` (Task 3).
5. Guest mất `checkout_token` (15 phút) giữa quote và đặt → token mới, cùng key → test `an expired checkout token is renewed
   with the same key` (Task 4).

---

## File map (`src/` = `web/storefront/src/`)

| File | Trách nhiệm | Task |
|---|---|---|
| `src/checkout/api.ts` (+ test), fixtures, `tests/contracts/test_storefront_fixtures.py` | order API có kiểu, checkout token trong bộ nhớ | 1 |
| `src/checkout/submit.ts` (+ test) | bản ghi đặt đơn, gửi lại cùng key, backoff | 2 |
| `src/checkout/CheckoutPage.tsx`, `src/checkout/Summary.tsx` (+ test), `src/app/App.tsx`, `src/cart/CartPage.tsx`, `src/i18n.tsx` | form, quote, tóm tắt, route, bật "Thanh toán" | 3 |
| `src/checkout/CheckoutPage.tsx` (+ test) | đặt đơn và kết quả §4.2 | 4 |
| `src/orders/OrderPage.tsx`, `src/orders/poll.ts` (+ test), `src/orders/status.ts` | trang đơn, poll, nhãn trạng thái | 5 |
| `src/orders/OrdersPage.tsx` (+ test), `src/app/Header.tsx`, `src/account/AccountPage.tsx` | danh sách đơn member, link | 6 |
| `tests/e2e/web-03.spec.ts`, docs, evidence | e2e, tài liệu | 7 |

---

### Task 1: Order API, checkout token, fixtures

**Files:** Create `src/checkout/api.ts`, `src/checkout/api.test.ts`, fixtures `src/test/fixtures/{checkout-token,quote,order-processing,order-confirmed,order-cancelled,orders-mine}.json`. Modify `tests/contracts/test_storefront_fixtures.py`.

**Interfaces — Produces:**
- Types `Address`, `Contact`, `QuoteRequest`, `Item`, `Totals`, `Quote`, `Order`, `OrderSummary`, `CreateBody = { cart_id; cart_version; quote_token }` (theo `order.yaml`).
- `quote(member: boolean, body: QuoteRequest, lang: 'vi'|'en'): Promise<Quote>`
- `createOrder(member: boolean, key: string, body: CreateBody): Promise<Order>`
- `getOrder(orderNo: string, member: boolean): Promise<Order>`
- `myOrders(cursor: string | null): Promise<{ items: OrderSummary[]; next_cursor: string | null }>`
- `forgetCheckoutToken(): void` (dùng khi đổi giỏ/đăng nhập và trong test)

- [ ] **Step 1: Failing tests** (`api.test.ts`, `mockFetch`):
  - `a guest quote fetches a checkout token first and sends it` → calls `POST /api/v1/carts/{id}/checkout-token` rồi `POST /api/v1/orders/quote` với header `checkout-token: example-checkout-token`, `auth` null.
  - `the checkout token is reused until 30 s before expiry` → hai quote, một lần lấy token; token `expires_at` còn 20 s → lấy lại.
  - `a 401 INVALID_CHECKOUT_TOKEN renews the token and resends once` → token gọi 2 lần, request gửi 2 lần cùng body; lần 401 thứ hai ném `HttpError(401)`.
  - `a member quote uses the Bearer and no checkout token`.
  - `createOrder sends the Idempotency-Key` → header `idempotency-key: k1`, body đúng `CreateBody`.
  - `quote sends Accept-Language` → `lang` của call = `en`.
- [ ] **Step 2:** `cd web/storefront && npx vitest run src/checkout/api.test.ts` → FAIL (module không có).
- [ ] **Step 3:** Implement. `quote` đặt `headers['Accept-Language'] = lang` (`http.ts` không tự gửi). Guest gọi `request` với `headers['Checkout-Token']`; renew khi thiếu, còn < 30 s, hoặc `HttpError(401, 'UNAUTHORIZED')` có `message === 'INVALID_CHECKOUT_TOKEN'` (một lần). Fixture lấy từ ví dụ `order.yaml` (synthetic), `order-cancelled.json` có `reason_code: OUT_OF_STOCK`, `processing_status: FINISHED`. Thêm 6 fixture vào registry với schema tương ứng (`CheckoutTokenResponse` của `cart.yaml`, `QuoteResponse`, `OrderResponse`, `OrderPageResponse`).
- [ ] **Step 4:** Vitest file PASS; `bash scripts/validate-contracts.sh` → OK, cộng 6 test fixture.
- [ ] **Step 5: Commit** `feat(WEB-03): typed order API, in-memory checkout token, contract-checked order fixtures`

### Task 2: Submit record and same-key resend

**Files:** Create `src/checkout/submit.ts`, `src/checkout/submit.test.ts`.

**Interfaces — Consumes:** `createOrder` (Task 1). **Produces:**
- `type Pending = { key: string; body: CreateBody }`
- `readPending(): Pending | null`, `clearPending(): void`
- `startPending(body: CreateBody): Pending` — key mới (`crypto.randomUUID()`), ghi storage **trước** khi gửi.
- `send(member: boolean, pending: Pending, wait?: (ms: number) => Promise<void>, onRetry?: (attempt: number) => void): Promise<Order>` — gửi; `HttpError` status 0/503 → chờ `min(1000·2^n, 30000)` rồi gửi lại cùng key + body, tối đa 5 lần tự động, rồi ném lỗi cuối. Lỗi khác ném ngay. 202 → `clearPending()` rồi trả `Order`. Không tự xóa bản ghi khi ném lỗi (caller quyết theo §4.2).

- [ ] **Step 1: Failing tests:**
  - `startPending stores key and body before anything is sent` → `sessionStorage['storefront.checkout']` có `key` và `body`.
  - `network failures resend the same key and body with backoff` → 2 lần status 0 rồi 202: 3 request cùng `idempotency-key`, cùng body; `wait` nhận `[1000, 2000]`.
  - `after five automatic retries the last error is thrown and the record is kept`.
  - `a 409 is thrown at once and the record is kept` (caller xử lý).
  - `a 202 clears the record`.
  - `blocked storage still sends` (stub `sessionStorage.setItem` throw).
- [ ] **Step 2:** `npx vitest run src/checkout/submit.test.ts` → FAIL.
- [ ] **Step 3:** Implement như Interfaces (`wait` mặc định `setTimeout`).
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(WEB-03): order submit record with same-key resend and capped backoff`

### Task 3: Checkout page — form, quote, summary

**Files:** Create `src/checkout/CheckoutPage.tsx`, `src/checkout/Summary.tsx`, `src/checkout/CheckoutPage.test.tsx`. Modify `src/app/App.tsx` (route `/checkout`), `src/cart/CartPage.tsx` (nút "Thanh toán" là `Link` tới `/checkout` khi có dòng; bỏ `checkoutSoon`), `src/i18n.tsx`.

**Interfaces — Consumes:** `quote`, `forgetCheckoutToken` (Task 1); `useCart()`; `useAuth()`; `listAddresses`, `placeName`, `geo.sample.json` (WEB-02a). **Produces:** `CheckoutPage`; `Summary({ cartItems, quote, priceChanges, expired, children })` (nút chính truyền qua `children`); state `quoted: { quote: Quote; at: values }` mà Task 4 dùng.

- [ ] **Step 1: Failing tests** (`render('/checkout')`, fixture giỏ guest/member):
  - `an empty cart goes back to /cart`.
  - `a guest sees contact, address, COD and a sign-in link` → link `/login?next=%2Fcheckout`; email bắt buộc.
  - `a member gets the default saved address selected and its email filled` → radio địa chỉ mặc định `checked`; email = `profile.email`.
  - `See total sends the cart version and shows the server totals` → body quote có `cart_id`, `cart_version`, `method: 'COD'`, `note: null`; tóm tắt hiện 340.000 ₫ và "Giá giữ đến"; nút "Đặt hàng · 340.000 ₫".
  - `invalid fields stop the quote and focus the summary` → không có call quote; `ErrorSummary` focus.
  - `editing a field drops the quote` → sau khi có quote, đổi địa chỉ chi tiết → nút "Xem tổng tiền", không còn "Giá giữ đến".
  - `an expired quote asks for a new one` (fake timers tới `expires_at`) → câu hết hạn trong `role=status`, nút "Xem tổng tiền", không có call `/orders`.
  - `quote errors are said plainly`: 400 `INVALID_FIELDS` field `shipping_address.phone` → lỗi dưới ô điện thoại người nhận; 409 `VERSION_CONFLICT` → giỏ tải lại + câu `cartChanged`; 409 `ITEM_UNAVAILABLE` → liệt kê SKU + link `/cart`; 422 `NO_SHIPPING_RULE` → "Chưa giao được tới khu vực này"; 503 → "Chưa tính được tổng tiền, vui lòng thử lại".
  - `the cart page links to checkout when it has lines` (`CartPage.test.tsx`).
- [ ] **Step 2:** `npx vitest run src/checkout src/cart/CartPage.test.tsx` → FAIL.
- [ ] **Step 3:** Implement theo spec §2–§3. Form dùng `useForm` với `phone`, `email` (guest bắt buộc), `maxLength` như `AddressForm`; map `errors[].field` `shipping_address.X` → trường `X`, `contact.X` → `contact_X`. Báo giá lưu kèm snapshot giá trị form; mọi `onChange` sau đó đặt `quoted = null`. Hết hạn: một `setTimeout` tới `expires_at`. Tóm tắt `lg:sticky lg:top-24`, mobile ở cuối (`order-last`).
- [ ] **Step 4:** PASS; `npm run typecheck`.
- [ ] **Step 5: Commit** `feat(WEB-03): checkout form with server quote, expiry and plain quote errors`

### Task 4: Place the order

**Files:** Modify `src/checkout/CheckoutPage.tsx`, `src/checkout/CheckoutPage.test.tsx`, `src/i18n.tsx`.

**Interfaces — Consumes:** `startPending`, `readPending`, `clearPending`, `send` (Task 2); `useCart().reload`. **Produces:** điều hướng `navigate('/orders/{order_no}', { replace: true })`.

- [ ] **Step 1: Failing tests:**
  - `placing an order sends the quote and goes to the order page` → body `{cart_id, cart_version, quote_token}`, có `idempotency-key`, URL `/orders/ORD202610000001`, giỏ được tải lại.
  - `double click places once`.
  - `a lost response is retried with the same key and says so` → status 0 rồi 202; câu "đang thử lại" hiện trong lúc chờ.
  - `a reload with a pending order resends the same key and body` → `sessionStorage` có bản ghi trước khi render → request ngay với key đó, không gọi quote.
  - `prices changed: a new quote is shown and nothing is placed` → 409 `PRICE_CHANGED` (`errors: [{field: variant_id, message: 'old=100000,new=120000'}]`) → một call quote mới, câu giá đổi + "100.000 ₫ → 120.000 ₫", chỉ một call `/orders`; bấm lại → key mới.
  - `an expired quote on submit goes back to See total` (409 `QUOTE_EXPIRED`), `an invalid quote is quoted again` (400 `INVALID_QUOTE`, không đặt).
  - `an order already placed for this cart opens it` → 409 `CART_ALREADY_ORDERED` `errors[0].message = 'ORD202610000009'` → `/orders/ORD202610000009`.
  - `an expired checkout token is renewed with the same key` → 401 `INVALID_CHECKOUT_TOKEN` rồi 202: hai `/orders` cùng key.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** Implement theo §4.1–§4.2; ref `placing` chặn bấm lặp; `useEffect` lúc mount: có `readPending()` → gửi ngay.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(WEB-03): place the order once per confirmation, resend the same key, reconfirm changed prices`

### Task 5: Order page and polling

**Files:** Create `src/orders/OrderPage.tsx`, `src/orders/poll.ts`, `src/orders/status.ts`, `src/orders/OrderPage.test.tsx`, `src/orders/poll.test.ts`. Modify `src/app/App.tsx` (route `/orders/:orderNo`), `src/i18n.tsx`.

**Interfaces — Consumes:** `getOrder` (Task 1). **Produces:**
- `usePolledOrder(orderNo: string): { state: 'loading' | 'ready' | 'missing' | 'error'; order: Order | null; stalled: boolean; check(): void }`
- `statusText(order: Order, t): string` và `MILESTONES` (`PENDING|CONFIRMED` → 1, `PACKING` → 2, `SHIPPING` → 3, `DELIVERED` → 4).

- [ ] **Step 1: Failing tests** (fake timers):
  - `poll.test.ts`: `polls while PROCESSING after retry_after_seconds and stops at READY`; `pauses while the tab is hidden and fetches when it shows again`; `stops after two minutes and reports stalled`; `network errors back off up to 30 s`.
  - `OrderPage.test.tsx`: `shows the order number, milestones, items and totals`; `a confirmed COD order says the shop will confirm`; `a cancelled out-of-stock order links back to the cart`; `a 404 says the order cannot be shown`; `a status change is announced` (`role=status`); `the heading gets focus`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** Implement. Ảnh dòng: `image_url` của đơn dùng trực tiếp làm `src` (null → khung "Chưa có ảnh" như `CatalogImage`). Member dựa `useAuth().status === 'member'`.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(WEB-03): order page with milestones and bounded polling`

### Task 6: Member order list

**Files:** Create `src/orders/OrdersPage.tsx`, `src/orders/OrdersPage.test.tsx`. Modify `src/app/App.tsx` (`/account/orders` trong `RequireAuth`), `src/app/Header.tsx` (mục "Đơn hàng của tôi" trong menu tài khoản), `src/account/AccountPage.tsx` (link), `src/i18n.tsx`.

**Interfaces — Consumes:** `myOrders` (Task 1), `usePaged`/`LoadMore` (catalog/ui), `statusText` (Task 5).

- [ ] **Step 1: Failing tests:** `lists orders newest first with links`; `Load more sends next_cursor`; `no orders shows the empty state with a shop link`; `a guest is sent to sign in` (RequireAuth).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(WEB-03): member order list`

### Task 7: E2E, docs, evidence

**Files:** Create `tests/e2e/web-03.spec.ts`, `docs/evidence/web-03-local-2026-10-25.md`. Modify `web/storefront/README.md`, `docs/design/14_frontend_behavior.md` (SF-06/07), `docs/delivery/10_backlog.md` (WEB-03 In progress), `docs/design/08_decisions.md` (bảng "WEB-03 — quyết định 2026-10-25" từ §8 D1–D10), `docs/superpowers/plans/README.md`.

- [ ] **Step 1:** E2E fixture (routes như `web-02.spec.ts`): `guest checkout: quote, price change, reconfirm, order page polls to confirmed` (desktop + 360px, không cuộn ngang); `a lost order response and a reload place one order` (đếm request `/orders` cùng key).
- [ ] **Step 2:** E2E chuỗi thật (CI): `guest adds, checks out with COD and sees the confirmed order` (không có sản phẩm public → chỉ kiểm `/checkout` về `/cart`); `a member sees the order in /account/orders`.
- [ ] **Step 3:** Chạy fixture: `npx vite preview --host 127.0.0.1 --port 4174` + `PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174 npx playwright test tests/e2e -g fixture` → tất cả PASS.
- [ ] **Step 4:** Docs + evidence (lệnh, số test, phần chưa chạy); `python3 -B scripts/check_docs.py`, docs unittest, `git diff --check`; full `npm test`, `npm run typecheck`, `npm run build`, `bash scripts/validate-contracts.sh`.
- [ ] **Step 5: Commit** `docs(WEB-03): e2e, behaviour docs, decisions and local evidence`

Sau Task 7: review toàn nhánh bằng subagent, sửa Critical/Important (RED→GREEN). Push `dev` và PR (tiếng Anh) khi chủ dự án
yêu cầu.
