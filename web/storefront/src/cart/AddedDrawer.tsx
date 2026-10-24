// TASK:WEB-02b: confirmation after "add to cart" — the line just added, then "view cart" or "keep shopping". It stays
// open until the visitor chooses (no auto-close).
import { Link } from 'react-router';

import type { Product, Variant } from '../catalog/api';
import { BUTTON, BUTTON_OUTLINE, CatalogImage, Price } from '../catalog/ui';
import { useLang } from '../i18n';
import { Drawer } from '../ui/Drawer';

export function AddedDrawer({ product, variant, onClose }: { product: Product; variant: Variant | null; onClose: () => void }) {
  const { t, pick } = useLang();
  const name = pick(product.name_vi, product.name_en);
  const image = variant ? (product.images.find((i) => i.variant_color === variant.color) ?? product.images[0]) : undefined;
  return (
    <Drawer open={variant !== null} onClose={onClose} title={t('addedToCart')}>
      {variant && (
        <div className="grid gap-6">
          <div className="grid grid-cols-[5rem_1fr] gap-4">
            <CatalogImage assetId={image?.asset_id} alt={name} />
            <div className="grid content-start gap-1">
              <p className="text-sm font-semibold uppercase">{name}</p>
              <p className="text-sm text-muted">{variant.color} · {variant.size}</p>
              <Price min={variant.effective_price} max={variant.effective_price} className="text-sm" />
            </div>
          </div>
          <div className="grid gap-2">
            <Link to="/cart" onClick={onClose} className={BUTTON}>{t('viewCart')}</Link>
            <button type="button" onClick={onClose} className={BUTTON_OUTLINE}>{t('continueShopping')}</button>
          </div>
        </div>
      )}
    </Drawer>
  );
}
