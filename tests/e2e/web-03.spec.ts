import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, test, type BrowserContext, type Page } from '@playwright/test';

// TASK:WEB-03 — checkout COD, order page and member orders, desktop and 360px projects.

async function fillAddress(page: Page) {
  const main = page.getByRole('main');
  await main.getByLabel('Người nhận', { exact: true }).fill('Khách E2E');
  await main.getByLabel('Điện thoại người nhận').fill('0912345678');
  await main.getByLabel('Tỉnh/thành phố').selectOption('79');
  await main.getByLabel('Phường/xã').selectOption('26734');
  await main.getByLabel('Địa chỉ chi tiết').fill('12 Đường E2E');
}

test.describe('real chain', () => {
  // POSTs carry the refresh cookie, so the Gateway checks Origin (ADR-20): the local allowlist is http://localhost:4173.
  const base = process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:4173';
  test.use({ baseURL: base.replace('://127.0.0.1', '://localhost') });

  test('a member checks out with COD, sees the order prepared and finds it in My orders', async ({ page }, info) => {
    await page.goto('/register');
    await page.getByLabel('Họ và tên').fill('Khách Đặt Hàng E2E');
    await page.getByLabel('Email').fill(`web03-${Date.now()}-${info.project.name}@example.test`);
    await page.getByLabel('Mật khẩu', { exact: true }).fill('e2e-password-123');
    await page.getByRole('main').getByRole('button', { name: 'Đăng ký' }).click();
    await expect(page).toHaveURL(/\/account$/);

    await page.goto('/products');
    const list = page.getByRole('list', { name: 'Danh sách sản phẩm' });
    await expect(list.or(page.getByText(/Chưa có sản phẩm/))).toBeVisible();
    if (!(await list.count())) {
      // The local smoke unpublishes its product: without one, an empty cart cannot check out.
      await page.goto('/checkout');
      await expect(page).toHaveURL(/\/cart$/);
      return;
    }
    await list.getByRole('link').first().click();
    for (const group of [/^Màu/, /^Size/]) {
      const radio = page.getByRole('group', { name: group }).getByRole('radio').first();
      await radio.focus();
      await page.keyboard.press('Space');
    }
    await page.getByRole('button', { name: 'Thêm vào giỏ' }).click();
    await page.getByRole('dialog', { name: 'Đã thêm vào giỏ' }).getByRole('link', { name: 'Xem giỏ' }).click();
    await page.getByRole('main').getByRole('link', { name: 'Thanh toán' }).click();
    await expect(page).toHaveURL(/\/checkout$/);

    await page.getByRole('main').getByLabel('Số điện thoại', { exact: true }).fill('0912345678');
    await fillAddress(page);
    await page.getByRole('button', { name: 'Xem tổng tiền' }).click();
    const place = page.getByRole('button', { name: /^Đặt hàng · / });
    await expect(place).toBeVisible();
    await place.click();
    await expect(page).toHaveURL(/\/orders\/[A-Z0-9]+$/);
    const orderNo = page.url().split('/').pop()!;
    // The saga reserves stock and creates the COD payment in the background; the page polls until it is done.
    await expect(page.getByRole('main').getByRole('status')).not.toHaveText('Đang chuẩn bị đơn', { timeout: 30_000 });
    await expect(page.getByRole('main').getByRole('status')).toHaveText('Đã tiếp nhận, chờ shop xác nhận');

    await page.goto('/account/orders');
    await expect(page.getByTestId('order-row').first()).toContainText(orderNo);
  });
});

test.describe('fixture', () => {
  const fixture = (name: string) => readFileSync(join(__dirname, '../../web/storefront/src/test/fixtures', name), 'utf8');
  const metadata = { request_id: 'e2e', trace_id: 'e2e' };
  const GUEST = JSON.parse(fixture('cart-guest.json')).data.cart_id as string;
  const problem = (status: number, code: string, message = code, errors?: unknown[]) =>
    ({ status, body: JSON.stringify({ code, message, errors, metadata }) });
  type Answer = { status: number; body?: string } | null;

  /** A guest with the fixture cart; `answer(method, path)` handles checkout and order calls. */
  async function shop(context: BrowserContext, answer: (method: string, path: string) => Answer | 'hang') {
    await context.addInitScript((id) => localStorage.setItem('storefront.cart', id), GUEST);
    await context.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      const result = answer(route.request().method(), path)
        ?? (path === '/api/v1/auth/refresh' ? problem(401, 'UNAUTHORIZED')
          : path === `/api/v1/carts/${GUEST}` ? { status: 200, body: fixture('cart-guest.json') }
            : path === `/api/v1/carts/${GUEST}/checkout-token` ? { status: 200, body: fixture('checkout-token.json') }
              : path.startsWith('/api/v1/catalog/') ? { status: 200, body: fixture(path.includes('/brands') ? 'brands.json'
                : path.includes('/collections') ? 'collections.json' : path.includes('/categories') ? 'categories.json' : 'products.json') }
                : problem(404, 'NOT_FOUND'));
      if (result === 'hang') return; // a response that never arrives
      await route.fulfill({ status: result.status, contentType: 'application/json', body: result.body ?? '' });
    });
  }
  async function fillGuest(page: Page) {
    await page.getByRole('main').getByLabel('Số điện thoại', { exact: true }).fill('0912345678');
    await page.getByRole('main').getByLabel('Email').fill('khach@example.test');
    await fillAddress(page);
  }

  test('guest checkout: quote, price change, reconfirm, order page polls to confirmed', async ({ page, context }) => {
    const quote = JSON.parse(fixture('quote.json'));
    const changed = { ...quote, data: { ...quote.data, quote_token: 'second-quote', totals: { ...quote.data.totals, subtotal: 320000, total_amount: 360000 } } };
    let quotes = 0;
    let creates = 0;
    let reads = 0;
    await shop(context, (method, path) => {
      if (path === '/api/v1/orders/quote') return { status: 200, body: JSON.stringify(quotes++ === 0 ? quote : changed) };
      if (path === '/api/v1/orders' && method === 'POST')
        return creates++ === 0 ? problem(409, 'PRICE_CHANGED', 'PRICE_CHANGED', [{ field: quote.data.items[0].variant_id, message: 'old=100000,new=120000' }])
          : { status: 202, body: fixture('order-processing.json') };
      if (path === '/api/v1/orders/ORD202610000001')
        return { status: 200, body: fixture(reads++ === 0 ? 'order-processing.json' : 'order-confirmed.json') };
      return null;
    });
    await page.goto('/checkout');
    await fillGuest(page);
    await page.getByRole('button', { name: 'Xem tổng tiền' }).click();
    await expect(page.getByTestId('summary')).toContainText('340.000');
    await page.getByRole('button', { name: /^Đặt hàng · 340\.000/ }).click();
    await expect(page.getByRole('main').getByRole('alert').first()).toContainText('Giá đã thay đổi. Vui lòng kiểm tra rồi đặt lại.');
    await page.getByRole('button', { name: /^Đặt hàng · 360\.000/ }).click();
    await expect(page).toHaveURL(/\/orders\/ORD202610000001$/);
    await expect(page.getByRole('main').getByRole('status')).toHaveText('Đã tiếp nhận, chờ shop xác nhận', { timeout: 10_000 });
    expect(creates).toBe(2);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  test('a lost order response and a reload place one order', async ({ page, context }) => {
    const keys: string[] = [];
    await shop(context, (method, path) => {
      if (path === '/api/v1/orders/quote') return { status: 200, body: fixture('quote.json') };
      if (path === '/api/v1/orders/ORD202610000001') return { status: 200, body: fixture('order-confirmed.json') };
      return null;
    });
    await context.route('**/api/v1/orders', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      keys.push(route.request().headers()['idempotency-key']);
      if (keys.length === 1) return; // the first response is lost
      await route.fulfill({ status: 202, contentType: 'application/json', body: fixture('order-processing.json') });
    });
    await page.goto('/checkout');
    await fillGuest(page);
    await page.getByRole('button', { name: 'Xem tổng tiền' }).click();
    await page.getByRole('button', { name: /^Đặt hàng · / }).click();
    await expect.poll(() => keys.length).toBe(1);
    await page.reload();
    await expect(page).toHaveURL(/\/orders\/ORD202610000001$/);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });
});
