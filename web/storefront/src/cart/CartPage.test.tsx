// @vitest-environment jsdom
// TASK:WEB-02b: the cart page — lines, quantity (debounced, 1–99), unavailable marks, preview subtotal, clear, checkout off.
import { act } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import cartGuest from '../test/fixtures/cart-guest.json';
import cartEmpty from '../test/fixtures/cart-empty.json';
import { FakeChannel, flush, json, mockFetch, render } from '../test/harness';
import { rememberDisplay } from './display';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); localStorage.setItem('storefront.cart', GUEST); });
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); localStorage.clear(); document.body.innerHTML = ''; });

const GUEST = cartGuest.data.cart_id;
const BLUE_LINE = cartGuest.data.items[0];
const okCart = { ...cartGuest, data: { ...cartGuest.data, preview_status: 'OK', subtotal_preview: 300000,
  items: cartGuest.data.items.map((i) => ({ ...i, preview: { ...i.preview, active: true, available: 5 } })) } };
const shop = (cart: unknown = cartGuest) => (path: string) =>
  path === `/api/v1/carts/${GUEST}` ? json(cart) : path.startsWith(`/api/v1/carts/${GUEST}`) ? json(cart) : null;

const lines = (container: Element) => [...container.querySelectorAll('main [data-testid="cart-line"]')];
const button = (scope: Element, label: string) =>
  [...scope.querySelectorAll('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label) as HTMLButtonElement;
async function setInput(input: HTMLInputElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

it('shows lines with display data or SKU', async () => {
  rememberDisplay(BLUE_LINE.variant_id, { product_id: '11111111-1111-4111-8111-111111111111', name_vi: 'Áo thun mẫu', name_en: 'Sample tee', asset_id: null, color: 'Blue', size: 'M' });
  mockFetch(shop());
  const container = await render('/cart');
  expect(lines(container)).toHaveLength(2);
  expect(lines(container)[0].textContent).toContain('Áo thun mẫu');
  expect(lines(container)[0].textContent).toContain('Blue · M');
  expect(lines(container)[0].querySelector('a[href="/products/11111111-1111-4111-8111-111111111111"]')).not.toBeNull();
  expect(lines(container)[1].textContent).toContain('TEE-M-RED');
});

it('quantity changes are sent once after 400 ms', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const calls = mockFetch(shop());
  const container = await render('/cart');
  const line = lines(container)[0];
  await act(async () => { button(line, 'Tăng số lượng').click(); });
  await act(async () => { button(line, 'Tăng số lượng').click(); });
  expect((line.querySelector('input') as HTMLInputElement).value).toBe('3');
  await act(async () => { await vi.advanceTimersByTimeAsync(399); });
  expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(0);
  await act(async () => { await vi.advanceTimersByTimeAsync(1); });
  await flush();
  const puts = calls.filter((c) => c.method === 'PUT');
  expect(puts).toHaveLength(1);
  expect(puts[0].body).toEqual({ quantity: 3, expected_version: cartGuest.data.version });
});

it('an out-of-range quantity is not sent', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  const calls = mockFetch(shop());
  const container = await render('/cart');
  const input = lines(container)[0].querySelector('input') as HTMLInputElement;
  expect(input.getAttribute('inputmode')).toBe('numeric');
  for (const bad of ['0', '100', 'abc', '']) {
    await setInput(input, bad);
    await act(async () => { await vi.advanceTimersByTimeAsync(500); });
    await act(async () => { input.dispatchEvent(new FocusEvent('focusout', { bubbles: true })); });
    expect(input.value).toBe(String(BLUE_LINE.quantity));
  }
  expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(0);
});

it('unavailable lines are marked and no subtotal is shown', async () => {
  mockFetch(shop());
  const container = await render('/cart');
  expect(lines(container)[1].textContent).toContain('Không còn bán');
  expect(lines(container)[0].textContent).not.toContain('Không còn bán');
  expect(container.querySelector('main [data-testid="subtotal"]')).toBeNull();
  expect(container.querySelector('main')!.textContent).toContain('Giá và tồn kho chỉ để tham khảo; phí giao hàng được tính ở bước đặt hàng.');
});

it('subtotal shows only when preview_status is OK', async () => {
  mockFetch(shop(okCart));
  const container = await render('/cart');
  expect(container.querySelector('main [data-testid="subtotal"]')!.textContent).toMatch(/300\.000\s₫/);
});

it('a line short of stock is marked', async () => {
  const short = { ...okCart, data: { ...okCart.data, preview_status: 'UNAVAILABLE', subtotal_preview: null,
    items: okCart.data.items.map((i, n) => (n === 1 ? { ...i, preview: { ...i.preview, available: 1 } } : i)) } };
  mockFetch(shop(short));
  const container = await render('/cart');
  expect(lines(container)[1].textContent).toContain('Không đủ hàng (tham khảo)');
});

it('removing a line sends the version and confirms', async () => {
  const calls = mockFetch(shop());
  const container = await render('/cart');
  await act(async () => { button(lines(container)[1], 'Xóa').click(); });
  await flush();
  expect(calls.find((c) => c.method === 'DELETE')!.url)
    .toBe(`/api/v1/carts/${GUEST}/items/${cartGuest.data.items[1].item_id}?expected_version=${cartGuest.data.version}`);
  expect([...container.querySelectorAll('main [role="status"]')].map((s) => s.textContent)).toContain('Đã xóa khỏi giỏ.');
});

it('clearing the cart asks first and focuses Cancel', async () => {
  const calls = mockFetch(shop());
  const container = await render('/cart');
  await act(async () => { button(container.querySelector('main')!, 'Xóa giỏ').click(); });
  await flush();
  const dialog = container.querySelector('main dialog[open]') as HTMLDialogElement;
  expect(document.activeElement).toBe(button(dialog, 'Hủy'));
  expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
  await act(async () => { button(dialog, 'Xóa giỏ hàng').click(); });
  await flush();
  expect(calls.find((c) => c.method === 'DELETE')!.url).toBe(`/api/v1/carts/${GUEST}?expected_version=${cartGuest.data.version}`);
});

it('the cart page links to checkout when it has lines', async () => {
  mockFetch(shop());
  const container = await render('/cart');
  expect(container.querySelector('main a[href="/checkout"]')!.textContent).toBe('Thanh toán');
});

it('an empty cart links to products', async () => {
  localStorage.clear();
  mockFetch(shop(cartEmpty));
  const container = await render('/cart');
  expect(container.querySelector('main')!.textContent).toContain('Giỏ hàng của bạn đang trống.');
  expect(container.querySelector('main a[href="/products"]')).not.toBeNull();
});

it('a line without a preview yet is not marked as not sold', async () => {
  const written = { ...cartGuest, data: { ...cartGuest.data, preview_status: 'NOT_LOADED', subtotal_preview: null,
    items: cartGuest.data.items.map((i) => ({ ...i, preview: null })) } };
  mockFetch(shop(written));
  const container = await render('/cart');
  expect(lines(container)[1].textContent).not.toContain('Không còn bán');
});

it('a quantity the server refused goes back to the cart value', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? json(cartGuest)
    : path.startsWith(`/api/v1/carts/${GUEST}/items/`)
      ? json({ code: 'OUT_OF_STOCK', message: 'OUT_OF_STOCK', metadata: { request_id: 't', trace_id: 't' } }, 409) : null));
  const container = await render('/cart');
  const line = lines(container)[0];
  await act(async () => { button(line, 'Tăng số lượng').click(); });
  await act(async () => { await vi.advanceTimersByTimeAsync(400); });
  await flush();
  expect((line.querySelector('input') as HTMLInputElement).value).toBe(String(BLUE_LINE.quantity));
});

it('a quantity over the limit says the limit', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? json(cartGuest)
    : path.startsWith(`/api/v1/carts/${GUEST}/items/`)
      ? json({ code: 'VALIDATION_ERROR', message: 'QUANTITY_LIMIT', metadata: { request_id: 't', trace_id: 't' } }, 400) : null));
  const container = await render('/cart');
  await act(async () => { button(lines(container)[0], 'Tăng số lượng').click(); });
  await act(async () => { await vi.advanceTimersByTimeAsync(400); });
  await flush();
  expect(container.querySelector('main [role="alert"]')!.textContent).toContain('Mỗi sản phẩm tối đa 99.');
});
