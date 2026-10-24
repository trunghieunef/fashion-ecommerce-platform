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
import { FakeChannel, flush, json, locationOf, mockFetch, navigateTo, render } from '../test/harness';
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

it('a sign-in in another tab reloads the profile, even when this tab was already a member (final review)', async () => {
  let current = profile;
  mockFetch((path) => (path === '/api/v1/auth/refresh' ? json(refresh) : path === '/api/v1/users/me' ? json(current) : listing(path)));
  const container = await render('/account');
  expect(container.querySelector('main')!.textContent).toContain('member@example.test');
  current = { ...profile, data: { ...profile.data, id: '99999999-0000-4000-8000-000000000002', email: 'other@example.test', full_name: 'Người Khác' } };
  await act(async () => { new FakeChannel('storefront.auth').postMessage({ type: 'login' }); });
  await flush();
  expect(container.querySelector('main')!.textContent).toContain('other@example.test');
  expect((container.querySelector('main [name="full_name"]') as HTMLInputElement).value).toBe('Người Khác');
});

it('a login visit carrying a stale signOut state does not sign the member out (final review)', async () => {
  // Reload/Back onto a /login entry left by an older flow must not end sessions — here or in other tabs.
  const otherTab = new FakeChannel('storefront.auth');
  const received: unknown[] = [];
  otherTab.onmessage = (event) => received.push(event.data);
  mockFetch(member);
  const container = await render('/login', 'vi', { state: { notice: 'passwordChanged', signOut: true } });
  expect(accountLink(container).getAttribute('href')).toBe('/account');
  expect(received).not.toContainEqual({ type: 'logout' });
});

it('a boot refresh answered after a sign-in leaves the member signed in (PR #38 item 1)', async () => {
  let release: (r: Response) => void = () => {};
  mockFetch((path) => (path === '/api/v1/auth/refresh' ? new Promise<Response>((resolve) => { release = resolve; })
    : path === '/api/v1/auth/login' ? json(session) : null));
  resetSessionForTests();
  let auth: ReturnType<typeof useAuth> | null = null;
  function Probe() { auth = useAuth(); return null; }
  const container = document.createElement('div');
  document.body.appendChild(container);
  await act(async () => createRoot(container).render(
    <LangProvider initial="vi"><MemoryRouter><AuthProvider><Probe /></AuthProvider></MemoryRouter></LangProvider>));
  await flush();
  await act(async () => { await auth!.signIn('member@example.test', 'correct horse'); });
  await act(async () => { release(json({ code: 'UNAUTHORIZED', message: 'old cookie', metadata: profile.metadata }, 401)); });
  await flush();
  expect(auth!.status).toBe('member');
});

it('a 503 from /users/me keeps a valid session signed in (PR #38 item 2)', async () => {
  mockFetch((path) => (path === '/api/v1/auth/refresh' ? json(refresh)
    : path === '/api/v1/users/me' ? json({ code: 'INTERNAL', message: 'down', metadata: profile.metadata }, 503) : listing(path)));
  const container = await render('/products');
  expect(accountLink(container).getAttribute('href')).toBe('/account');
  expect(accountLink(container).getAttribute('aria-busy')).toBeNull();
});

it('a 401 from /users/me after the retry makes the visitor a guest (PR #38 item 2)', async () => {
  let refreshes = 0;
  mockFetch((path) => (path === '/api/v1/auth/refresh' ? (refreshes++ === 0 ? json(refresh) : json({ code: 'UNAUTHORIZED', message: 'x', metadata: profile.metadata }, 401))
    : path === '/api/v1/users/me' ? json({ code: 'UNAUTHORIZED', message: 'x', metadata: profile.metadata }, 401) : listing(path)));
  const container = await render('/products');
  expect(accountLink(container).getAttribute('href')).toBe('/login');
});

it('a deliberate exit is used once; a later guest visit to /account keeps next (PR #38 item 3)', async () => {
  mockFetch((path) => (path === '/api/v1/auth/logout' ? new Response(null, { status: 204 }) : member(path)));
  const container = await render('/account');
  const signOut = [...container.querySelectorAll('main button')].find((b) => b.textContent === 'Đăng xuất') as HTMLButtonElement;
  await act(async () => { signOut.click(); });
  await flush();
  expect(locationOf(container)).toBe('/');
  await navigateTo('/account');
  expect(locationOf(container)).toBe('/login?next=%2Faccount');
});
