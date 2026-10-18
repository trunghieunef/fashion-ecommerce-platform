import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, test } from '@playwright/test';

// TASK:WEB-01 on the real chain (storefront -> Gateway -> catalog -> PostgreSQL), desktop and 360px projects.
// Data is whatever the local smoke left behind, so the detail part runs only when a public product exists.
test('home, listing with URL filters, detail by keyboard and language switch', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Bộ sưu tập' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Hàng mới' })).toBeVisible();
  await expect(page.getByText('Không thể tải dữ liệu')).toHaveCount(0);

  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Sản phẩm' }).click();
  await expect(page.getByRole('heading', { name: 'Sản phẩm' })).toBeVisible();
  await page.getByLabel('Size').fill('M');
  await page.getByRole('button', { name: 'Áp dụng' }).click();
  await expect(page).toHaveURL(/size=M/);
  await expect(page.getByText('Không thể tải dữ liệu')).toHaveCount(0);

  const list = page.getByRole('list', { name: 'Danh sách sản phẩm' });
  if (await list.count()) {
    await list.getByRole('link').first().click();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByText('Tồn kho chỉ để tham khảo')).toBeVisible();
    const size = page.getByRole('radio').first();
    await size.focus();
    await page.keyboard.press('Space');
    await expect(size).toBeChecked();
  } else {
    await expect(page.getByText(/Chưa có sản phẩm|Không có sản phẩm phù hợp/)).toBeVisible();
  }

  await page.getByRole('button', { name: 'English' }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.getByRole('button', { name: 'Tiếng Việt' })).toBeVisible();
  // No horizontal scroll at either viewport.
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

// The local smoke unpublishes its product, so the detail flow also runs against the contract-validated fixtures
// (tests/contracts/test_storefront_fixtures.py) to be deterministic on both viewports.
test('detail with contract fixtures: keyboard variant choice, stock states, image fallback, English', async ({ page }) => {
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
  await page.goto('/products');
  await page.getByRole('list', { name: 'Danh sách sản phẩm' }).getByRole('link', { name: 'Áo thun mẫu' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Áo thun mẫu' })).toBeVisible();
  await expect(page.getByText('Chọn size và màu')).toBeVisible();

  const sizeM = page.getByRole('radio', { name: 'M' });
  await sizeM.focus();
  await page.keyboard.press('Space');
  await expect(sizeM).toBeChecked();
  await page.keyboard.press('Tab');
  await page.keyboard.press('Space');
  await expect(page.getByRole('radio', { name: 'Blue' })).toBeChecked();
  await expect(page.getByText('Còn hàng (tham khảo)')).toBeVisible();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('radio', { name: 'Red' })).toBeChecked();
  await expect(page.getByText('Chưa rõ tồn kho')).toBeVisible();

  // Image endpoint 404s here, so the placeholder keeps the alt text.
  await expect(page.getByRole('img', { name: 'Áo thun mẫu' }).first()).toBeVisible();
  await expect(page.getByRole('table')).toBeVisible();
  await page.getByRole('button', { name: 'English' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Sample tee' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('unknown route and unknown product show not found', async ({ page }) => {
  await page.goto('/no/such/page');
  await expect(page.getByText('Không tìm thấy trang hoặc sản phẩm.')).toBeVisible();
  await page.goto('/products/00000000-0000-4000-8000-000000000000');
  await expect(page.getByText('Không tìm thấy trang hoặc sản phẩm.')).toBeVisible();
});
