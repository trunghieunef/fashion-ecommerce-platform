// TASK:WEB-01 SF-03: product detail. Stock is a labeled reference; nothing is reserved here.
import { useRef, useState } from 'react';
import { useParams } from 'react-router';

import { useLang } from '../i18n';
import { getProduct, imageUrl, type Product, type Variant } from './api';
import { CatalogImage, Failed, Loading, Price, useLoad } from './ui';

export function ProductPage() {
  const { id = '' } = useParams();
  const { lang } = useLang();
  const [state, retry] = useLoad((signal) => getProduct(id, lang, signal), [id, lang]);
  return (
    <main className="page">
      {state.status === 'loading' && <Loading />}
      {state.status === 'error' && <Failed notFound={state.notFound} retry={retry} />}
      {state.status === 'ready' && <ProductDetail key={state.data.id} product={state.data} />}
    </main>
  );
}

const unique = (values: string[]) => [...new Set(values)];

function ProductDetail({ product }: { product: Product }) {
  const { t, pick } = useLang();
  const sizes = unique(product.variants.map((v) => v.size));
  const colors = unique(product.variants.map((v) => v.color));
  const [size, setSize] = useState(sizes.length === 1 ? sizes[0] : '');
  const [color, setColor] = useState(colors.length === 1 ? colors[0] : '');
  const chosen = product.variants.find((v) => v.size === size && v.color === color);
  const name = pick(product.name_vi, product.name_en);
  const images = [...product.images].sort((a, b) => a.sort_order - b.sort_order);
  const shown = images.find((i) => color && i.variant_color === color) ?? images[0];

  return (
    <article className="product">
      <Gallery assetId={shown?.asset_id} alt={shown ? pick(shown.alt_vi, shown.alt_en) : name} />
      <div className="product-info">
        <h1>{name}</h1>
        {chosen ? <Price min={chosen.effective_price} max={chosen.effective_price} /> : <Price min={product.min_price} max={product.max_price} />}
        <fieldset>
          <legend>{t('size')}</legend>
          <Options values={sizes} selected={size} onSelect={setSize} name="size"
            enabled={(s) => product.variants.some((v) => v.size === s && (!color || v.color === color))} />
        </fieldset>
        <fieldset>
          <legend>{t('color')}</legend>
          <Options values={colors} selected={color} onSelect={setColor} name="color"
            enabled={(c) => product.variants.some((v) => v.color === c && (!size || v.size === size))} />
        </fieldset>
        <p aria-live="polite" className="stock">{chosen ? <Stock variant={chosen} /> : t('chooseVariant')}</p>
        <p className="note">{t('stockNote')}</p>
        <div className="description" dangerouslySetInnerHTML={{ __html: pick(product.description_vi, product.description_en) }} />
        {product.size_guide && (
          <section aria-labelledby="size-guide">
            <h2 id="size-guide">{t('sizeGuide')}</h2>
            <div dangerouslySetInnerHTML={{ __html: product.size_guide.guideline_html }} />
            <table>
              <thead><tr>{product.size_guide.table.columns.map((c) => <th key={c} scope="col">{c}</th>)}</tr></thead>
              <tbody>{product.size_guide.table.rows.map((row, i) => <tr key={i}>{row.map((cell, j) => <td key={j}>{cell}</td>)}</tr>)}</tbody>
            </table>
          </section>
        )}
      </div>
    </article>
  );
}

function Stock({ variant }: { variant: Variant }) {
  const { t } = useLang();
  if (variant.available === null) return <>{t('unknownStock')}</>;
  return <>{variant.available > 0 ? t('inStock') : t('outOfStock')}</>;
}

/** Native radio inputs: arrow keys move inside a group, a disabled option has no ACTIVE variant for the other choice. */
function Options({ values, selected, onSelect, name, enabled }: {
  values: string[]; selected: string; onSelect: (v: string) => void; name: string; enabled: (v: string) => boolean;
}) {
  return (
    <div className="options">
      {values.map((value) => (
        <label key={value} className="option">
          <input type="radio" name={name} value={value} checked={selected === value} disabled={!enabled(value)}
            onChange={() => onSelect(value)} />
          <span>{value}</span>
        </label>
      ))}
    </div>
  );
}

function Gallery({ assetId, alt }: { assetId: string | undefined; alt: string }) {
  const { t } = useLang();
  const dialog = useRef<HTMLDialogElement>(null);
  const opener = useRef<HTMLButtonElement>(null);
  if (!assetId) return <div className="gallery"><CatalogImage assetId={null} alt={alt} kind="image" /></div>;
  return (
    <div className="gallery">
      <button type="button" ref={opener} className="zoom" aria-label={t('zoom')} onClick={() => dialog.current?.showModal?.()}>
        <CatalogImage assetId={assetId} alt={alt} kind="image" />
      </button>
      <dialog ref={dialog} aria-label={alt} onClose={() => opener.current?.focus()}>
        <img src={imageUrl(assetId, 'image')} alt={alt} />
        <form method="dialog"><button type="submit">{t('close')}</button></form>
      </dialog>
    </div>
  );
}
