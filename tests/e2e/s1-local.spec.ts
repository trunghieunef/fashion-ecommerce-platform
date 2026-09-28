import { expect, test } from '@playwright/test';

// Runs against the real chain: storefront -> Gateway -> catalog -> PostgreSQL.
test('deep link renders catalog from the real chain while API misses stay 404', async ({ page, request }) => {
  await page.goto('/products');
  await expect(page.getByRole('heading', { name: 'Sản phẩm' })).toBeVisible();
  await expect(
    page.getByText('Chưa có sản phẩm').or(page.getByRole('list', { name: 'Danh sách sản phẩm' })),
  ).toBeVisible();
  await expect(page.getByText('Không thể tải sản phẩm')).toHaveCount(0);
  expect((await request.get('/api/not-found')).status()).toBe(404);
});
