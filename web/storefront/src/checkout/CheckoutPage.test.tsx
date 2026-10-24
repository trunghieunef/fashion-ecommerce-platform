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
const problem = (status: number, code: string, message = code, errors?: { field: string; message: string }[]) =>
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
  const soon = { ...quoteFixture, data: { ...quoteFixture.data, expires_at: new Date(Date.now() + 2000).toISOString() } };
  const calls = mockFetch(guestShop((path) => (path === '/api/v1/orders/quote' ? json(soon) : null)));
  const c = await render('/checkout');
  await fillGuest(c);
  await press(c);
  expect(primary(c).textContent).toMatch(/^Đặt hàng/);
  await act(async () => { await vi.advanceTimersByTimeAsync(2500); });
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
  const { c } = await quoteFails(() => problem(409, 'ITEM_UNAVAILABLE', 'ITEM_UNAVAILABLE',
    [{ field: cartGuest.data.items[1].variant_id, message: 'inactive' }]));
  const alert = main(c).querySelector('[role="alert"]')!;
  expect(alert.textContent).toContain('TEE-M-RED');
  expect(alert.querySelector('a[href="/cart"]')).not.toBeNull();
});

it('no shipping rule is said at the address', async () => {
  const { c } = await quoteFails(() => problem(422, 'UNPROCESSABLE', 'NO_SHIPPING_RULE'));
  expect(c.querySelector('#province_code-error')!.textContent).toBe('Chưa giao được tới khu vực này.');
});

it('a quote outage asks to try again', async () => {
  const { c } = await quoteFails(() => problem(503, 'TEMPORARILY_UNAVAILABLE', 'DEPENDENCY_UNAVAILABLE'));
  expect(main(c).querySelector('[role="alert"]')!.textContent).toContain('Chưa tính được tổng tiền, vui lòng thử lại.');
  expect(primary(c).textContent).toBe('Xem tổng tiền');
});
