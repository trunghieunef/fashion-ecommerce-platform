// @vitest-environment jsdom
// TASK:WEB-02a: sign-in and registration forms — inline validation, focused error summary, safe `next`, busy submit.
import { act } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import session from '../test/fixtures/session.json';
import { FakeChannel, flush, json, locationOf, mockFetch, render } from '../test/harness';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); });
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

const metadata = { request_id: 'web02a-test', trace_id: 'web02a-test' };
const field = (container: Element, name: string) => container.querySelector(`main [name="${name}"]`) as HTMLInputElement;

async function type(input: HTMLInputElement, value: string) {
  await act(async () => {
    input.focus();
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function blur(input: HTMLInputElement) {
  await act(async () => { input.dispatchEvent(new FocusEvent('focusout', { bubbles: true })); });
}
async function submit(container: Element) {
  await act(async () => { (container.querySelector('main form') as HTMLFormElement).requestSubmit(); });
  await flush();
}
async function fillLogin(container: Element, email = 'member@example.test', password = 'correct horse') {
  await type(field(container, 'email'), email);
  await type(field(container, 'password'), password);
}

it('shows field errors on blur and a focused summary on submit', async () => {
  const calls = mockFetch(() => null);
  const container = await render('/login');
  const email = field(container, 'email');
  await type(email, 'not-an-email');
  expect(container.querySelector('#email-error')).toBeNull();
  await blur(email);
  expect(container.querySelector('#email-error')!.textContent).not.toBe('');
  expect(email.getAttribute('aria-invalid')).toBe('true');
  expect(email.getAttribute('aria-describedby')).toContain('email-error');
  await submit(container);
  const summary = container.querySelector('main [role="alert"]') as HTMLElement;
  expect(document.activeElement).toBe(summary);
  expect(summary.querySelector('a[href="#email"]')).not.toBeNull();
  expect(calls.some((c) => c.url === '/api/v1/auth/login')).toBe(false);
});

it('a 401 shows the fixed message and keeps the typed email', async () => {
  mockFetch((path) => (path === '/api/v1/auth/login' ? json({ code: 'UNAUTHORIZED', message: 'INVALID_CREDENTIALS', metadata }, 401) : null));
  const container = await render('/login');
  await fillLogin(container);
  await submit(container);
  expect(container.querySelector('main [role="alert"]')!.textContent).toContain('Email hoặc mật khẩu không đúng.');
  expect(field(container, 'email').value).toBe('member@example.test');
});

it('a 429 shows the rate-limit sentence', async () => {
  mockFetch((path) => (path === '/api/v1/auth/login' ? json({ code: 'RATE_LIMITED', message: 'slow down', metadata }, 429) : null));
  const container = await render('/login');
  await fillLogin(container);
  await submit(container);
  expect(container.querySelector('main [role="alert"]')!.textContent).toContain('Bạn thử quá nhiều lần. Vui lòng thử lại sau ít phút.');
});

it('signs in and goes to next', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/login' ? json(session) : null));
  const container = await render('/login?next=%2Faccount%2Faddresses');
  await fillLogin(container);
  await submit(container);
  expect(calls.find((c) => c.url === '/api/v1/auth/login')!.body).toEqual({ email: 'member@example.test', password: 'correct horse' });
  expect(locationOf(container)).toBe('/account/addresses');
});

it('ignores an external next', async () => {
  mockFetch((path) => (path === '/api/v1/auth/login' ? json(session) : null));
  // Also backslash and control-character variants a URL parser turns into a protocol-relative URL (final review).
  for (const next of ['https%3A%2F%2Fevil.example', '%2F%2Fevil.example', '%2F%5Cevil.example', '%2F%09%2Fevil.example', '%2F%0A%2Fevil.example']) {
    const container = await render(`/login?next=${next}`);
    await fillLogin(container);
    await submit(container);
    expect(locationOf(container)).toBe('/account');
    document.body.innerHTML = '';
  }
});

it('the submit button is busy while signing in', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/login' ? new Promise<Response>(() => {}) : null));
  const container = await render('/login');
  await fillLogin(container);
  await submit(container);
  await submit(container);
  const button = container.querySelector('main button[type="submit"]') as HTMLButtonElement;
  expect(button.disabled).toBe(true);
  expect(button.getAttribute('aria-busy')).toBe('true');
  expect(calls.filter((c) => c.url === '/api/v1/auth/login')).toHaveLength(1);
});

it('the password toggle switches the input type and aria-pressed', async () => {
  mockFetch(() => null);
  const container = await render('/login');
  const toggle = container.querySelector('main button[aria-label="Hiện mật khẩu"]') as HTMLButtonElement;
  expect(field(container, 'password').type).toBe('password');
  await act(async () => { toggle.click(); });
  expect(field(container, 'password').type).toBe('text');
  expect(toggle.getAttribute('aria-pressed')).toBe('true');
  expect(toggle.getAttribute('aria-label')).toBe('Ẩn mật khẩu');
});

it('password fields allow paste and autocomplete', async () => {
  mockFetch(() => null);
  const container = await render('/login');
  const password = field(container, 'password');
  expect(password.dispatchEvent(new Event('paste', { bubbles: true, cancelable: true }))).toBe(true);
  expect(password.autocomplete).toBe('current-password');
  expect(field(container, 'email').autocomplete).toBe('email');
  expect(field(container, 'email').type).toBe('email');
});

async function fillRegister(container: Element, password = 'correct horse') {
  await type(field(container, 'full_name'), 'Khách Mẫu');
  await type(field(container, 'email'), 'new@example.test');
  await type(field(container, 'password'), password);
}

it('rejects a password over 72 UTF-8 bytes before sending', async () => {
  const calls = mockFetch(() => null);
  const container = await render('/register');
  await fillRegister(container, 'ệ'.repeat(30));
  await submit(container);
  expect(container.querySelector('#password-error')!.textContent).not.toBe('');
  expect(calls.some((c) => c.url === '/api/v1/auth/register')).toBe(false);
});

it('register maps 409 to the email field', async () => {
  mockFetch((path) => (path === '/api/v1/auth/register' ? json({ code: 'CONFLICT', message: 'EMAIL_TAKEN', metadata }, 409) : null));
  const container = await render('/register');
  await fillRegister(container);
  await submit(container);
  expect(container.querySelector('#email-error')!.textContent).toBe('Email này đã có tài khoản.');
  expect(field(container, 'email').getAttribute('aria-invalid')).toBe('true');
});

it('register sends the UI locale and lands on the account page', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/register' ? json(session, 201) : null));
  const container = await render('/register', 'en');
  await fillRegister(container);
  await submit(container);
  expect(calls.find((c) => c.url === '/api/v1/auth/register')!.body).toEqual({
    full_name: 'Khách Mẫu', email: 'new@example.test', password: 'correct horse', locale: 'en' });
  expect(locationOf(container)).toBe('/account');
});
