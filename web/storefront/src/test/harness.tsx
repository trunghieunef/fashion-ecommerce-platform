// TASK:WEB-01 test harness: renders the real routes in jsdom with a scripted fetch (fixtures match catalog.yaml).
import { act, StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';

import { AppRoutes } from '../app/App';
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
export function mockFetch(handler: (path: string) => Response | Promise<Response> | null | undefined, honourAbort = true) {
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
      Promise.resolve().then(() => handler(path) ?? fallback(path)).then(resolve, reject);
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

export async function flush() {
  for (let i = 0; i < 5; i++) await act(async () => { await Promise.resolve(); });
}

export async function render(path: string, lang: Lang = 'vi', { strict = false } = {}) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const app = (
    <LangProvider initial={lang}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
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
