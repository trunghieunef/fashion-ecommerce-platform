// @vitest-environment jsdom
// TASK:WEB-03: polling an order (spec §5): only while PROCESSING, after retry_after_seconds, paused in a hidden tab, backing
// off on network errors, and never for more than two minutes.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import confirmed from '../test/fixtures/order-confirmed.json';
import processing from '../test/fixtures/order-processing.json';
import { flush, json, mockFetch } from '../test/harness';
import { usePolledOrder } from './poll';

beforeEach(() => { vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] }); });
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); setHidden(false); document.body.innerHTML = ''; });

const PATH = '/api/v1/orders/ORD202610000001';
type Polled = ReturnType<typeof usePolledOrder>;
function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
}
async function mount() {
  const handle = {} as { polled: Polled };
  function Probe() { handle.polled = usePolledOrder('ORD202610000001', false); return null; }
  const container = document.createElement('div');
  document.body.appendChild(container);
  await act(async () => createRoot(container).render(<Probe />));
  await flush();
  return handle;
}
const advance = async (ms: number) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); await flush(); };
const reads = (calls: { url: string }[]) => calls.filter((c) => c.url === PATH).length;

it('polls while PROCESSING after retry_after_seconds and stops at READY', async () => {
  let n = 0;
  const calls = mockFetch((path) => (path === PATH ? json(n++ === 0 ? processing : confirmed) : null));
  const h = await mount();
  expect(h.polled.order?.status).toBe('PENDING');
  await advance(2999);
  expect(reads(calls)).toBe(1);
  await advance(1);
  expect(reads(calls)).toBe(2);
  expect(h.polled.order?.status).toBe('CONFIRMED');
  await advance(60_000);
  expect(reads(calls)).toBe(2);
});

it('pauses while the tab is hidden and fetches when it shows again', async () => {
  const calls = mockFetch((path) => (path === PATH ? json(processing) : null));
  await mount();
  setHidden(true);
  await advance(10_000);
  expect(reads(calls)).toBe(1);
  setHidden(false);
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
  await flush();
  expect(reads(calls)).toBe(2);
});

it('stops after two minutes and reports stalled', async () => {
  const calls = mockFetch((path) => (path === PATH ? json(processing) : null));
  const h = await mount();
  await advance(121_000);
  expect(h.polled.stalled).toBe(true);
  const count = reads(calls);
  await advance(60_000);
  expect(reads(calls)).toBe(count);
  await act(async () => { h.polled.check(); });
  await flush();
  expect(reads(calls)).toBe(count + 1);
  expect(h.polled.stalled).toBe(false);
});

it('network errors back off up to 30 s', async () => {
  const calls = mockFetch((path) => (path === PATH ? Promise.reject(new TypeError('offline')) : null));
  await mount();
  const seen = [reads(calls)];
  for (const ms of [3000, 6000, 12_000, 24_000, 30_000, 29_000, 1000]) { await advance(ms); seen.push(reads(calls)); }
  expect(seen).toEqual([1, 2, 3, 4, 5, 6, 6, 7]);
});

it('a missing order stops at once', async () => {
  const calls = mockFetch((path) => (path === PATH ? json({ code: 'NOT_FOUND', message: 'ORDER_NOT_FOUND', metadata: { request_id: 'x', trace_id: 'x' } }, 404) : null));
  const h = await mount();
  expect(h.polled.state).toBe('missing');
  await advance(60_000);
  expect(reads(calls)).toBe(1);
});
