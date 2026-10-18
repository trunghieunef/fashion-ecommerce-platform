// TASK:WEB-01 SF-01: running collections and new arrivals; each block loads and fails on its own.
import { Link } from 'react-router';

import { useLang } from '../i18n';
import { listCollections, listProducts } from './api';
import { CatalogImage, Failed, Loading, ProductGrid, useLoad } from './ui';

export function HomePage() {
  const { t, pick } = useLang();
  const [collections, retryCollections] = useLoad(listCollections, []);
  const [products, retryProducts] = useLoad((signal) => listProducts({ sort: 'NEWEST' }, undefined, signal, 8), []);
  return (
    <main className="page">
      <h1 className="visually-hidden">{t('store')}</h1>
      <section aria-labelledby="collections">
        <h2 id="collections">{t('collections')}</h2>
        {collections.status === 'loading' && <Loading />}
        {collections.status === 'error' && <Failed notFound={false} retry={retryCollections} />}
        {collections.status === 'ready' && collections.data.items.length === 0 && <p>{t('noCollections')}</p>}
        {collections.status === 'ready' && collections.data.items.length > 0 && (
          <ul className="collection-list">
            {collections.data.items.map((c) => {
              const name = pick(c.name_vi, c.name_en);
              return (
                <li key={c.id}>
                  <Link to={`/collections/${c.slug}`}>
                    <CatalogImage assetId={c.cover_asset_id} alt={name} />
                    <span>{name}</span>
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </section>
      <section aria-labelledby="new-arrivals">
        <h2 id="new-arrivals">{t('newArrivals')}</h2>
        {products.status === 'loading' && <Loading />}
        {products.status === 'error' && <Failed notFound={false} retry={retryProducts} />}
        {products.status === 'ready' && products.data.items.length === 0 && <p>{t('empty')}</p>}
        {products.status === 'ready' && products.data.items.length > 0 && (
          <>
            <ProductGrid products={products.data.items} label={t('newArrivals')} />
            <Link to="/products">{t('products')} →</Link>
          </>
        )}
      </section>
    </main>
  );
}
