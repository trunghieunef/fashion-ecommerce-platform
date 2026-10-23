// TASK:WEB-02a: sign in. One message for a wrong email or password (the server does not tell which); `next` may only
// point inside the site.
import { useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';

import { PAGE } from '../catalog/ui';
import { HttpError } from '../http';
import { useLang, type StringKey } from '../i18n';
import { ErrorSummary, Field, PasswordField, StatusMessage, SubmitButton, useForm } from '../ui/form';
import { useAuth } from './AuthProvider';
import { email, required } from './validate';

/** Only same-site paths: "/x" yes; "//host", "/\host" and absolute URLs fall back to the account page. */
export function safeNext(next: string | null): string {
  return next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\') ? next : '/account';
}

/** Message for errors every auth form shares; null when the caller handles it. */
export function commonError(error: unknown): StringKey {
  if (error instanceof HttpError && error.status === 429) return 'errRateLimited';
  return 'loadError';
}

export function LoginPage() {
  const { t } = useLang();
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const notice = (useLocation().state as { notice?: StringKey } | null)?.notice;
  const form = useForm({ email: '', password: '' }, { email, password: required });
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const onSubmit = form.submit(async (values) => {
    if (pending) return;
    setPending(true);
    setMessage(null);
    try {
      await signIn(values.email.trim(), values.password);
      navigate(safeNext(params.get('next')), { replace: true });
    } catch (error) {
      setMessage(t(error instanceof HttpError && error.status === 401 ? 'errCredentials' : commonError(error)));
      form.refocus();
    } finally {
      setPending(false);
    }
  });

  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-md gap-6">
        <h1 className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('signIn')}</h1>
        {notice && <StatusMessage message={t(notice)} />}
        <form noValidate onSubmit={onSubmit} className="grid gap-4">
          <ErrorSummary errors={form.summary({ email: t('email'), password: t('password') })} message={message} focusKey={form.focusKey} />
          <Field label={t('email')} type="email" autoComplete="email" {...form.bind('email')} />
          <PasswordField label={t('password')} autoComplete="current-password" {...form.bind('password')} />
          <SubmitButton pending={pending}>{t('signIn')}</SubmitButton>
        </form>
        <div className="flex flex-wrap justify-between gap-4 text-sm">
          <Link to="/forgot-password" className="underline">{t('forgotPassword')}</Link>
          <Link to="/register" className="underline">{t('register')}</Link>
        </div>
      </div>
    </main>
  );
}
