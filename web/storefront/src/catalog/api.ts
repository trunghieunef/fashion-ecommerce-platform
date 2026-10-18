// TASK:WEB-01: typed public catalog reads (contracts/openapi/catalog.yaml), always same-origin through the Gateway.
export type Lang = 'vi' | 'en';

export type ImageRef = { asset_id: string; alt_vi: string; alt_en: string };
export type ProductSummary = {
  id: string;
  slug: string;
  name_vi: string;
  name_en: string;
  min_price: number;
  max_price: number;
  image: ImageRef | null;
};
export type Page<T> = { items: T[]; next_cursor: string | null };
export type Variant = {
  id: string;
  sku: string;
  size: string;
  color: string;
  effective_price: number;
  available: number | null;
  observed_at: string | null;
};
export type ProductImage = ImageRef & { variant_color: string | null; sort_order: number };
export type SizeGuide = {
  category_id: string;
  locale: string;
  guideline_html: string;
  table: { columns: string[]; rows: string[][] };
};
export type Product = {
  id: string;
  slug: string;
  name_vi: string;
  name_en: string;
  description_vi: string;
  description_en: string;
  tags: string[];
  category: { id: string; name: string };
  brand: { id: string; name: string } | null;
  min_price: number;
  max_price: number;
  variants: Variant[];
  images: ProductImage[];
  size_guide: SizeGuide | null;
  available: number | null;
  observed_at: string | null;
};
export type Collection = {
  id: string;
  slug: string;
  name_vi: string;
  name_en: string;
  cover_asset_id: string | null;
  start_at: string | null;
  end_at: string | null;
};
export type CollectionDetail = {
  collection: Collection;
  lookbook: { asset_id: string; caption_vi: string; caption_en: string; sort_order: number }[];
  products: ProductSummary[];
  next_cursor: string | null;
};
export type Category = { id: string; slug: string; name_vi: string; name_en: string; children: Category[] };
export type Brand = { id: string; name: string };

/** Non-2xx response; status 0 means the network failed. fields: errors[].field of a 400 (which filter is invalid). */
export class ApiError extends Error {
  constructor(readonly status: number, readonly fields: string[] = []) {
    super(`catalog:${status}`);
  }
}

async function get<T>(path: string, signal: AbortSignal, lang?: Lang): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v1/catalog${path}`, { signal, headers: lang ? { 'Accept-Language': lang } : {} });
  } catch (error) {
    if (signal.aborted) throw error;
    throw new ApiError(0);
  }
  if (response.status === 400) {
    const body = (await response.json().catch(() => null)) as { errors?: { field?: string }[] } | null;
    throw new ApiError(400, (body?.errors ?? []).map((e) => e.field ?? '').filter(Boolean));
  }
  if (!response.ok) throw new ApiError(response.status);
  return ((await response.json()) as { data: T }).data;
}

function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value) search.set(key, value);
  const text = search.toString();
  return text ? `?${text}` : '';
}

/** Filter keys kept in the URL (spec section 3). */
export const FILTER_KEYS = ['q', 'category_id', 'brand_id', 'size', 'color', 'tag', 'min_price', 'max_price', 'sort'] as const;
export type Filters = Partial<Record<(typeof FILTER_KEYS)[number], string>>;

export const listProducts = (filters: Filters, cursor: string | undefined, signal: AbortSignal, limit = 24) =>
  get<Page<ProductSummary>>(`/${filters.q ? 'search' : 'products'}${query({ ...filters, cursor, limit: String(limit) })}`, signal);
export const getProduct = (id: string, lang: Lang, signal: AbortSignal) =>
  get<Product>(`/products/${encodeURIComponent(id)}`, signal, lang);
export const listCollections = (signal: AbortSignal) => get<Page<Collection>>('/collections?limit=6', signal);
export const getCollection = (slug: string, cursor: string | undefined, signal: AbortSignal) =>
  get<CollectionDetail>(`/collections/${encodeURIComponent(slug)}${query({ cursor })}`, signal);
export const listCategories = (signal: AbortSignal) => get<Category[]>('/categories', signal);
export const listBrands = (signal: AbortSignal) => get<Brand[]>('/brands', signal);

export const imageUrl = (assetId: string, kind: 'thumb' | 'image') =>
  `/api/v1/catalog/images/${encodeURIComponent(assetId)}?kind=${kind}`;
