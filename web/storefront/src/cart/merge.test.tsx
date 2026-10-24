// @vitest-environment jsdom
// TASK:WEB-02b: merging the guest cart into the member cart after sign-in (spec §4, CART-02).
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { AuthProvider, useAuth } from '../auth/AuthProvider';
import { resetSessionForTests } from '../auth/session';
import { LangProvider } from '../i18n';
import cartGuest from '../test/fixtures/cart-guest.json';
import cartMember from '../test/fixtures/cart-member.json';
import merge from '../test/fixtures/merge.json';
import profile from '../test/fixtures/profile.json';
import refresh from '../test/fixtures/refresh.json';
import session from '../test/fixtures/session.json';
import { FakeChannel, flush, json, mockFetch, type Call } from '../test/harness';
import { CartProvider, useCart } from './CartProvider';

beforeEach(() => {
  FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); resetSessionForTests();
  localStorage.setItem('storefront.cart', GUEST);
});
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear(); document.body.innerHTML = ''; });

const metadata = { request_id: 'web02b-test', trace_id: 'web02b-test' };
const GUEST = cartGuest.data.cart_id;
const conflict = () => json({ code: 'VERSION_CONFLICT', message: 'VERSION_CONFLICT', metadata }, 409);
const notFound = () => json({ code: 'NOT_FOUND', message: 'CART_NOT_FOUND', metadata }, 404);
type Answer = (path: string) => Response | Promise<Response> | null;

/** Guest at first (refresh 401) unless `restored`; sign-in returns the session fixture. */
function shop(answer: Answer, restored = false) {
  return (path: string) => answer(path)
    ?? (path === '/api/v1/auth/refresh' ? (restored ? json(refresh) : json({ code: 'UNAUTHORIZED', message: 'x', metadata }, 401))
      : path === '/api/v1/auth/login' ? json(session) : path === '/api/v1/users/me' ? json(profile)
        : path === `/api/v1/carts/${GUEST}` ? json(cartGuest) : path === '/api/v1/carts/mine' ? json(cartMember) : null);
}
type Handles = { cart: ReturnType<typeof useCart>; auth: ReturnType<typeof useAuth>; container: HTMLElement };
async function mount(): Promise<Handles> {
  const handles = {} as Handles;
  function Probe() { handles.cart = useCart(); handles.auth = useAuth(); return null; }
  handles.container = document.createElement('div');
  document.body.appendChild(handles.container);
  await act(async () => createRoot(handles.container).render(
    <LangProvider initial="vi"><MemoryRouter><AuthProvider><CartProvider><Probe /></CartProvider></AuthProvider></MemoryRouter></LangProvider>));
  await flush();
  return handles;
}
async function signIn(h: Handles) {
  await act(async () => { await h.auth.signIn('member@example.test', 'correct horse'); });
  await flush();
  await flush();
}
const merges = (calls: Call[]) => calls.filter((c) => c.url === '/api/v1/carts/merge');

it('signing in with a guest cart merges it and shows the adjustments', async () => {
  const calls = mockFetch(shop((path) => (path === '/api/v1/carts/merge' ? json(merge) : null)));
  const h = await mount();
  expect(h.cart.cart?.owner).toBe('GUEST');
  await signIn(h);
  expect(merges(calls)).toHaveLength(1);
  expect(merges(calls)[0].body).toEqual({ guest_cart_id: GUEST, guest_expected_version: cartGuest.data.version,
    member_expected_version: cartMember.data.version });
  expect(merges(calls)[0].headers['idempotency-key']).toBeTruthy();
  expect(h.cart.cart).toEqual(merge.data.cart);
  expect(localStorage.getItem('storefront.cart')).toBeNull();
  expect(sessionStorage.getItem('storefront.merge')).toBeNull();
  const notice = h.container.querySelector('dialog[open]') as HTMLDialogElement;
  expect(notice.textContent).toContain('TEE-M-BLUE');
  expect(notice.textContent).toContain('5 → 3');
});

it('a member cart load answered after the merge does not overwrite the merged cart', async () => {
  let releaseMine: (r: Response) => void = () => {};
  let mineCalls = 0;
  mockFetch(shop((path) => (path === '/api/v1/carts/merge' ? json(merge)
    : path === '/api/v1/carts/mine' && mineCalls++ === 0 ? new Promise<Response>((resolve) => { releaseMine = resolve; }) : null)));
  const h = await mount();
  await signIn(h);
  await act(async () => { releaseMine(json(cartMember)); });
  await flush();
  expect(h.cart.cart).toEqual(merge.data.cart);
});

it('already_merged is treated as done', async () => {
  const already = { ...merge, data: { ...merge.data, already_merged: true, adjustments: [] } };
  mockFetch(shop((path) => (path === '/api/v1/carts/merge' ? json(already) : null)));
  const h = await mount();
  await signIn(h);
  expect(localStorage.getItem('storefront.cart')).toBeNull();
  expect(h.container.querySelector('dialog[open]')).toBeNull();
  expect(h.cart.notice).toBeNull();
});

it('a merge retried after reload sends the same key', async () => {
  sessionStorage.setItem('storefront.merge', JSON.stringify({ guest_cart_id: GUEST, key: 'saved-key' }));
  const calls = mockFetch(shop((path) => (path === '/api/v1/carts/merge' ? json(merge) : null), true));
  await mount();
  expect(merges(calls)).toHaveLength(1);
  expect(merges(calls)[0].headers['idempotency-key']).toBe('saved-key');
});

it('a first conflict retries once with a new key', async () => {
  let n = 0;
  const calls = mockFetch(shop((path) => (path === '/api/v1/carts/merge' ? (n++ === 0 ? conflict() : json(merge)) : null)));
  const h = await mount();
  await signIn(h);
  const keys = merges(calls).map((c) => c.headers['idempotency-key']);
  expect(keys).toHaveLength(2);
  expect(keys[1]).not.toBe(keys[0]);
  expect(h.cart.cart).toEqual(merge.data.cart);
});

it('a second merge conflict keeps the guest cart for later', async () => {
  const calls = mockFetch(shop((path) => (path === '/api/v1/carts/merge' ? conflict() : null)));
  const h = await mount();
  await signIn(h);
  expect(merges(calls)).toHaveLength(2);
  expect(localStorage.getItem('storefront.cart')).toBe(GUEST);
  expect(h.cart.notice).toBe('Chưa gộp được giỏ, sẽ thử lại.');
  expect(h.auth.status).toBe('member');
});

it('a network failure keeps the key and the guest cart', async () => {
  mockFetch(shop((path) => (path === '/api/v1/carts/merge' ? Promise.reject(new TypeError('offline')) : null)));
  const h = await mount();
  await signIn(h);
  expect(localStorage.getItem('storefront.cart')).toBe(GUEST);
  expect(JSON.parse(sessionStorage.getItem('storefront.merge')!)).toMatchObject({ guest_cart_id: GUEST });
  expect(h.cart.notice).toBe('Chưa gộp được giỏ, sẽ thử lại.');
});

it('a missing guest cart clears the hint and shows the member cart', async () => {
  const calls = mockFetch(shop((path) => (path === `/api/v1/carts/${GUEST}` ? notFound() : null)));
  const h = await mount();
  await signIn(h);
  expect(merges(calls)).toHaveLength(0);
  expect(localStorage.getItem('storefront.cart')).toBeNull();
  expect(h.cart.cart?.owner).toBe('MEMBER');
});

it('no guest cart, no merge call', async () => {
  localStorage.clear();
  const calls = mockFetch(shop(() => null));
  const h = await mount();
  await signIn(h);
  expect(merges(calls)).toHaveLength(0);
  expect(h.cart.cart?.owner).toBe('MEMBER');
});

it('a merged cart without a preview is read again', async () => {
  const bare = { ...merge, data: { ...merge.data, cart: { ...merge.data.cart, preview_status: 'NOT_LOADED', subtotal_preview: null,
    items: merge.data.cart.items.map((i) => ({ ...i, preview: null })) } } };
  const read = { ...cartMember, data: { ...cartMember.data, version: 5 } };
  const calls = mockFetch(shop((path) => (path === '/api/v1/carts/merge' ? json(bare)
    : path === `/api/v1/carts/${merge.data.cart.cart_id}` ? json(read) : null)));
  const h = await mount();
  await signIn(h);
  await flush();
  expect(calls.some((c) => c.url === `/api/v1/carts/${merge.data.cart.cart_id}`)).toBe(true);
  expect(h.cart.cart?.preview_status).toBe('OK');
});
