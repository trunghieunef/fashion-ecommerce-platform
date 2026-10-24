import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, test, type BrowserContext, type Page } from '@playwright/test';

// TASK:WEB-02a — session, account and addresses, desktop and 360px projects.
const isMobile = (page: Page) => (page.viewportSize()?.width ?? 1280) < 1024;

test.describe('real chain', () => {
  // The refresh cookie is sent with POSTs, so the Gateway checks Origin (ADR-20); the local allowlist is
  // http://localhost:4173 (README), not 127.0.0.1.
  const base = process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:4173';
  test.use({ baseURL: base.replace('://127.0.0.1', '://localhost') });

  test('register, session survives a reload, profile, address, sign out', async ({ page }, info) => {
    const email = `web02-${Date.now()}-${info.project.name}@example.test`;
    await page.goto('/register');
    await page.getByLabel('Họ và tên').fill('Khách E2E');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Mật khẩu', { exact: true }).fill('e2e-password-123');
    await page.getByRole('main').getByRole('button', { name: 'Đăng ký' }).click();
    await expect(page).toHaveURL(/\/account$/);
    await expect(page.getByText(email)).toBeVisible();

    await page.reload();
    await expect(page).toHaveURL(/\/account$/);
    await expect(page.getByText(email)).toBeVisible();

    const profile = page.getByRole('form', { name: 'Hồ sơ' });
    await profile.getByLabel('Họ và tên').fill('Khách E2E Sửa');
    await profile.getByRole('button', { name: 'Lưu' }).click();
    await expect(profile.getByRole('status')).toHaveText('Đã lưu.');

    await page.getByRole('link', { name: 'Quản lý địa chỉ' }).click();
    await page.getByRole('button', { name: 'Thêm địa chỉ' }).click();
    const dialog = page.getByRole('dialog', { name: 'Thêm địa chỉ' });
    await dialog.getByLabel('Người nhận').fill('Khách E2E');
    await dialog.getByLabel('Số điện thoại').fill('0912345678');
    await dialog.getByLabel('Tỉnh/thành phố').selectOption('79');
    await dialog.getByLabel('Phường/xã').selectOption('26734');
    await dialog.getByLabel('Địa chỉ chi tiết').fill('1 Đường E2E');
    await dialog.getByRole('button', { name: 'Lưu' }).click();
    await expect(page.getByRole('main').getByRole('status')).toHaveText('Đã lưu địa chỉ.');
    await expect(page.getByTestId('address-card')).toHaveCount(1);
    await expect(page.getByTestId('address-card')).toContainText('Mặc định');

    await page.getByRole('main').getByRole('link', { name: 'Tài khoản' }).click();
    await page.getByRole('button', { name: 'Đăng xuất' }).click();
    // Sign out ends the session only after the server dropped the cookie; wait for the header to say so before
    // navigating, so the goto cannot abort an in-flight logout.
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('banner').getByRole('link', { name: 'Đăng nhập' })).toBeAttached();
    await page.goto('/account');
    await expect(page).toHaveURL(/\/login\?next=%2Faccount$/);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
  // TASK:WEB-02b: a guest cart follows the visitor into the new account.
  test('guest cart merges on registration, then quantity and clear', async ({ page }, info) => {
    await page.goto('/products');
    const list = page.getByRole('list', { name: 'Danh sách sản phẩm' });
    await expect(list.or(page.getByText(/Chưa có sản phẩm/))).toBeVisible();
    if (!(await list.count())) {
      // The local smoke unpublishes its product: without a public product only the empty cart can be checked.
      await page.goto('/cart');
      await expect(page.getByText('Giỏ hàng của bạn đang trống.')).toBeVisible();
      return;
    }
    await list.getByRole('link').first().click();
    for (const group of [/^Màu/, /^Size/]) {
      const radio = page.getByRole('group', { name: group }).getByRole('radio').first();
      await radio.focus();
      await page.keyboard.press('Space');
    }
    await page.getByRole('button', { name: 'Thêm vào giỏ' }).click();
    await expect(page.getByRole('dialog', { name: 'Đã thêm vào giỏ' })).toBeVisible();
    await page.getByRole('button', { name: 'Tiếp tục mua' }).click();
    await expect(page.getByRole('link', { name: 'Giỏ hàng, 1 sản phẩm' }).first()).toBeAttached();

    await page.goto('/register');
    await page.getByLabel('Họ và tên').fill('Khách Giỏ E2E');
    await page.getByLabel('Email').fill(`web02b-${Date.now()}-${info.project.name}@example.test`);
    await page.getByLabel('Mật khẩu', { exact: true }).fill('e2e-password-123');
    await page.getByRole('main').getByRole('button', { name: 'Đăng ký' }).click();
    await expect(page).toHaveURL(/\/account$/);
    await expect(page.getByRole('link', { name: 'Giỏ hàng, 1 sản phẩm' }).first()).toBeAttached();

    await page.goto('/cart');
    await expect(page.getByTestId('cart-line')).toHaveCount(1);
    await page.getByRole('button', { name: 'Tăng số lượng' }).click();
    await expect(page.getByRole('link', { name: 'Giỏ hàng, 2 sản phẩm' }).first()).toBeAttached();
    await page.getByRole('main').getByRole('button', { name: 'Xóa giỏ', exact: true }).click();
    await page.getByRole('dialog', { name: 'Xóa giỏ' }).getByRole('button', { name: 'Xóa giỏ hàng' }).click();
    await expect(page.getByText('Giỏ hàng của bạn đang trống.')).toBeVisible();
  });
});

test.describe('fixture', () => {
  const fixture = (name: string) => readFileSync(join(__dirname, '../../web/storefront/src/test/fixtures', name), 'utf8');
  const metadata = { request_id: 'e2e', trace_id: 'e2e' };

  /** Catalog reads from the contract-validated fixtures; auth/user calls answered by `auth`. */
  async function routes(context: BrowserContext, auth: (path: string) => { status: number; body?: string } | null) {
    await context.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      const answer = auth(path);
      if (answer) return route.fulfill({ status: answer.status, contentType: 'application/json', body: answer.body ?? '' });
      // Only catalog reads fall back to fixtures; an unexpected auth/user call must not get catalog data.
      if (!path.startsWith('/api/v1/catalog/') || path.startsWith('/api/v1/catalog/images/'))
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"code":"NOT_FOUND"}' });
      const name = path.startsWith('/api/v1/catalog/products/') ? 'product.json'
        : path.startsWith('/api/v1/catalog/collections') ? 'collections.json'
        : path.startsWith('/api/v1/catalog/categories') ? 'categories.json'
        : path.startsWith('/api/v1/catalog/brands') ? 'brands.json' : 'products.json';
      return route.fulfill({ status: 200, contentType: 'application/json', body: fixture(name) });
    });
  }
  const unauthorized = { status: 401, body: JSON.stringify({ code: 'UNAUTHORIZED', message: 'no', metadata }) };

  test('a wrong password shows the fixed message in a focused summary', async ({ page, context }) => {
    await routes(context, (path) => (path === '/api/v1/auth/refresh' || path === '/api/v1/auth/login' ? unauthorized : null));
    await page.goto('/login');
    await page.getByLabel('Email').fill('member@example.test');
    await page.getByLabel('Mật khẩu', { exact: true }).fill('wrong password');
    await page.getByRole('main').getByRole('button', { name: 'Đăng nhập' }).click();
    const summary = page.getByRole('main').getByRole('alert');
    await expect(summary).toContainText('Email hoặc mật khẩu không đúng.');
    await expect(summary).toBeFocused();
    await expect(page.getByLabel('Email')).toHaveValue('member@example.test');
  });

  test('two tabs restoring the session never refresh at the same time', async ({ context }) => {
    const spans: { start: number; end: number }[] = [];
    // Registered after the general routes: Playwright tries the most recently added route first.
    await routes(context, (path) => (path === '/api/v1/users/me' ? { status: 200, body: fixture('profile.json') } : null));
    await context.route('**/api/v1/auth/refresh', async (route) => {
      const start = Date.now();
      await new Promise((r) => setTimeout(r, 300));
      spans.push({ start, end: Date.now() });
      await route.fulfill({ status: 200, contentType: 'application/json', body: fixture('refresh.json') });
    });
    const [a, b] = [await context.newPage(), await context.newPage()];
    await Promise.all([a.goto('/'), b.goto('/')]);
    for (const page of [a, b]) {
      const account = isMobile(page) ? page.getByRole('link', { name: 'Tài khoản' }).first() : page.getByRole('banner').getByRole('link', { name: 'Tài khoản' });
      // While restoring, the link already says "Tài khoản" but is busy; wait until the session is settled.
      await expect(account).toBeVisible();
      await expect(account).not.toHaveAttribute('aria-busy', 'true');
    }
    expect(spans).toHaveLength(2);
    const [first, second] = [...spans].sort((x, y) => x.start - y.start);
    expect(second.start).toBeGreaterThanOrEqual(first.end);
  });
  // TASK:WEB-02b
  const GUEST = JSON.parse(fixture('cart-guest.json')).data.cart_id as string;
  const cartRoutes = (extra: (path: string, method: string) => { status: number; body?: string } | null = () => null) =>
    async (context: BrowserContext) => {
      await routes(context, (path) => (path === '/api/v1/auth/refresh' ? unauthorized : null));
      await context.route('**/api/v1/carts**', async (route) => {
        const path = new URL(route.request().url()).pathname;
        const method = route.request().method();
        const answer = extra(path, method)
          ?? (path === '/api/v1/carts' ? { status: 201, body: fixture('cart-empty.json') } : { status: 200, body: fixture('cart-guest.json') });
        await route.fulfill({ status: answer.status, contentType: 'application/json', body: answer.body ?? '' });
      });
    };

  test('add to cart from the product page, then a conflict on the cart page is explained', async ({ page, context }) => {
    await cartRoutes((path, method) => (method === 'PUT'
      ? { status: 409, body: JSON.stringify({ code: 'VERSION_CONFLICT', message: 'VERSION_CONFLICT', metadata }) } : null))(context);
    await page.goto('/products/11111111-1111-4111-8111-111111111111');
    for (const name of ['Blue', 'M']) {
      const radio = page.getByRole('radio', { name, exact: true });
      await radio.focus();
      await page.keyboard.press('Space');
    }
    await page.getByRole('button', { name: 'Thêm vào giỏ' }).click();
    const added = page.getByRole('dialog', { name: 'Đã thêm vào giỏ' });
    await expect(added).toContainText('Áo thun mẫu');
    await added.getByRole('link', { name: 'Xem giỏ' }).click();
    await expect(page).toHaveURL(/\/cart$/);
    await expect(page.getByTestId('cart-line')).toHaveCount(2);
    await expect(page.getByTestId('cart-line').first()).toContainText('Áo thun mẫu');
    await expect(page.getByTestId('cart-line').nth(1)).toContainText('Không còn bán');
    await page.getByTestId('cart-line').first().getByRole('button', { name: 'Tăng số lượng' }).click();
    await expect(page.getByRole('main').getByRole('alert')).toContainText('Giỏ hàng vừa thay đổi ở nơi khác, vui lòng kiểm tra lại.');
    await expect(page.getByRole('button', { name: 'Thanh toán' })).toBeDisabled();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  test('signing in with a guest cart shows what the merge adjusted', async ({ page, context }) => {
    await context.addInitScript((id) => localStorage.setItem('storefront.cart', id), GUEST);
    await cartRoutes((path) => (path === '/api/v1/carts/mine' ? { status: 200, body: fixture('cart-member.json') }
      : path === '/api/v1/carts/merge' ? { status: 200, body: fixture('merge.json') } : null))(context);
    await context.route('**/api/v1/auth/login', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: fixture('session.json') }));
    await page.goto('/login');
    await page.getByLabel('Email').fill('member@example.test');
    await page.getByLabel('Mật khẩu', { exact: true }).fill('correct horse');
    await page.getByRole('main').getByRole('button', { name: 'Đăng nhập' }).click();
    const notice = page.getByRole('dialog', { name: 'Đã gộp giỏ hàng' });
    await expect(notice).toContainText('TEE-M-BLUE');
    await expect(notice).toContainText('5 → 3');
    await notice.getByRole('button', { name: 'Đã hiểu' }).click();
    await expect(page.getByRole('link', { name: 'Giỏ hàng, 3 sản phẩm' }).first()).toBeAttached();
  });
});
