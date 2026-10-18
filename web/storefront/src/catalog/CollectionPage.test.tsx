// @vitest-environment jsdom
import { act } from 'react';
import { afterEach, expect, it, vi } from 'vitest';

import collection from '../test/fixtures/collection.json';
import collections from '../test/fixtures/collections.json';
import products from '../test/fixtures/products.json';
import { flush, json, mockFetch, render } from '../test/harness';

afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

it('home lists running collections by slug and new arrivals, each block on its own', async () => {
  mockFetch((path) => (path.startsWith('/collections') ? json(collections) : json({ code: 'INTERNAL' }, 503)));
  const container = await render('/');
  expect(container.querySelector('a[href="/collections/he-2026"]')!.textContent).toContain('Hè 2026 (mẫu)');
  expect(container.textContent).toContain('Không thể tải dữ liệu');
});

it('home shows new arrivals sorted newest', async () => {
  const calls = mockFetch((path) => (path.startsWith('/collections') ? json(collections) : json(products)));
  const container = await render('/');
  expect(calls.some((c) => c.url === '/api/v1/catalog/products?sort=NEWEST&limit=8')).toBe(true);
  expect(container.querySelectorAll('ul[aria-label="Hàng mới"] li')).toHaveLength(2);
});

it('collection shows the lookbook caption, falling back to Vietnamese when English is empty', async () => {
  mockFetch(() => json(collection));
  const vi1 = await render('/collections/he-2026');
  expect(vi1.querySelector('h1')!.textContent).toBe('Hè 2026 (mẫu)');
  expect(vi1.querySelector('figcaption')!.textContent).toBe('Phối đồ biển');
  expect(vi1.querySelector('.lookbook img')!.getAttribute('alt')).toBe('Phối đồ biển');
  document.body.innerHTML = '';
  const en = await render('/collections/he-2026', 'en');
  expect(en.querySelector('h1')!.textContent).toBe('Summer 2026 (sample)');
  expect(en.querySelector('figcaption')!.textContent).toBe('Phối đồ biển');
  expect(en.querySelectorAll('ul[aria-label="Product list"] li')).toHaveLength(1);
});

it('unknown collection and unknown route show not found', async () => {
  mockFetch(() => json({ code: 'NOT_FOUND', message: 'COLLECTION_NOT_FOUND', errors: [] }, 404));
  expect((await render('/collections/nope')).textContent).toContain('Không tìm thấy');
  document.body.innerHTML = '';
  expect((await render('/no/such/page')).textContent).toContain('Không tìm thấy');
});

it('collection pages past the first page with next_cursor (PR #28 P2)', async () => {
  const second = { ...collection, data: { ...collection.data, next_cursor: null, products: [
    { ...collection.data.products[0], id: '99999999-9999-4999-8999-999999999999', slug: 'ao-2', name_vi: 'Áo thứ 21', name_en: 'Tee 21' }] } };
  const calls = mockFetch((path) => json(path.includes('cursor=') ? second : { ...collection, data: { ...collection.data, next_cursor: 'c-2' } }));
  const container = await render('/collections/he-2026');
  const more = [...container.querySelectorAll('button')].find((b) => b.textContent === 'Tải thêm')!;
  await act(async () => { more.click(); });
  await flush();
  expect(calls.at(-1)!.url).toBe('/api/v1/catalog/collections/he-2026?cursor=c-2');
  expect(container.querySelectorAll('ul[aria-label="Danh sách sản phẩm"] li')).toHaveLength(2);
  expect(container.textContent).toContain('Áo thứ 21');
  expect([...container.querySelectorAll('button')].some((b) => b.textContent === 'Tải thêm')).toBe(false);
});
