// TASK:WEB-01 SF-03, TASK:WEB-01b: product detail — gallery plus a sticky panel with colour swatches, sizes, a
// size-guide drawer and stock (spec 3.5). Stock is a labeled reference; nothing is reserved here.
import { useState } from 'react';
import { Link, useParams } from 'react-router';

import { useLang } from '../i18n';
import { Drawer } from '../ui/Drawer';
import { ChevronDown } from '../ui/icons';
import { getProduct, type Product, type Variant } from './api';
import { AddToCart } from '../cart/AddToCart';
import { Gallery } from './Gallery';
import { CatalogImage, Failed, LABEL, Loading, PAGE, Price, useLoad } from './ui';

export function ProductPage() {
  const { id = '' } = useParams();
  const { lang } = useLang();
  const [state, retry] = useLoad((signal) => getProduct(id, lang, signal), [id, lang]);
  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      {state.status === 'loading' && <Loading />}
      {state.status === 'error' && <Failed notFound={state.notFound} retry={retry} />}
      {state.status === 'ready' && <ProductDetail key={state.data.id} product={state.data} />}
    </main>
  );
}

const unique = (values: string[]) => [...new Set(values)];
const desktop = () => window.matchMedia?.('(min-width: 1024px)')?.matches ?? true;

function ProductDetail({ product }: { product: Product }) {
  const { t, pick } = useLang();
  const sizes = unique(product.variants.map((v) => v.size));
  const colors = unique(product.variants.map((v) => v.color));
  const [size, setSize] = useState(sizes.length === 1 ? sizes[0] : '');
  const [color, setColor] = useState(colors.length === 1 ? colors[0] : '');
  const [guide, setGuide] = useState(false);
  const chosen = product.variants.find((v) => v.size === size && v.color === color);
  const name = pick(product.name_vi, product.name_en);
  const images = [...product.images].sort((a, b) => a.sort_order - b.sort_order);
  // Exact match on the variant colour, as in WEB-01 (Review Focus 4): no case folding.
  const swatch = (c: string) => images.find((i) => i.variant_color === c);
  const exists = (s: string, c: string) => product.variants.some((v) => v.size === s && v.color === c);

  return (
    <article className="grid gap-8 lg:grid-cols-[1.4fr_1fr] lg:gap-12">
      <Gallery images={images.map((i) => ({ assetId: i.asset_id, alt: pick(i.alt_vi, i.alt_en) || name, color: i.variant_color }))}
        fallbackAlt={name} focusColor={color} />
      <div data-testid="product-info" className="grid content-start gap-6 self-start lg:sticky lg:top-20">
        <div className="grid gap-2">
          <Link to={`/products?category_id=${product.category.id}`} className="text-xs font-semibold tracking-[0.02em] text-muted uppercase hover:underline">
            {product.category.name}
          </Link>
          <h1 className="text-2xl font-bold tracking-[-0.02em] break-words uppercase lg:text-3xl">{name}</h1>
          {chosen ? <Price min={chosen.effective_price} max={chosen.effective_price} className="text-lg" />
            : <Price min={product.min_price} max={product.max_price} className="text-lg" />}
        </div>

        <fieldset>
          <legend className={`${LABEL} mb-2`}>{color ? `${t('color')}: ${color}` : t('color')}</legend>
          <div className="flex flex-wrap gap-2">
            {colors.map((c) => {
              const image = swatch(c);
              return (
                <label key={c} className={`cursor-pointer border has-checked:outline-2 has-checked:outline-offset-2 has-checked:outline-fg has-focus-visible:outline-2 has-focus-visible:outline-focus ${image ? 'w-12 border-transparent' : 'min-h-11 border-line px-4 has-checked:border-fg'} grid place-items-center`}>
                  <input type="radio" name="color" value={c} checked={color === c} className="sr-only" onChange={() => {
                    setColor(c);
                    if (size && !exists(size, c)) setSize('');
                  }} />
                  {image ? <CatalogImage assetId={image.asset_id} alt={c} /> : <span className="text-sm">{c}</span>}
                </label>
              );
            })}
          </div>
        </fieldset>

        <fieldset className="relative">
          <legend className={`${LABEL} mb-2 flex min-h-11 items-center`}>{t('size')}</legend>
          {product.size_guide && (
            <button type="button" onClick={() => setGuide(true)}
              className={`${LABEL} absolute top-0 right-0 min-h-11 underline underline-offset-4`}>{t('sizeGuide')}</button>
          )}
          <div className="flex flex-wrap gap-2">
            {sizes.map((s) => (
              // Every size stays selectable (WEB-01 review 1); one that does not exist in the chosen colour is struck through.
              <label key={s} className={`grid min-h-11 min-w-11 cursor-pointer place-items-center border px-3 has-checked:border-fg has-checked:bg-fg has-checked:text-bg has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-focus ${color && !exists(s, color) ? 'border-line text-muted line-through' : 'border-fg'}`}>
                <input type="radio" name="size" value={s} checked={size === s} className="sr-only" onChange={() => {
                  setSize(s);
                  if (color && !exists(s, color)) setColor('');
                }} />
                <span className="text-sm font-semibold">{s}</span>
              </label>
            ))}
          </div>
        </fieldset>

        <div className="grid gap-1">
          <p data-testid="stock" aria-live="polite" className="font-semibold">{chosen ? <Stock variant={chosen} /> : t('chooseVariant')}</p>
          <p className="text-sm text-muted">{t('stockNote')}</p>
        </div>

        <AddToCart product={product} variant={chosen} />

        <details open={desktop()} className="group border-t border-fg pt-4">
          <summary className={`${LABEL} flex min-h-11 cursor-pointer list-none items-center justify-between`}>
            {t('description')}<ChevronDown className="size-5 transition group-open:rotate-180" />
          </summary>
          <div className="pt-2 text-sm leading-relaxed" dangerouslySetInnerHTML={{ __html: pick(product.description_vi, product.description_en) }} />
        </details>
      </div>

      {product.size_guide && (
        <Drawer open={guide} onClose={() => setGuide(false)} title={t('sizeGuide')}>
          <div className="mb-4 text-sm" dangerouslySetInnerHTML={{ __html: product.size_guide.guideline_html }} />
          <table className="w-full border-collapse text-sm">
            <thead><tr>{product.size_guide.table.columns.map((c) => <th key={c} scope="col" className="border-b border-fg py-2 text-left">{c}</th>)}</tr></thead>
            <tbody>{product.size_guide.table.rows.map((row, i) => <tr key={i}>{row.map((cell, j) => <td key={j} className="border-b border-line py-2">{cell}</td>)}</tr>)}</tbody>
          </table>
        </Drawer>
      )}
    </article>
  );
}

function Stock({ variant }: { variant: Variant }) {
  const { t } = useLang();
  if (variant.available === null) return <>{t('unknownStock')}</>;
  return <>{variant.available > 0 ? t('inStock') : t('outOfStock')}</>;
}
