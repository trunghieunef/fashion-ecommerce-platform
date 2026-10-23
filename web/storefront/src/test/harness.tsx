// TASK:WEB-01 test harness: renders the real routes in jsdom with a scripted fetch (fixtures match catalog.yaml).
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';

import { AppRoutes } from '../app/App';
import brands from './fixtures/brands.json';
import categories from './fixtures/categories.json';
import collections from './fixtures/collections.json';
import type { Lang } from '../catalog/api';
import { LangProvider } from '../i18n';

export type Call = { url: string; lang: string | null };

export function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** Shared lookups the header and filters read on every screen (TASK:WEB-01b); a handler returning null gets these. */
function fallback(path: string): Response {
  if (path.startsWith('/categories')) return json(categories);
  if (path.startsWith('/brands')) return json(brands);
  if (path.startsWith('/collections?')) return json(collections);
  throw new Error(`unmocked catalog path: ${path}`);
}

/**
 * handler gets the path+query after /api/v1/catalog and returns a Response (or a promise of one); null/undefined
 * means "not mine" and falls back to the shared fixtures above.
 */
export function mockFetch(handler: (path: string) => Response | Promise<Response> | null | undefined, honourAbort = true) {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', vi.fn((input: string, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    calls.push({ url: input, lang: headers.get('Accept-Language') });
    const signal = init?.signal;
    return new Promise<Response>((resolve, reject) => {
      // honourAbort=false models a response already in flight when abort fires (it still resolves).
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

export async function render(path: string, lang: Lang = 'vi') {
  const container = document.createElement('div');
  document.body.appendChild(container);
  await act(async () => createRoot(container).render(
    <LangProvider initial={lang}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </LangProvider>,
  ));
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
