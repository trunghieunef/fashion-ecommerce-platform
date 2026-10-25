// @vitest-environment jsdom
// TASK:WEB-03: /checkout — contact, address, COD, a server quote the visitor confirms; nothing is ever ordered on its own.
import { act } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import addresses from '../test/fixtures/addresses.json';
import cartGuest from '../test/fixtures/cart-guest.json';
import cartEmpty from '../test/fixtures/cart-empty.json';
import cartMember from '../test/fixtures/cart-member.json';
import checkoutToken from '../test/fixtures/checkout-token.json';
import profile from '../test/fixtures/profile.json';
import orderFixture from '../test/fixtures/order-processing.json';
import quoteFixture from '../test/fixtures/quote.json';
import refresh from '../test/fixtures/refresh.json';
import { forgetCheckoutToken } from './api';
import { FakeChannel, flush, json, mockFetch, render, type Call } from '../test/harness';

beforeEach(() => {
  FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); forgetCheckoutToken();
  localStorage.setItem('storefront.cart', GUEST);
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear(); document.body.innerHTML = ''; });

export const metadata = { request_id: 'web03-test', trace_id: 'web03-test' };
const GUEST = cartGuest.data.cart_id;
type Answer = (path: string) => Response | Promise<Response> | null;
/** A guest with the guest cart; `answer` handles order calls. */
export const guestShop = (answer: Answer = () => null) => (path: string) => answer(path)
  ?? (path === `/api/v1/carts/${GUEST}` ? json(cartGuest) : path === `/api/v1/carts/${GUEST}/checkout-token` ? json(checkoutToken)
    : path === '/api/v1/orders/quote' ? json(quoteFixture) : null);
const memberShop = (answer: Answer = () => null) => (path: string) => answer(path)
  ?? (path === '/api/v1/auth/refresh' ? json(refresh) : path === '/api/v1/users/me' ? json(profile)
    : path === '/api/v1/carts/mine' ? json(cartMember) : path === '/api/v1/users/me/addresses' ? json(addresses)
      : path === '/api/v1/orders/quote' ? json({ ...quoteFixture, data: { ...quoteFixture.data, cart_id: cartMember.data.cart_id, cart_version: 4 } }) : null);
/** common.yaml ApiError.code: only these exist; the specific reason travels in `message` (e.g. CONFLICT + CART_ALREADY_ORDERED). */
type ApiCode = 'VALIDATION_ERROR' | 'UNAUTHORIZED' | 'FORBIDDEN' | 'NOT_FOUND' | 'CONFLICT' | 'VERSION_CONFLICT' | 'PRICE_CHANGED'
  | 'QUOTE_EXPIRED' | 'OUT_OF_STOCK' | 'PROMOTION_LIMIT' | 'ALREADY_REVIEWED' | 'VOUCHER_INVALID' | 'PAYMENT_AMOUNT_UNSUPPORTED'
  | 'ACCOUNT_LOCKED' | 'RATE_LIMITED' | 'TEMPORARILY_UNAVAILABLE' | 'INTERNAL';
const problem = (status: number, code: ApiCode, message: string = code, errors?: { field: string; message: string }[]) =>
  json({ code, message, errors, metadata }, status);

export const main = (c: Element) => c.querySelector('main')!;
export const input = (c: Element, id: string) => c.querySelector(`#${id}`) as HTMLInputElement | HTMLSelectElement;
export const primary = (c: Element) => c.querySelector('button[type="submit"][form="checkout"]') as HTMLButtonElement;
const quotes = (calls: Call[]) => calls.filter((c) => c.url === '/api/v1/orders/quote');
export async function type(el: HTMLInputElement | HTMLSelectElement, value: string) {
  await act(async () => {
    const proto = el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(el, value);
    el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}
export async function fillGuest(c: Element) {
  await type(input(c, 'contact_phone'), '0912345678');
  await type(input(c, 'contact_email'), 'khach@example.test');
  await type(input(c, 'recipient_name'), 'Khách Mẫu');
  await type(input(c, 'phone'), '0912345678');
  await type(input(c, 'province_code'), '79');
  await type(input(c, 'ward_code'), '26734');
  await type(input(c, 'address_line'), '12 Đường Mẫu');
}
export async function press(c: Element) {
  await act(async () => { primary(c).click(); });
  await flush();
}

it('an empty cart goes back to /cart', async () => {
  mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? json(cartEmpty) : null));
  const c = await render('/checkout');
  expect(c.querySelector('[data-testid="location"]')!.textContent).toBe('/cart');
});

it('a guest sees contact, address, COD and a sign-in link; invalid fields stop the quote', async () => {
  const calls = mockFetch(guestShop());
  const c = await render('/checkout');
  expect(main(c).querySelector('a[href="/login?next=%2Fcheckout"]')).not.toBeNull();
  expect((main(c).querySelector('input[name="method"][value="COD"]') as HTMLInputElement).checked).toBe(true);
  expect(primary(c).textContent).toBe('Xem tổng tiền');
  await press(c);
  expect(quotes(calls)).toHaveLength(0);
  expect(document.activeElement?.getAttribute('role')).toBe('alert');
  expect(input(c, 'contact_email').getAttribute('aria-invalid')).toBe('true');
});

it('a member gets the default saved address selected and its email filled', async () => {
  localStorage.clear();
  const calls = mockFetch(memberShop());
  const c = await render('/checkout');
  await flush();
  const saved = main(c).querySelector(`input[name="address"][value="${addresses.data[0].id}"]`) as HTMLInputElement;
  expect(saved.checked).toBe(true);
  expect(input(c, 'contact_email').value).toBe(profile.data.email);
  await type(input(c, 'contact_phone'), '0912345678');
  await press(c);
  const sent = quotes(calls)[0];
  expect(sent.auth).toBe(`Bearer ${refresh.data.access_token}`);
  expect((sent.body as { shipping_address: unknown }).shipping_address).toEqual({
    recipient_name: 'Khách Mẫu', phone: '0912345678', province_code: '79', ward_code: '26734', address_line: '1 Đường Mẫu' });
});

it('See total sends the cart version and shows the server totals', async () => {
  const calls = mockFetch(guestShop());
  const c = await render('/checkout');
  await fillGuest(c);
  await press(c);
  expect(quotes(calls)[0].body).toEqual({
    cart_id: GUEST, cart_version: cartGuest.data.version, method: 'COD',
    shipping_address: { recipient_name: 'Khách Mẫu', phone: '0912345678', province_code: '79', ward_code: '26734', address_line: '12 Đường Mẫu' },
    contact: { phone: '0912345678', email: 'khach@example.test' }, note: null,
  });
  const summary = c.querySelector('[data-testid="summary"]')!;
  expect(summary.textContent).toMatch(/Tổng cộng\s*340\.000\s₫/);
  expect(summary.textContent).toContain('Giá giữ đến');
  expect(primary(c).textContent).toMatch(/^Đặt hàng · 340\.000\s₫$/);
});

it('editing a field drops the quote', async () => {
  mockFetch(guestShop());
  const c = await render('/checkout');
  await fillGuest(c);
  await press(c);
  await type(input(c, 'address_line'), '99 Đường Khác');
  expect(primary(c).textContent).toBe('Xem tổng tiền');
  expect(c.querySelector('[data-testid="summary"]')!.textContent).not.toContain('Giá giữ đến');
});

it('an expired quote asks for a new one', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const calls = mockFetch(guestShop());
  const c = await render('/checkout');
  await fillGuest(c);
  await press(c);
  await act(async () => { await vi.advanceTimersByTimeAsync(110_000); });
  expect(primary(c).textContent).toMatch(/^Đặt hàng/);
  await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
  expect(primary(c).textContent).toBe('Xem tổng tiền');
  expect(main(c).querySelector('[role="status"]')!.textContent).toBe('Báo giá đã hết hạn, bấm Xem tổng tiền để cập nhật.');
  expect(calls.some((x) => x.url === '/api/v1/orders')).toBe(false);
});

async function quoteFails(response: () => Response) {
  const calls = mockFetch(guestShop((path) => (path === '/api/v1/orders/quote' ? response() : null)));
  const c = await render('/checkout');
  await fillGuest(c);
  await press(c);
  return { c, calls };
}

it('a field the server rejects is marked under that field', async () => {
  const { c } = await quoteFails(() => problem(400, 'VALIDATION_ERROR', 'INVALID_FIELDS', [{ field: 'shipping_address.phone', message: 'pattern' }]));
  expect(input(c, 'phone').getAttribute('aria-invalid')).toBe('true');
  expect(c.querySelector('#phone-error')!.textContent).toBe('Giá trị không hợp lệ.');
});

it('a cart changed elsewhere reloads it and says so', async () => {
  const { c, calls } = await quoteFails(() => problem(409, 'VERSION_CONFLICT'));
  expect(calls.filter((x) => x.url === `/api/v1/carts/${GUEST}`).length).toBeGreaterThan(1);
  expect(main(c).textContent).toContain('Giỏ hàng vừa thay đổi ở nơi khác, vui lòng kiểm tra lại.');
  expect(quotes(calls)).toHaveLength(1);
});

it('unavailable items are listed with a link to the cart', async () => {
  const { c } = await quoteFails(() => problem(409, 'CONFLICT', 'ITEM_UNAVAILABLE',
    [{ field: cartGuest.data.items[1].variant_id, message: 'inactive' }]));
  const alert = main(c).querySelector('[role="alert"]')!;
  expect(alert.textContent).toContain('TEE-M-RED');
  expect(alert.querySelector('a[href="/cart"]')).not.toBeNull();
});

it('no shipping rule is said at the address', async () => {
  const { c } = await quoteFails(() => problem(422, 'VALIDATION_ERROR', 'NO_SHIPPING_RULE'));
  expect(c.querySelector('#province_code-error')!.textContent).toBe('Chưa giao được tới khu vực này.');
});

it('a quote outage asks to try again', async () => {
  const { c } = await quoteFails(() => problem(503, 'TEMPORARILY_UNAVAILABLE', 'DEPENDENCY_UNAVAILABLE'));
  expect(main(c).querySelector('[role="alert"]')!.textContent).toContain('Chưa tính được tổng tiền, vui lòng thử lại.');
  expect(primary(c).textContent).toBe('Xem tổng tiền');
});

// --- Task 4: placing the order (spec §4) ---
const orders = (calls: Call[]) => calls.filter((c) => c.url === '/api/v1/orders');
const location = (c: Element) => c.querySelector('[data-testid="location"]')!.textContent;
const placed = () => json(orderFixture, 202);
async function quoted(answer: Answer = () => null) {
  const calls = mockFetch(guestShop(answer));
  const c = await render('/checkout');
  await fillGuest(c);
  await press(c);
  return { c, calls };
}

it('placing an order sends the quote and goes to the order page', async () => {
  const { c, calls } = await quoted((path) => (path === '/api/v1/orders' ? placed() : null));
  await press(c);
  const sent = orders(calls);
  expect(sent).toHaveLength(1);
  expect(sent[0].body).toEqual({ cart_id: GUEST, cart_version: cartGuest.data.version, quote_token: 'example-signed-quote' });
  expect(sent[0].headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
  expect(location(c)).toBe('/orders/ORD202610000001');
  expect(sessionStorage.getItem('storefront.checkout')).toBeNull();
});

it('double click places once', async () => {
  const { c, calls } = await quoted((path) => (path === '/api/v1/orders' ? placed() : null));
  await act(async () => { primary(c).click(); primary(c).click(); });
  await flush();
  expect(orders(calls)).toHaveLength(1);
});

it('a lost response is retried with the same key and says so', async () => {
  let n = 0;
  const { c, calls } = await quoted((path) => (path === '/api/v1/orders' ? (n++ === 0 ? Promise.reject(new TypeError('offline')) : placed()) : null));
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  await press(c);
  expect(main(c).textContent).toContain('Chưa xác nhận được đơn, đang thử lại…');
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  await flush();
  const sent = orders(calls);
  expect(sent).toHaveLength(2);
  expect(sent[1].headers['idempotency-key']).toBe(sent[0].headers['idempotency-key']);
  expect(location(c)).toBe('/orders/ORD202610000001');
});

it('a reload with a pending order resends the same key and body', async () => {
  const body = { cart_id: GUEST, cart_version: 2, quote_token: 'example-signed-quote' };
  sessionStorage.setItem('storefront.checkout', JSON.stringify({ key: 'saved-key', owner: 'guest', body }));
  const calls = mockFetch(guestShop((path) => (path === '/api/v1/orders' ? placed() : null)));
  const c = await render('/checkout');
  await flush();
  expect(orders(calls).map((x) => [x.headers['idempotency-key'], x.body])).toEqual([['saved-key', body]]);
  expect(calls.some((x) => x.url === '/api/v1/orders/quote')).toBe(false);
  expect(location(c)).toBe('/orders/ORD202610000001');
});

it('prices changed: a new quote is shown and nothing is placed', async () => {
  let first = true;
  const changed = { ...quoteFixture, data: { ...quoteFixture.data, quote_token: 'second-quote',
    totals: { ...quoteFixture.data.totals, subtotal: 320000, total_amount: 360000 } } };
  const { c, calls } = await quoted((path) => (path === '/api/v1/orders/quote' && !first ? json(changed)
    : path === '/api/v1/orders' ? (first ? (first = false, problem(409, 'PRICE_CHANGED', 'PRICE_CHANGED',
      [{ field: quoteFixture.data.items[0].variant_id, message: 'old=100000,new=120000' }])) : placed()) : null));
  await press(c);
  expect(quotes(calls)).toHaveLength(2);
  expect(orders(calls)).toHaveLength(1);
  const alert = main(c).querySelector('[role="alert"]')!.textContent!;
  expect(alert).toContain('Giá đã thay đổi. Vui lòng kiểm tra rồi đặt lại.');
  expect(alert).toMatch(/100\.000\s₫ → 120\.000\s₫/);
  expect(primary(c).textContent).toMatch(/^Đặt hàng · 360\.000\s₫$/);
  await press(c);
  const sent = orders(calls);
  expect(sent).toHaveLength(2);
  expect(sent[1].headers['idempotency-key']).not.toBe(sent[0].headers['idempotency-key']);
  expect((sent[1].body as { quote_token: string }).quote_token).toBe('second-quote');
});

it('an expired quote on submit goes back to See total', async () => {
  const { c, calls } = await quoted((path) => (path === '/api/v1/orders' ? problem(409, 'QUOTE_EXPIRED') : null));
  await press(c);
  expect(primary(c).textContent).toBe('Xem tổng tiền');
  expect(orders(calls)).toHaveLength(1);
  expect(sessionStorage.getItem('storefront.checkout')).toBeNull();
});

it('an invalid quote is quoted again, not placed', async () => {
  const { c, calls } = await quoted((path) => (path === '/api/v1/orders' ? problem(400, 'VALIDATION_ERROR', 'INVALID_QUOTE') : null));
  await press(c);
  expect(quotes(calls)).toHaveLength(2);
  expect(orders(calls)).toHaveLength(1);
});

it('an order already placed for this cart opens it', async () => {
  const { c } = await quoted((path) => (path === '/api/v1/orders'
    ? problem(409, 'CONFLICT', 'CART_ALREADY_ORDERED', [{ field: 'order_no', message: 'ORD202610000009' }]) : null));
  await press(c);
  expect(location(c)).toBe('/orders/ORD202610000009');
});

it('an expired checkout token is renewed with the same key', async () => {
  let n = 0;
  const { c, calls } = await quoted((path) => (path === '/api/v1/orders'
    ? (n++ === 0 ? problem(401, 'UNAUTHORIZED', 'INVALID_CHECKOUT_TOKEN') : placed()) : null));
  await press(c);
  const sent = orders(calls);
  expect(sent).toHaveLength(2);
  expect(sent[1].headers['idempotency-key']).toBe(sent[0].headers['idempotency-key']);
  expect(location(c)).toBe('/orders/ORD202610000001');
});

// --- final review fixes ---
it('a client clock running fast still gets a usable quote', async () => {
  const skewed = { ...quoteFixture, data: { ...quoteFixture.data, expires_at: new Date(Date.now() - 60_000).toISOString() } };
  mockFetch(guestShop((path) => (path === '/api/v1/orders/quote' ? json(skewed) : null)));
  const c = await render('/checkout');
  await fillGuest(c);
  await press(c);
  expect(primary(c).textContent).toMatch(/^Đặt hàng · 340\.000\s₫$/);
});

it('the main button resends the stuck order with the same key', async () => {
  let up = false;
  const { c, calls } = await quoted((path) => (path === '/api/v1/orders' ? (up ? placed() : Promise.reject(new TypeError('offline'))) : null));
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  await press(c);
  await act(async () => { await vi.advanceTimersByTimeAsync(31_000); });
  await flush();
  expect(main(c).textContent).toContain('Chưa xác nhận được đơn. Bấm Thử lại');
  // Signing in now would replay this guest order as the member (PR #40 review I3).
  expect(main(c).querySelector('a[href^="/login"]')).toBeNull();
  up = true;
  await press(c);
  const keys = new Set(orders(calls).map((x) => x.headers['idempotency-key']));
  expect(keys.size).toBe(1);
  expect(location(c)).toBe('/orders/ORD202610000001');
});

it('a resumed order with unavailable items says so and links to the cart', async () => {
  sessionStorage.setItem('storefront.checkout', JSON.stringify({ key: 'saved-key', owner: 'guest', body: { cart_id: GUEST, cart_version: 2, quote_token: 'q' } }));
  mockFetch(guestShop((path) => (path === '/api/v1/orders'
    ? problem(409, 'CONFLICT', 'ITEM_UNAVAILABLE', [{ field: cartGuest.data.items[1].variant_id, message: 'inactive' }]) : null)));
  const c = await render('/checkout');
  await flush();
  const alert = [...main(c).querySelectorAll('[role="alert"]')].find((x) => x.querySelector('a[href="/cart"]'));
  expect(alert).toBeTruthy();
});

// --- PR #40 review ---
it('a pending order is stored with who placed it', async () => {
  const { c } = await quoted((path) => (path === '/api/v1/orders' ? Promise.reject(new TypeError('offline')) : null));
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  await press(c);
  expect(JSON.parse(sessionStorage.getItem('storefront.checkout')!).owner).toBe('guest');
});

it('a guest order still pending after signing in is not replayed as the member', async () => {
  sessionStorage.setItem('storefront.checkout', JSON.stringify({ key: 'saved-key', owner: 'guest', body: { cart_id: GUEST, cart_version: 2, quote_token: 'q' } }));
  const calls = mockFetch(memberShop());
  const c = await render('/checkout');
  await flush();
  expect(orders(calls)).toHaveLength(0);
  expect(quotes(calls)).toHaveLength(0);
  expect(main(c).textContent).toContain('Đơn bạn xác nhận trước đó có thể đã được tạo');
  expect(sessionStorage.getItem('storefront.checkout')).toBeNull();
});

it('signing out forgets the pending order', async () => {
  sessionStorage.setItem('storefront.checkout', JSON.stringify({ key: 'saved-key', owner: profile.data.id, body: { cart_id: 'x', cart_version: 1, quote_token: 'q' } }));
  mockFetch(memberShop((path) => (path === '/api/v1/auth/logout' ? new Response(null, { status: 204 }) : null)));
  const c = await render('/account');
  const button = [...c.querySelectorAll('main button')].find((b) => b.textContent === 'Đăng xuất') as HTMLButtonElement;
  await act(async () => { button.click(); });
  await flush();
  expect(sessionStorage.getItem('storefront.checkout')).toBeNull();
});
