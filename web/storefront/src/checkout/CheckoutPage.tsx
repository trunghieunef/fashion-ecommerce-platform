// TASK:WEB-03: /checkout (spec §2–§4). One page: contact, delivery address, COD, note. "See total" asks the server for a
// quote (2 minutes); only a quote that still matches what is typed and has not expired can be confirmed. Nothing is ever
// ordered on its own: an expired, changed or refused quote only brings the visitor back to "See total".
import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router';

import { placeName } from '../account/AddressForm';
import geo from '../account/geo.sample.json';
import * as accountApi from '../auth/api';
import { useAuth } from '../auth/AuthProvider';
import { email, maxLength, phone, type Validator } from '../auth/validate';
import { useCart } from '../cart/CartProvider';
import { BUTTON, formatVnd, LABEL, Loading, PAGE } from '../catalog/ui';
import { HttpError } from '../http';
import { useLang } from '../i18n';
import { ErrorSummary, Field, SelectField, StatusMessage, useForm } from '../ui/form';
import * as api from './api';
import { clearPending, readPending, send, startPending, type Pending } from './submit';
import { Summary } from './Summary';

const FIELDS = ['contact_phone', 'contact_email', 'recipient_name', 'phone', 'province_code', 'ward_code', 'address_line', 'note'] as const;
type Name = (typeof FIELDS)[number];
const ADDRESS_FIELDS = ['recipient_name', 'phone', 'province_code', 'ward_code', 'address_line'] as const;
const NEW = 'new';
// order.yaml: a quote lasts 2 minutes. Timed from its arrival, not from expires_at, so a wrong device clock cannot
// expire it at once; the margin covers the round trip, and the server still answers QUOTE_EXPIRED if we are late.
const QUOTE_HOLD_MS = 115_000;
/** PRICE_CHANGED errors carry `old=..,new=..` per variant_id (order.yaml). */
const prices = (message: string) => message.match(/old=(\d+),new=(\d+)/)?.slice(1).map(Number) ?? null;

const optional = (check: Validator): Validator => (v) => (v.trim() ? check(v) : null);
const upTo = (n: number): Validator => (v) => ([...v.trim()].length > n ? 'errTooLong' : null);
/** order.yaml error fields → form fields (shipping_address.phone → phone, contact.email → contact_email). */
const fieldOf = (field: string): Name | null => {
  const name = field.replace(/^shipping_address\./, '').replace(/^contact\./, 'contact_');
  return (FIELDS as readonly string[]).includes(name) ? (name as Name) : null;
};

export function CheckoutPage() {
  const { t, pick, lang } = useLang();
  const { status, me } = useAuth();
  const member = status === 'member';
  const { cart, loading, reload } = useCart();
  const [saved, setSaved] = useState<accountApi.Address[]>([]);
  const [choice, setChoice] = useState(NEW);
  const useSaved = member && choice !== NEW;
  const form = useForm<Name>(
    Object.fromEntries(FIELDS.map((f) => [f, ''])) as Record<Name, string>,
    {
      contact_phone: phone, contact_email: member ? optional(email) : email, note: upTo(500),
      ...(useSaved ? {} : { recipient_name: maxLength(100), phone, province_code: maxLength(10), ward_code: maxLength(10), address_line: maxLength(255) }),
    });
  const [quoted, setQuoted] = useState<{ quote: api.Quote; request: string; heldUntil: number } | null>(null);
  const [expired, setExpired] = useState(false);
  const [message, setMessage] = useState<{ text: string; toCart?: boolean } | null>(null);
  const [status_, setStatus] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const navigate = useNavigate();
  // A confirmation whose outcome is unknown (lost response, reload) is resent unchanged before anything else (spec §4.1).
  const [resuming, setResuming] = useState(() => readPending() !== null);
  const [retrying, setRetrying] = useState(false);
  const [stuck, setStuck] = useState<Pending | null>(null);

  // Member: saved addresses (the default one chosen) and the account email.
  useEffect(() => {
    if (!member) return;
    accountApi.listAddresses().then((list) => {
      setSaved(list);
      const preferred = list.find((a) => a.is_default) ?? list[0];
      if (preferred) setChoice(preferred.id);
    }, () => undefined);
  }, [member]);
  useEffect(() => { if (me?.email && !form.values.contact_email) form.setValue('contact_email', me.email); }, [me?.email]);

  const v = form.values;
  const chosen = saved.find((a) => a.id === choice);
  const address: api.Address = useSaved && chosen
    ? { recipient_name: chosen.recipient_name, phone: chosen.phone, province_code: chosen.province_code, ward_code: chosen.ward_code, address_line: chosen.address_line }
    : { recipient_name: v.recipient_name.trim(), phone: v.phone.trim(), province_code: v.province_code, ward_code: v.ward_code, address_line: v.address_line.trim() };
  const request: api.QuoteRequest | null = cart && {
    cart_id: cart.cart_id, cart_version: cart.version, method: 'COD', shipping_address: address,
    contact: { phone: v.contact_phone.trim(), email: v.contact_email.trim() || null }, note: v.note.trim() || null,
  };
  // A quote counts only while it matches exactly what is on screen (any edit drops it) and has not expired.
  const current = quoted && request && !expired && quoted.request === JSON.stringify(request) ? quoted.quote : null;

  useEffect(() => {
    if (!quoted) return;
    setExpired(false);
    const timer = setTimeout(() => { setExpired(true); setStatus(t('quoteExpired')); }, quoted.heldUntil - Date.now());
    return () => clearTimeout(timer);
  }, [quoted]);

  useEffect(() => {
    const saved = readPending();
    if (!saved || status === 'unknown' || busy.current) return;
    busy.current = true;
    void place(saved).finally(() => { busy.current = false; setResuming(false); });
  }, [status]);

  if (resuming) return <main id="main" tabIndex={-1} className={PAGE}><p role="status">{t('placingOrder')}</p></main>;
  if (loading && !cart) return <main id="main" tabIndex={-1} className={PAGE}><Loading /></main>;
  if (!cart || cart.items.length === 0) return <Navigate to="/cart" replace />;

  async function getQuote(body: api.QuoteRequest) {
    setMessage(null);
    try {
      const quote = await api.quote(member, body, lang);
      setQuoted({ quote, request: JSON.stringify(body), heldUntil: Date.now() + QUOTE_HOLD_MS });
    } catch (error) {
      quoteFailed(error);
    }
  }

  function quoteFailed(error: unknown) {
    const e = error instanceof HttpError ? error : null;
    if (e?.status === 400 && e.fields.some((f) => fieldOf(f.field))) {
      e.fields.forEach((f) => { const name = fieldOf(f.field); if (name) form.setFieldError(name, t('errInvalid')); });
    } else if (e?.code === 'VERSION_CONFLICT') {
      void reload();
      setMessage({ text: t('cartChanged') });
    } else if (e?.message === 'ITEM_UNAVAILABLE') {
      // cart may not be loaded yet when a stored order is resent right after a reload.
      const skus = e.fields.map((f) => cart?.items.find((i) => i.variant_id === f.field)?.sku ?? f.field);
      setMessage({ text: t('errItemsUnavailable').replace('{skus}', skus.join(', ')), toCart: true });
    } else if (e?.message === 'NO_SHIPPING_RULE') {
      form.setFieldError('province_code', t('errNoShipping'));
    } else if (e?.message === 'CART_EMPTY' || e?.status === 404 || e?.status === 401) {
      void reload();
      setMessage({ text: t('cartChanged'), toCart: true });
    } else if (e?.status === 422) {
      setMessage({ text: t('errCannotOrder') });
    } else {
      setMessage({ text: t('errQuoteFailed') });
    }
    form.refocus();
  }

  async function place(record: Pending) {
    setStuck(null);
    setRetrying(false);
    try {
      const order = await send(member, record, undefined, () => setRetrying(true));
      void reload();
      navigate(`/orders/${encodeURIComponent(order.order_no)}`, { replace: true });
    } catch (error) {
      setRetrying(false);
      await placeFailed(error, record);
    }
  }

  async function placeFailed(error: unknown, record: Pending) {
    const e = error instanceof HttpError ? error : null;
    if (e && (e.status === 0 || e.status === 503)) { setStuck(record); return; } // still unknown: keep the record, same key
    clearPending();
    if (e?.code === 'PRICE_CHANGED') {
      const before = quoted?.quote;
      const lines = e.fields.flatMap((f) => {
        const pair = prices(f.message);
        const item = before?.items.find((i) => i.variant_id === f.field);
        return pair ? [`${item ? `${item.name} ${item.color} · ${item.size}` : f.field}: ${formatVnd(pair[0])} → ${formatVnd(pair[1])}`] : [];
      });
      setQuoted(null);
      if (request) await getQuote(request);
      setMessage({ text: [t('priceChanged'), ...lines].join(' ') });
      form.refocus();
    } else if (e?.code === 'QUOTE_EXPIRED') {
      setExpired(true);
      setStatus(t('quoteExpired'));
    } else if (e?.message === 'INVALID_QUOTE') {
      setQuoted(null);
      if (request) await getQuote(request);
    } else if (e?.code === 'CART_ALREADY_ORDERED' && e.fields[0]?.message) {
      void reload();
      navigate(`/orders/${encodeURIComponent(e.fields[0].message)}`, { replace: true });
    } else if (e?.code === 'VERSION_CONFLICT' || e?.message === 'ITEM_UNAVAILABLE') {
      setQuoted(null);
      quoteFailed(error);
    } else {
      setMessage({ text: t('errOrderFailed') });
      form.refocus();
    }
  }

  const onSubmit = form.submit(async () => {
    if (busy.current || !request) return;
    busy.current = true;
    setPending(true);
    try {
      // Outcome still unknown: the main button resends the same key, never a new one for the same order (spec §4.1).
      if (stuck) await place(stuck);
      else if (!current) await getQuote(request);
      else await place(startPending({ cart_id: request.cart_id, cart_version: current.cart_version, quote_token: current.quote_token }));
    } finally {
      busy.current = false;
      setPending(false);
    }
  });

  const labels: Record<Name, string> = {
    contact_phone: t('phone'), contact_email: t('email'), recipient_name: t('recipient'), phone: t('recipientPhone'),
    province_code: t('province'), ward_code: t('ward'), address_line: t('addressLine'), note: t('secNote'),
  };
  const province = geo.provinces.find((p) => p.code === v.province_code);
  const { error: provinceError, onChange: provinceChange, ...provinceProps } = form.bind('province_code');
  const fieldset = 'grid gap-4 border-t border-fg pt-4';
  const legend = `${LABEL} float-left mb-4 w-full`;

  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-6xl gap-8 lg:grid-cols-[1fr_24rem]">
        <form id="checkout" noValidate onSubmit={onSubmit} className="grid content-start gap-6">
          <div className="grid gap-2">
            <h1 className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('checkout')}</h1>
            {status === 'guest' && (
              <p className="text-sm text-muted">{t('haveAccount')}{' '}
                <Link to={`/login?next=${encodeURIComponent('/checkout')}`} className="underline underline-offset-4">{t('signInForAddresses')}</Link></p>)}
          </div>
          <ErrorSummary errors={form.summary(labels)} focusKey={form.focusKey}
            message={message && (message.toCart ? null : message.text)} />
          {message?.toCart && (
            <div role="alert" className="grid gap-2 border border-danger p-4 text-sm">
              <p className="font-semibold">{message.text}</p>
              <Link to="/cart" className="underline">{t('fixCart')}</Link>
            </div>)}
          <StatusMessage message={status_} />

          <fieldset className={fieldset}>
            <legend className={legend}>1 · {t('secContact')}</legend>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={labels.contact_phone} type="tel" autoComplete="tel" {...form.bind('contact_phone')} />
              <Field label={labels.contact_email} type="email" autoComplete="email" {...form.bind('contact_email')} />
            </div>
          </fieldset>

          <fieldset className={fieldset}>
            <legend className={legend}>2 · {t('secAddress')}</legend>
            {member && saved.length > 0 && (
              <div className="grid gap-2">
                {[...saved.map((a) => ({ id: a.id, text: `${a.recipient_name} · ${a.phone} · ${a.address_line}, ${placeName(pick, a.province_code, a.ward_code)}, ${placeName(pick, a.province_code)}` })),
                  { id: NEW, text: t('otherAddress') }].map((o) => (
                  <label key={o.id} className="flex min-h-11 items-start gap-3 border border-line p-3 text-sm has-[:checked]:border-fg">
                    <input type="radio" name="address" value={o.id} checked={choice === o.id} onChange={() => setChoice(o.id)} className="mt-0.5 size-5" />
                    {o.text}
                  </label>))}
              </div>)}
            {!useSaved && (
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label={labels.recipient_name} autoComplete="name" {...form.bind('recipient_name')} />
                <Field label={labels.phone} type="tel" autoComplete="tel" {...form.bind('phone')} />
                <SelectField label={labels.province_code} placeholder={t('choose')} error={provinceError} {...provinceProps}
                  options={geo.provinces.map((p) => ({ value: p.code, label: pick(p.name_vi, p.name_en) }))}
                  onChange={(e) => { provinceChange(e); form.setValue('ward_code', ''); }} />
                <SelectField label={labels.ward_code} placeholder={t('choose')} {...form.bind('ward_code')}
                  options={(province?.wards ?? []).map((w) => ({ value: w.code, label: pick(w.name_vi, w.name_en) }))} />
                <div className="sm:col-span-2"><Field label={labels.address_line} autoComplete="address-line1" {...form.bind('address_line')} /></div>
                <p className="text-xs text-muted sm:col-span-2">{t('sampleGeo')}</p>
              </div>)}
          </fieldset>

          <fieldset className={fieldset}>
            <legend className={legend}>3 · {t('secMethod')}</legend>
            <label className="flex min-h-11 items-start gap-3 border border-fg p-3 text-sm">
              <input type="radio" name="method" value="COD" checked readOnly className="mt-0.5 size-5" />
              <span><span className="font-semibold">{t('cod')}</span><br /><span className="text-muted">{t('onlineSoon')}</span></span>
            </label>
          </fieldset>

          <fieldset className={fieldset}>
            <legend className={legend}>4 · {t('secNote')}</legend>
            <Field label={labels.note} {...form.bind('note')} />
          </fieldset>
        </form>

        <Summary cartItems={cart.items} quote={current} heldUntil={quoted?.heldUntil ?? null}>
          <button type="submit" form="checkout" disabled={pending} aria-busy={pending || undefined} className={`${BUTTON} w-full`}>
            {stuck ? t('retry') : current ? `${t('placeOrder')} · ${formatVnd(current.totals.total_amount)}` : t('seeTotal')}
          </button>
          {retrying && <p role="status" className="text-sm">{t('retryingOrder')}</p>}
          {stuck && (
            <div role="alert" className="grid gap-2 text-sm">
              <p>{t('orderUnknown')}</p>
              <button type="button" onClick={() => { if (!busy.current) { busy.current = true; void place(stuck).finally(() => { busy.current = false; }); } }}
                className="min-h-11 underline">{t('retry')}</button>
            </div>)}
        </Summary>
      </div>
    </main>
  );
}
