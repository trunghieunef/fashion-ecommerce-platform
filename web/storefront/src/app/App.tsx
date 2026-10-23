import { BrowserRouter, Route, Routes } from 'react-router';

import { AuthProvider } from '../auth/AuthProvider';
import { RequireAuth } from '../auth/RequireAuth';

import { CollectionPage } from '../catalog/CollectionPage';
import { HomePage } from '../catalog/HomePage';
import { ListingPage } from '../catalog/ListingPage';
import { ProductPage } from '../catalog/ProductPage';
import { NotFound, PAGE } from '../catalog/ui';
import { LangProvider, useLang, type StringKey } from '../i18n';
import { Header } from './Header';
import './styles.css';

function SkipLink() {
  const { t } = useLang();
  return (
    <a href="#main" className="sr-only z-50 bg-fg px-4 py-3 text-bg focus:not-sr-only focus:fixed focus:top-2 focus:left-2">
      {t('skipToContent')}
    </a>
  );
}

/** Placeholder until the WEB-02a page lands (plan tasks 4-7 replace each one). */
function Pending({ title }: { title: StringKey }) {
  const { t } = useLang();
  return <main id="main" tabIndex={-1} className={PAGE}><h1 className="text-3xl font-bold uppercase">{t(title)}</h1></main>;
}

export function AppRoutes() {
  return (
    <AuthProvider>
      <SkipLink />
      <Header />
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/products" element={<ListingPage />} />
        <Route path="/search" element={<ListingPage />} />
        <Route path="/products/:id" element={<ProductPage />} />
        <Route path="/collections/:slug" element={<CollectionPage />} />
        <Route path="/login" element={<Pending title="signIn" />} />
        <Route path="/register" element={<Pending title="register" />} />
        <Route path="/forgot-password" element={<Pending title="forgotPassword" />} />
        <Route path="/reset-password" element={<Pending title="resetPassword" />} />
        <Route path="/account" element={<RequireAuth><Pending title="account" /></RequireAuth>} />
        <Route path="/account/addresses" element={<RequireAuth><Pending title="addresses" /></RequireAuth>} />
        <Route path="*" element={<main id="main" tabIndex={-1} className={PAGE}><NotFound /></main>} />
      </Routes>
    </AuthProvider>
  );
}

export function App() {
  return (
    <LangProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </LangProvider>
  );
}
