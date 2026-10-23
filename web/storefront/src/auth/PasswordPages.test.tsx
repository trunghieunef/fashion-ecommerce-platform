// @vitest-environment jsdom
// TASK:WEB-02a: forgot password never reveals whether an account exists; reset reads the token from the fragment once.
import { act } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import forgot from '../test/fixtures/forgot.json';
import { FakeChannel, flush, json, locationOf, mockFetch, render } from '../test/harness';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); });
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

const metadata = { request_id: 'web02a-test', trace_id: 'web02a-test' };
const SENT = 'Nếu email này có tài khoản, chúng tôi đã gửi hướng dẫn đặt lại mật khẩu.';
const field = (container: Element, name: string) => container.querySelector(`main [name="${name}"]`) as HTMLInputElement;

async function type(input: HTMLInputElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function submit(container: Element) {
  await act(async () => { (container.querySelector('main form') as HTMLFormElement).requestSubmit(); });
  await flush();
}

it('forgot shows the same sentence for every accepted email', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/password/forgot' ? json(forgot, 202) : null));
  const texts: string[] = [];
  for (const address of ['member@example.test', 'nobody@example.test']) {
    const container = await render('/forgot-password');
    await type(field(container, 'email'), address);
    await submit(container);
    texts.push(container.querySelector('main')!.textContent ?? '');
    expect(container.querySelector('main form')).toBeNull();
    document.body.innerHTML = '';
  }
  expect(texts[0]).toContain(SENT);
  expect(texts[1]).toBe(texts[0]);
  expect(calls.filter((c) => c.url === '/api/v1/auth/password/forgot').map((c) => c.body)).toEqual([
    { email: 'member@example.test' }, { email: 'nobody@example.test' }]);
});

it('forgot shows the rate-limit sentence on 429', async () => {
  mockFetch((path) => (path === '/api/v1/auth/password/forgot' ? json({ code: 'RATE_LIMITED', message: 'x', metadata }, 429) : null));
  const container = await render('/forgot-password');
  await type(field(container, 'email'), 'member@example.test');
  await submit(container);
  expect(container.querySelector('main [role="alert"]')!.textContent).toContain('Bạn thử quá nhiều lần. Vui lòng thử lại sau ít phút.');
  expect(container.querySelector('main form')).not.toBeNull();
});

it('reset reads the token from the fragment and removes it from the URL', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/password/reset' ? new Response(null, { status: 204 }) : null));
  const container = await render('/reset-password#token=synthetic-reset-token');
  expect(locationOf(container)).toBe('/reset-password');
  await type(field(container, 'password'), 'brand new pass');
  await submit(container);
  expect(calls.find((c) => c.url === '/api/v1/auth/password/reset')!.body).toEqual({
    token: 'synthetic-reset-token', new_password: 'brand new pass' });
});

it('an empty or missing reset token shows the invalid-link state', async () => {
  const calls = mockFetch(() => null);
  for (const path of ['/reset-password', '/reset-password#token=']) {
    const container = await render(path);
    expect(container.querySelector('main form')).toBeNull();
    expect(container.querySelector('main a[href="/forgot-password"]')).not.toBeNull();
    expect(container.querySelector('main')!.textContent).toContain('Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.');
    document.body.innerHTML = '';
  }
  expect(calls.some((c) => c.url === '/api/v1/auth/password/reset')).toBe(false);
});

it('a successful reset ends the session and asks to sign in again', async () => {
  mockFetch((path) => (path === '/api/v1/auth/password/reset' ? new Response(null, { status: 204 }) : null));
  const container = await render('/reset-password#token=synthetic-reset-token');
  await type(field(container, 'password'), 'brand new pass');
  await submit(container);
  expect(locationOf(container)).toBe('/login');
  expect(container.querySelector('main [role="status"]')!.textContent).toBe('Mật khẩu đã đổi. Vui lòng đăng nhập lại.');
});

it('a 400 on reset shows the invalid-link state', async () => {
  mockFetch((path) => (path === '/api/v1/auth/password/reset'
    ? json({ code: 'VALIDATION_ERROR', message: 'INVALID_TOKEN', errors: [{ field: 'token', message: 'invalid' }], metadata }, 400) : null));
  const container = await render('/reset-password#token=expired-token');
  await type(field(container, 'password'), 'brand new pass');
  await submit(container);
  expect(container.querySelector('main a[href="/forgot-password"]')).not.toBeNull();
  expect(container.querySelector('main form')).toBeNull();
});
