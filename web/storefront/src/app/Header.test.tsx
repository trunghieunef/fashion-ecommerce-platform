// @vitest-environment jsdom
// TASK:WEB-01b: header with category mega-menu, lazy collections panel, search overlay and mobile menu (spec 3.1).
import { act } from 'react';
import { afterEach, expect, it, vi } from 'vitest';

import categories from '../test/fixtures/categories.json';
import products from '../test/fixtures/products.json';
import { byText, click, flush, json, mockFetch, render } from '../test/harness';

afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

const listing = (path: string) => (path.startsWith('/products?') || path.startsWith('/search?') ? json(products) : null);
const nav = (container: Element) => container.querySelector('nav[aria-label="Điều hướng chính"]') as HTMLElement;

async function focus(element: Element | null | undefined) {
  await act(async () => { (element as HTMLElement).focus(); });
  await flush();
}

it('lists top-level categories from /categories and opens the panel by focus', async () => {
  mockFetch(listing);
  const container = await render('/products');
  const tops = byText(nav(container), 'button', 'Áo')!;
  expect(tops.getAttribute('aria-expanded')).toBe('false');
  await focus(tops);
  expect(tops.getAttribute('aria-expanded')).toBe('true');
  const panel = document.getElementById(tops.getAttribute('aria-controls')!)!;
  const child = panel.querySelector('a[href="/products?category_id=44444444-4444-4444-8444-444444444445"]');
  expect(child?.textContent).toBe('Áo thun');
});

it('Escape closes the panel and returns focus to its button', async () => {
  mockFetch(listing);
  const container = await render('/products');
  const tops = byText(nav(container), 'button', 'Áo') as HTMLButtonElement;
  await focus(tops);
  const panel = document.getElementById(tops.getAttribute('aria-controls')!)!;
  await focus(panel.querySelector('a'));
  await act(async () => { panel.querySelector('a')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); });
  await flush();
  expect(tops.getAttribute('aria-expanded')).toBe('false');
  expect(document.activeElement).toBe(tops);
});

it('the collections panel loads only when first opened', async () => {
  const calls = mockFetch(listing);
  const container = await render('/products');
  expect(calls.some((c) => c.url.includes('/collections'))).toBe(false);
  await click(byText(nav(container), 'button', 'Bộ sưu tập'));
  expect(calls.filter((c) => c.url === '/api/v1/catalog/collections?limit=6')).toHaveLength(1);
  expect(nav(container).querySelector('a[href="/collections/he-2026"]')?.textContent).toBe('Hè 2026 (mẫu)');
});

it('keeps a Products link when /categories fails', async () => {
  mockFetch((path) => (path.startsWith('/categories') ? json({ code: 'INTERNAL' }, 503) : listing(path)));
  const container = await render('/products');
  const header = container.querySelector('header')!;
  expect(header.querySelector('a[href="/products"]')?.textContent).toBe('Sản phẩm');
  expect(header.textContent).not.toContain('Không thể tải dữ liệu');
});

it('categories arriving after the mobile menu opened show up without breaking the menu', async () => {
  // The header and the listing filters both ask for /categories; hold every one of them.
  const pending: ((r: Response) => void)[] = [];
  mockFetch((path) => (path.startsWith('/categories') ? new Promise<Response>((resolve) => { pending.push(resolve); }) : listing(path)));
  const container = await render('/products');
  await click(container.querySelector('header button[aria-label="Menu"]'));
  const dialog = container.querySelector('header dialog') as HTMLDialogElement;
  expect(dialog.open).toBe(true);
  expect(dialog.querySelector('a[href="/products"]')).not.toBeNull();
  await act(async () => { pending.forEach((release) => release(json(categories))); });
  await flush();
  expect(byText(dialog, 'summary', 'Áo')).toBeDefined();
  expect(dialog.open).toBe(true);
});

it('search submits a trimmed query and ignores an empty one', async () => {
  mockFetch(listing);
  const container = await render('/products');
  const submit = async (value: string) => {
    await click(container.querySelector('header button[aria-label="Mở tìm kiếm"]'));
    const form = container.querySelector('header form[role="search"]') as HTMLFormElement;
    const input = form.querySelector('input[type="search"]') as HTMLInputElement;
    expect(document.activeElement).toBe(input);
    expect(input.maxLength).toBe(100);
    input.value = value;
    await act(async () => { form.requestSubmit(); });
    await flush();
  };
  await submit('   ');
  expect(container.querySelector('h1')!.textContent).not.toMatch(/^Tìm kiếm/);
  await click(container.querySelector('header button[aria-label="Đóng tìm kiếm"]'));
  await submit('  áo  ');
  expect(container.querySelector('h1')!.textContent).toBe('Tìm kiếm: áo');
  expect(container.querySelector('header form[role="search"]')).toBeNull();
});

it('navigating closes the mobile drawer', async () => {
  mockFetch(listing);
  const container = await render('/products');
  await click(container.querySelector('header button[aria-label="Menu"]'));
  const dialog = container.querySelector('header dialog') as HTMLDialogElement;
  expect(dialog.open).toBe(true);
  await click(byText(dialog, 'a', 'Hàng mới'));
  expect(dialog.open).toBe(false);
});

it('the skip link targets main', async () => {
  mockFetch(listing);
  const container = await render('/products');
  expect(container.querySelector('a[href="#main"]')?.textContent).toBe('Bỏ qua tới nội dung chính');
  expect(document.getElementById('main')?.tagName).toBe('MAIN');
});

it('the collections panel loads under StrictMode (final review: aborted controller)', async () => {
  mockFetch(listing);
  const container = await render('/products', 'vi', { strict: true });
  await click(byText(nav(container), 'button', 'Bộ sưu tập'));
  expect(nav(container).querySelector('a[href="/collections/he-2026"]')?.textContent).toBe('Hè 2026 (mẫu)');
});
