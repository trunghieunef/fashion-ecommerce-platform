// @vitest-environment jsdom
// TASK:WEB-02b: typed cart API — guest calls ride the cookie, member calls carry the Bearer token.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import cartGuest from '../test/fixtures/cart-guest.json';
import cartMember from '../test/fixtures/cart-member.json';
import merge from '../test/fixtures/merge.json';
import refresh from '../test/fixtures/refresh.json';
import { FakeChannel, json, mockFetch } from '../test/harness';
import { resetSessionForTests, startSession } from '../auth/session';
import * as cart from './api';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); resetSessionForTests(); });
afterEach(() => { vi.unstubAllGlobals(); resetSessionForTests(); });

const CART = cartGuest.data.cart_id;

it('guest calls carry no Authorization, member calls carry Bearer', async () => {
  const calls = mockFetch((path) => (path.startsWith('/api/v1/carts') ? json(path.endsWith('/mine') ? cartMember : cartGuest)
    : path === '/api/v1/auth/refresh' ? json(refresh) : null));
  await cart.getCart(false, CART);
  startSession('member-token', 900);
  await cart.getMyCart();
  await cart.createCart(true);
  expect(calls[0]).toMatchObject({ url: `/api/v1/carts/${CART}`, method: 'GET', auth: null });
  expect(calls[1]).toMatchObject({ url: '/api/v1/carts/mine', auth: 'Bearer member-token' });
  expect(calls[2]).toMatchObject({ url: '/api/v1/carts', method: 'POST', auth: 'Bearer member-token' });
});

it('addItem sends the Idempotency-Key header', async () => {
  const calls = mockFetch(() => json(cartGuest));
  await cart.addItem(false, CART, { variant_id: '55555555-5555-4555-8555-555555555551', quantity: 2, expected_version: 3 }, 'key-1');
  expect(calls[0]).toMatchObject({ url: `/api/v1/carts/${CART}/items`, method: 'POST',
    body: { variant_id: '55555555-5555-4555-8555-555555555551', quantity: 2, expected_version: 3 } });
  expect(calls[0].headers['idempotency-key']).toBe('key-1');
});

it('setQuantity sends the absolute quantity and version', async () => {
  const calls = mockFetch(() => json(cartGuest));
  await cart.setQuantity(false, CART, 'item-1', 4, 5);
  expect(calls[0]).toMatchObject({ url: `/api/v1/carts/${CART}/items/item-1`, method: 'PUT', body: { quantity: 4, expected_version: 5 } });
});

it('remove and clear send expected_version as a query', async () => {
  const calls = mockFetch(() => json(cartGuest));
  await cart.removeItem(false, CART, 'item-1', 6);
  await cart.clearCart(false, CART, 7);
  expect(calls.map((c) => [c.method, c.url])).toEqual([
    ['DELETE', `/api/v1/carts/${CART}/items/item-1?expected_version=6`],
    ['DELETE', `/api/v1/carts/${CART}?expected_version=7`],
  ]);
});

it('mergeCarts posts the three versions with the key, as a member', async () => {
  const calls = mockFetch(() => json(merge));
  startSession('member-token', 900);
  const result = await cart.mergeCarts({ guest_cart_id: CART, guest_expected_version: 2, member_expected_version: 0 }, 'merge-key');
  expect(calls[0]).toMatchObject({ url: '/api/v1/carts/merge', method: 'POST', auth: 'Bearer member-token',
    body: { guest_cart_id: CART, guest_expected_version: 2, member_expected_version: 0 } });
  expect(calls[0].headers['idempotency-key']).toBe('merge-key');
  expect(result.adjustments[0].reason).toBe('QUANTITY_CAPPED');
});
