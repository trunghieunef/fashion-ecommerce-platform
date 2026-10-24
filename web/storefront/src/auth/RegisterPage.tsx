// TASK:WEB-02a: create an account; e-mails go out in the language the visitor is using now.
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';

import { PAGE } from '../catalog/ui';
import { HttpError } from '../http';
import { useLang } from '../i18n';
import { ErrorSummary, Field, PasswordField, SubmitButton, useForm } from '../ui/form';
import { useAuth } from './AuthProvider';
import { commonError } from './LoginPage';
import { email, maxLength, password } from './validate';

const FIELDS = ['full_name', 'email', 'password'] as const;

export function RegisterPage() {
  const { t, lang } = useLang();
  const { register } = useAuth();
  const navigate = useNavigate();
  const form = useForm({ full_name: '', email: '', password: '' }, { full_name: maxLength(200), email, password });
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const onSubmit = form.submit(async (values) => {
    if (pending) return;
    setPending(true);
    setMessage(null);
    try {
      await register({ full_name: values.full_name.trim(), email: values.email.trim(), password: values.password, locale: lang });
      navigate('/account', { replace: true });
    } catch (error) {
      if (error instanceof HttpError && error.status === 409) form.setFieldError('email', t('errEmailTaken'));
      else if (error instanceof HttpError && error.status === 400 && error.fields.length > 0)
        error.fields.forEach((f) => { if ((FIELDS as readonly string[]).includes(f.field)) form.setFieldError(f.field as (typeof FIELDS)[number], t('errInvalid')); });
      else { setMessage(t(commonError(error))); form.refocus(); }
    } finally {
      setPending(false);
    }
  });

  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-md gap-6">
        <h1 className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('register')}</h1>
        <form noValidate onSubmit={onSubmit} className="grid gap-4">
          <ErrorSummary errors={form.summary({ full_name: t('fullName'), email: t('email'), password: t('password') })}
            message={message} focusKey={form.focusKey} />
          <Field label={t('fullName')} autoComplete="name" {...form.bind('full_name')} />
          <Field label={t('email')} type="email" autoComplete="email" {...form.bind('email')} />
          <PasswordField label={t('password')} autoComplete="new-password" hint={t('passwordHint')} {...form.bind('password')} />
          <SubmitButton pending={pending}>{t('register')}</SubmitButton>
        </form>
        <p className="text-sm">{t('haveAccount')} <Link to="/login" className="underline">{t('signIn')}</Link></p>
      </div>
    </main>
  );
}
