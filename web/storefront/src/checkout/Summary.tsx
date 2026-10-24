// TASK:WEB-03: the order summary next to (desktop, sticky) or after (mobile, spec D10) the checkout form. Before a quote it
// shows the cart lines at reference prices; with a quote, the server's lines and totals and how long the price is held.
import type { ReactNode } from 'react';

import type { CartItem } from '../cart/api';
import { displayOf } from '../cart/display';
import { CatalogImage, LABEL, Price } from '../catalog/ui';
import { useLang } from '../i18n';
import type { Quote } from './api';

export function Summary({ cartItems, quote, children }: { cartItems: CartItem[]; quote: Quote | null; children: ReactNode }) {
  const { t, pick, lang } = useLang();
  const count = (quote?.items ?? cartItems).reduce((sum, item) => sum + item.quantity, 0);
  const time = quote && new Date(quote.expires_at).toLocaleTimeString(lang === 'vi' ? 'vi-VN' : 'en-GB', { hour: '2-digit', minute: '2-digit' });
  const rows = quote && ([
    ['subtotal', quote.totals.subtotal], ['discount', quote.totals.discount_amount], ['shippingFee', quote.totals.shipping_fee],
    ['discount', quote.totals.shipping_discount], ['codFee', quote.totals.cod_fee],
  ] as const).filter(([key, amount]) => key !== 'discount' || amount > 0);

  return (
    <aside data-testid="summary" className="grid content-start gap-4 border border-fg p-4 lg:sticky lg:top-24">
      <h2 className={LABEL}>{t('orderSummary').replace('{n}', String(count))}</h2>
      <ul className="grid gap-3">
        {quote
          ? quote.items.map((item) => (
            <li key={item.variant_id} className="grid grid-cols-[3.5rem_1fr_auto] gap-3 text-sm">
              {item.image_url
                ? <img src={item.image_url} alt={item.name} className="aspect-[3/4] w-full bg-soft object-cover" />
                : <CatalogImage assetId={null} alt={item.name} />}
              <div><p className="font-semibold uppercase">{item.name}</p><p className="text-muted">{item.color} · {item.size} · ×{item.quantity}</p></div>
              <Price min={item.line_total} max={item.line_total} />
            </li>))
          : cartItems.map((item) => {
            const display = displayOf(item.variant_id);
            const name = display ? pick(display.name_vi, display.name_en) : `${t('sku')} ${item.sku}`;
            const price = item.preview?.effective_price ?? item.price_snapshot;
            return (
              <li key={item.item_id} className="grid grid-cols-[3.5rem_1fr_auto] gap-3 text-sm">
                <CatalogImage assetId={display?.asset_id} alt={name} />
                <div><p className="font-semibold uppercase">{name}</p>
                  <p className="text-muted">{display ? `${display.color} · ${display.size} · ` : ''}×{item.quantity}</p></div>
                <Price min={price} max={price} />
              </li>);
          })}
      </ul>
      {quote && rows ? (
        <dl className="grid gap-2 border-t border-line pt-3 text-sm">
          {rows.map(([key, amount], i) => (
            <div key={i} className="flex justify-between gap-4"><dt>{t(key)}</dt><dd><Price min={amount} max={amount} /></dd></div>))}
          <div className="flex justify-between gap-4 border-t border-fg pt-3 text-lg font-bold">
            <dt>{t('total')}</dt><dd><Price min={quote.totals.total_amount} max={quote.totals.total_amount} /></dd>
          </div>
          <p className="text-muted">{t('heldUntil').replace('{time}', time ?? '')}</p>
        </dl>
      ) : <p className="border-l-2 border-fg bg-soft p-2 text-sm">{t('quoteFirst')}</p>}
      {children}
    </aside>
  );
}
