// TASK:WEB-01b: listing filters in a drawer (spec 3.3). Every change applies at once through the URL, so the WEB-01
// rules (drop the cursor, abort the old request, never mix pages) hold unchanged. Selects apply on change; text and
// number fields on blur or Enter. Size and colour are free text: the catalog API has no list of them.
import { useEffect, useState, type KeyboardEvent } from 'react';

import { useLang, type StringKey } from '../i18n';
import { Drawer } from '../ui/Drawer';
import type { Brand, Category, Filters } from './api';
import { BUTTON, BUTTON_OUTLINE, LABEL, type Load } from './ui';

/** Depth-first list with children marked, for one native select. */
export const flatCategories = (list: Category[]): Category[] =>
  list.flatMap((c) => [c, ...c.children.map((child) => ({ ...child, name_vi: `— ${child.name_vi}`, name_en: `— ${child.name_en}` }))]);

const FIELD = 'min-h-11 w-full border border-line bg-bg px-3 aria-[invalid=true]:border-danger';

/**
 * A text/number filter that stays mounted while the URL changes (final review: re-keying the form destroyed the field
 * Tab had just moved to). It shows what the user types and resyncs when the applied URL value changes.
 */
function TextFilter({ name, label, applied, numeric, invalid, onApply }: {
  name: string; label: string; applied: string; numeric: boolean; invalid: boolean; onApply: (value: string) => void;
}) {
  const [value, setValue] = useState(applied);
  useEffect(() => setValue(applied), [applied]);
  return (
    <label className="grid gap-1">
      <span className={LABEL}>{label}</span>
      <input name={name} value={value} className={FIELD} onChange={(e) => setValue(e.currentTarget.value)}
        {...(numeric ? { type: 'number', min: 0, step: 1, inputMode: 'numeric' as const } : {})}
        {...(numeric ? { 'aria-invalid': invalid, 'aria-describedby': invalid ? 'price-error' : undefined } : {})}
        onBlur={(e) => onApply(e.currentTarget.value)}
        onKeyDown={(e: KeyboardEvent<HTMLInputElement>) => { if (e.key === 'Enter') onApply(e.currentTarget.value); }} />
    </label>
  );
}

export function FilterDrawer({ open, onClose, filters, onChange, categories, brands }: {
  open: boolean;
  onClose: () => void;
  filters: Filters;
  onChange: (next: Filters) => void;
  categories: Load<Category[]>;
  brands: Load<Brand[]>;
}) {
  const { t, pick } = useLang();
  const [priceError, setPriceError] = useState(false);

  function set(name: keyof Filters, raw: string) {
    const value = raw.trim();
    if ((filters[name] ?? '') === value) return;
    const next: Filters = { ...filters, [name]: value };
    if (!value) delete next[name];
    if (name === 'min_price' || name === 'max_price') {
      const inverted = next.min_price && next.max_price && Number(next.min_price) > Number(next.max_price);
      setPriceError(Boolean(inverted));
      if (inverted) return;
    }
    onChange(next);
  }
  const text = (name: keyof Filters, label: StringKey, numeric = false) => (
    <TextFilter name={name} label={t(label)} applied={filters[name] ?? ''} numeric={numeric} invalid={numeric && priceError}
      onApply={(value) => set(name, value)} />
  );

  return (
    <Drawer open={open} onClose={onClose} title={t('filters')}>
      <div className="grid gap-5">
        {categories.status === 'ready' && (
          <label className="grid gap-1">
            <span className={LABEL}>{t('category')}</span>
            <select name="category_id" value={filters.category_id ?? ''} className={FIELD} onChange={(e) => set('category_id', e.currentTarget.value)}>
              <option value="">{t('all')}</option>
              {flatCategories(categories.data).map((c) => <option key={c.id} value={c.id}>{pick(c.name_vi, c.name_en)}</option>)}
            </select>
          </label>
        )}
        {brands.status === 'ready' && brands.data.length > 0 && (
          <label className="grid gap-1">
            <span className={LABEL}>{t('brand')}</span>
            <select name="brand_id" value={filters.brand_id ?? ''} className={FIELD} onChange={(e) => set('brand_id', e.currentTarget.value)}>
              <option value="">{t('all')}</option>
              {brands.data.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </label>
        )}
        {text('size', 'size')}
        {text('color', 'color')}
        <div className="grid grid-cols-2 gap-3">
          {text('min_price', 'minPrice', true)}
          {text('max_price', 'maxPrice', true)}
          {priceError && <p id="price-error" role="alert" className="col-span-2 text-sm text-danger">{t('priceOrder')}</p>}
        </div>
        <div className="flex gap-2 pt-2">
          <button type="button" className={`${BUTTON_OUTLINE} flex-1 px-4`}
            onClick={() => onChange({ ...(filters.q ? { q: filters.q } : {}), ...(filters.sort ? { sort: filters.sort } : {}) })}>
            {t('clear')}
          </button>
          <button type="button" className={`${BUTTON} flex-[2]`} onClick={onClose}>{t('showResults')}</button>
        </div>
      </div>
    </Drawer>
  );
}
