// TASK:WEB-01 test harness: renders the real routes in jsdom with a scripted fetch (fixtures match catalog.yaml).
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';

import { AppRoutes } from '../app/App';
import type { Lang } from '../catalog/api';
import { LangProvider } from '../i18n';

export type Call = { url: string; lang: string | null };

export function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** handler gets the path+query after /api/v1/catalog and returns a Response (or a promise of one). */
export function mockFetch(handler: (path: string) => Response | Promise<Response>) {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', vi.fn((input: string, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    calls.push({ url: input, lang: headers.get('Accept-Language') });
    const signal = init?.signal;
    return new Promise<Response>((resolve, reject) => {
      signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      Promise.resolve(handler(input.replace('/api/v1/catalog', ''))).then(resolve, reject);
    });
  }));
  return calls;
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
