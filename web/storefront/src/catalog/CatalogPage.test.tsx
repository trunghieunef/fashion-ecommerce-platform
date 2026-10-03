// @vitest-environment jsdom

import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, expect, it, vi } from 'vitest';

import { CatalogPage } from './CatalogPage';

afterEach(() => {
  vi.unstubAllGlobals();
});

it('shows an empty state after an empty response', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
    code: 'OK',
    data: { items: [], next_cursor: null },
    metadata: { request_id: 'req-1', trace_id: 'trace-1' },
  }), { status: 200 })));
  const container = document.createElement('div');

  await act(async () => createRoot(container).render(<CatalogPage />));

  expect(container.textContent).toContain('Chưa có sản phẩm');
});

it('offers retry after a network error', async () => {
  const fetchMock = vi.fn()
    .mockRejectedValueOnce(new TypeError('network'))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      code: 'OK',
      data: { items: [], next_cursor: null },
      metadata: { request_id: 'req-2', trace_id: 'trace-2' },
    }), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  const container = document.createElement('div');

  await act(async () => createRoot(container).render(<CatalogPage />));

  const retry = container.querySelector('button');
  expect(retry?.textContent).toBe('Thử lại');

  await act(async () => retry?.click());

  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(container.textContent).toContain('Chưa có sản phẩm');
});
