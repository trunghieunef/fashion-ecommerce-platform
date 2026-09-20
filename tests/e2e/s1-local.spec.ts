import { expect, test } from '@playwright/test';

test('deep link works while API misses stay 404', async ({ page, request }) => {
  await page.route('**/api/v1/catalog/products**', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'OK',
        data: { items: [], next_cursor: null },
        metadata: { request_id: 'e2e-1', trace_id: 'e2e-1' },
      }),
    });
  });

  await page.goto('/products');
  await expect(page.getByRole('heading', { name: 'Sản phẩm' })).toBeVisible();
  await expect(page.getByText('Chưa có sản phẩm')).toBeVisible();
  expect((await request.get('/api/not-found')).status()).toBe(404);
});
