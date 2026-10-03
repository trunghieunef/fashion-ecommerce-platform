export type ProductSummary = {
  id: string;
  slug: string;
  name_vi: string;
  name_en: string;
};

export type ProductPage = {
  code: 'OK';
  data: {
    items: ProductSummary[];
    next_cursor: string | null;
  };
  metadata: {
    request_id: string;
    trace_id: string;
  };
};

export async function getProducts(signal: AbortSignal): Promise<ProductPage> {
  const response = await fetch('/api/v1/catalog/products?limit=1', { signal });
  if (!response.ok) {
    throw new Error(`catalog:${response.status}`);
  }
  return response.json() as Promise<ProductPage>;
}
