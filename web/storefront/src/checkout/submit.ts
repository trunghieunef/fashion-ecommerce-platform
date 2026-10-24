// TASK:WEB-03: placing an order (spec §4.1). One confirmation gets one Idempotency-Key; key and body are stored before the
// request so a lost response — even across a reload — is resent unchanged and the server answers with the same order.
import { HttpError } from '../http';
import { createOrder, type CreateBody, type Order } from './api';

export type Pending = { key: string; body: CreateBody };

const KEY = 'storefront.checkout';
const RETRIES = 5;
const CAP_MS = 30_000;

export function readPending(): Pending | null {
  try {
    const saved = JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as Pending | null;
    return saved?.key && saved.body?.cart_id && saved.body.quote_token ? saved : null;
  } catch {
    return null;
  }
}

export function clearPending() {
  try { sessionStorage.removeItem(KEY); } catch { /* blocked storage: nothing was kept */ }
}

export function startPending(body: CreateBody): Pending {
  const pending = { key: crypto.randomUUID(), body };
  try {
    sessionStorage.setItem(KEY, JSON.stringify(pending));
  } catch {
    // Blocked storage: same-key retries still work in this page, only a reload loses them.
  }
  return pending;
}

/** Network failure and 503 leave the outcome unknown: wait and resend the same key + body; anything else is the caller's. */
const unknown = (e: unknown) => e instanceof HttpError && (e.status === 0 || e.status === 503);
const sleep = (ms: number) => new Promise<void>((resolve) => { setTimeout(resolve, ms); });

export async function send(member: boolean, pending: Pending, wait = sleep, onRetry?: (attempt: number) => void): Promise<Order> {
  for (let attempt = 0; ; attempt++) {
    try {
      const order = await createOrder(member, pending.key, pending.body);
      clearPending();
      return order;
    } catch (error) {
      if (!unknown(error) || attempt >= RETRIES) throw error;
      onRetry?.(attempt + 1);
      await wait(Math.min(1000 * 2 ** attempt, CAP_MS));
    }
  }
}
