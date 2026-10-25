// TASK:WEB-01: shared catalog UI pieces and the abortable loader every screen uses (restyled for TASK:WEB-01b).
import { useEffect, useRef, useState, type DependencyList } from 'react';
import { Link } from 'react-router';

import { useLang } from '../i18n';
import { ChevronLeft, ChevronRight } from '../ui/icons';
import { ApiError, imageUrl, type ImageRef, type ProductSummary } from './api';

export type Load<T> = { status: 'loading' } | { status: 'ready'; data: T } | { status: 'error'; notFound: boolean };

/** Runs `load` on mount and when deps change; the previous request is aborted so a late response never lands. */
export function useLoad<T>(load: (signal: AbortSignal) => Promise<T>, deps: DependencyList): [Load<T>, () => void] {
  const [state, setState] = useState<Load<T>>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    load(controller.signal)
      .then((data) => { if (!controller.signal.aborted) setState({ status: 'ready', data }); })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'error', notFound: error instanceof ApiError && error.status === 404 });
      });
    return () => controller.abort();
  }, [...deps, attempt]);
  return [state, () => setAttempt((n) => n + 1)];
}

export type Paged<T, H> =
  | { status: 'loading' }
  | { status: 'error'; error: unknown }
  | { status: 'ready'; items: T[]; cursor: string | null; head: H; more: 'idle' | 'loading' | 'error' };
export type PageResult<T> = { items: T[]; cursor: string | null };

/**
 * First page + "load more" by cursor. A new `key` (filters, slug) aborts both requests and drops any page that still
 * arrives for the old key, so pages of two keys never mix (spec section 3). Shared by listing and collection.
 */
export function usePaged<T, H>(first: (signal: AbortSignal) => Promise<PageResult<T> & { head: H }>,
                               next: (cursor: string, signal: AbortSignal) => Promise<PageResult<T>>, key: string) {
  const [state, setState] = useState<Paged<T, H>>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const current = useRef(key);
  const more = useRef<AbortController | null>(null);
  useEffect(() => {
    current.current = key;
    more.current?.abort();
    const controller = new AbortController();
    setState({ status: 'loading' });
    first(controller.signal)
      .then((page) => { if (!controller.signal.aborted) setState({ status: 'ready', ...page, more: 'idle' }); })
      .catch((error: unknown) => { if (!controller.signal.aborted) setState({ status: 'error', error }); });
    return () => controller.abort();
  }, [key, attempt]);

  function loadMore() {
    if (state.status !== 'ready' || !state.cursor) return;
    const asked = key;
    const controller = new AbortController();
    more.current = controller;
    setState({ ...state, more: 'loading' });
    next(state.cursor, controller.signal)
      .then((page) => {
        if (current.current !== asked) return;
        setState((s) => s.status === 'ready' ? { ...s, items: [...s.items, ...page.items], cursor: page.cursor, more: 'idle' } : s);
      })
      .catch(() => {
        if (!controller.signal.aborted && current.current === asked) setState((s) => s.status === 'ready' ? { ...s, more: 'error' } : s);
      });
  }
  return { state, loadMore, retry: () => setAttempt((n) => n + 1) };
}

/** "Load more" button with its own error/retry; rendered only while a next cursor exists. */
export function LoadMore({ state, onClick }: { state: { cursor: string | null; more: 'idle' | 'loading' | 'error' }; onClick: () => void }) {
  const { t } = useLang();
  if (!state.cursor) return null;
  return (
    <div className="mt-10 grid justify-items-center gap-2">
      {state.more === 'error' && <p aria-live="polite">{t('loadError')}</p>}
      <button type="button" onClick={onClick} disabled={state.more === 'loading'} className={BUTTON_OUTLINE}>
        {state.more === 'error' ? t('retry') : t('loadMore')}
      </button>
    </div>
  );
}

export const LABEL = 'text-sm font-semibold uppercase tracking-[0.02em]';
export const BUTTON = `${LABEL} inline-flex min-h-11 items-center justify-center bg-fg px-6 text-bg disabled:opacity-50`;
export const BUTTON_OUTLINE = `${LABEL} inline-flex min-h-11 items-center justify-center border border-fg px-8 disabled:opacity-50`;
export const PAGE = 'mx-auto w-full max-w-screen-2xl px-4 py-8 lg:px-8';

const VND = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' });
/** The same VND text as Price, for places that need a string (a button label). */
export const formatVnd = (amount: number) => VND.format(amount);
/** Server amounts only; no arithmetic on the client. */
export function Price({ min, max, className = '' }: { min: number; max: number; className?: string }) {
  return (
    <span data-testid="price" className={`tabular-nums ${className}`}>
      {min === max ? VND.format(min) : `${VND.format(min)} – ${VND.format(max)}`}
    </span>
  );
}

const FRAME = 'aspect-[3/4] w-full';
/** Fixed 3:4 frame (no layout shift); a missing or broken image keeps its alt on a placeholder. */
export function CatalogImage({ assetId, alt, kind = 'thumb', className = FRAME, eager = false }: {
  assetId: string | null | undefined; alt: string; kind?: 'thumb' | 'image'; className?: string; eager?: boolean;
}) {
  const { t } = useLang();
  const [failed, setFailed] = useState(false);
  if (!assetId || failed)
    return (
      <div className={`${className} grid place-items-center bg-soft text-xs text-muted`} role="img" aria-label={alt || t('imageMissing')}>
        {t('imageMissing')}
      </div>
    );
  return <img src={imageUrl(assetId, kind)} alt={alt} loading={eager ? 'eager' : 'lazy'} onError={() => setFailed(true)}
    className={`${className} bg-soft object-cover`} />;
}

export function imageAlt(image: ImageRef | null, pick: (vi: string, en: string) => string, fallback: string) {
  return image ? pick(image.alt_vi, image.alt_en) : fallback;
}

export function ProductCard({ product, className }: { product: ProductSummary; className?: string }) {
  const { pick } = useLang();
  const name = pick(product.name_vi, product.name_en);
  return (
    <li className={className}>
      <Link to={`/products/${product.id}`} className="group grid gap-2">
        <div className="overflow-hidden">
          <CatalogImage assetId={product.image?.asset_id} alt={imageAlt(product.image, pick, name)}
            className={`${FRAME} transition duration-200 group-hover:scale-[1.03] group-focus-visible:scale-[1.03]`} />
        </div>
        <span className="text-xs font-semibold uppercase tracking-[0.02em]">{name}</span>
        <Price min={product.min_price} max={product.max_price} className="text-sm" />
      </Link>
    </li>
  );
}

/** Mobile is always two columns; from lg the viewer picks 2 or 4 (listing). */
export function ProductGrid({ products, label, columns = 4 }: { products: ProductSummary[]; label: string; columns?: 2 | 4 }) {
  return (
    <ul aria-label={label} className={`grid grid-cols-2 gap-x-2 gap-y-8 lg:gap-x-4 ${columns === 2 ? 'lg:grid-cols-2' : 'lg:grid-cols-4'}`}>
      {products.map((p) => <ProductCard key={p.id} product={p} />)}
    </ul>
  );
}

/** Horizontal scroll-snap rail; desktop arrows scroll by most of a viewport and disable at either end. */
export function ProductRail({ products, label }: { products: ProductSummary[]; label: string }) {
  const { t } = useLang();
  const rail = useRef<HTMLUListElement>(null);
  const [ends, setEnds] = useState({ start: true, end: true });
  const measure = () => {
    const el = rail.current;
    if (!el) return;
    setEnds({ start: el.scrollLeft <= 0, end: el.scrollLeft + el.clientWidth >= el.scrollWidth - 1 });
  };
  useEffect(measure, [products]);
  const scroll = (direction: 1 | -1) => rail.current?.scrollBy?.({ left: direction * rail.current.clientWidth * 0.8, behavior: 'smooth' });
  return (
    <div className="relative">
      <ul ref={rail} aria-label={label} onScroll={measure}
        className="flex snap-x snap-mandatory gap-2 overflow-x-auto pb-2 lg:gap-4 [scrollbar-width:none]">
        {products.map((p) => (
          <ProductCard key={p.id} product={p} className="w-[45%] shrink-0 snap-start sm:w-[30%] lg:w-[22%]" />
        ))}
      </ul>
      <div className="mt-4 hidden justify-end gap-2 lg:flex">
        <button type="button" aria-label={t('previous')} disabled={ends.start} onClick={() => scroll(-1)}
          className="grid size-11 place-items-center border border-fg disabled:border-line disabled:text-line"><ChevronLeft /></button>
        <button type="button" aria-label={t('next')} disabled={ends.end} onClick={() => scroll(1)}
          className="grid size-11 place-items-center border border-fg disabled:border-line disabled:text-line"><ChevronRight /></button>
      </div>
    </div>
  );
}

/** Skeleton grid of the right size so the page does not jump; the status text is for screen readers. */
export function Loading() {
  const { t } = useLang();
  return (
    <div role="status" className="grid grid-cols-2 gap-x-2 gap-y-8 lg:grid-cols-4 lg:gap-x-4">
      <span className="sr-only">{t('loading')}</span>
      {[0, 1, 2, 3].map((i) => <div key={i} aria-hidden="true" className="aspect-[3/4] animate-pulse bg-soft" />)}
    </div>
  );
}

export function NotFound() {
  const { t } = useLang();
  return (
    <section className="grid justify-items-start gap-4 py-16">
      <p className="text-2xl font-bold uppercase tracking-[-0.03em]">{t('notFound')}</p>
      <Link to="/" className={BUTTON}>{t('backHome')}</Link>
    </section>
  );
}

/** Error state: 404 is "not found", anything else offers a retry of the same safe GET. */
export function Failed({ notFound, retry }: { notFound: boolean; retry: () => void }) {
  const { t } = useLang();
  if (notFound) return <NotFound />;
  return (
    <section className="grid justify-items-start gap-3 py-8" aria-live="polite">
      <p>{t('loadError')}</p>
      <button type="button" onClick={retry} className={BUTTON_OUTLINE}>{t('retry')}</button>
    </section>
  );
}
