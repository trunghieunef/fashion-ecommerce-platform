// TASK:WEB-02b: the visitor's current cart (spec §3). No cart is created for browsing: the first "add to cart" creates
// it. A guest's cart id is kept in localStorage only as a hint (the HttpOnly cookie is the credential). Every write
// carries the shown version; a 409 reloads the cart and says so — the client never drops an item on its own.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

import { useAuth } from '../auth/AuthProvider';
import { HttpError } from '../http';
import { useLang } from '../i18n';
import * as api from './api';
import { mergeGuestCart } from './merge';
import { MergeNotice } from './MergeNotice';

const HINT = 'storefront.cart';
export const readHint = () => { try { return localStorage.getItem(HINT); } catch { return null; } };
export const writeHint = (cartId: string | null) => {
  try {
    if (cartId) localStorage.setItem(HINT, cartId);
    else localStorage.removeItem(HINT);
  } catch {
    // Blocked storage: the guest cart cannot be found again after a reload.
  }
};

/** 'added', or what to tell the visitor next to the button. */
type AddResult = 'added' | { problem: string };
type CartContext = {
  cart: api.Cart | null;
  count: number;
  loading: boolean;
  notice: string | null;
  clearNotice(): void;
  add(variantId: string, quantity: number): Promise<AddResult>;
  /** true when the server applied the change (false: conflict reloaded, or failed with a notice). */
  setQuantity(itemId: string, quantity: number): Promise<boolean>;
  remove(itemId: string): Promise<boolean>;
  clear(): Promise<boolean>;
  reload(): Promise<void>;
};

const Context = createContext<CartContext | null>(null);
const isVersionConflict = (e: unknown) => e instanceof HttpError && e.status === 409 && e.code === 'VERSION_CONFLICT';
/** Network failures and 503 leave nothing written: the same operation may be retried with the same key. */
const isRetryable = (e: unknown) => e instanceof HttpError && (e.status === 0 || e.status === 503);
const isNotFound = (e: unknown) => e instanceof HttpError && e.status === 404;
/** The cart itself is gone (expired or cleared guest cookie, deleted member cart): drop it rather than retry into it. */
const isCartGone = (e: unknown) => isNotFound(e) && (e as HttpError).message === 'CART_NOT_FOUND';
const REFUSALS = { VARIANT_NOT_AVAILABLE: 'errNotSold', QUANTITY_LIMIT: 'errQuantityLimit', CART_ITEM_LIMIT: 'errCartFull' } as const;

/** The server's expected refusals (cart.yaml 400/409) said plainly; null for anything else. */
function refusal(error: unknown, t: ReturnType<typeof useLang>['t']): string | null {
  if (!(error instanceof HttpError)) return null;
  if (error.code === 'OUT_OF_STOCK') {
    const left = error.fields.find((f) => f.field === 'quantity')?.message.match(/available=(\d+)/)?.[1];
    return left ? t('errOutOfStockLeft').replace('{n}', left) : t('errOutOfStock');
  }
  const key = REFUSALS[error.message as keyof typeof REFUSALS];
  return key ? t(key) : null;
}

export function CartProvider({ children }: { children: ReactNode }) {
  const { t } = useLang();
  // Read through a ref in the merge: a language switch must not re-run the status effect (refetch, lost edits).
  const tRef = useRef(t);
  tRef.current = t;
  const { status, me, onSignedIn } = useAuth();
  const userId = me?.id ?? null;
  const member = status === 'member';
  const [cart, setCartState] = useState<api.Cart | null>(null);
  const cartRef = useRef<api.Cart | null>(null);
  // true until the first load settles, so /cart does not flash "empty" while the session restores.
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<string | null>(null);
  // One app-wide status line for screen readers (spec: Contextual Live Badge Updates).
  const [announcement, setAnnouncement] = useState('');
  const pending = useRef<{ variantId: string; quantity: number; key: string } | null>(null);
  const [adjustments, setAdjustments] = useState<api.Adjustment[] | null>(null);
  // Every load/merge takes a number; an answer for an older number is dropped (e.g. a member-cart load that lands after
  // the merge result must not overwrite the merged cart).
  const seq = useRef(0);
  const merging = useRef<Promise<void> | null>(null);
  // Writes run one after another, so each sends the version the previous one returned (no self-inflicted 409).
  const queue = useRef<Promise<unknown>>(Promise.resolve());
  const serial = <T,>(task: () => Promise<T>) => {
    const next = queue.current.then(task);
    queue.current = next.catch(() => undefined);
    return next;
  };

  const setCart = (next: api.Cart | null) => { cartRef.current = next; setCartState(next); };

  const load = useCallback(async (asMember: boolean) => {
    const mine = ++seq.current;
    const hint = readHint();
    if (!asMember && !hint) { setCart(null); setLoading(false); return; }
    setLoading(true);
    try {
      const next = asMember ? await api.getMyCart() : await api.getCart(false, hint!);
      if (mine === seq.current) setCart(next);
    } catch (error) {
      if (isNotFound(error) && mine === seq.current) {
        if (!asMember) writeHint(null);
        setCart(null);
      }
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  }, []);

  /** Writes and the merge answer without previews (cart.yaml: NOT_LOADED); read the cart again for prices and stock. */
  const refresh = useCallback(async (written: api.Cart, asMember: boolean) => {
    if (written.preview_status !== 'NOT_LOADED') return;
    try {
      const next = await api.getCart(asMember, written.cart_id);
      // A later write may have answered first: never step back to an older version.
      if (cartRef.current?.cart_id === next.cart_id && next.version >= cartRef.current.version) setCart(next);
    } catch {
      // Keep the written cart; the next load brings the preview.
    }
  }, []);

  /** Single-flight: sign-in (onSignedIn) and the status change both ask for it; one merge runs. */
  const runMerge = useCallback(() => {
    if (merging.current) return merging.current;
    merging.current = (async () => {
      const mine = ++seq.current;
      setLoading(true);
      const { outcome, result } = await mergeGuestCart();
      if (outcome === 'merged' && result) {
        if (mine === seq.current) { setCart(result.cart); setLoading(false); void refresh(result.cart, true); }
        if (result.adjustments.length > 0) setAdjustments(result.adjustments);
        return;
      }
      if (outcome === 'later') setNotice(tRef.current('mergeLater'));
      await load(true);
    })().finally(() => { merging.current = null; });
    return merging.current;
  }, [load, refresh]);

  const reload = useCallback(async () => {
    if (status === 'unknown') return;
    if (status === 'member' && readHint()) return runMerge();
    return load(status === 'member');
  }, [status, load, runMerge]);

  useEffect(() => onSignedIn(() => (readHint() ? runMerge() : undefined)), [onSignedIn, runMerge]);

  // Status or account changes (restore, sign in — also as someone else, here or in another tab — sign out): show the
  // cart that belongs to who the visitor is now.
  useEffect(() => {
    setCart(null);
    void reload();
  }, [reload, userId]);

  const drop = useCallback(async () => {
    if (!member) writeHint(null);
    setCart(null);
    await reload();
  }, [member, reload]);

  const conflict = useCallback(async () => {
    await reload();
    setNotice(t('cartChanged'));
  }, [reload, t]);

  const write = useCallback((run: (c: api.Cart) => Promise<api.Cart>) => serial(async () => {
    const current = cartRef.current;
    if (!current) return false;
    try {
      const next = await run(current);
      setCart(next);
      void refresh(next, member);
      return true;
    } catch (error) {
      if (isVersionConflict(error)) { await conflict(); return false; }
      if (isCartGone(error)) await drop();
      setNotice(refusal(error, t) ?? t('errCartWrite'));
      return false;
    }
  }), [conflict, drop, refresh, member, t]);

  const value = useMemo<CartContext>(() => ({
    cart,
    count: cart ? cart.items.reduce((sum, item) => sum + item.quantity, 0) : 0,
    loading,
    notice,
    clearNotice: () => setNotice(null),
    add: (variantId, quantity) => serial(async (): Promise<AddResult> => {
      const previous = pending.current;
      const key = previous && previous.variantId === variantId && previous.quantity === quantity ? previous.key : crypto.randomUUID();
      pending.current = { variantId, quantity, key };
      try {
        let current = cartRef.current;
        if (!current) {
          current = await api.createCart(member);
          if (!member) writeHint(current.cart_id);
          setCart(current);
        }
        const next = await api.addItem(member, current.cart_id, { variant_id: variantId, quantity, expected_version: current.version }, key);
        setCart(next);
        void refresh(next, member);
        pending.current = null;
        setAnnouncement(t('addedAnnounce').replace('{n}', String(next.items.reduce((sum, item) => sum + item.quantity, 0))));
        return 'added';
      } catch (error) {
        if (!isRetryable(error)) pending.current = null;
        if (isVersionConflict(error)) { await conflict(); return { problem: t('cartChanged') }; }
        if (isCartGone(error)) await drop();
        return { problem: refusal(error, t) ?? t('addFailed') };
      }
    }),
    setQuantity: (itemId, quantity) => write((c) => api.setQuantity(member, c.cart_id, itemId, quantity, c.version)),
    remove: (itemId) => write((c) => api.removeItem(member, c.cart_id, itemId, c.version)),
    clear: () => write((c) => api.clearCart(member, c.cart_id, c.version)),
    reload,
  }), [cart, loading, notice, member, conflict, drop, write, refresh, reload, t]);

  return (
    <Context.Provider value={value}>
      <p role="status" className="sr-only">{announcement}</p>
      {children}
      <MergeNotice adjustments={adjustments} onClose={() => setAdjustments(null)} />
    </Context.Provider>
  );
}

export function useCart(): CartContext {
  const value = useContext(Context);
  if (!value) throw new Error('useCart outside CartProvider');
  return value;
}
