// TASK:WEB-02a: form building blocks shared by account, cart and checkout screens (spec 3.1): visible labels, errors
// tied to their field, a focused error summary after a failed submit, a busy submit button and a short status message.
import { useEffect, useRef, useState, type FormEvent, type InputHTMLAttributes, type ReactNode } from 'react';

import { BUTTON, LABEL } from '../catalog/ui';
import { useLang, type StringKey } from '../i18n';
import type { Validator } from '../auth/validate';

const INPUT = 'min-h-11 w-full border border-line bg-bg px-3 aria-[invalid=true]:border-danger';

type FieldProps = { label: string; name: string; error?: string | null; hint?: string } & InputHTMLAttributes<HTMLInputElement>;

function describedBy(name: string, error?: string | null, hint?: string) {
  return [hint ? `${name}-hint` : '', error ? `${name}-error` : ''].filter(Boolean).join(' ') || undefined;
}

function FieldShell({ label, name, error, hint, children }: { label: string; name: string; error?: string | null; hint?: string; children: ReactNode }) {
  return (
    <div className="grid gap-1">
      <label htmlFor={name} className={LABEL}>{label}</label>
      {children}
      {hint && <p id={`${name}-hint`} className="text-sm text-muted">{hint}</p>}
      {error && <p id={`${name}-error`} className="text-sm text-danger">{error}</p>}
    </div>
  );
}

export function Field({ label, name, error, hint, ...input }: FieldProps) {
  return (
    <FieldShell label={label} name={name} error={error} hint={hint}>
      <input id={name} name={name} aria-invalid={Boolean(error)} aria-describedby={describedBy(name, error, hint)} className={INPUT} {...input} />
    </FieldShell>
  );
}

/** Native select with the same label/error wiring as Field; `placeholder` is the empty first option. */
export function SelectField({ label, name, error, placeholder, options, value, onChange, onBlur }: {
  label: string; name: string; error?: string | null; placeholder: string; options: { value: string; label: string }[];
  value: string; onChange: (e: { currentTarget: { value: string } }) => void; onBlur?: () => void;
}) {
  return (
    <FieldShell label={label} name={name} error={error}>
      <select id={name} name={name} value={value} onChange={onChange} onBlur={onBlur} aria-invalid={Boolean(error)}
        aria-describedby={describedBy(name, error)} className={INPUT}>
        <option value="">{placeholder}</option>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </FieldShell>
  );
}

/** Password with a show/hide toggle; paste and password managers stay allowed (Accessible Authentication). */
export function PasswordField({ label, name, error, hint, ...input }: FieldProps) {
  const { t } = useLang();
  const [shown, setShown] = useState(false);
  return (
    <FieldShell label={label} name={name} error={error} hint={hint}>
      <div className="flex">
        <input id={name} name={name} type={shown ? 'text' : 'password'} aria-invalid={Boolean(error)}
          aria-describedby={describedBy(name, error, hint)} className={`${INPUT} flex-1`} {...input} />
        <button type="button" aria-pressed={shown} aria-label={shown ? t('hidePassword') : t('showPassword')}
          onClick={() => setShown((s) => !s)} className="min-h-11 border border-l-0 border-line px-3 text-sm">
          {shown ? t('hide') : t('show')}
        </button>
      </div>
    </FieldShell>
  );
}

export type SummaryItem = { name: string; label: string; message: string };

/** Shown after a failed submit; takes focus each time `focusKey` changes; every item links to its field. */
export function ErrorSummary({ errors, message, focusKey }: { errors: SummaryItem[]; message?: string | null; focusKey: number }) {
  const { t } = useLang();
  const ref = useRef<HTMLDivElement>(null);
  const visible = errors.length > 0 || Boolean(message);
  useEffect(() => { if (visible && focusKey > 0) ref.current?.focus(); }, [focusKey]);
  if (!visible) return null;
  return (
    <div ref={ref} role="alert" tabIndex={-1} className="grid gap-2 border border-danger p-4 text-sm">
      {message && <p className="font-semibold">{message}</p>}
      {errors.length > 0 && (
        <>
          <p className="font-semibold">{t('fixErrors')}</p>
          <ul className="grid gap-1">
            {errors.map((e) => (
              <li key={e.name}><a href={`#${e.name}`} className="underline" onClick={(ev) => { ev.preventDefault(); document.getElementById(e.name)?.focus(); }}>
                {e.label}: {e.message}
              </a></li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

/** Short confirmation read by screen readers; hides itself after 5 s. */
export function StatusMessage({ message }: { message: string | null }) {
  const [shown, setShown] = useState(message);
  useEffect(() => {
    setShown(message);
    if (!message) return;
    const timer = setTimeout(() => setShown(null), 5000);
    return () => clearTimeout(timer);
  }, [message]);
  return <p role="status" className="min-h-6 text-sm font-semibold">{shown}</p>;
}

export function SubmitButton({ pending, children }: { pending: boolean; children: ReactNode }) {
  const { t } = useLang();
  return (
    <button type="submit" disabled={pending} aria-busy={pending || undefined} className={`${BUTTON} w-full`}>
      {children}{pending && <span aria-hidden="true">&nbsp;· {t('sending')}</span>}
    </button>
  );
}

/**
 * Field values, client checks and server errors. A field's own error shows after it was left once or after a submit;
 * a failed submit bumps `focusKey` so the summary takes focus.
 */
export function useForm<K extends string>(initial: Record<K, string>, validators: Partial<Record<K, Validator>>) {
  const { t } = useLang();
  const [values, setValues] = useState(initial);
  const [touched, setTouched] = useState<Partial<Record<K, boolean>>>({});
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<Partial<Record<K, string>>>({});
  const [focusKey, setFocusKey] = useState(0);

  const clientError = (name: K): StringKey | null => validators[name]?.(values[name]) ?? null;
  const errorOf = (name: K): string | null => {
    const local = touched[name] || submitted ? clientError(name) : null;
    return server[name] ?? (local ? t(local) : null);
  };
  return {
    values,
    focusKey,
    errorOf,
    bind: (name: K) => ({
      name,
      value: values[name],
      error: errorOf(name),
      onChange: (e: { currentTarget: { value: string } }) => {
        const value = e.currentTarget.value;
        setValues((v) => ({ ...v, [name]: value }));
        setServer((s) => ({ ...s, [name]: undefined }));
      },
      onBlur: () => setTouched((x) => ({ ...x, [name]: true })),
    }),
    setValue: (name: K, value: string) => setValues((v) => ({ ...v, [name]: value })),
    /** Server-side error for one field (409, 400 fields); also refocuses the summary. */
    setFieldError: (name: K, message: string) => { setServer((s) => ({ ...s, [name]: message })); setFocusKey((k) => k + 1); },
    refocus: () => setFocusKey((k) => k + 1),
    summary: (labels: Record<K, string>): SummaryItem[] =>
      (Object.keys(values) as K[]).flatMap((name) => {
        const message = errorOf(name);
        return message && (submitted || server[name]) ? [{ name, label: labels[name], message }] : [];
      }),
    submit: (onValid: (values: Record<K, string>) => void) => (event: FormEvent) => {
      event.preventDefault();
      setSubmitted(true);
      if ((Object.keys(values) as K[]).some((name) => clientError(name))) { setFocusKey((k) => k + 1); return; }
      onValid(values);
    },
  };
}
