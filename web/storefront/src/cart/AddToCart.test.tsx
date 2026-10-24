// @vitest-environment jsdom
// TASK:WEB-02b: add to cart from the product page — confirmation drawer, header badge, one announcement.
import { act } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import cartEmpty from '../test/fixtures/cart-empty.json';
import cartGuest from '../test/fixtures/cart-guest.json';
import product from '../test/fixtures/product.json';
import { FakeChannel, flush, json, mockFetch, render } from '../test/harness';
import { displayOf } from './display';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); });
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); document.body.innerHTML = ''; });

const metadata = { request_id: 'web02b-test', trace_id: 'web02b-test' };
const PATH = '/products/11111111-1111-4111-8111-111111111111';
const GUEST = cartGuest.data.cart_id;
const BLUE_M = '55555555-5555-4555-8555-555555555551';
type Extra = (path: string) => Response | Promise<Response> | null;
const shop = (items: Extra) => (path: string) =>
  path.startsWith('/products/') ? json(product) : path === '/api/v1/carts' ? json(cartEmpty, 201)
    : path === `/api/v1/carts/${GUEST}/items` ? items(path) : null;

const addButton = (container: Element) =>
  [...container.querySelectorAll('main button')].find((b) => b.textContent?.startsWith('Thêm vào giỏ')) as HTMLButtonElement;
async function choose(container: Element, name: string, value: string) {
  await act(async () => { (container.querySelector(`input[name="${name}"][value="${value}"]`) as HTMLInputElement).click(); });
  await flush();
}
async function chooseBlueM(container: Element) {
  await choose(container, 'color', 'Blue');
  await choose(container, 'size', 'M');
}

it('the button waits for a full variant', async () => {
  mockFetch(shop(() => json(cartGuest)));
  const container = await render(PATH);
  expect(addButton(container).disabled).toBe(true);
  await choose(container, 'color', 'Blue');
  expect(addButton(container).disabled).toBe(true);
  await choose(container, 'size', 'M');
  expect(addButton(container).disabled).toBe(false);
});

it('adding opens the drawer, remembers the display data and announces the count', async () => {
  const calls = mockFetch(shop(() => json(cartGuest)));
  const container = await render(PATH);
  await chooseBlueM(container);
  await act(async () => { addButton(container).click(); });
  await flush();
  expect(calls.find((c) => c.url.endsWith('/items'))!.body).toEqual({ variant_id: BLUE_M, quantity: 1, expected_version: 0 });
  const dialog = container.querySelector('dialog[open]') as HTMLDialogElement;
  expect(dialog.textContent).toContain('Đã thêm vào giỏ');
  expect(dialog.textContent).toContain('Áo thun mẫu');
  expect(dialog.querySelector('a[href="/cart"]')?.textContent).toBe('Xem giỏ');
  expect(displayOf(BLUE_M)).toMatchObject({ product_id: product.data.id, name_vi: 'Áo thun mẫu', color: 'Blue', size: 'M' });
  const status = [...container.querySelectorAll('[role="status"]')].map((s) => s.textContent);
  expect(status).toContain('Đã thêm vào giỏ. Giỏ hàng có 3 sản phẩm.');
});

it('double click adds once', async () => {
  const calls = mockFetch(shop(() => new Promise<Response>(() => {})));
  const container = await render(PATH);
  await chooseBlueM(container);
  // Two clicks in the same tick, before React re-renders with the busy state.
  await act(async () => { const button = addButton(container); button.click(); button.click(); });
  await flush();
  expect(calls.filter((c) => c.url.endsWith('/items'))).toHaveLength(1);
  expect(addButton(container).getAttribute('aria-busy')).toBe('true');
});

it('the header badge label counts items', async () => {
  mockFetch(shop(() => json(cartGuest)));
  const container = await render(PATH);
  expect(container.querySelector('header a[href="/cart"]')!.getAttribute('aria-label')).toBe('Giỏ hàng, 0 sản phẩm');
  await chooseBlueM(container);
  await act(async () => { addButton(container).click(); });
  await flush();
  expect(container.querySelector('header a[href="/cart"]')!.getAttribute('aria-label')).toBe('Giỏ hàng, 3 sản phẩm');
});

it('a failed add shows the reason by the button', async () => {
  mockFetch(shop(() => json({ code: 'OUT_OF_STOCK', message: 'OUT_OF_STOCK', errors: [{ field: 'quantity', message: 'available=0' }], metadata }, 409)));
  const container = await render(PATH);
  await chooseBlueM(container);
  await act(async () => { addButton(container).click(); });
  await flush();
  expect(container.querySelector('[data-testid="product-info"] [role="alert"]')!.textContent).toBe('Không thêm được vào giỏ. Vui lòng thử lại.');
  expect(container.querySelector('dialog[open]')).toBeNull();
});
