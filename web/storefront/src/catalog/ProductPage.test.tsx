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
  mockFetch((path) => (path.startsWith('/products/') ? json(product) : null));
  const container = await render(PATH);
  expect(container.querySelector('h1')!.textContent).toBe('Áo thun mẫu');
  expect(container.querySelector('[data-testid="stock"]')!.textContent).toBe('Chọn size và màu');
  expect(container.textContent).toContain('Tồn kho chỉ để tham khảo');

  await choose(container, 'size', 'L');
  // L has no Red variant: picking Red clears L instead of disabling Red (no dead end, review 1).
  await choose(container, 'color', 'Red');
  expect((container.querySelector('input[name="size"][value="L"]') as HTMLInputElement).checked).toBe(false);
  await choose(container, 'size', 'L');
  expect((container.querySelector('input[name="color"][value="Red"]') as HTMLInputElement).checked).toBe(false);
  await choose(container, 'color', 'Blue');
  expect(container.querySelector('[data-testid="stock"]')!.textContent).toBe('Hết hàng');
  expect(container.querySelector('[data-testid="product-info"] [data-testid="price"]')!.textContent).toMatch(/^120\.000\s₫$/);

  await choose(container, 'size', 'M');
  expect(container.querySelector('[data-testid="stock"]')!.textContent).toBe('Còn hàng (tham khảo)');
  await choose(container, 'color', 'Red');
  expect(container.querySelector('[data-testid="stock"]')!.textContent).toBe('Chưa rõ tồn kho');
});

it('renders the size guide table and sends Accept-Language, refetching in English', async () => {
  const calls = mockFetch((path) => (path.startsWith('/products/') ? json(product) : null));
  const container = await render(PATH);
  const detailCalls = () => calls.filter((c) => c.url.includes('/products/'));
  expect(detailCalls()[0].lang).toBe('vi');
  await click(byText(container, 'main button', 'Bảng size'));
  const guide = container.querySelector('main dialog[open]')!;
  expect(byText(guide, 'h2', 'Bảng size')).toBeDefined();
  expect([...guide.querySelectorAll('th')].map((th) => th.textContent)).toEqual(['Size', 'Ngực (cm)']);
  await click(byText(container, 'button', 'English'));
  expect(detailCalls().at(-1)!.lang).toBe('en');
  expect(container.querySelector('h1')!.textContent).toBe('Sample tee');
  expect(document.documentElement.lang).toBe('en');
});

it('opens the zoom dialog from the image button', async () => {
  mockFetch((path) => (path.startsWith('/products/') ? json(product) : null));
  const container = await render(PATH);
  const dialog = container.querySelector('[data-testid="zoom"]') as HTMLDialogElement;
  dialog.showModal = vi.fn(() => dialog.setAttribute('open', ''));
  await click(container.querySelector('button[aria-label^="Phóng to ảnh"]'));
  expect(dialog.showModal).toHaveBeenCalled();
  expect(dialog.querySelector('img')!.getAttribute('src')).toBe('/api/v1/catalog/images/66666666-6666-4666-8666-666666666666?kind=image');
});

it('shows not found for a 404 and keeps the image alt when it fails to load', async () => {
  mockFetch((path) => (path.startsWith('/products/') ? json({ code: 'NOT_FOUND', message: 'PRODUCT_NOT_FOUND', errors: [] }, 404) : null));
  expect((await render(PATH)).textContent).toContain('Không tìm thấy trang hoặc sản phẩm.');
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
  mockFetch((path) => (path.startsWith('/products/') ? json(product) : null));
  const container = await render(PATH);
  const img = container.querySelector('[data-testid="gallery"] img')!;
  await act(async () => { img.dispatchEvent(new Event('error')); });
  await flush();
  expect(container.querySelector('[data-testid="gallery"] [role="img"]')!.getAttribute('aria-label')).toBe('Áo thun mẫu');
});

it('partial size/colour matrix never gets stuck (review 1)', async () => {
  const partial = { ...product, data: { ...product.data, variants: [
    { ...product.data.variants[0], id: '55555555-5555-4555-8555-555555555561', size: 'S', color: 'Red', sku: 'S-RED' },
    { ...product.data.variants[0], id: '55555555-5555-4555-8555-555555555562', size: 'M', color: 'Blue', sku: 'M-BLUE' },
  ] } };
  mockFetch((path) => (path.startsWith('/products/') ? json(partial) : null));
  const container = await render(PATH);
  await choose(container, 'size', 'S');
  await choose(container, 'color', 'Red');
  const m = container.querySelector('input[name="size"][value="M"]') as HTMLInputElement;
  expect(m.disabled).toBe(false);
  await choose(container, 'size', 'M');
  expect((container.querySelector('input[name="color"][value="Red"]') as HTMLInputElement).checked).toBe(false);
  await choose(container, 'color', 'Blue');
  expect(container.querySelector('[data-testid="stock"]')!.textContent).toBe('Còn hàng (tham khảo)');
});

it('every image is reachable and the zoom shows the chosen one (PR #28 P2)', async () => {
  const many = { ...product, data: { ...product.data, images: [
    { asset_id: '66666666-6666-4666-8666-666666666661', alt_vi: 'Mặt trước', alt_en: 'Front', variant_color: null, sort_order: 0 },
    { asset_id: '66666666-6666-4666-8666-666666666662', alt_vi: 'Mặt sau', alt_en: 'Back', variant_color: null, sort_order: 1 },
    { asset_id: '66666666-6666-4666-8666-666666666663', alt_vi: 'Đỏ trước', alt_en: 'Red front', variant_color: 'Red', sort_order: 2 },
    { asset_id: '66666666-6666-4666-8666-666666666664', alt_vi: 'Đỏ sau', alt_en: 'Red back', variant_color: 'Red', sort_order: 3 },
  ] } };
  mockFetch((path) => (path.startsWith('/products/') ? json(many) : null));
  const container = await render(PATH);
  // TASK:WEB-01b: every image is in the gallery (no thumbnail strip); each one opens the zoom on itself.
  const zooms = () => [...container.querySelectorAll('[data-testid="gallery"] button[aria-label^="Phóng to ảnh"]')] as HTMLButtonElement[];
  expect(zooms()).toHaveLength(4);
  const zoomed = () => container.querySelector('[data-testid="zoom"] img')!.getAttribute('src');
  await click(zooms()[1]);
  expect(zoomed()).toContain('666666666662');
  await choose(container, 'color', 'Red');
  expect(zooms()).toHaveLength(4);
  await click(zooms().find((b) => b.getAttribute('aria-label')?.includes('Đỏ sau')));
  expect(zoomed()).toContain('666666666664');
});

// TASK:WEB-01b — swatches, legend, size-guide drawer, breadcrumb.

const withImages = (images: unknown[]) => ({ ...product, data: { ...product.data, images } });

it('colour swatch is a thumbnail when an image has that variant_color, a text pill otherwise', async () => {
  mockFetch((path) => (path.startsWith('/products/') ? json(withImages([
    { asset_id: '66666666-6666-4666-8666-666666666671', alt_vi: 'Xanh', alt_en: 'Blue', variant_color: 'Blue', sort_order: 0 },
    // Matching is exact (as in WEB-01): a lower-case "red" image does not illustrate the "Red" variant.
    { asset_id: '66666666-6666-4666-8666-666666666672', alt_vi: 'Đỏ', alt_en: 'Red', variant_color: 'red', sort_order: 1 },
  ])) : null));
  const container = await render(PATH);
  const swatch = (color: string) => container.querySelector(`input[name="color"][value="${color}"]`)!.closest('label')!;
  const blue = swatch('Blue').querySelector('img')!;
  expect(blue.getAttribute('src')).toBe('/api/v1/catalog/images/66666666-6666-4666-8666-666666666671?kind=thumb');
  expect(blue.getAttribute('alt')).toBe('Blue');
  expect(swatch('Red').querySelector('img, [role="img"]')).toBeNull();
  expect(swatch('Red').textContent).toBe('Red');
});

it('legend shows the chosen colour', async () => {
  mockFetch((path) => (path.startsWith('/products/') ? json(product) : null));
  const container = await render(PATH);
  const legend = () => container.querySelector('input[name="color"]')!.closest('fieldset')!.querySelector('legend')!.textContent;
  expect(legend()).toBe('Màu');
  await choose(container, 'color', 'Red');
  expect(legend()).toBe('Màu: Red');
});

it('size guide opens in a drawer and is hidden without a guide', async () => {
  mockFetch((path) => (path.startsWith('/products/') ? json(product) : null));
  const container = await render(PATH);
  await click(byText(container, 'main button', 'Bảng size'));
  const open = container.querySelector('main dialog[open]')!;
  expect(byText(open, 'th', 'Ngực (cm)')).toBeDefined();
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
  mockFetch((path) => (path.startsWith('/products/') ? json({ ...product, data: { ...product.data, size_guide: null } }) : null));
  const none = await render(PATH);
  expect(byText(none, 'main button', 'Bảng size')).toBeUndefined();
});

it('breadcrumb links to the category', async () => {
  mockFetch((path) => (path.startsWith('/products/') ? json(product) : null));
  const container = await render(PATH);
  const crumb = container.querySelector('[data-testid="product-info"] a[href="/products?category_id=44444444-4444-4444-8444-444444444444"]');
  expect(crumb?.textContent).toBe('Áo');
});

it('a single image fills the gallery instead of half of a two-column grid', async () => {
  mockFetch((path) => (path.startsWith('/products/') ? json(product) : null));
  const container = await render(PATH);
  expect(container.querySelector('[data-testid="gallery"] ul')!.className).not.toContain('lg:grid-cols-2');
});

it('the size group is named by a legend that is the fieldset\'s first child (final review)', async () => {
  mockFetch((path) => (path.startsWith('/products/') ? json(product) : null));
  const container = await render(PATH);
  const fieldset = container.querySelector('input[name="size"]')!.closest('fieldset')!;
  expect(fieldset.firstElementChild?.tagName).toBe('LEGEND');
  expect(fieldset.firstElementChild?.textContent).toBe('Size');
});

it('choosing a colour scrolls the gallery strip, never the page (final review)', async () => {
  const scrollIntoView = vi.fn();
  const scrollTo = vi.fn();
  Element.prototype.scrollIntoView = scrollIntoView;
  HTMLElement.prototype.scrollTo = scrollTo as unknown as HTMLElement['scrollTo'];
  try {
    mockFetch((path) => (path.startsWith('/products/') ? json({ ...product, data: { ...product.data, images: [
      { asset_id: '66666666-6666-4666-8666-666666666681', alt_vi: 'Xanh', alt_en: 'Blue', variant_color: 'Blue', sort_order: 0 },
      { asset_id: '66666666-6666-4666-8666-666666666682', alt_vi: 'Đỏ', alt_en: 'Red', variant_color: 'Red', sort_order: 1 },
    ] } }) : null));
    const container = await render(PATH);
    await choose(container, 'color', 'Red');
    expect(scrollIntoView).not.toHaveBeenCalled();
    expect(scrollTo).toHaveBeenCalled();
  } finally {
    delete (Element.prototype as Partial<Element>).scrollIntoView;
    delete (HTMLElement.prototype as Partial<HTMLElement>).scrollTo;
  }
});
