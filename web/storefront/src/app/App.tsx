import { BrowserRouter, Route, Routes } from 'react-router';

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
    <>
      <SkipLink />
      <Header />
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/products" element={<ListingPage />} />
        <Route path="/search" element={<ListingPage />} />
        <Route path="/products/:id" element={<ProductPage />} />
        <Route path="/collections/:slug" element={<CollectionPage />} />
        <Route path="*" element={<main id="main" tabIndex={-1} className={PAGE}><NotFound /></main>} />
      </Routes>
    </>
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
