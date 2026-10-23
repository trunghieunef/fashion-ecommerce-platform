// @vitest-environment jsdom
import { act } from 'react';
import { afterEach, expect, it, vi } from 'vitest';

import empty from '../test/fixtures/products-empty.json';
import page2 from '../test/fixtures/products-page2.json';
import products from '../test/fixtures/products.json';
import { byText, click, flush, json, mockFetch, render } from '../test/harness';

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear(); document.body.innerHTML = ''; });

const passthrough = (path: string) => (path.startsWith('/categories') ? json({ code: 'OK', data: [], metadata: products.metadata })
  : path.startsWith('/brands') ? json({ code: 'OK', data: [], metadata: products.metadata }) : null);
const productCalls = (calls: { url: string }[]) => calls.filter((c) => c.url.includes('/products?') || c.url.includes('/search?'));

/** TASK:WEB-01b: filters live in a drawer and apply at once — selects on change, text/number fields on blur. */
async function setFilter(container: Element, name: string, value: string) {
  const dialog = container.querySelector('main dialog') as HTMLDialogElement;
  if (!dialog.open) await click(byText(container, 'main button', /^Bộ lọc/));
  const field = dialog.querySelector(`[name="${name}"]`) as HTMLInputElement | HTMLSelectElement;
  await act(async () => {
    field.value = value;
    field.dispatchEvent(field.tagName === 'SELECT' ? new Event('change', { bubbles: true }) : new FocusEvent('focusout', { bubbles: true }));
  });
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
  await setFilter(container, 'color', 'Red');
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
  await setFilter(container, 'size', 'M');
  expect(productCalls(calls).at(-1)!.url).toMatch(/^\/api\/v1\/catalog\/search\?q=%C3%A1o.*size=M/);
});

it('drops a late load-more page even when the old request ignores abort (review 11)', async () => {
  let releaseOld: (r: Response) => void = () => {};
  mockFetch((path) => {
    const p = passthrough(path);
    if (p) return p;
    if (path.includes('cursor=')) return new Promise<Response>((resolve) => { releaseOld = resolve; });
    return json(path.includes('color=Red') ? empty : products);
  }, false);
  const container = await render('/products');
  await click(byText(container, 'button', 'Tải thêm'));
  await setFilter(container, 'color', 'Red');
  await act(async () => releaseOld(json(page2)));
  await flush();
  expect(container.textContent).not.toContain('Váy mẫu');
});

it('a 400 filter error names the field and offers clear instead of a retry loop (review 2)', async () => {
  mockFetch((path) => passthrough(path) ?? json({ code: 'VALIDATION_ERROR', message: 'INVALID_FILTER',
    errors: [{ field: 'category_id', message: 'invalid value' }], metadata: products.metadata }, 400));
  const container = await render('/products?category_id=not-a-uuid');
  expect(container.textContent).toContain('Bộ lọc không hợp lệ: Danh mục');
  expect(byText(container, 'button', 'Thử lại')).toBeUndefined();
  expect(byText(container, '[data-testid="invalid-filter"] button', 'Xóa lọc')).toBeDefined();
});

it('min price above max price is refused in the drawer, keeping the URL (review 2)', async () => {
  const calls = mockFetch((path) => passthrough(path) ?? json(products));
  const container = await render('/products?min_price=500000');
  const before = productCalls(calls).length;
  await setFilter(container, 'max_price', '100000');
  expect(container.querySelector('main dialog [role="alert"]')!.textContent).toContain('Giá từ phải nhỏ hơn hoặc bằng Giá đến');
  expect(productCalls(calls)).toHaveLength(before);
});

it('keeps tag when other filters are applied (review 4)', async () => {
  const calls = mockFetch((path) => passthrough(path) ?? json(products));
  const container = await render('/products?tag=new');
  await setFilter(container, 'size', 'M');
  expect(productCalls(calls).at(-1)!.url).toMatch(/tag=new/);
});

it('keeps category and brand from the URL when their option lists failed to load (PR #28 P2)', async () => {
  const calls = mockFetch((path) => (path.startsWith('/categories') || path.startsWith('/brands') ? json({ code: 'INTERNAL' }, 503) : json(products)));
  const container = await render('/products?category_id=44444444-4444-4444-8444-444444444444&brand_id=88888888-8888-4888-8888-888888888888');
  await setFilter(container, 'size', 'M');
  const last = calls.filter((c) => c.url.includes('/products?')).at(-1)!.url;
  expect(last).toContain('category_id=44444444-4444-4444-8444-444444444444');
  expect(last).toContain('brand_id=88888888-8888-4888-8888-888888888888');
  expect(last).toContain('size=M');
});

// TASK:WEB-01b — chips, title, columns and the filter count.

it('a chip removes only its filter', async () => {
  const calls = mockFetch((path) => passthrough(path) ?? json(products));
  const container = await render('/products?size=M&color=Red');
  const chips = container.querySelector('ul[aria-label="Bộ lọc đang chọn"]')!;
  await click(chips.querySelector('button[aria-label="Bỏ lọc Size: M"]'));
  const last = calls.filter((c) => c.url.includes('/products?')).at(-1)!.url;
  expect(last).toContain('color=Red');
  expect(last).not.toContain('size=');
  expect(last).not.toContain('cursor=');
});

it('clear all keeps the search query', async () => {
  const calls = mockFetch((path) => passthrough(path) ?? json(products));
  const container = await render('/search?q=%C3%A1o&size=M');
  await click(byText(container, 'ul[aria-label="Bộ lọc đang chọn"] button', 'Xóa tất cả'));
  expect(productCalls(calls).at(-1)!.url).toBe('/api/v1/catalog/search?q=%C3%A1o&limit=24');
});

it('chips wrap instead of clipping', async () => {
  mockFetch((path) => passthrough(path) ?? json(products));
  const container = await render('/products?size=M');
  expect(container.querySelector('ul[aria-label="Bộ lọc đang chọn"]')!.classList).toContain('flex-wrap');
});

it('the title is the category name', async () => {
  mockFetch((path) => (path.startsWith('/products?') ? json(products) : null));
  const container = await render('/products?category_id=44444444-4444-4444-8444-444444444445');
  expect(container.querySelector('h1')!.textContent).toBe('Áo thun');
  expect(container.querySelector('button[aria-label="Bỏ lọc Danh mục: Áo thun"]')).not.toBeNull();
});

it('grid columns persist and survive a throwing localStorage', async () => {
  mockFetch((path) => passthrough(path) ?? json(products));
  const container = await render('/products');
  await click(container.querySelector('input[name="columns"][value="2"]'));
  expect(localStorage.getItem('storefront.grid')).toBe('2');
  expect(container.querySelector('ul[aria-label="Danh sách sản phẩm"]')!.className).toContain('lg:grid-cols-2');
  document.body.innerHTML = '';

  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
  const again = await render('/products');
  expect(again.querySelectorAll('ul[aria-label="Danh sách sản phẩm"] li')).toHaveLength(2);
  expect((again.querySelector('input[name="columns"][value="4"]') as HTMLInputElement).checked).toBe(true);
});

it('the toolbar button counts active filters, not the query or sort', async () => {
  mockFetch((path) => passthrough(path) ?? json(products));
  const container = await render('/products?size=M&sort=PRICE_ASC');
  expect(byText(container, 'main button', /^Bộ lọc/)!.textContent).toBe('Bộ lọc (1)');
});
