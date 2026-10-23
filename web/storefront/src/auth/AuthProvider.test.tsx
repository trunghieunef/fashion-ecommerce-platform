// @vitest-environment jsdom
// TASK:WEB-02a: session restore on load, protected account routes, header account button, cross-tab sign out.
import { act, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { LangProvider } from '../i18n';
import profile from '../test/fixtures/profile.json';
import products from '../test/fixtures/products.json';
import refresh from '../test/fixtures/refresh.json';
import session from '../test/fixtures/session.json';
import { FakeChannel, flush, json, locationOf, mockFetch, render } from '../test/harness';
import { AuthProvider, useAuth } from './AuthProvider';
import { resetSessionForTests } from './session';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); });
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

const listing = (path: string) => (path.startsWith('/products?') ? json(products) : null);
const member = (path: string) =>
  path === '/api/v1/auth/refresh' ? json(refresh) : path === '/api/v1/users/me' ? json(profile) : listing(path);
const accountLink = (container: Element) => container.querySelector('header a[href="/account"], header a[href="/login"]') as HTMLAnchorElement;

it('boot refresh 200 makes the visitor a member', async () => {
  mockFetch(member);
  const container = await render('/products');
  const link = accountLink(container);
  expect(link.getAttribute('href')).toBe('/account');
  expect(link.getAttribute('aria-label')).toBe('Tài khoản');
  expect(link.getAttribute('aria-busy')).toBeNull();
});

it('boot refresh 401 makes the visitor a guest without an error', async () => {
  mockFetch(listing);
  const container = await render('/products');
  const link = accountLink(container);
  expect(link.getAttribute('href')).toBe('/login');
  expect(link.getAttribute('aria-label')).toBe('Đăng nhập');
  expect(container.querySelector('header')!.textContent).not.toContain('Không thể tải dữ liệu');
});

it('RequireAuth sends a guest to login with next', async () => {
  mockFetch(() => null);
  const container = await render('/account/addresses');
  expect(locationOf(container)).toBe('/login?next=%2Faccount%2Faddresses');
});

it('a member reaches the protected account route', async () => {
  mockFetch(member);
  const container = await render('/account');
  expect(locationOf(container)).toBe('/account');
});

it('a logout broadcast from another tab signs this tab out', async () => {
  mockFetch(member);
  const container = await render('/account');
  expect(locationOf(container)).toBe('/account');
  await act(async () => { new FakeChannel('storefront.auth').postMessage({ type: 'logout' }); });
  await flush();
  expect(locationOf(container)).toBe('/login?next=%2Faccount');
  expect(accountLink(container).getAttribute('href')).toBe('/login');
});

it('onSignedIn callbacks run after signIn, with the user from the session response', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/login' ? json(session) : null));
  resetSessionForTests();
  const ran: string[] = [];
  let auth: ReturnType<typeof useAuth> | null = null;
  function Probe() {
    const value = useAuth();
    auth = value;
    useEffect(() => value.onSignedIn(() => ran.push('signed-in')), [value.onSignedIn]);
    return null;
  }
  const container = document.createElement('div');
  document.body.appendChild(container);
  await act(async () => createRoot(container).render(
    <LangProvider initial="vi"><MemoryRouter><AuthProvider><Probe /></AuthProvider></MemoryRouter></LangProvider>));
  await flush();
  expect(auth!.status).toBe('guest');
  await act(async () => { await auth!.signIn('member@example.test', 'correct horse'); });
  await flush();
  expect(ran).toEqual(['signed-in']);
  expect(auth!.status).toBe('member');
  expect(auth!.me?.email).toBe('member@example.test');
  expect(calls.some((c) => c.url === '/api/v1/users/me')).toBe(false);
});
