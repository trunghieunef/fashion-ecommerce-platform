// TASK:WEB-02a: request a reset e-mail. Every accepted request shows the same sentence, so the page never tells
// whether an account exists.
import { useState } from 'react';
import { Link } from 'react-router';

import { PAGE } from '../catalog/ui';
import { HttpError } from '../http';
import { useLang } from '../i18n';
import { ErrorSummary, Field, SubmitButton, useForm } from '../ui/form';
import { forgotPassword } from './api';
import { commonError } from './LoginPage';
import { email } from './validate';

export function ForgotPasswordPage() {
  const { t } = useLang();
  const form = useForm({ email: '' }, { email });
  const [pending, setPending] = useState(false);
  const [sent, setSent] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const onSubmit = form.submit(async (values) => {
    if (pending) return;
    setPending(true);
    setMessage(null);
    try {
      await forgotPassword(values.email.trim());
      setSent(true);
    } catch (error) {
      if (error instanceof HttpError && error.status === 400) form.setFieldError('email', t('errEmail'));
      else { setMessage(t(commonError(error))); form.refocus(); }
    } finally {
      setPending(false);
    }
  });

  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-md gap-6">
        <h1 className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('forgotPassword')}</h1>
        {sent ? (
          <p role="status">{t('forgotSent')}</p>
        ) : (
          <form noValidate onSubmit={onSubmit} className="grid gap-4">
            <p className="text-sm text-muted">{t('forgotIntro')}</p>
            <ErrorSummary errors={form.summary({ email: t('email') })} message={message} focusKey={form.focusKey} />
            <Field label={t('email')} type="email" autoComplete="email" {...form.bind('email')} />
            <SubmitButton pending={pending}>{t('sendResetLink')}</SubmitButton>
          </form>
        )}
        <Link to="/login" className="text-sm underline">{t('backToSignIn')}</Link>
      </div>
    </main>
  );
}
