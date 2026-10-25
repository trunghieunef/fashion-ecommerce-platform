// @vitest-environment jsdom
// TASK:WEB-03: order API — a guest proves the cart with a checkout token kept in memory, a member uses the Bearer.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import checkoutToken from '../test/fixtures/checkout-token.json';
import order from '../test/fixtures/order-processing.json';
import quoteFixture from '../test/fixtures/quote.json';
import { resetSessionForTests, startSession } from '../auth/session';
import { FakeChannel, json, mockFetch } from '../test/harness';
import * as api from './api';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); resetSessionForTests(); api.forgetCheckoutToken(); });
afterEach(() => { vi.unstubAllGlobals(); resetSessionForTests(); });

const metadata = { request_id: 'web03-test', trace_id: 'web03-test' };
const CART = quoteFixture.data.cart_id;
const body: api.QuoteRequest = {
  cart_id: CART, cart_version: 2, method: 'COD', shipping_address: quoteFixture.data.shipping_address,
  contact: { phone: '0912345678', email: 'khach@example.test' }, note: null,
};
const TOKEN = `/api/v1/carts/${CART}/checkout-token`;
const badToken = () => json({ code: 'UNAUTHORIZED', message: 'INVALID_CHECKOUT_TOKEN', metadata }, 401);

it('a guest quote fetches a checkout token first and sends it', async () => {
  const calls = mockFetch((path) => (path === TOKEN ? json(checkoutToken) : path === '/api/v1/orders/quote' ? json(quoteFixture) : null));
  const result = await api.quote(false, body, 'vi');
  expect(result.totals.total_amount).toBe(340000);
  expect(calls.map((c) => [c.method, c.url])).toEqual([['POST', TOKEN], ['POST', '/api/v1/orders/quote']]);
  expect(calls[1].headers['checkout-token']).toBe('example-checkout-token');
  expect(calls[1].auth).toBeNull();
  expect(calls[1].body).toEqual(body);
});

it('the checkout token is reused until 30 s before expiry', async () => {
  let expires = new Date(Date.now() + 10 * 60_000).toISOString();
  const calls = mockFetch((path) => (path === TOKEN ? json({ ...checkoutToken, data: { ...checkoutToken.data, expires_at: expires } })
    : path === '/api/v1/orders/quote' ? json(quoteFixture) : null));
  await api.quote(false, body, 'vi');
  await api.quote(false, body, 'vi');
  expect(calls.filter((c) => c.url === TOKEN)).toHaveLength(1);
  api.forgetCheckoutToken();
  expires = new Date(Date.now() + 20_000).toISOString();
  await api.quote(false, body, 'vi');
  await api.quote(false, body, 'vi');
  expect(calls.filter((c) => c.url === TOKEN)).toHaveLength(3);
});

it('a 401 INVALID_CHECKOUT_TOKEN renews the token and resends once', async () => {
  let quotes = 0;
  const calls = mockFetch((path) => (path === TOKEN ? json(checkoutToken)
    : path === '/api/v1/orders/quote' ? (quotes++ === 0 ? badToken() : json(quoteFixture)) : null));
  await api.quote(false, body, 'vi');
  expect(calls.filter((c) => c.url === TOKEN)).toHaveLength(2);
  const sent = calls.filter((c) => c.url === '/api/v1/orders/quote');
  expect(sent).toHaveLength(2);
  expect(sent[1].body).toEqual(sent[0].body);

  api.forgetCheckoutToken();
  mockFetch((path) => (path === TOKEN ? json(checkoutToken) : path === '/api/v1/orders/quote' ? badToken() : null));
  await expect(api.quote(false, body, 'vi')).rejects.toMatchObject({ status: 401 });
});

it('a member quote uses the Bearer and no checkout token', async () => {
  startSession('member-token', 900);
  const calls = mockFetch((path) => (path === '/api/v1/orders/quote' ? json(quoteFixture) : null));
  await api.quote(true, body, 'vi');
  expect(calls).toHaveLength(1);
  expect(calls[0].auth).toBe('Bearer member-token');
  expect(calls[0].headers['checkout-token']).toBeUndefined();
});

it('createOrder sends the Idempotency-Key', async () => {
  const calls = mockFetch((path) => (path === TOKEN ? json(checkoutToken) : path === '/api/v1/orders' ? json(order, 202) : null));
  const created = await api.createOrder(false, 'k1', { cart_id: CART, cart_version: 2, quote_token: 'example-signed-quote' });
  expect(created.order_no).toBe('ORD202610000001');
  const sent = calls.find((c) => c.url === '/api/v1/orders')!;
  expect(sent.headers['idempotency-key']).toBe('k1');
  expect(sent.headers['checkout-token']).toBe('example-checkout-token');
  expect(sent.body).toEqual({ cart_id: CART, cart_version: 2, quote_token: 'example-signed-quote' });
});

it('quote sends Accept-Language', async () => {
  startSession('member-token', 900);
  const calls = mockFetch((path) => (path === '/api/v1/orders/quote' ? json(quoteFixture) : null));
  await api.quote(true, body, 'en');
  expect(calls[0].lang).toBe('en');
});

it('reads one order and the member list', async () => {
  startSession('member-token', 900);
  const calls = mockFetch((path) => (path.startsWith('/api/v1/orders/mine') ? json({ code: 'OK', data: { items: [], next_cursor: null }, metadata })
    : path === '/api/v1/orders/ORD202610000001' ? json(order) : null));
  expect((await api.getOrder('ORD202610000001', false)).status).toBe('PENDING');
  expect(calls[0].auth).toBeNull();
  await api.myOrders('c 2');
  expect(calls[1].url).toBe('/api/v1/orders/mine?limit=20&cursor=c%202');
  expect(calls[1].auth).toBe('Bearer member-token');
});
