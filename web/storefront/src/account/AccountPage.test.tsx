// @vitest-environment jsdom
// TASK:WEB-02a: account page — profile (name, e-mail language), password change, sign out.
import { act } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import profile from '../test/fixtures/profile.json';
import refresh from '../test/fixtures/refresh.json';
import { FakeChannel, flush, json, locationOf, mockFetch, render } from '../test/harness';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); });
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

const metadata = { request_id: 'web02a-test', trace_id: 'web02a-test' };
type Extra = (path: string) => Response | null;
const member = (extra: Extra = () => null) => (path: string) =>
  extra(path) ?? (path === '/api/v1/auth/refresh' ? json(refresh) : path === '/api/v1/users/me' ? json(profile)
    : path === '/api/v1/auth/logout' ? new Response(null, { status: 204 }) : null);
const form = (container: Element, label: string) => container.querySelector(`main form[aria-label="${label}"]`) as HTMLFormElement;

async function set(element: HTMLInputElement | HTMLSelectElement, value: string) {
  await act(async () => {
    const proto = element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(element, value);
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}
async function submit(target: HTMLFormElement) {
  await act(async () => { target.requestSubmit(); });
  await flush();
}

it('shows the profile from /users/me', async () => {
  mockFetch(member());
  const container = await render('/account');
  expect(container.querySelector('main')!.textContent).toContain('member@example.test');
  expect((form(container, 'Hồ sơ').querySelector('[name="full_name"]') as HTMLInputElement).value).toBe('Khách Mẫu');
  expect((form(container, 'Hồ sơ').querySelector('[name="locale"]') as HTMLSelectElement).value).toBe('vi');
});

it('saving the profile sends full_name and locale and shows a status', async () => {
  const calls = mockFetch(member());
  const container = await render('/account');
  const profileForm = form(container, 'Hồ sơ');
  await set(profileForm.querySelector('[name="full_name"]') as HTMLInputElement, 'Tên Mới');
  await set(profileForm.querySelector('[name="locale"]') as HTMLSelectElement, 'en');
  await submit(profileForm);
  const put = calls.find((c) => c.url === '/api/v1/users/me' && c.method === 'PUT')!;
  expect(put.body).toEqual({ full_name: 'Tên Mới', locale: 'en' });
  expect(put.auth).toBe(`Bearer ${refresh.data.access_token}`);
  expect(profileForm.querySelector('[role="status"]')!.textContent).toBe('Đã lưu.');
});

it('changing the email language does not change the UI language', async () => {
  mockFetch(member());
  const container = await render('/account');
  const profileForm = form(container, 'Hồ sơ');
  await set(profileForm.querySelector('[name="locale"]') as HTMLSelectElement, 'en');
  await submit(profileForm);
  expect(document.documentElement.lang).toBe('vi');
  expect(container.querySelector('main h1')!.textContent).toBe('Tài khoản');
});

it('a wrong current password is shown on that field', async () => {
  mockFetch(member((path) => (path === '/api/v1/auth/password/change'
    ? json({ code: 'VALIDATION_ERROR', message: 'x', errors: [{ field: 'current_password', message: 'wrong' }], metadata }, 400) : null)));
  const container = await render('/account');
  const passwordForm = form(container, 'Đổi mật khẩu');
  await set(passwordForm.querySelector('[name="current_password"]') as HTMLInputElement, 'not my password');
  await set(passwordForm.querySelector('[name="new_password"]') as HTMLInputElement, 'brand new pass');
  await submit(passwordForm);
  expect(container.querySelector('#current_password-error')!.textContent).toBe('Mật khẩu hiện tại không đúng.');
  expect(locationOf(container)).toBe('/account');
});

it('a successful password change signs out and shows the notice on login', async () => {
  const calls = mockFetch(member((path) => (path === '/api/v1/auth/password/change' ? new Response(null, { status: 204 }) : null)));
  const container = await render('/account');
  const passwordForm = form(container, 'Đổi mật khẩu');
  await set(passwordForm.querySelector('[name="current_password"]') as HTMLInputElement, 'old password');
  await set(passwordForm.querySelector('[name="new_password"]') as HTMLInputElement, 'brand new pass');
  await submit(passwordForm);
  expect(calls.find((c) => c.url === '/api/v1/auth/password/change')!.body).toEqual({
    current_password: 'old password', new_password: 'brand new pass' });
  expect(locationOf(container)).toBe('/login');
  expect(container.querySelector('main [role="status"]')!.textContent).toBe('Mật khẩu đã đổi. Vui lòng đăng nhập lại.');
  expect(container.querySelector('header a[href="/login"]')).not.toBeNull();
});

it('sign out returns home as a guest', async () => {
  const calls = mockFetch(member());
  const container = await render('/account');
  const button = [...container.querySelectorAll('main button')].find((b) => b.textContent === 'Đăng xuất') as HTMLButtonElement;
  await act(async () => { button.click(); });
  await flush();
  expect(calls.some((c) => c.url === '/api/v1/auth/logout' && c.method === 'POST')).toBe(true);
  expect(locationOf(container)).toBe('/');
  expect(container.querySelector('header a[href="/login"]')).not.toBeNull();
});
