import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, test, type Page } from '@playwright/test';

// TASK:WEB-01b: below lg the desktop navigation and language button are hidden, so these helpers go through the menu.
const isMobile = (page: Page) => (page.viewportSize()?.width ?? 1280) < 1024;
async function goToProducts(page: Page) {
  if (isMobile(page)) {
    await page.getByRole('button', { name: 'Menu' }).click();
    await page.getByRole('dialog', { name: 'Menu' }).getByRole('link', { name: 'Sản phẩm', exact: true }).click();
  } else {
    await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Sản phẩm', exact: true }).click();
  }
}
async function switchToEnglish(page: Page) {
  if (isMobile(page)) await page.getByRole('button', { name: 'Menu' }).click();
  await page.getByRole('button', { name: 'English' }).click();
}
/** Opens "Bộ sưu tập" (desktop mega-menu panel or mobile menu accordion); returns where its links render. */
async function openCollections(page: Page) {
  if (isMobile(page)) {
    await page.getByRole('button', { name: 'Menu' }).click();
    const menu = page.getByRole('dialog', { name: 'Menu' });
    await menu.getByText('Bộ sưu tập', { exact: true }).click();
    return menu;
  }
  const nav = page.getByRole('navigation', { name: 'Điều hướng chính' });
  await nav.getByRole('button', { name: 'Bộ sưu tập' }).click();
  return nav;
}
const noHorizontalScroll = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

// TASK:WEB-01 on the real chain (storefront -> Gateway -> catalog -> PostgreSQL), desktop and 360px projects.
// Data is whatever the local smoke left behind, so the detail part runs only when a public product exists.
test('home, listing with drawer filters and chips, detail by keyboard and language switch', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Hàng mới' })).toBeVisible();
  await expect(page.getByText('Không thể tải dữ liệu')).toHaveCount(0);

  // TASK:WEB-01b: the lazily loaded collections menu settles (links or "no collections"), never stuck loading.
  const collections = await openCollections(page);
  await expect(collections.getByText('Đang tải…')).toHaveCount(0);
  await expect(collections.getByText('Không thể tải dữ liệu')).toHaveCount(0);
  if (isMobile(page)) await page.keyboard.press('Escape');

  await goToProducts(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Tất cả sản phẩm' })).toBeVisible();
  await page.getByRole('button', { name: /^Bộ lọc/ }).click();
  const filters = page.getByRole('dialog', { name: 'Bộ lọc' });
  await filters.getByLabel('Size').fill('M');
  await page.keyboard.press('Tab');
  await expect(page).toHaveURL(/size=M/);
  await filters.getByRole('button', { name: 'Xem kết quả' }).click();
  await expect(page.getByRole('button', { name: 'Bỏ lọc Size: M' })).toBeVisible();
  await expect(page.getByText('Không thể tải dữ liệu')).toHaveCount(0);

  const list = page.getByRole('list', { name: 'Danh sách sản phẩm' });
  if (await list.count()) {
    await list.getByRole('link').first().click();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByText('Tồn kho chỉ để tham khảo')).toBeVisible();
    const size = page.getByRole('group', { name: 'Size' }).getByRole('radio').first();
    await size.focus();
    await page.keyboard.press('Space');
    await expect(size).toBeChecked();
  } else {
    await expect(page.getByText(/Chưa có sản phẩm|Không có sản phẩm phù hợp/)).toBeVisible();
  }

  await switchToEnglish(page);
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  expect(await noHorizontalScroll(page)).toBe(true);
});

// The local smoke unpublishes its product, so the detail flow also runs against the contract-validated fixtures
// (tests/contracts/test_storefront_fixtures.py) to be deterministic on both viewports. Needs no backend, so it also
// runs against `vite preview` (TASK:WEB-01b evidence screenshots).
test('detail with contract fixtures: keyboard variant choice, stock states, image fallback, size guide, English', async ({ page }) => {
  const fixture = (name: string) => readFileSync(join(__dirname, '../../web/storefront/src/test/fixtures', name), 'utf8');
  await page.route('**/api/v1/catalog/**', (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.startsWith('/api/v1/catalog/images/'))
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"code":"NOT_FOUND"}' });
    const name = url.pathname.startsWith('/api/v1/catalog/products/') ? 'product.json'
      : url.pathname.startsWith('/api/v1/catalog/collections') ? 'collections.json'
      : url.pathname.startsWith('/api/v1/catalog/categories') ? 'categories.json'
      : url.pathname.startsWith('/api/v1/catalog/brands') ? 'brands.json' : 'products.json';
    return route.fulfill({ status: 200, contentType: 'application/json', body: fixture(name) });
  });
  const shot = (name: string) => page.screenshot({ path: test.info().outputPath(`${name}.png`), fullPage: true });

  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Hè 2026 (mẫu)' })).toBeVisible();
  await shot('home');

  // Category menu from categories.json ("Áo" → "Áo thun") and the lazy collections list.
  if (isMobile(page)) {
    await page.getByRole('button', { name: 'Menu' }).click();
    const menu = page.getByRole('dialog', { name: 'Menu' });
    await menu.getByText('Áo', { exact: true }).click();
    await expect(menu.getByRole('link', { name: 'Áo thun' })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
  } else {
    const nav = page.getByRole('navigation', { name: 'Điều hướng chính' });
    const tops = nav.getByRole('button', { name: 'Áo', exact: true });
    // PR #37 item 5: moving the pointer straight down from the button into the panel must keep it open.
    await tops.hover();
    const panel = page.locator(`#${await tops.getAttribute('aria-controls')}`);
    const button = (await tops.boundingBox())!;
    await page.mouse.move(button.x + button.width / 2, ((await panel.boundingBox())!).y + 10, { steps: 20 });
    await expect(panel).toBeVisible();
    await page.mouse.move(0, 400);
    await expect(panel).toBeHidden();
    await tops.focus();
    await expect(nav.getByRole('link', { name: 'Áo thun' })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(tops).toHaveAttribute('aria-expanded', 'false');
    await expect(tops).toBeFocused();
  }
  const collections = await openCollections(page);
  await expect(collections.getByRole('link', { name: 'Hè 2026 (mẫu)' })).toBeVisible();
  if (isMobile(page)) await page.keyboard.press('Escape');

  await page.goto('/products?size=M');
  await expect(page.getByRole('button', { name: 'Bỏ lọc Size: M' })).toBeVisible();
  await shot('listing');
  await page.getByRole('list', { name: 'Danh sách sản phẩm' }).getByRole('link', { name: /Áo thun mẫu/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Áo thun mẫu' })).toBeVisible();
  await expect(page.getByText('Chọn size và màu')).toBeVisible();

  const blue = page.getByRole('radio', { name: 'Blue' });
  await blue.focus();
  await page.keyboard.press('Space');
  await expect(blue).toBeChecked();
  const sizeM = page.getByRole('radio', { name: 'M', exact: true });
  await sizeM.focus();
  await page.keyboard.press('Space');
  await expect(sizeM).toBeChecked();
  await expect(page.getByText('Còn hàng (tham khảo)')).toBeVisible();
  await blue.focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('radio', { name: 'Red' })).toBeChecked();
  await expect(page.getByText('Chưa rõ tồn kho')).toBeVisible();

  // Image endpoint 404s here, so the placeholder keeps the alt text.
  await expect(page.getByRole('img', { name: 'Áo thun mẫu' }).first()).toBeVisible();
  await shot('detail');
  await page.getByRole('button', { name: 'Bảng size' }).click();
  const guide = page.getByRole('dialog', { name: 'Bảng size' });
  await expect(guide.getByRole('table')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(guide).toBeHidden();

  await switchToEnglish(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Sample tee' })).toBeVisible();
  expect(await noHorizontalScroll(page)).toBe(true);
});

test('unknown route and unknown product show not found', async ({ page }) => {
  await page.goto('/no/such/page');
  await expect(page.getByText('Không tìm thấy trang hoặc sản phẩm.')).toBeVisible();
  await page.goto('/products/00000000-0000-4000-8000-000000000000');
  await expect(page.getByText('Không tìm thấy trang hoặc sản phẩm.')).toBeVisible();
});
