// @vitest-environment jsdom
// TASK:WEB-01b SF-01: editorial home — hero from the first running collection, collection tiles, new-arrivals rail.
import { afterEach, expect, it, vi } from 'vitest';

import collections from '../test/fixtures/collections.json';
import products from '../test/fixtures/products.json';
import { byText, json, mockFetch, render } from '../test/harness';

afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

const COVER = '99999999-9999-4999-8999-999999999999';
const first = collections.data.items[0];
const withItems = (items: unknown[]) => ({ ...collections, data: { ...collections.data, items } });
const home = (collectionsBody: unknown) => (path: string) =>
  path.startsWith('/collections') ? json(collectionsBody) : path.startsWith('/products') ? json(products) : null;

it('home lists running collections by slug and new arrivals, each block on its own', async () => {
  mockFetch((path) => (path.startsWith('/collections') ? json(collections) : json({ code: 'INTERNAL' }, 503)));
  const container = await render('/');
  expect(container.querySelector('main a[href="/collections/he-2026"]')!.textContent).toContain('Hè 2026 (mẫu)');
  expect(container.textContent).toContain('Không thể tải dữ liệu');
});

it('home shows new arrivals sorted newest', async () => {
  const calls = mockFetch(home(collections));
  const container = await render('/');
  expect(calls.some((c) => c.url === '/api/v1/catalog/products?sort=NEWEST&limit=8')).toBe(true);
  expect(container.querySelectorAll('ul[aria-label="Hàng mới"] li')).toHaveLength(2);
  expect(byText(container, 'main a', 'Xem tất cả')?.getAttribute('href')).toBe('/products?sort=NEWEST');
});

it('hero uses the first collection cover', async () => {
  mockFetch(home(withItems([{ ...first, cover_asset_id: COVER }])));
  const container = await render('/');
  const hero = container.querySelector('[data-testid="hero"]')!;
  expect(hero.querySelector(`img[src="/api/v1/catalog/images/${COVER}?kind=image"]`)).not.toBeNull();
  expect(container.querySelector('h1')!.textContent).toBe('Hè 2026 (mẫu)');
  expect(byText(hero, 'a', /^Khám phá/)?.getAttribute('href')).toBe('/collections/he-2026');
});

it('hero without a cover is text only', async () => {
  mockFetch(home(collections));
  const container = await render('/');
  const hero = container.querySelector('[data-testid="hero"]')!;
  expect(hero.querySelector('img, [role="img"]')).toBeNull();
  expect(container.querySelector('h1')!.textContent).toBe('Hè 2026 (mẫu)');
});

it('no collections: no hero, new arrivals still render', async () => {
  mockFetch(home(withItems([])));
  const container = await render('/');
  expect(container.querySelector('[data-testid="hero"]')).toBeNull();
  expect(byText(container, 'main a', /^Khám phá/)).toBeUndefined();
  expect(container.querySelectorAll('ul[aria-label="Hàng mới"] li')).toHaveLength(2);
});

it('later collections become tiles', async () => {
  const second = { ...first, id: '77777777-7777-4777-8777-777777777778', slug: 'linen', name_vi: 'Linen thành phố', name_en: 'City linen' };
  mockFetch(home(withItems([first, second])));
  const container = await render('/');
  const tiles = container.querySelector('ul[aria-label="Bộ sưu tập"]')!;
  expect(tiles.querySelectorAll('li')).toHaveLength(1);
  expect(tiles.querySelector('a[href="/collections/linen"]')!.textContent).toContain('Linen thành phố');
});

it('rail buttons are disabled at the ends', async () => {
  mockFetch(home(collections));
  const container = await render('/');
  expect((container.querySelector('button[aria-label="Trước"]') as HTMLButtonElement).disabled).toBe(true);
  expect((container.querySelector('button[aria-label="Sau"]') as HTMLButtonElement).disabled).toBe(true);
});
