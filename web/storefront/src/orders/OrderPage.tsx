// TASK:WEB-03: /orders/:orderNo (spec §5) — where the order stands, in customer words, with the snapshot the order holds.
// A member reads with the Bearer, a guest with the HttpOnly order_access cookie; nobody can look an order up by number or
// phone alone. Polls while the shop is still preparing it (poll.ts). Cancel, history and tracking come with WEB-04.
import { useEffect, useRef } from 'react';
import { Link, useParams } from 'react-router';

import { placeName } from '../account/AddressForm';
import { useAuth } from '../auth/AuthProvider';
import { useCart } from '../cart/CartProvider';
import { BUTTON, BUTTON_OUTLINE, CatalogImage, LABEL, Loading, PAGE, Price } from '../catalog/ui';
import { useLang } from '../i18n';
import { usePolledOrder } from './poll';
import { MILESTONES, milestone, statusText } from './status';

export function OrderPage() {
  const { orderNo = '' } = useParams();
  const { t, pick, lang } = useLang();
  const { status } = useAuth();
  const { state, order, stalled, check } = usePolledOrder(orderNo, status === 'unknown' ? null : status === 'member');
  const heading = useRef<HTMLHeadingElement>(null);
  // The order empties the cart only once stock is held (after PROCESSING), so the cart is read again then (review I2).
  const { reload } = useCart();
  const settled = order !== null && order.processing_status !== 'PROCESSING';
  useEffect(() => { if (settled) void reload(); }, [settled]);
  useEffect(() => { heading.current?.focus(); }, [order !== null]);

  const time = (iso: string) => new Date(iso).toLocaleString(lang === 'vi' ? 'vi-VN' : 'en-GB', { dateStyle: 'short', timeStyle: 'short' });
  const step = order && milestone(order.status);
  const row = 'flex justify-between gap-4';

  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-4xl gap-6">
        <h1 ref={heading} tabIndex={-1} className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('orderTitle').replace('{no}', orderNo)}</h1>
        {state === 'loading' && <Loading />}
        {state === 'missing' && (
          <div className="grid justify-items-start gap-4">
            <p>{t('orderMissing')}</p>
            <Link to="/" className={BUTTON_OUTLINE}>{t('backHome')}</Link>
          </div>)}
        {state === 'error' && !order && (
          <div className="grid justify-items-start gap-4">
            <p role="alert">{t('loadError')}</p>
            <button type="button" onClick={check} className={BUTTON_OUTLINE}>{t('retry')}</button>
          </div>)}
        {order && (
          <>
            <p role="status" className="text-lg font-semibold">{t(statusText(order))}</p>
            {order.status === 'CANCELLED' && (order.reason_code === 'OUT_OF_STOCK' || order.reason_code === 'RESERVE_FAILED') && (
              <Link to="/cart" className={`${BUTTON} justify-self-start`}>{t('fixCart')}</Link>)}
            {step !== null && (
              <ol aria-label={t('orderProgress')} className="grid grid-cols-4 gap-2 text-xs">
                {MILESTONES.map((key, i) => (
                  <li key={key} aria-current={i === step ? 'step' : undefined}
                    className={`border-t-4 pt-2 ${i <= step ? 'border-fg font-semibold' : 'border-line text-muted'}`}>{t(key)}</li>))}
              </ol>)}
            {stalled && (
              <div className="grid justify-items-start gap-2 border border-line p-4 text-sm">
                <p>{t('orderStalled')}</p>
                <button type="button" onClick={check} className={BUTTON_OUTLINE}>{t('checkAgain')}</button>
              </div>)}
            {(order.status === 'PENDING' || order.status === 'CONFIRMED') && (
              <p className="text-sm text-muted">{t('orderWaitUntil').replace('{time}', time(order.expires_at))}</p>)}

            <section className="grid gap-3">
              <h2 className={LABEL}>{t('orderItems')}</h2>
              <ul className="grid gap-3">
                {order.items.map((item) => (
                  <li key={item.variant_id} className="grid grid-cols-[4rem_1fr_auto] gap-3 border-b border-line pb-3 text-sm">
                    {item.image_url
                      ? <img src={item.image_url} alt={item.name} className="aspect-[3/4] w-full bg-soft object-cover" />
                      : <CatalogImage assetId={null} alt={item.name} />}
                    <div><p className="font-semibold uppercase">{item.name}</p><p className="text-muted">{item.color} · {item.size} · ×{item.quantity}</p></div>
                    <Price min={item.line_total} max={item.line_total} />
                  </li>))}
              </ul>
              <dl className="grid gap-2 text-sm">
                <div className={row}><dt>{t('subtotal')}</dt><dd><Price min={order.totals.subtotal} max={order.totals.subtotal} /></dd></div>
                {order.totals.discount_amount > 0 && <div className={row}><dt>{t('discount')}</dt><dd><Price min={order.totals.discount_amount} max={order.totals.discount_amount} /></dd></div>}
                <div className={row}><dt>{t('shippingFee')}</dt><dd><Price min={order.totals.shipping_fee} max={order.totals.shipping_fee} /></dd></div>
                <div className={row}><dt>{t('codFee')}</dt><dd><Price min={order.totals.cod_fee} max={order.totals.cod_fee} /></dd></div>
                <div className={`${row} border-t border-fg pt-2 text-lg font-bold`}><dt>{t('total')}</dt><dd><Price min={order.totals.total_amount} max={order.totals.total_amount} /></dd></div>
              </dl>
            </section>

            <section className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-1 text-sm">
                <h2 className={LABEL}>{t('deliverTo')}</h2>
                <p>{order.shipping_address.recipient_name} · {order.shipping_address.phone}</p>
                <p>{order.shipping_address.address_line}, {placeName(pick, order.shipping_address.province_code, order.shipping_address.ward_code)}, {placeName(pick, order.shipping_address.province_code)}</p>
                {order.note && <p className="text-muted">{order.note}</p>}
              </div>
              <div className="grid gap-1 text-sm">
                <h2 className={LABEL}>{t('paymentMethod')}</h2>
                <p>{t('cod')}</p>
                <p className="text-muted">{order.contact.phone}{order.contact.email ? ` · ${order.contact.email}` : ''}</p>
              </div>
            </section>
          </>)}
      </div>
    </main>
  );
}
