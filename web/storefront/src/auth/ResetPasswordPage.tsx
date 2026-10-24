// TASK:WEB-02a: set a new password from the e-mailed link `/reset-password#token=…`. The token is read once and the
// fragment is dropped from the URL straight away (it never reaches the server log or the history entry).
import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';

import { PAGE } from '../catalog/ui';
import { HttpError } from '../http';
import { useLang } from '../i18n';
import { ErrorSummary, PasswordField, SubmitButton, useForm } from '../ui/form';
import { resetPassword } from './api';
import { commonError } from './LoginPage';
import { broadcast, endSession } from './session';
import { password } from './validate';

const tokenOf = (hash: string) => new URLSearchParams(hash.replace(/^#/, '')).get('token')?.trim() ?? '';

export function ResetPasswordPage() {
  const { t } = useLang();
  const navigate = useNavigate();
  const location = useLocation();
  const [token] = useState(() => tokenOf(location.hash));
  const [invalid, setInvalid] = useState(!token);
  const form = useForm({ password: '' }, { password });
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (location.hash) navigate(location.pathname, { replace: true });
  }, []);

  const onSubmit = form.submit(async (values) => {
    if (pending) return;
    setPending(true);
    setMessage(null);
    try {
      await resetPassword(token, values.password);
      // The server revoked every session of this account.
      endSession();
      broadcast('logout');
      navigate('/login', { replace: true, state: { notice: 'passwordChanged' } });
    } catch (error) {
      if (error instanceof HttpError && error.status === 400) setInvalid(true);
      else { setMessage(t(commonError(error))); form.refocus(); }
    } finally {
      setPending(false);
    }
  });

  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-md gap-6">
        <h1 className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('resetPassword')}</h1>
        {invalid ? (
          <div className="grid gap-3">
            <p role="alert">{t('resetInvalid')}</p>
            <Link to="/forgot-password" className="underline">{t('forgotPassword')}</Link>
          </div>
        ) : (
          <form noValidate onSubmit={onSubmit} className="grid gap-4">
            <ErrorSummary errors={form.summary({ password: t('newPassword') })} message={message} focusKey={form.focusKey} />
            <PasswordField label={t('newPassword')} autoComplete="new-password" hint={t('passwordHint')} {...form.bind('password')} />
            <SubmitButton pending={pending}>{t('resetPassword')}</SubmitButton>
          </form>
        )}
      </div>
    </main>
  );
}
