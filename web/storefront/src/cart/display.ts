// TASK:WEB-02b: what a cart line looks like. The cart API returns no name/image/colour/size (cart.yaml CartItem), so the
// product page saves them here when an item is added (spec §3, §7.6). Display only — prices always come from the server.
// Lines without an entry (merged or opened on another device) fall back to the SKU.
export type ItemDisplay = { product_id: string; name_vi: string; name_en: string; asset_id: string | null; color: string; size: string };

const KEY = 'storefront.cartDisplay';
const MAX = 200;

function readAll(): Record<string, ItemDisplay> {
  try {
    const value = JSON.parse(localStorage.getItem(KEY) ?? '{}') as unknown;
    return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, ItemDisplay>) : {};
  } catch {
    return {};
  }
}

export function displayOf(variantId: string): ItemDisplay | null {
  return readAll()[variantId] ?? null;
}

/** Newest last (insertion order); re-adding moves a variant to the end; the oldest go once there are more than MAX. */
export function rememberDisplay(variantId: string, info: ItemDisplay): void {
  const all = readAll();
  delete all[variantId];
  all[variantId] = info;
  const keys = Object.keys(all);
  for (const old of keys.slice(0, Math.max(0, keys.length - MAX))) delete all[old];
  try {
    localStorage.setItem(KEY, JSON.stringify(all));
  } catch {
    // Blocked or full storage: the line shows its SKU instead.
  }
}
