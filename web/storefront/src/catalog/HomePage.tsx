// TASK:WEB-01 SF-01, TASK:WEB-01b: editorial home — the first running collection is the hero, the rest are tiles,
// then a new-arrivals rail. Each block loads and fails on its own (spec 3.2).
import { Link } from 'react-router';

import { useLang } from '../i18n';
import { ChevronRight } from '../ui/icons';
import { listCollections, listProducts, type Collection } from './api';
import { BUTTON, CatalogImage, Failed, LABEL, Loading, PAGE, ProductRail, useLoad } from './ui';

function Hero({ collection }: { collection: Collection }) {
  const { t, pick } = useLang();
  const name = pick(collection.name_vi, collection.name_en);
  const cta = (
    <Link to={`/collections/${collection.slug}`} className={`${BUTTON} mt-6`}>
      {t('explore')}<span className="sr-only"> {name}</span>
    </Link>
  );
  const title = <h1 className="text-5xl leading-[0.9] font-bold tracking-[-0.04em] uppercase lg:text-7xl">{name}</h1>;
  if (!collection.cover_asset_id)
    return (
      <section data-testid="hero" className="bg-soft">
        <div className={`${PAGE} grid min-h-[50vh] content-end py-12`}>{title}<div>{cta}</div></div>
      </section>
    );
  return (
    <section data-testid="hero" className="relative">
      <CatalogImage assetId={collection.cover_asset_id} alt={name} kind="image" eager className="h-[70vh] w-full lg:h-[85vh]" />
      <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/50 to-transparent text-bg">
        <div className={`${PAGE} pt-24`}>{title}<div>{cta}</div></div>
      </div>
    </section>
  );
}

export function HomePage() {
  const { t, pick } = useLang();
  const [collections, retryCollections] = useLoad(listCollections, []);
  const [products, retryProducts] = useLoad((signal) => listProducts({ sort: 'NEWEST' }, undefined, signal, 8), []);
  const running = collections.status === 'ready' ? collections.data.items : [];
  const [hero, ...tiles] = running;

  return (
    <main id="main" tabIndex={-1}>
      {!hero && <h1 className="sr-only">{t('store')}</h1>}
      {collections.status === 'loading' && <div aria-hidden="true" className="h-[50vh] animate-pulse bg-soft" />}
      {collections.status === 'error' && <div className={PAGE}><Failed notFound={false} retry={retryCollections} /></div>}
      {hero && <Hero collection={hero} />}

      {tiles.length > 0 && (
        <ul aria-label={t('collections')} className="mt-0.5 grid grid-cols-1 gap-0.5 lg:grid-cols-2">
          {tiles.map((c) => {
            const name = pick(c.name_vi, c.name_en);
            return (
              <li key={c.id}>
                <Link to={`/collections/${c.slug}`} className="group relative block">
                  <CatalogImage assetId={c.cover_asset_id} alt={name} className="aspect-[4/3] w-full" />
                  <span className={`${LABEL} absolute bottom-4 left-4 flex items-center gap-1 bg-bg px-2 py-1 group-hover:underline`}>
                    {name}<ChevronRight className="size-4" />
                  </span>
                </Link>
              </li>
            );
          })}
        </ul>
      )}

      <section aria-labelledby="new-arrivals" className={PAGE}>
        <div className="mb-4 flex items-baseline justify-between gap-4">
          <h2 id="new-arrivals" className="text-2xl font-bold tracking-[-0.03em] uppercase">{t('newArrivals')}</h2>
          <Link to="/products?sort=NEWEST" className={`${LABEL} hover:underline`}>{t('viewAll')}</Link>
        </div>
        {products.status === 'loading' && <Loading />}
        {products.status === 'error' && <Failed notFound={false} retry={retryProducts} />}
        {products.status === 'ready' && products.data.items.length === 0 && <p>{t('empty')}</p>}
        {products.status === 'ready' && products.data.items.length > 0 && <ProductRail products={products.data.items} label={t('newArrivals')} />}
      </section>
    </main>
  );
}
