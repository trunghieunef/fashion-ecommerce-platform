// TASK:WEB-01 test harness: renders the real routes in jsdom with a scripted fetch (fixtures match catalog.yaml).
import { act, StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter, useLocation } from 'react-router';
import { vi } from 'vitest';

import { AppRoutes } from '../app/App';
import { resetSessionForTests } from '../auth/session';
import brands from './fixtures/brands.json';
import categories from './fixtures/categories.json';
import collections from './fixtures/collections.json';
import type { Lang } from '../catalog/api';
import { LangProvider } from '../i18n';

/** One fetch the code under test made; method/auth/body/headers added for the account and cart APIs (TASK:WEB-02a). */
export type Call = { url: string; lang: string | null; method: string; auth: string | null; body: unknown; headers: Record<string, string> };

export function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** Shared lookups the header and filters read on every screen (TASK:WEB-01b); a handler returning null gets these. */
function fallback(path: string): Response {
  if (path.startsWith('/categories')) return json(categories);
  if (path.startsWith('/brands')) return json(brands);
  if (path.startsWith('/collections?')) return json(collections);
  // TASK:WEB-02a: no session unless a test signs in, so every catalog test runs as a guest.
  if (path === '/api/v1/auth/refresh')
    return json({ code: 'UNAUTHORIZED', message: 'refresh token missing', metadata: { request_id: 'harness', trace_id: 'harness' } }, 401);
  throw new Error(`unmocked path: ${path}`);
}

/**
 * handler gets the path+query after /api/v1/catalog (other APIs: the full path, e.g. /api/v1/auth/login) and returns a Response (or a promise of one); null/undefined
 * means "not mine" and falls back to the shared fixtures above.
 */
type Handled = Response | null | undefined;
export function mockFetch(handler: (path: string) => Handled | Promise<Handled>, honourAbort = true) {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', vi.fn((input: string, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
    calls.push({ url: input, lang: headers.get('Accept-Language'), method: init?.method ?? 'GET', auth: headers.get('Authorization'),
      body, headers: Object.fromEntries(headers.entries()) });
    const signal = init?.signal;
    return new Promise<Response>((resolve, reject) => {
      // honourAbort=false models a response already in flight when abort fires (it still resolves).
      // Like real fetch, an already-aborted signal rejects at once (final review: StrictMode reused an aborted controller).
      if (honourAbort && signal?.aborted) return reject(new DOMException('aborted', 'AbortError'));
      if (honourAbort) signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      const path = input.replace('/api/v1/catalog', '');
      Promise.resolve().then(() => handler(path)).then((response) => response ?? fallback(path)).then(resolve, reject);
    });
  }));
  return calls;
}

// jsdom has no modal dialog support: open/close flags are enough for the Drawer and the zoom dialog (TASK:WEB-01b).
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) { this.open = true; };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    if (!this.open) return;
    this.open = false;
    this.dispatchEvent(new Event('close'));
  };
}

/** Fake BroadcastChannel: instances with the same name receive each other's messages, like real tabs (TASK:WEB-02a). */
export class FakeChannel {
  static all: FakeChannel[] = [];
  onmessage: ((event: MessageEvent) => void) | null = null;
  constructor(readonly name: string) { FakeChannel.all.push(this); }
  postMessage(data: unknown) {
    for (const other of FakeChannel.all) if (other !== this && other.name === this.name) other.onmessage?.({ data } as MessageEvent);
  }
  close() { FakeChannel.all = FakeChannel.all.filter((c) => c !== this); }
}

function splitPath(path: string) {
  const url = new URL(path, 'http://harness.test');
  return { pathname: url.pathname, search: url.search, hash: url.hash };
}

/** The router location as text, so tests can assert redirects (`/login?next=...`). */
function LocationProbe() {
  const { pathname, search, hash, state } = useLocation();
  return <output data-testid="location" data-state={JSON.stringify(state ?? null)} hidden>{pathname + search + hash}</output>;
}

export const locationOf = (container: Element) => container.querySelector('[data-testid="location"]')?.textContent ?? '';
export const locationStateOf = (container: Element): unknown =>
  JSON.parse(container.querySelector('[data-testid="location"]')?.getAttribute('data-state') ?? 'null');

export async function flush() {
  for (let i = 0; i < 5; i++) await act(async () => { await Promise.resolve(); });
}

export async function render(path: string, lang: Lang = 'vi', { strict = false, state }: { strict?: boolean; state?: unknown } = {}) {
  resetSessionForTests();
  const container = document.createElement('div');
  document.body.appendChild(container);
  const app = (
    <LangProvider initial={lang}>
      <MemoryRouter initialEntries={[state === undefined ? path : { ...splitPath(path), state }]}>
        <AppRoutes />
        <LocationProbe />
      </MemoryRouter>
    </LangProvider>
  );
  // strict: main.tsx renders under StrictMode (mount → cleanup → mount), which unit tests must be able to reproduce.
  await act(async () => createRoot(container).render(strict ? <StrictMode>{app}</StrictMode> : app));
  await flush();
  return container;
}

export async function click(element: Element | null | undefined) {
  if (!element) throw new Error('element not found');
  await act(async () => { (element as HTMLElement).click(); });
  await flush();
}

export function byText(container: Element, selector: string, text: string | RegExp) {
  return [...container.querySelectorAll(selector)].find((e) =>
    typeof text === 'string' ? e.textContent?.trim() === text : text.test(e.textContent ?? ''));
}
