// TASK:WEB-02a: the member's account — profile (name and the language e-mails are sent in; the UI language stays the
// visitor's own choice), password change (the server ends every session, so the member signs in again), sign out.
import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';

import * as api from '../auth/api';
import { useAuth } from '../auth/AuthProvider';
import { maxLength, password, required } from '../auth/validate';
import { BUTTON_OUTLINE, Failed, LABEL, Loading, PAGE } from '../catalog/ui';
import { HttpError } from '../http';
import { useLang } from '../i18n';
import { ErrorSummary, Field, PasswordField, StatusMessage, SubmitButton, useForm } from '../ui/form';
import { commonError } from '../auth/LoginPage';

const H2 = 'text-xl font-bold tracking-[-0.02em] uppercase';

function ProfileForm({ profile }: { profile: api.Profile }) {
  const { t } = useLang();
  const form = useForm({ full_name: profile.full_name, locale: profile.locale as string }, { full_name: maxLength(200) });
  const [pending, setPending] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const { error: _unused, ...locale } = form.bind('locale');

  const onSubmit = form.submit(async (values) => {
    if (pending) return;
    setPending(true);
    setStatus(null);
    setMessage(null);
    try {
      await api.updateMe({ full_name: values.full_name.trim(), locale: values.locale === 'en' ? 'en' : 'vi' });
      setStatus(t('saved'));
    } catch (error) {
      setMessage(t(commonError(error)));
      form.refocus();
    } finally {
      setPending(false);
    }
  });

  return (
    <form noValidate aria-label={t('profile')} onSubmit={onSubmit} className="grid gap-4">
      <h2 className={H2}>{t('profile')}</h2>
      <p className="text-sm"><span className={LABEL}>{t('email')}</span><br />{profile.email}</p>
      <ErrorSummary errors={form.summary({ full_name: t('fullName'), locale: t('emailLanguage') })} message={message} focusKey={form.focusKey} />
      <Field label={t('fullName')} autoComplete="name" {...form.bind('full_name')} />
      <div className="grid gap-1">
        <label htmlFor="locale" className={LABEL}>{t('emailLanguage')}</label>
        <select id="locale" {...locale} className="min-h-11 border border-line bg-bg px-3">
          <option value="vi">Tiếng Việt</option>
          <option value="en">English</option>
        </select>
      </div>
      <SubmitButton pending={pending}>{t('save')}</SubmitButton>
      <StatusMessage message={status} />
    </form>
  );
}

function PasswordForm() {
  const { t } = useLang();
  const navigate = useNavigate();
  const form = useForm({ current_password: '', new_password: '' }, { current_password: required, new_password: password });
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const onSubmit = form.submit(async (values) => {
    if (pending) return;
    setPending(true);
    setMessage(null);
    try {
      await api.changePassword(values.current_password, values.new_password);
      // The server already ended every session. The local one ends on the login page (signOut flag): ending it here
      // would let RequireAuth redirect to /login?next=/account before this navigation lands, losing the notice.
      navigate('/login', { replace: true, state: { notice: 'passwordChanged', signOut: true } });
    } catch (error) {
      if (error instanceof HttpError && error.status === 400 && error.fields.length > 0)
        error.fields.forEach((f) => form.setFieldError(
          f.field === 'current_password' ? 'current_password' : 'new_password',
          t(f.field === 'current_password' ? 'errCurrentPassword' : 'errInvalid')));
      else { setMessage(t(commonError(error))); form.refocus(); }
    } finally {
      setPending(false);
    }
  });

  return (
    <form noValidate aria-label={t('changePassword')} onSubmit={onSubmit} className="grid gap-4">
      <h2 className={H2}>{t('changePassword')}</h2>
      <ErrorSummary errors={form.summary({ current_password: t('currentPassword'), new_password: t('newPassword') })}
        message={message} focusKey={form.focusKey} />
      <PasswordField label={t('currentPassword')} autoComplete="current-password" {...form.bind('current_password')} />
      <PasswordField label={t('newPassword')} autoComplete="new-password" hint={t('passwordHint')} {...form.bind('new_password')} />
      <SubmitButton pending={pending}>{t('changePassword')}</SubmitButton>
    </form>
  );
}

export function AccountPage() {
  const { t } = useLang();
  const { signOut } = useAuth();
  const navigate = useNavigate();
  const [profile, setProfile] = useState<api.Profile | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let alive = true;
    setFailed(false);
    api.getMe().then((p) => { if (alive) setProfile(p); }, () => { if (alive) setFailed(true); });
    return () => { alive = false; };
  }, [attempt]);

  return (
    <main id="main" tabIndex={-1} className={PAGE}>
      <div className="mx-auto grid max-w-xl gap-10">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <h1 className="text-3xl font-bold tracking-[-0.03em] uppercase">{t('account')}</h1>
          <Link to="/account/addresses" className={`${LABEL} underline underline-offset-4`}>{t('manageAddresses')}</Link>
        </div>
        {failed && <Failed notFound={false} retry={() => setAttempt((n) => n + 1)} />}
        {!failed && !profile && <Loading />}
        {profile && <ProfileForm profile={profile} />}
        <PasswordForm />
        <button type="button" className={BUTTON_OUTLINE}
          onClick={async () => { navigate('/', { replace: true }); await signOut(); }}>
          {t('signOut')}
        </button>
      </div>
    </main>
  );
}
