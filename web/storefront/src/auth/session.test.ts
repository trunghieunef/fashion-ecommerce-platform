// @vitest-environment jsdom
// TASK:WEB-02a: in-memory access token, single-flight refresh (one tab and across tabs), Bearer calls with one retry.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import refresh from '../test/fixtures/refresh.json';
import { FakeChannel, json, mockFetch } from '../test/harness';

const metadata = { request_id: 'web02a-test', trace_id: 'web02a-test' };
const unauthorized = () => json({ code: 'UNAUTHORIZED', message: 'no', metadata }, 401);
const ok = (data: unknown) => json({ code: 'OK', data, metadata });
type Session = typeof import('./session');

/** Fake Web Locks: one holder at a time per name, FIFO; records names and the most holders seen at once. */
function fakeLocks() {
  const tails = new Map<string, Promise<unknown>>();
  const state = { names: [] as string[], active: 0, maxActive: 0 };
  const request = vi.fn((name: string, callback: () => Promise<unknown>) => {
    state.names.push(name);
    const run = (tails.get(name) ?? Promise.resolve()).then(async () => {
      state.active++; state.maxActive = Math.max(state.maxActive, state.active);
      try { return await callback(); } finally { state.active--; }
    });
    tails.set(name, run.catch(() => undefined));
    return run;
  });
  return { request, state };
}

async function freshSession(): Promise<Session> {
  vi.resetModules();
  return import('./session');
}

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); });
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); localStorage.clear(); sessionStorage.clear(); });

it('two concurrent refreshes send one request', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/refresh' ? ok(refresh.data) : null));
  const session = await freshSession();
  const [a, b] = await Promise.all([session.refreshAccessToken(), session.refreshAccessToken()]);
  expect([a, b]).toEqual([true, true]);
  expect(calls.filter((c) => c.url === '/api/v1/auth/refresh')).toHaveLength(1);
  expect(session.getAccessToken()).toBe(refresh.data.access_token);
});

it('serialises refreshes across tabs through one Web Lock', async () => {
  const locks = fakeLocks();
  vi.stubGlobal('navigator', { ...navigator, locks: { request: locks.request } });
  let inFlight = 0;
  let overlapped = false;
  mockFetch(async (path) => {
    if (path !== '/api/v1/auth/refresh') return null;
    inFlight++; if (inFlight > 1) overlapped = true;
    await new Promise((r) => setTimeout(r, 5));
    inFlight--;
    return ok(refresh.data);
  });
  const tabA = await freshSession();
  const tabB = await freshSession();
  expect(tabA).not.toBe(tabB);
  await Promise.all([tabA.refreshAccessToken(), tabB.refreshAccessToken()]);
  expect(locks.state.names).toEqual(['storefront.auth.refresh', 'storefront.auth.refresh']);
  expect(locks.state.maxActive).toBe(1);
  expect(overlapped).toBe(false);
});

it('refresh 401 ends the session and resolves false', async () => {
  mockFetch((path) => (path === '/api/v1/auth/refresh' ? unauthorized() : null));
  const session = await freshSession();
  session.startSession('old-token', 900);
  const seen: boolean[] = [];
  session.onSessionChange((signedIn) => seen.push(signedIn));
  await expect(session.refreshAccessToken()).resolves.toBe(false);
  expect(session.getAccessToken()).toBeNull();
  expect(seen).toEqual([false]);
});

it('a network error during refresh keeps the session state and throws', async () => {
  mockFetch((path) => (path === '/api/v1/auth/refresh' ? Promise.reject(new TypeError('offline')) : null));
  const session = await freshSession();
  session.startSession('still-valid', 900);
  await expect(session.refreshAccessToken()).rejects.toMatchObject({ status: 0 });
  expect(session.getAccessToken()).toBe('still-valid');
});

it('authRequest refreshes once after a 401 and retries once', async () => {
  let me = 0;
  const calls = mockFetch((path) => {
    if (path === '/api/v1/auth/refresh') return ok(refresh.data);
    if (path === '/api/v1/users/me') return me++ === 0 ? unauthorized() : ok({ id: 'x' });
    return null;
  });
  const session = await freshSession();
  session.startSession('expired-server-side', 900);
  await expect(session.authRequest('/api/v1/users/me')).resolves.toEqual({ id: 'x' });
  expect(calls.map((c) => c.url)).toEqual(['/api/v1/users/me', '/api/v1/auth/refresh', '/api/v1/users/me']);
  expect(calls[0].auth).toBe('Bearer expired-server-side');
  expect(calls[2].auth).toBe(`Bearer ${refresh.data.access_token}`);
});

it('authRequest gives up after a second 401', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/refresh' ? ok(refresh.data) : path === '/api/v1/users/me' ? unauthorized() : null));
  const session = await freshSession();
  session.startSession('t', 900);
  await expect(session.authRequest('/api/v1/users/me')).rejects.toMatchObject({ status: 401 });
  expect(calls.filter((c) => c.url === '/api/v1/auth/refresh')).toHaveLength(1);
  expect(calls.filter((c) => c.url === '/api/v1/users/me')).toHaveLength(2);
});

it('authRequest without a session refreshes first and fails as 401 when refresh is refused', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/refresh' ? unauthorized() : null));
  const session = await freshSession();
  await expect(session.authRequest('/api/v1/users/me')).rejects.toMatchObject({ status: 401 });
  expect(calls.map((c) => c.url)).toEqual(['/api/v1/auth/refresh']);
});

it('a token about to expire is refreshed before the call', async () => {
  const calls = mockFetch((path) => (path === '/api/v1/auth/refresh' ? ok(refresh.data) : path === '/api/v1/users/me' ? ok({}) : null));
  const session = await freshSession();
  session.startSession('nearly-expired', 20);
  await session.authRequest('/api/v1/users/me');
  expect(calls.map((c) => c.url)).toEqual(['/api/v1/auth/refresh', '/api/v1/users/me']);
  expect(calls[1].auth).toBe(`Bearer ${refresh.data.access_token}`);
});

it('the access token never reaches web storage', async () => {
  mockFetch((path) => (path === '/api/v1/auth/refresh' ? ok(refresh.data) : null));
  const session = await freshSession();
  session.startSession('first-token', 900);
  await session.refreshAccessToken();
  const stored = [localStorage, sessionStorage].flatMap((s) => Object.keys(s).map((k) => s.getItem(k) ?? ''));
  expect(stored.some((v) => v.includes('first-token') || v.includes(refresh.data.access_token))).toBe(false);
  expect(document.cookie).not.toContain('token');
});

it('a logout message from another tab ends the session', async () => {
  mockFetch(() => null);
  const session = await freshSession();
  session.startSession('t', 900);
  const seen: boolean[] = [];
  session.onSessionChange((signedIn) => seen.push(signedIn));
  new FakeChannel('storefront.auth').postMessage({ type: 'logout' });
  expect(session.getAccessToken()).toBeNull();
  expect(seen).toEqual([false]);
});

it('broadcast posts to the other tabs', async () => {
  mockFetch(() => null);
  const session = await freshSession();
  const other = new FakeChannel('storefront.auth');
  const received: unknown[] = [];
  other.onmessage = (event) => received.push(event.data);
  session.broadcast('logout');
  expect(received).toEqual([{ type: 'logout' }]);
});

it('a refresh that resolves after endSession does not revive the session (final review)', async () => {
  let release: (r: Response) => void = () => {};
  mockFetch((path) => (path === '/api/v1/auth/refresh' ? new Promise<Response>((resolve) => { release = resolve; }) : null));
  const session = await freshSession();
  const pending = session.refreshAccessToken();
  await new Promise((r) => setTimeout(r, 0));
  session.endSession();
  release(ok(refresh.data));
  await expect(pending).resolves.toBe(false);
  expect(session.getAccessToken()).toBeNull();
});

it('a refresh superseded by a sign-in reports the newer session instead of signing out (PR #38 item 1)', async () => {
  for (const answer of [() => ok(refresh.data), () => unauthorized()]) {
    let release: (r: Response) => void = () => {};
    mockFetch((path) => (path === '/api/v1/auth/refresh' ? new Promise<Response>((resolve) => { release = resolve; }) : null));
    const session = await freshSession();
    const pending = session.refreshAccessToken();
    await new Promise((r) => setTimeout(r, 0));
    session.startSession('signed-in-meanwhile', 900);
    release(answer());
    await expect(pending).resolves.toBe(true);
    expect(session.getAccessToken()).toBe('signed-in-meanwhile');
    vi.unstubAllGlobals();
    vi.stubGlobal('BroadcastChannel', FakeChannel);
  }
});
