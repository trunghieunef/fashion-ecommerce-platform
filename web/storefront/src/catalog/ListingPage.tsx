// TASK:WEB-01 SF-02, TASK:WEB-01b: listing/search. The URL holds the filters; a filter change resets the cursor and
// drops late pages. WEB-01b adds the toolbar (filter drawer, sort, 2/4 columns) and removable chips (spec 3.3).
import { useState } from 'react';
import { useSearchParams } from 'react-router';

import { useLang, type StringKey } from '../i18n';
import { CloseIcon } from '../ui/icons';
import { ApiError, FILTER_KEYS, listBrands, listCategories, listProducts, type Category, type Filters, type ProductSummary } from './api';
import { FilterDrawer } from './FilterDrawer';
import { BUTTON_OUTLINE, Failed, LABEL, Loading, LoadMore, PAGE, ProductGrid, useLoad, usePaged } from './ui';

function filtersOf(params: URLSearchParams): Filters {
  const filters: Filters = {};
  for (const key of FILTER_KEYS) {
    const value = params.get(key)?.trim();
    if (value) filters[key] = value;
  }
  return filters;
}

/** errors[].field of a 400 and chip keys -> the label the user sees. */
const LABELS: Record<string, StringKey> = {
  q: 'query', category_id: 'category', brand_id: 'brand', size: 'size', color: 'color', tag: 'tag',
  min_price: 'minPrice', max_price: 'maxPrice', sort: 'sort',
};
const NOT_CHIPS = new Set(['q', 'sort']);
const VND = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' });

const GRID_KEY = 'storefront.grid';
function storedColumns(): 2 | 4 {
  try {
    return localStorage.getItem(GRID_KEY) === '2' ? 2 : 4;
  } catch {
    return 4;
  }
}

function findCategory(list: Category[], id: string): Category | undefined {
  for (const c of list) {
    if (c.id === id) return c;
    const child = findCategory(c.children, id);
    if (child) return child;
  }
  return undefined;
}

export function ListingPage() {
  const { t, pick } = useLang();
  const [params, setParams] = useSearchParams();
  const filters = filtersOf(params);
  const [categories] = useLoad(listCategories, []);
  const [brands] = useLoad(listBrands, []);
  const [drawer, setDrawer] = useState(false);
  const [columns, setColumns] = useState<2 | 4>(storedColumns);
  const { state, loadMore, retry } = usePaged<ProductSummary, null>(
    (signal) => listProducts(filters, undefined, signal).then((p) => ({ items: p.items, cursor: p.next_cursor, head: null })),
    (cursor, signal) => listProducts(filters, cursor, signal).then((p) => ({ items: p.items, cursor: p.next_cursor })),
    JSON.stringify(filters));
  // A 400 (bad filter in a shared/edited URL) is not retried: the same GET fails again (review 2).
  const invalid = state.status === 'error' && state.error instanceof ApiError && state.error.status === 400 ? state.error.fields : null;

  const apply = (next: Filters) => setParams(next as Record<string, string>);
  const keep = { ...(filters.q ? { q: filters.q } : {}), ...(filters.sort ? { sort: filters.sort } : {}) };
  const chipKeys = Object.keys(filters).filter((k) => !NOT_CHIPS.has(k)) as (keyof Filters)[];
  const category = filters.category_id && categories.status === 'ready' ? findCategory(categories.data, filters.category_id) : undefined;
  const display = (key: keyof Filters, value: string) => {
    if (key === 'category_id') return category ? pick(category.name_vi, category.name_en) : value;
    if (key === 'brand_id' && brands.status === 'ready') return brands.data.find((b) => b.id === value)?.name ?? value;
    if (key === 'min_price' || key === 'max_price') return Number.isFinite(Number(value)) ? VND.format(Number(value)) : value;
    return value;
  };
  function chooseColumns(next: 2 | 4) {
    setColumns(next);
    try {
      localStorage.setItem(GRID_KEY, String(next));
    } catch {
      // Blocked storage: the choice lasts for this page only.
    }
  }

  const title = filters.q ? `${t('search')}: ${filters.q}` : category ? pick(category.name_vi, category.name_en) : t('allProducts');
  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <h1 className="text-3xl font-bold tracking-[-0.03em] break-words uppercase lg:text-4xl">{title}</h1>
        <div className="flex flex-wrap items-center gap-4">
          <button type="button" className={`${LABEL} min-h-11 underline-offset-4 hover:underline`} onClick={() => setDrawer(true)}>
            {t('filters')}{chipKeys.length > 0 ? ` (${chipKeys.length})` : ''}
          </button>
          <label className="flex items-center gap-2">
            <span className={LABEL}>{t('sort')}</span>
            <select name="sort" value={filters.sort ?? 'NEWEST'} onChange={(e) => apply({ ...filters, sort: e.currentTarget.value })}
              className="min-h-11 border-b border-fg bg-bg text-sm">
              <option value="NEWEST">{t('sortNewest')}</option>
              <option value="BEST_SELLING">{t('sortBest')}</option>
              <option value="PRICE_ASC">{t('sortPriceAsc')}</option>
              <option value="PRICE_DESC">{t('sortPriceDesc')}</option>
            </select>
          </label>
          <div role="radiogroup" aria-label={t('columns')} className="hidden gap-1 lg:flex">
            {([2, 4] as const).map((n) => (
              <label key={n} className="grid size-11 cursor-pointer place-items-center border border-line has-checked:border-fg has-checked:bg-fg has-checked:text-bg">
                <input type="radio" name="columns" value={n} checked={columns === n} onChange={() => chooseColumns(n)} className="sr-only" />
                <span className="text-sm font-semibold">{n}</span>
              </label>
            ))}
          </div>
        </div>
      </div>

      {chipKeys.length > 0 && (
        <ul aria-label={t('activeFilters')} className="mt-4 flex flex-wrap gap-2">
          {chipKeys.map((key) => {
            const label = `${t(LABELS[key] ?? 'filters')}: ${display(key, filters[key]!)}`;
            const next = { ...filters };
            delete next[key];
            return (
              <li key={key}>
                <button type="button" aria-label={`${t('removeFilter')} ${label}`} onClick={() => apply(next)}
                  className="inline-flex min-h-9 max-w-full items-center gap-1 bg-fg px-3 text-left text-xs font-semibold text-bg uppercase">
                  <span className="break-words">{label}</span><CloseIcon className="size-4 shrink-0" />
                </button>
              </li>
            );
          })}
          <li>
            <button type="button" onClick={() => apply(keep)} className={`${LABEL} min-h-9 px-1 underline underline-offset-4`}>{t('clearAll')}</button>
          </li>
        </ul>
      )}

      <FilterDrawer open={drawer} onClose={() => setDrawer(false)} filters={filters} onChange={apply} categories={categories} brands={brands} />

      <div className="mt-8">
        {state.status === 'loading' && <Loading />}
        {state.status === 'error' && invalid === null && <Failed notFound={false} retry={retry} />}
        {state.status === 'error' && invalid !== null && (
          <section data-testid="invalid-filter" className="grid justify-items-start gap-3 py-8" aria-live="polite">
            <p>{t('invalidFilter')}{invalid.length > 0 && `: ${invalid.map((f) => t(LABELS[f] ?? 'filters')).join(', ')}`}</p>
            <button type="button" className={BUTTON_OUTLINE} onClick={() => setParams(filters.q ? { q: filters.q } : {})}>{t('clear')}</button>
          </section>
        )}
        {state.status === 'ready' && state.items.length === 0 && <p aria-live="polite">{chipKeys.length > 0 ? t('noResults') : t('empty')}</p>}
        {state.status === 'ready' && state.items.length > 0 && (
          <>
            <ProductGrid products={state.items} label={t('productList')} columns={columns} />
            <LoadMore state={state} onClick={loadMore} />
          </>
        )}
      </div>
    </main>
  );
}
