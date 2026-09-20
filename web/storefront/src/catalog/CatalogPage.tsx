import { useEffect, useState } from 'react';

import { getProducts, type ProductPage } from './catalogClient';

type CatalogState =
  | { status: 'loading' }
  | { status: 'ready'; page: ProductPage }
  | { status: 'error' };

export function CatalogPage() {
  const [state, setState] = useState<CatalogState>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });

    getProducts(controller.signal)
      .then((page) => setState({ status: 'ready', page }))
      .catch(() => {
        if (!controller.signal.aborted) {
          setState({ status: 'error' });
        }
      });

    return () => controller.abort();
  }, [attempt]);

  return (
    <main className="catalog-page">
      <h1>Sản phẩm</h1>
      {state.status === 'loading' && <p role="status">Đang tải sản phẩm…</p>}
      {state.status === 'error' && (
        <section aria-live="polite">
          <p>Không thể tải sản phẩm. Vui lòng thử lại.</p>
          <button type="button" onClick={() => setAttempt((value) => value + 1)}>
            Thử lại
          </button>
        </section>
      )}
      {state.status === 'ready' && state.page.data.items.length === 0 && (
        <p aria-live="polite">Chưa có sản phẩm</p>
      )}
      {state.status === 'ready' && state.page.data.items.length > 0 && (
        <ul className="product-list" aria-label="Danh sách sản phẩm">
          {state.page.data.items.map((product) => (
            <li key={product.id}>
              <a href={`/products/${product.slug}`}>{product.name_vi}</a>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
