// TASK:WEB-03: typed order calls (contracts/openapi/order.yaml). A member is the Bearer (authRequest); a guest proves its cart
// with a short-lived checkout token from cart-service, kept only in this module (never storage, URL or logs) and sent as
// the Checkout-Token header. Money is whatever the server says: quote and order totals, never computed here.
import { authRequest } from '../auth/session';
import { HttpError, request, type RequestInit } from '../http';

export type Address = { recipient_name: string; phone: string; province_code: string; ward_code: string; address_line: string };
export type Contact = { phone: string; email: string | null };
export type Method = 'COD' | 'VNPAY' | 'MOMO';
export type QuoteRequest = { cart_id: string; cart_version: number; method: Method; shipping_address: Address; contact: Contact; note: string | null };
export type Item = {
  variant_id: string; sku: string; name: string; size: string; color: string; image_url: string | null;
  quantity: number; unit_price: number; line_total: number;
};
export type Totals = {
  subtotal: number; discount_amount: number; shipping_fee: number; shipping_discount: number; cod_fee: number; total_amount: number;
  currency: 'VND';
};
export type Quote = {
  quote_token: string; expires_at: string; cart_id: string; cart_version: number; method: Method; shipping_address: Address;
  items: Item[]; totals: Totals;
};
export type OrderStatus = 'PENDING' | 'WAITING_PAYMENT' | 'CONFIRMED' | 'PAID' | 'PACKING' | 'SHIPPING' | 'DELIVERED' | 'CANCELLING'
  | 'CANCELLED' | 'REFUNDING' | 'REFUNDED' | 'RETURNING' | 'RETURNED';
export type Order = {
  order_id: string; order_no: string; status: OrderStatus; processing_status: 'PROCESSING' | 'READY' | 'ACTION_REQUIRED' | 'FINISHED';
  reason_code: string | null; status_url: string; retry_after_seconds: number | null; version: number; method: Method; items: Item[];
  totals: Totals; shipping_address: Address; contact: Contact; note: string | null; created_at: string; expires_at: string;
  allowed_actions: 'CANCEL'[];
};
export type OrderSummary = { order_no: string; status: OrderStatus; total_amount: number; currency: 'VND'; created_at: string; item_count: number };
export type CreateBody = { cart_id: string; cart_version: number; quote_token: string };

const ORDERS = '/api/v1/orders';
const EARLY_RENEW_MS = 30_000;
let checkout: { cartId: string; token: string; expiresAt: number } | null = null;

export const forgetCheckoutToken = () => { checkout = null; };

async function checkoutToken(cartId: string): Promise<string> {
  if (!checkout || checkout.cartId !== cartId || checkout.expiresAt - Date.now() < EARLY_RENEW_MS) {
    const issued = await request<{ checkout_token: string; expires_at: string }>(
      `/api/v1/carts/${encodeURIComponent(cartId)}/checkout-token`, { method: 'POST' });
    checkout = { cartId, token: issued.checkout_token, expiresAt: Date.parse(issued.expires_at) };
  }
  return checkout.token;
}

const expiredToken = (e: unknown) => e instanceof HttpError && e.status === 401 && e.message === 'INVALID_CHECKOUT_TOKEN';

/** Member: Bearer. Guest: Checkout-Token for this cart; one renewal and resend after INVALID_CHECKOUT_TOKEN. */
async function send<T>(member: boolean, cartId: string, path: string, init: RequestInit): Promise<T> {
  if (member) return authRequest<T>(path, init);
  const once = async () => request<T>(path, { ...init, headers: { ...init.headers, 'Checkout-Token': await checkoutToken(cartId) } });
  try {
    return await once();
  } catch (error) {
    if (!expiredToken(error)) throw error;
    checkout = null;
    return once();
  }
}

export const quote = (member: boolean, body: QuoteRequest, lang: 'vi' | 'en') =>
  send<Quote>(member, body.cart_id, `${ORDERS}/quote`, { method: 'POST', body, headers: { 'Accept-Language': lang } });

export const createOrder = (member: boolean, key: string, body: CreateBody) =>
  send<Order>(member, body.cart_id, ORDERS, { method: 'POST', body, headers: { 'Idempotency-Key': key } });

/** Member: Bearer; guest: the HttpOnly order_access cookie the server set for this order. */
export const getOrder = (orderNo: string, member: boolean) => {
  const path = `${ORDERS}/${encodeURIComponent(orderNo)}`;
  return member ? authRequest<Order>(path) : request<Order>(path);
};

export const myOrders = (cursor: string | null) =>
  authRequest<{ items: OrderSummary[]; next_cursor: string | null }>(
    `${ORDERS}/mine?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
