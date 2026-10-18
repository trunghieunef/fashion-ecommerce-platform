// TASK:WEB-01: shared catalog UI pieces and the abortable loader every screen uses.
import { useEffect, useRef, useState, type DependencyList } from 'react';
import { Link } from 'react-router';

import { useLang } from '../i18n';
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
    <div className="more">
      {state.more === 'error' && <p aria-live="polite">{t('loadError')}</p>}
      <button type="button" onClick={onClick} disabled={state.more === 'loading'}>
        {state.more === 'error' ? t('retry') : t('loadMore')}
      </button>
    </div>
  );
}

const VND = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' });
/** Server amounts only; no arithmetic on the client. */
export function Price({ min, max }: { min: number; max: number }) {
  return <span className="price">{min === max ? VND.format(min) : `${VND.format(min)} – ${VND.format(max)}`}</span>;
}

export function CatalogImage({ assetId, alt, kind = 'thumb' }: { assetId: string | null | undefined; alt: string; kind?: 'thumb' | 'image' }) {
  const { t } = useLang();
  const [failed, setFailed] = useState(false);
  if (!assetId || failed)
    return <div className="image-placeholder" role="img" aria-label={alt || t('imageMissing')}>{t('imageMissing')}</div>;
  return <img src={imageUrl(assetId, kind)} alt={alt} loading="lazy" onError={() => setFailed(true)} />;
}

export function imageAlt(image: ImageRef | null, pick: (vi: string, en: string) => string, fallback: string) {
  return image ? pick(image.alt_vi, image.alt_en) : fallback;
}

export function ProductCard({ product }: { product: ProductSummary }) {
  const { pick } = useLang();
  const name = pick(product.name_vi, product.name_en);
  return (
    <li className="product-card">
      <Link to={`/products/${product.id}`}>
        <CatalogImage assetId={product.image?.asset_id} alt={imageAlt(product.image, pick, name)} />
        <span className="product-name">{name}</span>
      </Link>
      <Price min={product.min_price} max={product.max_price} />
    </li>
  );
}

export function ProductGrid({ products, label }: { products: ProductSummary[]; label: string }) {
  return (
    <ul className="product-grid" aria-label={label}>
      {products.map((p) => <ProductCard key={p.id} product={p} />)}
    </ul>
  );
}

export function Loading() {
  const { t } = useLang();
  return <p role="status">{t('loading')}</p>;
}

export function NotFound() {
  const { t } = useLang();
  return (
    <section className="state">
      <p>{t('notFound')}</p>
      <Link to="/">{t('backHome')}</Link>
    </section>
  );
}

/** Error state: 404 is "not found", anything else offers a retry of the same safe GET. */
export function Failed({ notFound, retry }: { notFound: boolean; retry: () => void }) {
  const { t } = useLang();
  if (notFound) return <NotFound />;
  return (
    <section className="state" aria-live="polite">
      <p>{t('loadError')}</p>
      <button type="button" onClick={retry}>{t('retry')}</button>
    </section>
  );
}
