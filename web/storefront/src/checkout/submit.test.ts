// @vitest-environment jsdom
// TASK:WEB-03: one confirmation = one Idempotency-Key, stored before sending; an unknown outcome is resent with the same
// key and body (spec §4.1), never a new key.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import order from '../test/fixtures/order-processing.json';
import { resetSessionForTests } from '../auth/session';
import { FakeChannel, json, mockFetch } from '../test/harness';
import { forgetCheckoutToken } from './api';
import { clearPending, readPending, send, startPending } from './submit';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); resetSessionForTests(); forgetCheckoutToken(); });
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });

const metadata = { request_id: 'web03-test', trace_id: 'web03-test' };
const body = { cart_id: '88888888-0000-4000-8000-0000000000c1', cart_version: 2, quote_token: 'example-signed-quote' };
const token = (path: string) => (path.endsWith('/checkout-token')
  ? json({ code: 'OK', data: { checkout_token: 't', expires_at: '2099-01-01T00:00:00Z' }, metadata }) : null);
const network = () => Promise.reject(new TypeError('offline'));
const waits: number[] = [];
const wait = async (ms: number) => { waits.push(ms); };
beforeEach(() => { waits.length = 0; });

it('startPending stores key and body before anything is sent', () => {
  const pending = startPending(body);
  expect(pending.key).toMatch(/^[0-9a-f-]{36}$/);
  expect(JSON.parse(sessionStorage.getItem('storefront.checkout')!)).toEqual({ key: pending.key, body });
  expect(readPending()).toEqual(pending);
});

it('network failures resend the same key and body with backoff', async () => {
  let n = 0;
  const calls = mockFetch((path) => token(path) ?? (path === '/api/v1/orders' ? (n++ < 2 ? network() : json(order, 202)) : null));
  const pending = startPending(body);
  const created = await send(false, pending, wait);
  expect(created.order_no).toBe('ORD202610000001');
  const sent = calls.filter((c) => c.url === '/api/v1/orders');
  expect(sent).toHaveLength(3);
  expect(new Set(sent.map((c) => c.headers['idempotency-key']))).toEqual(new Set([pending.key]));
  sent.forEach((c) => expect(c.body).toEqual(body));
  expect(waits).toEqual([1000, 2000]);
});

it('after five automatic retries the last error is thrown and the record is kept', async () => {
  const calls = mockFetch((path) => token(path) ?? (path === '/api/v1/orders' ? json({ code: 'TEMPORARILY_UNAVAILABLE', message: 'x', metadata }, 503) : null));
  const pending = startPending(body);
  await expect(send(false, pending, wait)).rejects.toMatchObject({ status: 503 });
  expect(calls.filter((c) => c.url === '/api/v1/orders')).toHaveLength(6);
  expect(waits).toEqual([1000, 2000, 4000, 8000, 16000]);
  expect(readPending()).toEqual(pending);
});

it('a 409 is thrown at once and the record is kept', async () => {
  const calls = mockFetch((path) => token(path) ?? (path === '/api/v1/orders' ? json({ code: 'PRICE_CHANGED', message: 'PRICE_CHANGED', metadata }, 409) : null));
  const pending = startPending(body);
  await expect(send(false, pending, wait)).rejects.toMatchObject({ status: 409, code: 'PRICE_CHANGED' });
  expect(calls.filter((c) => c.url === '/api/v1/orders')).toHaveLength(1);
  expect(readPending()).toEqual(pending);
});

it('a 202 clears the record', async () => {
  mockFetch((path) => token(path) ?? (path === '/api/v1/orders' ? json(order, 202) : null));
  await send(false, startPending(body), wait);
  expect(readPending()).toBeNull();
});

it('blocked storage still sends', async () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
  const calls = mockFetch((path) => token(path) ?? (path === '/api/v1/orders' ? json(order, 202) : null));
  const pending = startPending(body);
  await send(false, pending, wait);
  expect(calls.find((c) => c.url === '/api/v1/orders')!.headers['idempotency-key']).toBe(pending.key);
  vi.restoreAllMocks();
  clearPending();
});
