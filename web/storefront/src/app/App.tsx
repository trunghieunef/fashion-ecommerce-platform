import type { FormEvent } from 'react';
import { BrowserRouter, Link, Route, Routes, useNavigate } from 'react-router';

import { CollectionPage } from '../catalog/CollectionPage';
import { HomePage } from '../catalog/HomePage';
import { ListingPage } from '../catalog/ListingPage';
import { ProductPage } from '../catalog/ProductPage';
import { NotFound } from '../catalog/ui';
import { LangProvider, useLang } from '../i18n';
import './styles.css';

function Header() {
  const { t, lang, setLang } = useLang();
  const navigate = useNavigate();
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const q = String(new FormData(event.currentTarget).get('q') ?? '').trim();
    if (q) navigate(`/search?q=${encodeURIComponent(q)}`);
  }
  return (
    <header className="site-header">
      <Link to="/" className="brand">{t('store')}</Link>
      <nav aria-label={t('nav')}>
        <Link to="/">{t('home')}</Link>
        <Link to="/products">{t('products')}</Link>
      </nav>
      <form role="search" onSubmit={search}>
        <label className="visually-hidden" htmlFor="site-search">{t('searchLabel')}</label>
        <input id="site-search" name="q" type="search" maxLength={100} placeholder={t('search')} />
        <button type="submit">{t('search')}</button>
      </form>
      <button type="button" className="lang" lang={lang === 'vi' ? 'en' : 'vi'} onClick={() => setLang(lang === 'vi' ? 'en' : 'vi')}>
        {t('switchLang')}
      </button>
    </header>
  );
}

export function AppRoutes() {
  return (
    <>
      <Header />
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/products" element={<ListingPage />} />
        <Route path="/search" element={<ListingPage />} />
        <Route path="/products/:id" element={<ProductPage />} />
        <Route path="/collections/:slug" element={<CollectionPage />} />
        <Route path="*" element={<main className="page"><NotFound /></main>} />
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
