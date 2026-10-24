// TASK:WEB-02b: the cart page (spec §3). Prices are server previews; the subtotal shows only when every line could be
// priced (preview_status OK) and is never the amount to pay — shipping and the final total come from the order quote
// (WEB-03). Quantity edits go out once, 400 ms after the last change, as an absolute 1–99.
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';

import { BUTTON, BUTTON_OUTLINE, CatalogImage, LABEL, Loading, PAGE, Price } from '../catalog/ui';
import { useLang } from '../i18n';
import { Drawer } from '../ui/Drawer';
import { StatusMessage } from '../ui/form';
import type { CartItem } from './api';
import { useCart } from './CartProvider';
import { displayOf } from './display';

const DEBOUNCE_MS = 400;
const valid = (text: string) => /^\d{1,2}$/.test(text) && Number(text) >= 1 && Number(text) <= 99;

function CartLine({ item, onRemoved }: { item: CartItem; onRemoved: () => void }) {
  const { t, pick } = useLang();
  const { setQuantity, remove } = useCart();
  const display = displayOf(item.variant_id);
  const name = display ? pick(display.name_vi, display.name_en) : item.sku;
  const [draft, setDraft] = useState(String(item.quantity));
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => { setDraft(String(item.quantity)); }, [item.quantity]);
  // A refused change leaves item.quantity as it was, so the effect above does not fire: put the box back by hand.
  const latest = useRef(item.quantity);
  latest.current = item.quantity;
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  const change = (text: string) => {
    setDraft(text);
    if (timer.current) clearTimeout(timer.current);
    if (!valid(text) || Number(text) === item.quantity) return;
    timer.current = setTimeout(async () => {
      // Only if the visitor has not typed something newer meanwhile.
      if (!(await setQuantity(item.item_id, Number(text)))) setDraft((now) => (now === text ? String(latest.current) : now));
    }, DEBOUNCE_MS);
  };
  const step = (delta: number) => {
    const base = valid(draft) ? Number(draft) : item.quantity;
    change(String(Math.min(99, Math.max(1, base + delta))));
  };
  const removing = useRef(false);
  const onRemove = async () => {
    if (removing.current) return;
    removing.current = true;
    const ok = await remove(item.item_id);
    removing.current = false;
    if (ok) onRemoved();
  };
  const preview = item.preview;
  // No preview (just written, or catalog/stock unreachable) says nothing about the line: no mark.
  const mark = !preview ? null : !preview.active ? t('notSold') : preview.available < item.quantity ? t('notEnough') : null;

  return (
    <li data-testid="cart-line" className="grid grid-cols-[5rem_1fr] gap-4 border-b border-line py-4 lg:grid-cols-[7rem_1fr]">
      <CatalogImage assetId={display?.asset_id} alt={name} />
      <div className="grid gap-2">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="grid gap-1">
            {display
              ? <Link to={`/products/${display.product_id}`} className="text-sm font-semibold break-words uppercase hover:underline">{name}</Link>
              : <p className="text-sm font-semibold break-words">{t('sku')} {item.sku}</p>}
            {display && <p className="text-sm text-muted">{display.color} · {display.size}</p>}
            {mark && <p className="text-sm font-semibold text-danger">{mark}</p>}
          </div>
          <Price min={preview?.effective_price ?? item.price_snapshot} max={preview?.effective_price ?? item.price_snapshot} className="text-sm" />
        </div>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center">
            <button type="button" aria-label={t('decrease')} onClick={() => step(-1)} className="grid size-11 place-items-center border border-line">−</button>
            <label className="sr-only" htmlFor={`qty-${item.item_id}`}>{t('quantity')}</label>
            <input id={`qty-${item.item_id}`} inputMode="numeric" value={draft} onChange={(e) => change(e.currentTarget.value)}
              onBlur={() => { if (!valid(draft)) setDraft(String(item.quantity)); }}
              className="h-11 w-14 border-y border-line text-center" />
            <button type="button" aria-label={t('increase')} onClick={() => step(1)} className="grid size-11 place-items-center border border-line">+</button>
          </div>
          <button type="button" onClick={() => void onRemove()}
            className={`${LABEL} min-h-11 px-2 underline underline-offset-4`}>{t('removeLine')}</button>
        </div>
      </div>
    </li>
  );
}

export function CartPage() {
  const { t } = useLang();
  const { cart, loading, notice, clearNotice, clear } = useCart();
  const [status, setStatus] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const cancelRef = useRef<HTMLButtonElement>(null);
  useEffect(() => { if (confirming) cancelRef.current?.focus(); }, [confirming]);

  const items = cart?.items ?? [];
  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-4xl gap-6">
        <h1 className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('cart')}</h1>
        <StatusMessage message={status} />
        {notice && (
          <div role="alert" className="flex items-start justify-between gap-4 border border-danger p-3 text-sm">
            <p>{notice}</p>
            <button type="button" onClick={clearNotice} className="underline">{t('close')}</button>
          </div>
        )}
        {loading && !cart && <Loading />}
        {!loading && items.length === 0 && (
          <div className="grid justify-items-start gap-4 py-8">
            <p>{t('cartEmpty')}</p>
            <Link to="/products" className={BUTTON}>{t('shopNow')}</Link>
          </div>
        )}
        {items.length > 0 && (
          <div className="grid gap-8 lg:grid-cols-[1fr_20rem]">
            <div>
              <ul>{items.map((item) => <CartLine key={item.item_id} item={item} onRemoved={() => setStatus(t('removed'))} />)}</ul>
              <button type="button" onClick={() => setConfirming(true)} className={`${LABEL} mt-4 min-h-11 underline underline-offset-4`}>{t('clearCart')}</button>
            </div>
            <aside className="grid content-start gap-4 border border-line p-4">
              {cart?.preview_status === 'OK' && cart.subtotal_preview !== null && (
                <p data-testid="subtotal" className="flex justify-between gap-4">
                  <span className={LABEL}>{t('subtotalPreview')}</span>
                  <Price min={cart.subtotal_preview} max={cart.subtotal_preview} />
                </p>
              )}
              <p className="text-sm text-muted">{t('cartNote')}</p>
              <button type="button" disabled aria-describedby="checkout-soon" className={`${BUTTON} w-full`}>{t('checkout')}</button>
              <p id="checkout-soon" className="text-xs text-muted">{t('checkoutSoon')}</p>
            </aside>
          </div>
        )}
      </div>
      <Drawer open={confirming} onClose={() => setConfirming(false)} title={t('clearCart')}>
        <div className="grid gap-4">
          <p>{t('clearCartConfirm')}</p>
          <div className="flex gap-2">
            <button type="button" ref={cancelRef} onClick={() => setConfirming(false)} className={`${BUTTON_OUTLINE} flex-1 px-4`}>{t('cancel')}</button>
            <button type="button" onClick={async () => { setConfirming(false); if (await clear()) setStatus(t('cartCleared')); }}
              className={`${BUTTON} flex-1`}>{t('clearCartAction')}</button>
          </div>
        </div>
      </Drawer>
    </main>
  );
}
