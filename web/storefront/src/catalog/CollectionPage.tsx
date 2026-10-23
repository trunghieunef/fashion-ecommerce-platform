// TASK:WEB-01 SF-01, TASK:WEB-01b: one collection — cover hero, lookbook grid (caption is the alternate text) and
// its products with "load more" (spec 3.4).
import { useParams } from 'react-router';

import { useLang } from '../i18n';
import { ApiError, getCollection, type CollectionDetail, type ProductSummary } from './api';
import { CatalogImage, Failed, Loading, LoadMore, PAGE, ProductGrid, usePaged } from './ui';

const TITLE = 'text-4xl leading-[0.9] font-bold tracking-[-0.04em] break-words uppercase lg:text-6xl';

export function CollectionPage() {
  const { slug = '' } = useParams();
  const { t, pick } = useLang();
  const { state, loadMore, retry } = usePaged<ProductSummary, Pick<CollectionDetail, 'collection' | 'lookbook'>>(
    (signal) => getCollection(slug, undefined, signal)
      .then((d) => ({ items: d.products, cursor: d.next_cursor, head: { collection: d.collection, lookbook: d.lookbook } })),
    (cursor, signal) => getCollection(slug, cursor, signal).then((d) => ({ items: d.products, cursor: d.next_cursor })),
    slug);
  if (state.status === 'loading') return <main id="main" tabIndex={-1} className={PAGE}><Loading /></main>;
  if (state.status === 'error')
    return <main id="main" tabIndex={-1} className={PAGE}><Failed notFound={state.error instanceof ApiError && state.error.status === 404} retry={retry} /></main>;
  const { collection, lookbook } = state.head;
  const products = state.items;
  const name = pick(collection.name_vi, collection.name_en);
  return (
    <main id="main" tabIndex={-1}>
      {collection.cover_asset_id ? (
        <section data-testid="hero" className="relative">
          <CatalogImage assetId={collection.cover_asset_id} alt={name} kind="image" eager className="h-[60vh] w-full lg:h-[75vh]" />
          <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/50 to-transparent text-bg">
            <div className={`${PAGE} pt-24`}><h1 className={TITLE}>{name}</h1></div>
          </div>
        </section>
      ) : (
        <section data-testid="hero" className="bg-soft">
          <div className={`${PAGE} py-16`}><h1 className={TITLE}>{name}</h1></div>
        </section>
      )}

      <div className={PAGE}>
        {lookbook.length > 0 && (
          <section aria-labelledby="lookbook" className="mb-12">
            <h2 id="lookbook" className="mb-4 text-2xl font-bold tracking-[-0.03em] uppercase">{t('lookbook')}</h2>
            <ul aria-label={t('lookbook')} className="grid grid-cols-2 gap-2 lg:grid-cols-3 lg:gap-4">
              {[...lookbook].sort((a, b) => a.sort_order - b.sort_order).map((item) => {
                const caption = pick(item.caption_vi, item.caption_en);
                return (
                  <li key={item.asset_id}>
                    <figure className="grid gap-2">
                      <CatalogImage assetId={item.asset_id} alt={caption || name} kind="image" />
                      {caption && <figcaption className="text-sm text-muted">{caption}</figcaption>}
                    </figure>
                  </li>
                );
              })}
            </ul>
          </section>
        )}
        {products.length === 0 ? <p>{t('empty')}</p> : (
          <>
            <ProductGrid products={products} label={t('productList')} />
            <LoadMore state={state} onClick={loadMore} />
          </>
        )}
      </div>
    </main>
  );
}
