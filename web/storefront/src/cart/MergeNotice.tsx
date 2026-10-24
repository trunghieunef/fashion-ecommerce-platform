// TASK:WEB-02b: what changed while merging the guest cart (CART-02 adjustments) — never silent.
import { BUTTON } from '../catalog/ui';
import { useLang, type StringKey } from '../i18n';
import { Drawer } from '../ui/Drawer';
import type { Adjustment } from './api';
import { displayOf } from './display';

const REASON: Record<Adjustment['reason'], StringKey> = {
  UNAVAILABLE: 'adjUnavailable', OUT_OF_STOCK: 'adjOutOfStock', QUANTITY_CAPPED: 'adjCapped', CART_ITEM_LIMIT: 'adjItemLimit',
};

export function MergeNotice({ adjustments, onClose }: { adjustments: Adjustment[] | null; onClose: () => void }) {
  const { t, pick } = useLang();
  return (
    <Drawer open={Boolean(adjustments?.length)} onClose={onClose} title={t('mergeTitle')}>
      <div className="grid gap-4">
        <p className="text-sm">{t('mergeIntro')}</p>
        <ul className="grid gap-3">
          {(adjustments ?? []).map((a) => {
            const display = displayOf(a.variant_id);
            return (
              <li key={a.variant_id} className="grid gap-1 border-b border-line pb-3 text-sm">
                <p className="font-semibold">{display ? `${pick(display.name_vi, display.name_en)} · ${a.sku}` : a.sku}</p>
                <p>{t(REASON[a.reason])} · {a.requested} → {a.applied}</p>
              </li>
            );
          })}
        </ul>
        <button type="button" onClick={onClose} className={BUTTON}>{t('understood')}</button>
      </div>
    </Drawer>
  );
}
