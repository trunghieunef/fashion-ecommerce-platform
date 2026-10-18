// @vitest-environment jsdom
import { act } from 'react';
import { afterEach, expect, it, vi } from 'vitest';

import product from '../test/fixtures/product.json';
import { byText, click, flush, json, mockFetch, render } from '../test/harness';

afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });
const PATH = '/products/11111111-1111-4111-8111-111111111111';

async function choose(container: Element, name: string, value: string) {
  const input = container.querySelector(`input[name="${name}"][value="${value}"]`) as HTMLInputElement;
  await click(input);
}

it('asks for a variant, then shows its price and the three stock states', async () => {
  mockFetch(() => json(product));
  const container = await render(PATH);
  expect(container.querySelector('h1')!.textContent).toBe('Áo thun mẫu');
  expect(container.querySelector('.stock')!.textContent).toBe('Chọn size và màu');
  expect(container.textContent).toContain('Tồn kho chỉ để tham khảo');

  await choose(container, 'size', 'L');
  // L has no Red variant: picking Red clears L instead of disabling Red (no dead end, review 1).
  await choose(container, 'color', 'Red');
  expect((container.querySelector('input[name="size"][value="L"]') as HTMLInputElement).checked).toBe(false);
  await choose(container, 'size', 'L');
  expect((container.querySelector('input[name="color"][value="Red"]') as HTMLInputElement).checked).toBe(false);
  await choose(container, 'color', 'Blue');
  expect(container.querySelector('.stock')!.textContent).toBe('Hết hàng');
  expect(container.querySelector('.product-info .price')!.textContent).toMatch(/^120\.000\s₫$/);

  await choose(container, 'size', 'M');
  expect(container.querySelector('.stock')!.textContent).toBe('Còn hàng (tham khảo)');
  await choose(container, 'color', 'Red');
  expect(container.querySelector('.stock')!.textContent).toBe('Chưa rõ tồn kho');
});

it('renders the size guide table and sends Accept-Language, refetching in English', async () => {
  const calls = mockFetch(() => json(product));
  const container = await render(PATH);
  expect(calls[0].lang).toBe('vi');
  expect(byText(container, 'h2', 'Bảng size')).toBeDefined();
  expect([...container.querySelectorAll('th')].map((th) => th.textContent)).toEqual(['Size', 'Ngực (cm)']);
  await click(byText(container, 'button', 'English'));
  expect(calls.at(-1)!.lang).toBe('en');
  expect(container.querySelector('h1')!.textContent).toBe('Sample tee');
  expect(document.documentElement.lang).toBe('en');
});

it('opens the zoom dialog from the image button', async () => {
  mockFetch(() => json(product));
  const container = await render(PATH);
  const dialog = container.querySelector('dialog') as HTMLDialogElement;
  dialog.showModal = vi.fn(() => dialog.setAttribute('open', ''));
  await click(container.querySelector('button.zoom'));
  expect(dialog.showModal).toHaveBeenCalled();
  expect(dialog.querySelector('img')!.getAttribute('src')).toBe('/api/v1/catalog/images/66666666-6666-4666-8666-666666666666?kind=image');
});

it('shows not found for a 404 and keeps the image alt when it fails to load', async () => {
  mockFetch(() => json({ code: 'NOT_FOUND', message: 'PRODUCT_NOT_FOUND', errors: [] }, 404));
  expect((await render(PATH)).textContent).toContain('Không tìm thấy trang hoặc sản phẩm.');
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
  mockFetch(() => json(product));
  const container = await render(PATH);
  const img = container.querySelector('.gallery img')!;
  await act(async () => { img.dispatchEvent(new Event('error')); });
  await flush();
  expect(container.querySelector('.gallery [role="img"]')!.getAttribute('aria-label')).toBe('Áo thun mẫu');
});

it('partial size/colour matrix never gets stuck (review 1)', async () => {
  const partial = { ...product, data: { ...product.data, variants: [
    { ...product.data.variants[0], id: '55555555-5555-4555-8555-555555555561', size: 'S', color: 'Red', sku: 'S-RED' },
    { ...product.data.variants[0], id: '55555555-5555-4555-8555-555555555562', size: 'M', color: 'Blue', sku: 'M-BLUE' },
  ] } };
  mockFetch(() => json(partial));
  const container = await render(PATH);
  await choose(container, 'size', 'S');
  await choose(container, 'color', 'Red');
  const m = container.querySelector('input[name="size"][value="M"]') as HTMLInputElement;
  expect(m.disabled).toBe(false);
  await choose(container, 'size', 'M');
  expect((container.querySelector('input[name="color"][value="Red"]') as HTMLInputElement).checked).toBe(false);
  await choose(container, 'color', 'Blue');
  expect(container.querySelector('.stock')!.textContent).toBe('Còn hàng (tham khảo)');
});
