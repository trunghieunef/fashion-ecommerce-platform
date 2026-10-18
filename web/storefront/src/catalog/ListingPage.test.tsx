// @vitest-environment jsdom
import { act } from 'react';
import { afterEach, expect, it, vi } from 'vitest';

import empty from '../test/fixtures/products-empty.json';
import page2 from '../test/fixtures/products-page2.json';
import products from '../test/fixtures/products.json';
import { byText, click, flush, json, mockFetch, render } from '../test/harness';

afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

const passthrough = (path: string) => (path.startsWith('/categories') ? json({ code: 'OK', data: [], metadata: products.metadata })
  : path.startsWith('/brands') ? json({ code: 'OK', data: [], metadata: products.metadata }) : null);

async function submitFilters(container: Element, values: Record<string, string>) {
  const form = container.querySelector('form.filters') as HTMLFormElement;
  for (const [name, value] of Object.entries(values)) (form.elements.namedItem(name) as HTMLInputElement).value = value;
  await act(async () => { form.requestSubmit(); });
  await flush();
}

it('reads filters from the URL, renders VND prices and appends the next cursor page', async () => {
  const calls = mockFetch((path) => passthrough(path) ?? json(path.includes('cursor=') ? page2 : products));
  const container = await render('/products?size=M&sort=PRICE_ASC');
  const first = calls.find((c) => c.url.includes('/products?'))!;
  expect(first.url).toBe('/api/v1/catalog/products?size=M&sort=PRICE_ASC&limit=24');
  const list = container.querySelector('ul[aria-label="Danh sách sản phẩm"]')!;
  expect(list.querySelectorAll('li')).toHaveLength(2);
  expect(list.textContent).toMatch(/100\.000\s₫\s–\s120\.000\s₫/);
  expect(container.querySelector('a[href="/products/11111111-1111-4111-8111-111111111111"]')).not.toBeNull();
  await click(byText(container, 'button', 'Tải thêm'));
  expect(calls.some((c) => c.url.includes('cursor=cursor-page-2') && c.url.includes('size=M'))).toBe(true);
  expect(list.querySelectorAll('li')).toHaveLength(3);
  expect(byText(container, 'button', 'Tải thêm')).toBeUndefined();
});

it('a filter change resets the cursor and ignores a late page of the old filter', async () => {
  let releaseOld: (r: Response) => void = () => {};
  const calls = mockFetch((path) => {
    const p = passthrough(path);
    if (p) return p;
    if (path.includes('cursor=')) return new Promise<Response>((resolve) => { releaseOld = resolve; });
    return json(path.includes('color=Red') ? empty : products);
  });
  const container = await render('/products');
  await click(byText(container, 'button', 'Tải thêm'));
  await submitFilters(container, { color: 'Red' });
  const latest = calls.filter((c) => c.url.includes('/products?')).at(-1)!;
  expect(latest.url).toContain('color=Red');
  expect(latest.url).not.toContain('cursor=');
  await act(async () => releaseOld(json(page2)));
  await flush();
  expect(container.textContent).toContain('Không có sản phẩm phù hợp');
  expect(container.textContent).not.toContain('Váy mẫu');
});

it('shows the empty state without filters and no-results with filters', async () => {
  mockFetch((path) => passthrough(path) ?? json(empty));
  expect((await render('/products')).textContent).toContain('Chưa có sản phẩm');
  document.body.innerHTML = '';
  expect((await render('/products?size=XL')).textContent).toContain('Không có sản phẩm phù hợp');
});

it('offers a retry after a failure and recovers', async () => {
  let fail = true;
  mockFetch((path) => passthrough(path) ?? (fail ? json({ code: 'INTERNAL' }, 503) : json(products)));
  const container = await render('/products');
  expect(container.textContent).toContain('Không thể tải dữ liệu');
  fail = false;
  await click(byText(container, 'button', 'Thử lại'));
  expect(container.querySelectorAll('ul[aria-label="Danh sách sản phẩm"] li')).toHaveLength(2);
});

it('searches through /search and keeps the query when filters are applied', async () => {
  const calls = mockFetch((path) => passthrough(path) ?? json(products));
  const container = await render('/search?q=%C3%A1o');
  expect(container.querySelector('h1')!.textContent).toBe('Tìm kiếm: áo');
  expect(calls.some((c) => c.url.startsWith('/api/v1/catalog/search?q=%C3%A1o'))).toBe(true);
  await submitFilters(container, { size: 'M' });
  expect(calls.at(-1)!.url).toMatch(/^\/api\/v1\/catalog\/search\?q=%C3%A1o.*size=M/);
});
