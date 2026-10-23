// TASK:WEB-02a: add/edit one address. Province and ward come from the sample directory (spec §4, O04 pending);
// changing the province clears the ward.
import { useState } from 'react';

import * as api from '../auth/api';
import { maxLength, phone, required } from '../auth/validate';
import { commonError } from '../auth/LoginPage';
import { BUTTON_OUTLINE } from '../catalog/ui';
import { HttpError } from '../http';
import { useLang } from '../i18n';
import { ErrorSummary, Field, SelectField, SubmitButton, useForm } from '../ui/form';
import geo from './geo.sample.json';

type Province = (typeof geo.provinces)[number];

/** Display name for a code from the sample directory; codes it does not know are shown as they are. */
export function placeName(pick: (vi: string, en: string) => string, provinceCode: string, wardCode?: string) {
  const province: Province | undefined = geo.provinces.find((p) => p.code === provinceCode);
  if (wardCode === undefined) return province ? pick(province.name_vi, province.name_en) : provinceCode;
  const ward = province?.wards.find((w) => w.code === wardCode);
  return ward ? pick(ward.name_vi, ward.name_en) : wardCode;
}

const FIELDS = ['recipient_name', 'phone', 'province_code', 'ward_code', 'address_line'] as const;
type Name = (typeof FIELDS)[number];

export function AddressForm({ initial, onSaved, onCancel }: { initial: api.Address | null; onSaved: () => void; onCancel: () => void }) {
  const { t, pick } = useLang();
  const form = useForm<Name>({
    recipient_name: initial?.recipient_name ?? '',
    phone: initial?.phone ?? '',
    province_code: initial?.province_code ?? '',
    ward_code: initial?.ward_code ?? '',
    address_line: initial?.address_line ?? '',
  }, { recipient_name: maxLength(100), phone, province_code: required, ward_code: required, address_line: maxLength(255) });
  const [isDefault, setIsDefault] = useState(initial?.is_default ?? false);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const province = geo.provinces.find((p) => p.code === form.values.province_code);
  const { error: provinceError, onChange: provinceChange, ...provinceProps } = form.bind('province_code');

  const onSubmit = form.submit(async (values) => {
    if (pending) return;
    setPending(true);
    setMessage(null);
    const input: api.AddressInput = {
      recipient_name: values.recipient_name.trim(), phone: values.phone.trim(), province_code: values.province_code,
      district_code: initial?.district_code ?? null, ward_code: values.ward_code, address_line: values.address_line.trim(),
      is_default: isDefault,
    };
    try {
      if (initial) await api.updateAddress(initial.id, input);
      else await api.createAddress(input);
      onSaved();
    } catch (error) {
      const known = error instanceof HttpError ? error.fields.filter((f) => (FIELDS as readonly string[]).includes(f.field)) : [];
      if (known.length > 0) known.forEach((f) => form.setFieldError(f.field as Name, t('errInvalid')));
      else {
        // user.yaml: unsetting the current default without choosing another → 400 DEFAULT_ADDRESS_REQUIRED.
        setMessage(t(error instanceof HttpError && error.code === 'DEFAULT_ADDRESS_REQUIRED' ? 'errDefaultRequired'
          : error instanceof HttpError && error.status === 400 ? 'errFormInvalid' : commonError(error)));
        form.refocus();
      }
    } finally {
      setPending(false);
    }
  });

  const labels: Record<Name, string> = {
    recipient_name: t('recipient'), phone: t('phone'), province_code: t('province'), ward_code: t('ward'), address_line: t('addressLine'),
  };
  return (
    <form noValidate onSubmit={onSubmit} className="grid gap-4">
      <ErrorSummary errors={form.summary(labels)} message={message} focusKey={form.focusKey} />
      <Field label={labels.recipient_name} autoComplete="name" {...form.bind('recipient_name')} />
      <Field label={labels.phone} type="tel" autoComplete="tel" {...form.bind('phone')} />
      <SelectField label={labels.province_code} placeholder={t('choose')} error={provinceError} {...provinceProps}
        options={geo.provinces.map((p) => ({ value: p.code, label: pick(p.name_vi, p.name_en) }))}
        onChange={(e) => { provinceChange(e); form.setValue('ward_code', ''); }} />
      <SelectField label={labels.ward_code} placeholder={t('choose')} {...form.bind('ward_code')}
        options={(province?.wards ?? []).map((w) => ({ value: w.code, label: pick(w.name_vi, w.name_en) }))} />
      <Field label={labels.address_line} autoComplete="street-address" {...form.bind('address_line')} />
      <label className="flex min-h-11 items-center gap-2 text-sm">
        <input type="checkbox" name="is_default" checked={isDefault} onChange={(e) => setIsDefault(e.currentTarget.checked)} className="size-5" />
        {t('makeDefault')}
      </label>
      <p className="text-xs text-muted">{t('sampleGeo')}</p>
      <div className="flex gap-2">
        <button type="button" onClick={onCancel} className={`${BUTTON_OUTLINE} flex-1 px-4`}>{t('cancel')}</button>
        <div className="flex-[2]"><SubmitButton pending={pending}>{t('save')}</SubmitButton></div>
      </div>
    </form>
  );
}
