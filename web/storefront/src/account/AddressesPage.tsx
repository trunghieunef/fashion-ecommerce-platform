// TASK:WEB-02a: the member's address book (spec §4). The server keeps exactly one default; deleting the default
// while others exist is refused (400) and the reason is shown.
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';

import * as api from '../auth/api';
import { BUTTON, BUTTON_OUTLINE, Failed, LABEL, Loading, PAGE } from '../catalog/ui';
import { HttpError } from '../http';
import { useLang } from '../i18n';
import { Drawer } from '../ui/Drawer';
import { StatusMessage } from '../ui/form';
import { AddressForm, placeName } from './AddressForm';

const inputOf = (a: api.Address, isDefault: boolean): api.AddressInput => ({
  recipient_name: a.recipient_name, phone: a.phone, province_code: a.province_code, district_code: a.district_code,
  ward_code: a.ward_code, address_line: a.address_line, is_default: isDefault,
});

export function AddressesPage() {
  const { t, pick } = useLang();
  const [list, setList] = useState<api.Address[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [editing, setEditing] = useState<api.Address | 'new' | null>(null);
  const [deleting, setDeleting] = useState<api.Address | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  const load = async () => {
    try {
      setList(await api.listAddresses());
      setFailed(false);
    } catch {
      setFailed(true);
    }
  };
  useEffect(() => { void load(); }, []);
  useEffect(() => { if (deleting) cancelRef.current?.focus(); }, [deleting]);

  const done = async (message: string) => { setProblem(null); await load(); setStatus(message); };
  const makeDefault = async (a: api.Address) => {
    try {
      await api.updateAddress(a.id, inputOf(a, true));
      await done(t('addressSaved'));
    } catch {
      setProblem(t('loadError'));
    }
  };
  const confirmDelete = async () => {
    const target = deleting;
    setDeleting(null);
    if (!target) return;
    try {
      await api.deleteAddress(target.id);
      await done(t('addressDeleted'));
    } catch (error) {
      setProblem(t(error instanceof HttpError && error.status === 400 ? 'errDeleteAddress' : 'loadError'));
    }
  };

  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-2xl gap-6">
        <Link to="/account" className={`${LABEL} text-muted hover:underline`}>{t('account')}</Link>
        <div className="flex flex-wrap items-end justify-between gap-4">
          <h1 className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('addresses')}</h1>
          <button type="button" className={BUTTON} onClick={() => setEditing('new')}>{t('addAddress')}</button>
        </div>
        <StatusMessage message={status} />
        {problem && <p role="alert" className="border border-danger p-3 text-sm">{problem}</p>}
        {failed && <Failed notFound={false} retry={() => void load()} />}
        {!failed && !list && <Loading />}
        {list && list.length === 0 && <p>{t('noAddresses')}</p>}
        {list && list.length > 0 && (
          <ul className="grid gap-4">
            {list.map((a) => (
              <li key={a.id} data-testid="address-card" className={`grid gap-2 border p-4 ${a.is_default ? 'border-fg' : 'border-line'}`}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="font-semibold break-words">{a.recipient_name} · {a.phone}</p>
                  {a.is_default && <span className="bg-fg px-2 py-0.5 text-xs font-semibold text-bg uppercase">{t('default')}</span>}
                </div>
                <p className="text-sm break-words">
                  {a.address_line}, {placeName(pick, a.province_code, a.ward_code)}, {placeName(pick, a.province_code)}
                </p>
                <div className="flex flex-wrap gap-2">
                  <button type="button" className={`${BUTTON_OUTLINE} px-4`} onClick={() => setEditing(a)}>{t('edit')}</button>
                  {!a.is_default && <button type="button" className={`${BUTTON_OUTLINE} px-4`} onClick={() => void makeDefault(a)}>{t('makeDefault')}</button>}
                  <button type="button" className={`${BUTTON_OUTLINE} px-4`} onClick={() => setDeleting(a)}>{t('delete')}</button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      <Drawer open={editing !== null} onClose={() => setEditing(null)} title={editing === 'new' ? t('addAddress') : t('editAddress')}>
        {editing !== null && (
          <AddressForm key={editing === 'new' ? 'new' : editing.id} initial={editing === 'new' ? null : editing}
            onCancel={() => setEditing(null)}
            onSaved={() => { setEditing(null); void done(t('addressSaved')); }} />
        )}
      </Drawer>
      <Drawer open={deleting !== null} onClose={() => setDeleting(null)} title={t('deleteAddress')}>
        <div className="grid gap-4">
          <p>{t('deleteAddressConfirm')}</p>
          <div className="flex gap-2">
            <button type="button" ref={cancelRef} className={`${BUTTON_OUTLINE} flex-1 px-4`} onClick={() => setDeleting(null)}>{t('cancel')}</button>
            <button type="button" className={`${BUTTON} flex-1`} onClick={() => void confirmDelete()}>{t('deleteAddress')}</button>
          </div>
        </div>
      </Drawer>
    </main>
  );
}
