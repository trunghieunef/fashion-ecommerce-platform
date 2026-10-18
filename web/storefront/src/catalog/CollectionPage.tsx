// TASK:WEB-01 SF-01: one collection with its lookbook (caption is alternate text) and products.
import { useParams } from 'react-router';

import { useLang } from '../i18n';
import { ApiError, getCollection, type CollectionDetail, type ProductSummary } from './api';
import { CatalogImage, Failed, Loading, LoadMore, ProductGrid, usePaged } from './ui';

export function CollectionPage() {
  const { slug = '' } = useParams();
  const { t, pick } = useLang();
  const { state, loadMore, retry } = usePaged<ProductSummary, Pick<CollectionDetail, 'collection' | 'lookbook'>>(
    (signal) => getCollection(slug, undefined, signal)
      .then((d) => ({ items: d.products, cursor: d.next_cursor, head: { collection: d.collection, lookbook: d.lookbook } })),
    (cursor, signal) => getCollection(slug, cursor, signal).then((d) => ({ items: d.products, cursor: d.next_cursor })),
    slug);
  if (state.status === 'loading') return <main className="page"><Loading /></main>;
  if (state.status === 'error')
    return <main className="page"><Failed notFound={state.error instanceof ApiError && state.error.status === 404} retry={retry} /></main>;
  const { collection, lookbook } = state.head;
  const products = state.items;
  const name = pick(collection.name_vi, collection.name_en);
  return (
    <main className="page">
      <h1>{name}</h1>
      {lookbook.length > 0 && (
        <section aria-labelledby="lookbook">
          <h2 id="lookbook">{t('lookbook')}</h2>
          <ul className="lookbook">
            {[...lookbook].sort((a, b) => a.sort_order - b.sort_order).map((item) => {
              const caption = pick(item.caption_vi, item.caption_en);
              return (
                <li key={item.asset_id}>
                  <figure>
                    <CatalogImage assetId={item.asset_id} alt={caption || name} kind="image" />
                    {caption && <figcaption>{caption}</figcaption>}
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
    </main>
  );
}
