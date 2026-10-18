// TASK:WEB-01 SF-01: one collection with its lookbook (caption is alternate text) and products.
import { useParams } from 'react-router';

import { useLang } from '../i18n';
import { getCollection } from './api';
import { CatalogImage, Failed, Loading, ProductGrid, useLoad } from './ui';

export function CollectionPage() {
  const { slug = '' } = useParams();
  const { t, pick } = useLang();
  const [state, retry] = useLoad((signal) => getCollection(slug, undefined, signal), [slug]);
  if (state.status === 'loading') return <main className="page"><Loading /></main>;
  if (state.status === 'error') return <main className="page"><Failed notFound={state.notFound} retry={retry} /></main>;
  // ponytail: first page of products only; add "load more" via next_cursor when collections exceed one page.
  const { collection, lookbook, products } = state.data;
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
      {products.length === 0 ? <p>{t('empty')}</p> : <ProductGrid products={products} label={t('productList')} />}
    </main>
  );
}
