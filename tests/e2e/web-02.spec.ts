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
    await expect(page.getByRole('status')).toHaveText('Đã lưu địa chỉ.');
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
      const name = path.startsWith('/api/v1/catalog/collections') ? 'collections.json'
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
});
