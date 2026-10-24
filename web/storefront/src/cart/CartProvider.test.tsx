// @vitest-environment jsdom
// TASK:WEB-02b: the current cart — lazy creation, guest hint, member cart, retry keys, version-conflict reload.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { AuthProvider, useAuth } from '../auth/AuthProvider';
import { resetSessionForTests } from '../auth/session';
import { LangProvider } from '../i18n';
import cartEmpty from '../test/fixtures/cart-empty.json';
import cartGuest from '../test/fixtures/cart-guest.json';
import cartMember from '../test/fixtures/cart-member.json';
import profile from '../test/fixtures/profile.json';
import refresh from '../test/fixtures/refresh.json';
import session from '../test/fixtures/session.json';
import { FakeChannel, flush, json, mockFetch } from '../test/harness';
import { CartProvider, useCart } from './CartProvider';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); resetSessionForTests(); });
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear(); document.body.innerHTML = ''; });

const metadata = { request_id: 'web02b-test', trace_id: 'web02b-test' };
const GUEST = cartGuest.data.cart_id;
const BLUE_M = '55555555-5555-4555-8555-555555555551';
const notFound = () => json({ code: 'NOT_FOUND', message: 'CART_NOT_FOUND', metadata }, 404);
const conflict = () => json({ code: 'VERSION_CONFLICT', message: 'VERSION_CONFLICT', errors: [{ field: 'expected_version', message: 'current=9' }], metadata }, 409);
const memberAuth = (path: string) =>
  path === '/api/v1/auth/refresh' ? json(refresh) : path === '/api/v1/users/me' ? json(profile)
    : path === '/api/v1/auth/logout' ? new Response(null, { status: 204 }) : null;

type Handles = { cart: ReturnType<typeof useCart>; auth: ReturnType<typeof useAuth> };
async function mount(): Promise<Handles> {
  const handles = {} as Handles;
  function Probe() { handles.cart = useCart(); handles.auth = useAuth(); return null; }
  const container = document.createElement('div');
  document.body.appendChild(container);
  await act(async () => createRoot(container).render(
    <LangProvider initial="vi"><MemoryRouter><AuthProvider><CartProvider><Probe /></CartProvider></AuthProvider></MemoryRouter></LangProvider>));
  await flush();
  return handles;
}

it('a visitor who only browses never calls /carts', async () => {
  const calls = mockFetch(() => null);
  const h = await mount();
  expect(h.cart.cart).toBeNull();
  expect(h.cart.count).toBe(0);
  expect(calls.some((c) => c.url.startsWith('/api/v1/carts'))).toBe(false);
});

it('the first add creates the cart and stores the hint', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/carts' ? json(cartEmpty, 201)
    : path === `/api/v1/carts/${GUEST}/items` ? json(cartGuest) : null));
  const h = await mount();
  let result: unknown = null;
  await act(async () => { result = await h.cart.add(BLUE_M, 1); });
  await flush();
  expect(result).toBe('added');
  expect(calls.map((c) => [c.method, c.url]).filter(([, u]) => u.startsWith('/api/v1/carts'))).toEqual([
    ['POST', '/api/v1/carts'], ['POST', `/api/v1/carts/${GUEST}/items`]]);
  expect(calls.find((c) => c.url.endsWith('/items'))!.body).toEqual({ variant_id: BLUE_M, quantity: 1, expected_version: 0 });
  expect(localStorage.getItem('storefront.cart')).toBe(GUEST);
  expect(h.cart.count).toBe(3);
});

it('an expired guest cart hint is dropped quietly', async () => {
  localStorage.setItem('storefront.cart', GUEST);
  mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? notFound() : null));
  const h = await mount();
  expect(h.cart.cart).toBeNull();
  expect(h.cart.notice).toBeNull();
  expect(localStorage.getItem('storefront.cart')).toBeNull();
});

it('a guest with a hint sees the cart', async () => {
  localStorage.setItem('storefront.cart', GUEST);
  const calls = mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? json(cartGuest) : null));
  const h = await mount();
  expect(h.cart.count).toBe(3);
  expect(calls.find((c) => c.url === `/api/v1/carts/${GUEST}`)!.auth).toBeNull();
});

it('a member loads /carts/mine', async () => {
  const calls = mockFetch((path) => memberAuth(path) ?? (path === '/api/v1/carts/mine' ? json(cartMember) : null));
  const h = await mount();
  expect(h.cart.cart?.owner).toBe('MEMBER');
  expect(h.cart.count).toBe(3);
  expect(calls.find((c) => c.url === '/api/v1/carts/mine')!.auth).toBe(`Bearer ${refresh.data.access_token}`);
});

it('retrying a failed add reuses its key', async () => {
  localStorage.setItem('storefront.cart', GUEST);
  let adds = 0;
  const calls = mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? json(cartEmpty)
    : path === `/api/v1/carts/${GUEST}/items`
      ? (adds++ === 0 ? json({ code: 'TEMPORARILY_UNAVAILABLE', message: 'DEPENDENCY_UNAVAILABLE', metadata }, 503) : json(cartGuest))
      : null));
  const h = await mount();
  const results: string[] = [];
  const push = async (q: number) => { const r = await h.cart.add(BLUE_M, q); results.push(r === 'added' ? r : 'failed'); };
  await act(async () => { await push(1); });
  await act(async () => { await push(1); });
  await act(async () => { await push(2); });
  const keys = calls.filter((c) => c.url.endsWith('/items')).map((c) => c.headers['idempotency-key']);
  expect(results).toEqual(['failed', 'added', 'added']);
  expect(keys[0]).toBeTruthy();
  expect(keys[1]).toBe(keys[0]);
  expect(keys[2]).not.toBe(keys[1]);
});

it('a version conflict reloads and keeps every item', async () => {
  localStorage.setItem('storefront.cart', GUEST);
  const calls = mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? json(cartGuest)
    : path.startsWith(`/api/v1/carts/${GUEST}/items/`) ? conflict() : null));
  const h = await mount();
  await act(async () => { await h.cart.setQuantity(cartGuest.data.items[0].item_id, 4); });
  await flush();
  expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ quantity: 4, expected_version: cartGuest.data.version });
  expect(calls.filter((c) => c.url === `/api/v1/carts/${GUEST}` && c.method === 'GET')).toHaveLength(2);
  expect(h.cart.cart?.items).toHaveLength(2);
  expect(h.cart.notice).toBe('Giỏ hàng vừa thay đổi ở nơi khác, vui lòng kiểm tra lại.');
});

it('remove and clear send the current version and take the server cart', async () => {
  localStorage.setItem('storefront.cart', GUEST);
  const calls = mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? json(cartGuest)
    : path.startsWith(`/api/v1/carts/${GUEST}/items/`) || path.startsWith(`/api/v1/carts/${GUEST}?`) ? json(cartEmpty) : null));
  const h = await mount();
  await act(async () => { await h.cart.remove(cartGuest.data.items[1].item_id); });
  expect(calls.find((c) => c.method === 'DELETE')!.url).toBe(`/api/v1/carts/${GUEST}/items/${cartGuest.data.items[1].item_id}?expected_version=2`);
  expect(h.cart.count).toBe(0);
});

it('signing out drops the member cart from view', async () => {
  mockFetch((path) => memberAuth(path) ?? (path === '/api/v1/carts/mine' ? json(cartMember) : null));
  const h = await mount();
  expect(h.cart.count).toBe(3);
  await act(async () => { await h.auth.signOut(); });
  await flush();
  expect(h.cart.cart).toBeNull();
  expect(h.cart.count).toBe(0);
});

const notLoaded = (cart: typeof cartGuest, version: number) => ({ ...cart, data: { ...cart.data, version, preview_status: 'NOT_LOADED',
  subtotal_preview: null, items: cart.data.items.map((i) => ({ ...i, preview: null })) } });

it('a write answered without a preview reads the cart again', async () => {
  localStorage.setItem('storefront.cart', GUEST);
  let written = false;
  const calls = mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? json({ ...cartGuest, data: { ...cartGuest.data, version: written ? 3 : 2 } })
    : path.startsWith(`/api/v1/carts/${GUEST}/items/`) ? (written = true, json(notLoaded(cartGuest, 3))) : null));
  const h = await mount();
  await act(async () => { await h.cart.setQuantity(cartGuest.data.items[0].item_id, 4); });
  await flush();
  expect(calls.filter((c) => c.url === `/api/v1/carts/${GUEST}` && c.method === 'GET')).toHaveLength(2);
  expect(h.cart.cart?.preview_status).toBe('UNAVAILABLE');
  expect(h.cart.cart?.version).toBe(3);
});

it('overlapping writes send the version from the previous answer', async () => {
  localStorage.setItem('storefront.cart', GUEST);
  let release: (r: Response) => void = () => {};
  const calls = mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? json(cartGuest)
    : path.startsWith(`/api/v1/carts/${GUEST}/items/`)
      ? (calls.filter((c) => c.method === 'PUT').length === 1 ? new Promise<Response>((r) => { release = r; }) : json(cartGuest))
      : null));
  const h = await mount();
  let first: Promise<boolean> = Promise.resolve(true);
  let second: Promise<boolean> = Promise.resolve(true);
  await act(async () => {
    first = h.cart.setQuantity(cartGuest.data.items[0].item_id, 4);
    second = h.cart.setQuantity(cartGuest.data.items[1].item_id, 1);
  });
  await act(async () => { release(json({ ...cartGuest, data: { ...cartGuest.data, version: 3 } })); await first; await second; });
  const puts = calls.filter((c) => c.method === 'PUT');
  expect(puts.map((c) => (c.body as { expected_version: number }).expected_version)).toEqual([2, 3]);
  expect(h.cart.notice).toBeNull();
});

it('signing in as someone else replaces the previous member cart', async () => {
  const other = { ...session, data: { ...session.data, user: { ...session.data.user, id: '99999999-0000-4000-8000-0000000000ff' } } };
  const theirs = { ...cartMember, data: { ...cartMember.data, cart_id: '88888888-0000-4000-8000-0000000000ff', items: [] } };
  let signedInAgain = false;
  mockFetch((path) => (path === '/api/v1/auth/login' ? (signedInAgain = true, json(other))
    : path === '/api/v1/carts/mine' ? json(signedInAgain ? theirs : cartMember) : memberAuth(path)));
  const h = await mount();
  expect(h.cart.count).toBe(3);
  await act(async () => { await h.auth.signIn('other@example.test', 'correct horse'); });
  await flush();
  expect(h.cart.cart?.cart_id).toBe(theirs.data.cart_id);
  expect(h.cart.count).toBe(0);
});

it('a write on a cart that is gone drops it', async () => {
  localStorage.setItem('storefront.cart', GUEST);
  let gone = false;
  mockFetch((path) => (path === `/api/v1/carts/${GUEST}` ? (gone ? notFound() : json(cartGuest))
    : path.startsWith(`/api/v1/carts/${GUEST}/items/`) ? (gone = true, notFound()) : null));
  const h = await mount();
  await act(async () => { await h.cart.remove(cartGuest.data.items[0].item_id); });
  await flush();
  expect(h.cart.cart).toBeNull();
  expect(localStorage.getItem('storefront.cart')).toBeNull();
});
