import { BrowserRouter, Route, Routes } from 'react-router';

import { AccountPage } from '../account/AccountPage';
import { AddressesPage } from '../account/AddressesPage';
import { AuthProvider } from '../auth/AuthProvider';
import { ForgotPasswordPage } from '../auth/ForgotPasswordPage';
import { LoginPage } from '../auth/LoginPage';
import { RegisterPage } from '../auth/RegisterPage';
import { ResetPasswordPage } from '../auth/ResetPasswordPage';
import { RequireAuth } from '../auth/RequireAuth';
import { CartProvider } from '../cart/CartProvider';

import { CollectionPage } from '../catalog/CollectionPage';
import { HomePage } from '../catalog/HomePage';
import { ListingPage } from '../catalog/ListingPage';
import { ProductPage } from '../catalog/ProductPage';
import { NotFound, PAGE } from '../catalog/ui';
import { LangProvider, useLang } from '../i18n';
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

export function AppRoutes() {
  return (
    <AuthProvider>
      <CartProvider>
      <SkipLink />
      <Header />
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/products" element={<ListingPage />} />
        <Route path="/search" element={<ListingPage />} />
        <Route path="/products/:id" element={<ProductPage />} />
        <Route path="/collections/:slug" element={<CollectionPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/account" element={<RequireAuth><AccountPage /></RequireAuth>} />
        <Route path="/account/addresses" element={<RequireAuth><AddressesPage /></RequireAuth>} />
        <Route path="*" element={<main id="main" tabIndex={-1} className={PAGE}><NotFound /></main>} />
      </Routes>
      </CartProvider>
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
