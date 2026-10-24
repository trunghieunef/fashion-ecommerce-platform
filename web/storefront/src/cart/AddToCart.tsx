// TASK:WEB-02b: "add to cart" in the product panel. Needs a full variant (colour + size); stock shown on the page is a
// reference only, so an out-of-stock variant can still be tried — the server decides. One request per click (busy state).
import { useRef, useState } from 'react';

import type { Product, Variant } from '../catalog/api';
import { BUTTON } from '../catalog/ui';
import { useAuth } from '../auth/AuthProvider';
import { useLang } from '../i18n';
import { AddedDrawer } from './AddedDrawer';
import { useCart } from './CartProvider';
import { rememberDisplay } from './display';

export function AddToCart({ product, variant }: { product: Product; variant: Variant | undefined }) {
  const { t } = useLang();
  const { add } = useCart();
  // Until the session is known, an add could create a guest cart for a member.
  const restoring = useAuth().status === 'unknown';
  const [pending, setPending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [added, setAdded] = useState<Variant | null>(null);
  // A ref, not the state: two clicks in the same tick both see the old `pending` state.
  const busy = useRef(false);

  async function onAdd() {
    if (!variant || restoring || busy.current) return;
    busy.current = true;
    setPending(true);
    setProblem(null);
    const images = [...product.images].sort((a, b) => a.sort_order - b.sort_order);
    const image = images.find((i) => i.variant_color === variant.color) ?? images[0];
    // Saved before the request: the line can show its name even if the response comes back after a reload.
    rememberDisplay(variant.id, {
      product_id: product.id, name_vi: product.name_vi, name_en: product.name_en, asset_id: image?.asset_id ?? null,
      color: variant.color, size: variant.size,
    });
    const result = await add(variant.id, 1);
    busy.current = false;
    setPending(false);
    if (result === 'added') setAdded(variant);
    else setProblem(result.problem);
  }

  return (
    <div className="grid gap-2">
      <button type="button" onClick={() => void onAdd()} disabled={!variant} aria-disabled={pending || restoring || undefined}
        aria-busy={pending || undefined} className={`${BUTTON} w-full`}>
        {t('addToCart')}{pending && <span aria-hidden="true">&nbsp;· {t('sending')}</span>}
      </button>
      {problem && <p role="alert" className="text-sm text-danger">{problem}</p>}
      <AddedDrawer product={product} variant={added} onClose={() => setAdded(null)} />
    </div>
  );
}
