// TASK:WEB-02b: merge the guest cart into the member cart (spec §4, CART-02). The Idempotency-Key lives in sessionStorage
// until the merge is final, so a reload in the middle resends the same key. On any failure the guest cart and the hint
// stay: nothing is deleted on the client, and the next start tries again.
import { HttpError } from '../http';
import * as api from './api';
import { readHint, writeHint } from './CartProvider';

const KEY = 'storefront.merge';
type Saved = { guest_cart_id: string; key: string };
export type MergeOutcome = { outcome: 'merged' | 'none' | 'later'; result?: api.MergeResult };

function savedKey(guestCartId: string, fresh: boolean): string {
  try {
    const saved = JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as Saved | null;
    if (!fresh && saved?.guest_cart_id === guestCartId && saved.key) return saved.key;
  } catch {
    // unreadable: start a new key
  }
  const key = crypto.randomUUID();
  try { sessionStorage.setItem(KEY, JSON.stringify({ guest_cart_id: guestCartId, key } satisfies Saved)); } catch { /* best effort */ }
  return key;
}
const dropKey = () => { try { sessionStorage.removeItem(KEY); } catch { /* best effort */ } };
const status = (error: unknown) => (error instanceof HttpError ? error.status : -1);

export async function mergeGuestCart(): Promise<MergeOutcome> {
  const hint = readHint();
  if (!hint) return { outcome: 'none' };
  for (let attempt = 0; attempt < 2; attempt++) {
    let guest: api.Cart;
    let member: api.Cart;
    try {
      guest = await api.getCart(false, hint);
    } catch (error) {
      if (status(error) === 404) { writeHint(null); dropKey(); return { outcome: 'none' }; }
      return { outcome: 'later' };
    }
    try {
      member = await api.getMyCart().catch((error: unknown) => (status(error) === 404 ? api.createCart(true) : Promise.reject(error)));
    } catch {
      return { outcome: 'later' };
    }
    // First attempt reuses a key saved by an interrupted merge; the retry after a conflict uses a new one.
    const key = savedKey(hint, attempt > 0);
    try {
      const result = await api.mergeCarts(
        { guest_cart_id: hint, guest_expected_version: guest.version, member_expected_version: member.version }, key);
      writeHint(null);
      dropKey();
      return { outcome: 'merged', result };
    } catch (error) {
      if (status(error) === 409 && attempt === 0) continue;
      if (status(error) === 404) { writeHint(null); dropKey(); return { outcome: 'none' }; }
      if (status(error) === 409) dropKey();
      return { outcome: 'later' };
    }
  }
  return { outcome: 'later' };
}
