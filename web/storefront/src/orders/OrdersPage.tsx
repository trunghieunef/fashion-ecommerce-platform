// TASK:WEB-03: /account/orders (spec §6) — the member's orders, newest first; each row opens /orders/{order_no}.
import { Link } from 'react-router';

import { myOrders, type OrderSummary } from '../checkout/api';
import { BUTTON, Failed, LABEL, LoadMore, Loading, PAGE, Price, usePaged } from '../catalog/ui';
import { useLang } from '../i18n';
import { statusLabel } from './status';

const page = (cursor: string | null) => myOrders(cursor).then((p) => ({ items: p.items, cursor: p.next_cursor }));

export function OrdersPage() {
  const { t, lang } = useLang();
  const { state, loadMore, retry } = usePaged<OrderSummary, null>(
    () => page(null).then((p) => ({ ...p, head: null })), (cursor) => page(cursor), 'mine');
  const date = (iso: string) => new Date(iso).toLocaleDateString(lang === 'vi' ? 'vi-VN' : 'en-GB');

  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-4xl gap-6">
        <h1 className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('myOrders')}</h1>
        {state.status === 'loading' && <Loading />}
        {state.status === 'error' && <Failed notFound={false} retry={retry} />}
        {state.status === 'ready' && state.items.length === 0 && (
          <div className="grid justify-items-start gap-4">
            <p>{t('noOrders')}</p>
            <Link to="/products" className={BUTTON}>{t('shopNow')}</Link>
          </div>)}
        {state.status === 'ready' && state.items.length > 0 && (
          <>
            <ul className="grid">
              {state.items.map((o) => (
                <li key={o.order_no} data-testid="order-row" className="border-b border-line">
                  <Link to={`/orders/${encodeURIComponent(o.order_no)}`} className="grid min-h-11 gap-1 py-4 sm:grid-cols-[1fr_auto] sm:items-center">
                    <span className="grid gap-1">
                      <span className={LABEL}>{o.order_no}</span>
                      <span className="text-sm text-muted">{date(o.created_at)} · {t('itemCount').replace('{n}', String(o.item_count))} · {t(statusLabel(o.status))}</span>
                    </span>
                    <Price min={o.total_amount} max={o.total_amount} className="font-semibold" />
                  </Link>
                </li>))}
            </ul>
            <LoadMore state={state} onClick={loadMore} />
          </>)}
      </div>
    </main>
  );
}
