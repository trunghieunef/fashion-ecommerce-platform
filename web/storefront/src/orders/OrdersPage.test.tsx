// @vitest-environment jsdom
// TASK:WEB-03: /account/orders — the member's orders, newest first, "load more" by cursor.
import { act } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import mine from '../test/fixtures/orders-mine.json';
import profile from '../test/fixtures/profile.json';
import refresh from '../test/fixtures/refresh.json';
import { FakeChannel, flush, json, locationOf, mockFetch, render } from '../test/harness';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); });
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

type Answer = (path: string) => Response | null;
const member = (answer: Answer) => (path: string) => answer(path)
  ?? (path === '/api/v1/auth/refresh' ? json(refresh) : path === '/api/v1/users/me' ? json(profile) : null);
const rows = (c: Element) => [...c.querySelectorAll('main [data-testid="order-row"]')];
const empty = { code: 'OK', data: { items: [], next_cursor: null }, metadata: mine.metadata };

it('lists orders newest first with links', async () => {
  mockFetch(member((path) => (path === '/api/v1/orders/mine?limit=20' ? json(mine) : null)));
  const c = await render('/account/orders');
  await flush();
  expect(rows(c)).toHaveLength(2);
  expect(rows(c)[0].querySelector('a')!.getAttribute('href')).toBe('/orders/ORD202610000002');
  expect(rows(c)[0].textContent).toContain('Đã tiếp nhận, chờ shop xác nhận');
  expect(rows(c)[0].textContent).toMatch(/340\.000\s₫/);
  expect(rows(c)[0].textContent).toContain('3 sản phẩm');
});

it('Load more sends next_cursor', async () => {
  const calls = mockFetch(member((path) => (path === '/api/v1/orders/mine?limit=20' ? json(mine)
    : path === '/api/v1/orders/mine?limit=20&cursor=cursor-2' ? json(empty) : null)));
  const c = await render('/account/orders');
  await flush();
  const more = [...c.querySelectorAll('main button')].find((b) => b.textContent === 'Tải thêm') as HTMLButtonElement;
  await act(async () => { more.click(); });
  await flush();
  expect(calls.some((x) => x.url === '/api/v1/orders/mine?limit=20&cursor=cursor-2')).toBe(true);
});

it('no orders shows the empty state with a shop link', async () => {
  mockFetch(member((path) => (path === '/api/v1/orders/mine?limit=20' ? json(empty) : null)));
  const c = await render('/account/orders');
  await flush();
  expect(c.querySelector('main')!.textContent).toContain('Bạn chưa có đơn hàng nào.');
  expect(c.querySelector('main a[href="/products"]')).not.toBeNull();
});

it('a guest is sent to sign in', async () => {
  mockFetch(() => null);
  const c = await render('/account/orders');
  await flush();
  expect(locationOf(c)).toMatch(/^\/login\?next=/);
});

it('the account page links to the orders', async () => {
  mockFetch(member(() => null));
  const c = await render('/account');
  await flush();
  expect(c.querySelector('main a[href="/account/orders"]')!.textContent).toBe('Đơn hàng của tôi');
});
