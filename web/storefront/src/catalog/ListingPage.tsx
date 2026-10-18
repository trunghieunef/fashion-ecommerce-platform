// TASK:WEB-01 SF-02: listing/search; the URL holds the filters, a filter change resets the cursor and drops late pages.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router';

import { useLang, type StringKey } from '../i18n';
import { ApiError, FILTER_KEYS, listBrands, listCategories, listProducts, type Category, type Filters, type ProductSummary } from './api';
import { Failed, Loading, ProductGrid, useLoad } from './ui';

type Listing =
  | { status: 'loading' }
  | { status: 'error'; invalid: string[] | null }
  | { status: 'ready'; items: ProductSummary[]; cursor: string | null; more: 'idle' | 'loading' | 'error' };

function filtersOf(params: URLSearchParams): Filters {
  const filters: Filters = {};
  for (const key of FILTER_KEYS) {
    const value = params.get(key)?.trim();
    if (value) filters[key] = value;
  }
  return filters;
}

export function ListingPage() {
  const { t, pick } = useLang();
  const [params, setParams] = useSearchParams();
  const filters = filtersOf(params);
  const key = JSON.stringify(filters);
  const [state, setState] = useState<Listing>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  // The filter set a page belongs to; a "load more" answer for an older set is discarded (spec section 3).
  const current = useRef(key);
  const more = useRef<AbortController | null>(null);

  useEffect(() => {
    current.current = key;
    more.current?.abort();
    const controller = new AbortController();
    setState({ status: 'loading' });
    listProducts(filters, undefined, controller.signal)
      .then((page) => { if (!controller.signal.aborted) setState({ status: 'ready', items: page.items, cursor: page.next_cursor, more: 'idle' }); })
      .catch((error: unknown) => {
        // A 400 (bad filter in a shared/edited URL) is not retried: the same GET fails again (review 2).
        if (!controller.signal.aborted)
          setState({ status: 'error', invalid: error instanceof ApiError && error.status === 400 ? error.fields : null });
      });
    return () => controller.abort();
  }, [key, attempt]);

  function loadMore() {
    if (state.status !== 'ready' || !state.cursor) return;
    const asked = key;
    const controller = new AbortController();
    more.current = controller;
    setState({ ...state, more: 'loading' });
    listProducts(filters, state.cursor, controller.signal)
      .then((page) => {
        if (current.current !== asked) return;
        setState((s) => s.status === 'ready' ? { status: 'ready', items: [...s.items, ...page.items], cursor: page.next_cursor, more: 'idle' } : s);
      })
      .catch(() => {
        if (!controller.signal.aborted && current.current === asked) setState((s) => s.status === 'ready' ? { ...s, more: 'error' } : s);
      });
  }

  const searching = Boolean(filters.q);
  const filtered = Object.keys(filters).some((k) => k !== 'sort');
  return (
    <main className="page">
      <h1>{searching ? `${t('search')}: ${filters.q}` : t('products')}</h1>
      <FilterForm filters={filters} onApply={(next) => setParams(next)} pick={pick} />
      {state.status === 'loading' && <Loading />}
      {state.status === 'error' && state.invalid === null && <Failed notFound={false} retry={() => setAttempt((n) => n + 1)} />}
      {state.status === 'error' && state.invalid !== null && (
        <section className="state" aria-live="polite">
          <p>{t('invalidFilter')}{state.invalid.length > 0 && `: ${state.invalid.map((f) => t(LABELS[f] ?? 'filters')).join(', ')}`}</p>
          <button type="button" onClick={() => setParams(filters.q ? { q: filters.q } : {})}>{t('clear')}</button>
        </section>
      )}
      {state.status === 'ready' && state.items.length === 0 && <p aria-live="polite">{filtered ? t('noResults') : t('empty')}</p>}
      {state.status === 'ready' && state.items.length > 0 && (
        <>
          <ProductGrid products={state.items} label={t('productList')} />
          {state.cursor && (
            <div className="more">
              {state.more === 'error' && <p aria-live="polite">{t('loadError')}</p>}
              <button type="button" onClick={loadMore} disabled={state.more === 'loading'}>
                {state.more === 'error' ? t('retry') : t('loadMore')}
              </button>
            </div>
          )}
        </>
      )}
    </main>
  );
}

/** errors[].field of a 400 -> the label the user sees in the form. */
const LABELS: Record<string, StringKey> = {
  q: 'query', category_id: 'category', brand_id: 'brand', size: 'size', color: 'color', tag: 'tag',
  min_price: 'minPrice', max_price: 'maxPrice', sort: 'sort',
};

function FilterForm({ filters, onApply, pick }: {
  filters: Filters;
  onApply: (next: Record<string, string>) => void;
  pick: (vi: string, en: string) => string;
}) {
  const { t } = useLang();
  // Option lists are optional helpers: if they fail the selects are simply not offered.
  const [categories] = useLoad(listCategories, []);
  const [brands] = useLoad(listBrands, []);
  const [priceError, setPriceError] = useState(false);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next: Record<string, string> = {};
    new FormData(event.currentTarget).forEach((value, name) => {
      const text = String(value).trim();
      if (text) next[name] = text;
    });
    const priceInverted = next.min_price && next.max_price && Number(next.min_price) > Number(next.max_price);
    setPriceError(Boolean(priceInverted));
    if (!priceInverted) onApply(next);
  }
  const flat = (list: Category[]): Category[] => list.flatMap((c) => [c, ...c.children.map((child) => ({ ...child, name_vi: `— ${child.name_vi}`, name_en: `— ${child.name_en}` }))]);

  return (
    <form className="filters" onSubmit={submit} aria-label={t('filters')} key={JSON.stringify(filters)}>
      {filters.q && <input type="hidden" name="q" value={filters.q} />}
      {filters.tag && <input type="hidden" name="tag" value={filters.tag} />}
      {categories.status === 'ready' && (
        <label>{t('category')}
          <select name="category_id" defaultValue={filters.category_id ?? ''}>
            <option value="">{t('all')}</option>
            {flat(categories.data).map((c) => <option key={c.id} value={c.id}>{pick(c.name_vi, c.name_en)}</option>)}
          </select>
        </label>
      )}
      {brands.status === 'ready' && brands.data.length > 0 && (
        <label>{t('brand')}
          <select name="brand_id" defaultValue={filters.brand_id ?? ''}>
            <option value="">{t('all')}</option>
            {brands.data.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </label>
      )}
      <label>{t('size')}<input name="size" defaultValue={filters.size ?? ''} /></label>
      <label>{t('color')}<input name="color" defaultValue={filters.color ?? ''} /></label>
      <label>{t('minPrice')}<input name="min_price" type="number" min="0" step="1" inputMode="numeric" defaultValue={filters.min_price ?? ''}
        aria-invalid={priceError} aria-describedby={priceError ? 'price-error' : undefined} /></label>
      <label>{t('maxPrice')}<input name="max_price" type="number" min="0" step="1" inputMode="numeric" defaultValue={filters.max_price ?? ''}
        aria-invalid={priceError} aria-describedby={priceError ? 'price-error' : undefined} /></label>
      {priceError && <p id="price-error" role="alert" className="field-error">{t('priceOrder')}</p>}
      <label>{t('sort')}
        <select name="sort" defaultValue={filters.sort ?? 'NEWEST'}>
          <option value="NEWEST">{t('sortNewest')}</option>
          <option value="BEST_SELLING">{t('sortBest')}</option>
          <option value="PRICE_ASC">{t('sortPriceAsc')}</option>
          <option value="PRICE_DESC">{t('sortPriceDesc')}</option>
        </select>
      </label>
      <div className="filter-actions">
        <button type="submit">{t('apply')}</button>
        <button type="button" onClick={() => onApply(filters.q ? { q: filters.q } : {})}>{t('clear')}</button>
      </div>
    </form>
  );
}
