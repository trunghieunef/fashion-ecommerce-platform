// @vitest-environment jsdom
// TASK:WEB-03: /orders/:orderNo — status in plain words, milestones, the order snapshot; polls while the shop prepares it.
import { act } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import cartGuest from '../test/fixtures/cart-guest.json';
import cancelled from '../test/fixtures/order-cancelled.json';
import confirmed from '../test/fixtures/order-confirmed.json';
import processing from '../test/fixtures/order-processing.json';
import { FakeChannel, flush, json, mockFetch, render } from '../test/harness';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); });
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); document.body.innerHTML = ''; });

const PATH = '/api/v1/orders/ORD202610000001';
const main = (c: Element) => c.querySelector('main')!;

it('shows the order number, milestones, items and totals', async () => {
  mockFetch((path) => (path === PATH ? json(confirmed) : null));
  const c = await render('/orders/ORD202610000001');
  expect(main(c).querySelector('h1')!.textContent).toContain('ORD202610000001');
  expect(main(c).querySelector('[aria-current="step"]')!.textContent).toBe('Đã nhận đơn');
  expect(main(c).querySelectorAll('ol li')).toHaveLength(4);
  expect(main(c).textContent).toContain('Áo thun mẫu');
  expect(main(c).textContent).toMatch(/Tổng cộng\s*340\.000\s₫/);
  expect(main(c).textContent).toContain('12 Đường Mẫu');
  expect(main(c).textContent).toContain('Thanh toán khi nhận hàng (COD)');
});

it('a confirmed COD order says the shop will confirm', async () => {
  mockFetch((path) => (path === PATH ? json(confirmed) : null));
  const c = await render('/orders/ORD202610000001');
  expect(main(c).querySelector('[role="status"]')!.textContent).toBe('Đã tiếp nhận, chờ shop xác nhận');
});

it('a cancelled out-of-stock order links back to the cart', async () => {
  mockFetch((path) => (path === PATH ? json(cancelled) : null));
  const c = await render('/orders/ORD202610000001');
  expect(main(c).querySelector('[role="status"]')!.textContent).toBe('Đơn bị hủy vì hết hàng. Giỏ hàng vẫn còn, hãy sửa giỏ rồi đặt lại.');
  expect(main(c).querySelector('a[href="/cart"]')).not.toBeNull();
  expect(main(c).querySelector('ol')).toBeNull();
});

it('a 404 says the order cannot be shown', async () => {
  mockFetch((path) => (path === PATH ? json({ code: 'NOT_FOUND', message: 'ORDER_NOT_FOUND', metadata: { request_id: 'x', trace_id: 'x' } }, 404) : null));
  const c = await render('/orders/ORD202610000001');
  expect(main(c).textContent).toContain('Không xem được đơn này. Nếu bạn đặt bằng thiết bị khác, hãy đăng nhập hoặc liên hệ shop.');
});

it('a status change is announced', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  let n = 0;
  mockFetch((path) => (path === PATH ? json(n++ === 0 ? processing : confirmed) : null));
  const c = await render('/orders/ORD202610000001');
  expect(main(c).querySelector('[role="status"]')!.textContent).toBe('Đang chuẩn bị đơn');
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  await flush();
  expect(main(c).querySelector('[role="status"]')!.textContent).toBe('Đã tiếp nhận, chờ shop xác nhận');
});

it('a stalled order says the shop will contact and can be checked again', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
  const calls = mockFetch((path) => (path === PATH ? json(processing) : null));
  const c = await render('/orders/ORD202610000001');
  await act(async () => { await vi.advanceTimersByTimeAsync(121_000); });
  await flush();
  expect(main(c).textContent).toContain('Đơn đang được xử lý, shop sẽ liên hệ với bạn.');
  const before = calls.filter((x) => x.url === PATH).length;
  const check = [...main(c).querySelectorAll('button')].find((b) => b.textContent === 'Kiểm tra lại')!;
  await act(async () => { check.click(); });
  await flush();
  expect(calls.filter((x) => x.url === PATH).length).toBe(before + 1);
});

it('the heading gets focus', async () => {
  mockFetch((path) => (path === PATH ? json(confirmed) : null));
  const c = await render('/orders/ORD202610000001');
  expect(document.activeElement).toBe(main(c).querySelector('h1'));
});

it('the cart is read again once the order leaves processing (PR #40 review I2)', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  localStorage.setItem('storefront.cart', cartGuest.data.cart_id);
  let n = 0;
  const calls = mockFetch((path) => (path === PATH ? json(n++ === 0 ? processing : confirmed)
    : path === `/api/v1/carts/${cartGuest.data.cart_id}` ? json(cartGuest) : null));
  await render('/orders/ORD202610000001');
  const reads = () => calls.filter((x) => x.url === `/api/v1/carts/${cartGuest.data.cart_id}`).length;
  const before = reads();
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  await flush();
  expect(reads()).toBe(before + 1);
  localStorage.clear();
});
