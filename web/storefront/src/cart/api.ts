// TASK:WEB-02b: typed cart calls (contracts/openapi/cart.yaml). A guest is identified by the HttpOnly `cart_token` cookie
// the browser sends by itself; a member by the Bearer token (authRequest). Prices here are previews, never a payable total.
import { request, type RequestInit } from '../http';
import { authRequest } from '../auth/session';

export type Preview = { effective_price: number; price_changed: boolean; active: boolean; available: number; observed_at: string | null };
export type CartItem = {
  item_id: string;
  variant_id: string;
  sku: string;
  quantity: number;
  price_snapshot: number;
  item_version: number;
  preview: Preview | null;
};
export type Cart = {
  cart_id: string;
  version: number;
  owner: 'GUEST' | 'MEMBER';
  expires_at: string | null;
  items: CartItem[];
  preview_status: 'OK' | 'UNAVAILABLE' | 'EMPTY' | 'NOT_LOADED';
  subtotal_preview: number | null;
  shipping_fee_status: 'NOT_CALCULATED';
};
export type Adjustment = {
  variant_id: string;
  sku: string;
  reason: 'UNAVAILABLE' | 'OUT_OF_STOCK' | 'QUANTITY_CAPPED' | 'CART_ITEM_LIMIT';
  requested: number;
  applied: number;
};
export type MergeResult = { cart: Cart; merged_from: string; already_merged: boolean; adjustments: Adjustment[] };

const CARTS = '/api/v1/carts';
const send = <T>(member: boolean, path: string, init?: RequestInit) => (member ? authRequest<T>(path, init) : request<T>(path, init));
const id = (value: string) => encodeURIComponent(value);

export const createCart = (member: boolean) => send<Cart>(member, CARTS, { method: 'POST' });
export const getCart = (member: boolean, cartId: string) => send<Cart>(member, `${CARTS}/${id(cartId)}`);
export const getMyCart = () => authRequest<Cart>(`${CARTS}/mine`);
export const addItem = (member: boolean, cartId: string, body: { variant_id: string; quantity: number; expected_version: number }, key: string) =>
  send<Cart>(member, `${CARTS}/${id(cartId)}/items`, { method: 'POST', body, headers: { 'Idempotency-Key': key } });
export const setQuantity = (member: boolean, cartId: string, itemId: string, quantity: number, expectedVersion: number) =>
  send<Cart>(member, `${CARTS}/${id(cartId)}/items/${id(itemId)}`, { method: 'PUT', body: { quantity, expected_version: expectedVersion } });
export const removeItem = (member: boolean, cartId: string, itemId: string, expectedVersion: number) =>
  send<Cart>(member, `${CARTS}/${id(cartId)}/items/${id(itemId)}?expected_version=${expectedVersion}`, { method: 'DELETE' });
export const clearCart = (member: boolean, cartId: string, expectedVersion: number) =>
  send<Cart>(member, `${CARTS}/${id(cartId)}?expected_version=${expectedVersion}`, { method: 'DELETE' });
export const mergeCarts = (body: { guest_cart_id: string; guest_expected_version: number; member_expected_version: number }, key: string) =>
  authRequest<MergeResult>(`${CARTS}/merge`, { method: 'POST', body, headers: { 'Idempotency-Key': key } });
