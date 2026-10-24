// TASK:WEB-02b: the visitor's current cart (spec §3). No cart is created for browsing: the first "add to cart" creates
// it. A guest's cart id is kept in localStorage only as a hint (the HttpOnly cookie is the credential). Every write
// carries the shown version; a 409 reloads the cart and says so — the client never drops an item on its own.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

import { useAuth } from '../auth/AuthProvider';
import { HttpError } from '../http';
import { useLang } from '../i18n';
import * as api from './api';

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

type AddResult = 'added' | 'conflict' | 'failed';
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

export function CartProvider({ children }: { children: ReactNode }) {
  const { t } = useLang();
  const { status } = useAuth();
  const member = status === 'member';
  const [cart, setCartState] = useState<api.Cart | null>(null);
  const cartRef = useRef<api.Cart | null>(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  // One app-wide status line for screen readers (spec: Contextual Live Badge Updates).
  const [announcement, setAnnouncement] = useState('');
  const pending = useRef<{ variantId: string; quantity: number; key: string } | null>(null);

  const setCart = (next: api.Cart | null) => { cartRef.current = next; setCartState(next); };

  const reload = useCallback(async () => {
    if (status === 'unknown') return;
    const hint = readHint();
    if (status === 'guest' && !hint) { setCart(null); return; }
    setLoading(true);
    try {
      setCart(status === 'member' ? await api.getMyCart() : await api.getCart(false, hint!));
    } catch (error) {
      if (isNotFound(error)) {
        if (status === 'guest') writeHint(null);
        setCart(null);
      }
    } finally {
      setLoading(false);
    }
  }, [status]);

  // Status changes (restore, sign in, sign out): show the cart that belongs to who the visitor is now.
  useEffect(() => {
    setCart(null);
    void reload();
  }, [reload]);

  const conflict = useCallback(async () => {
    await reload();
    setNotice(t('cartChanged'));
  }, [reload, t]);

  const write = useCallback(async (run: (c: api.Cart) => Promise<api.Cart>) => {
    const current = cartRef.current;
    if (!current) return false;
    try {
      setCart(await run(current));
      return true;
    } catch (error) {
      if (isVersionConflict(error)) await conflict();
      else setNotice(t('loadError'));
      return false;
    }
  }, [conflict, t]);

  const value = useMemo<CartContext>(() => ({
    cart,
    count: cart ? cart.items.reduce((sum, item) => sum + item.quantity, 0) : 0,
    loading,
    notice,
    clearNotice: () => setNotice(null),
    add: async (variantId, quantity) => {
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
        pending.current = null;
        setAnnouncement(t('addedAnnounce').replace('{n}', String(next.items.reduce((sum, item) => sum + item.quantity, 0))));
        return 'added';
      } catch (error) {
        if (!isRetryable(error)) pending.current = null;
        if (isVersionConflict(error)) { await conflict(); return 'conflict'; }
        return 'failed';
      }
    },
    setQuantity: (itemId, quantity) => write((c) => api.setQuantity(member, c.cart_id, itemId, quantity, c.version)),
    remove: (itemId) => write((c) => api.removeItem(member, c.cart_id, itemId, c.version)),
    clear: () => write((c) => api.clearCart(member, c.cart_id, c.version)),
    reload,
  }), [cart, loading, notice, member, conflict, write, reload, t]);

  return (
    <Context.Provider value={value}>
      <p role="status" className="sr-only">{announcement}</p>
      {children}
    </Context.Provider>
  );
}

export function useCart(): CartContext {
  const value = useContext(Context);
  if (!value) throw new Error('useCart outside CartProvider');
  return value;
}
