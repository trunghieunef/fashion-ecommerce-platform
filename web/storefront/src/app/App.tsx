import { BrowserRouter, Link, Route, Routes } from 'react-router';

import { CatalogPage } from '../catalog/CatalogPage';
import './styles.css';

export function App() {
  return (
    <BrowserRouter>
      <header className="site-header">
        <Link to="/">Fashion Store</Link>
        <nav aria-label="Điều hướng chính">
          <Link to="/products">Sản phẩm</Link>
        </nav>
      </header>
      <Routes>
        <Route path="/" element={<CatalogPage />} />
        <Route path="/products" element={<CatalogPage />} />
      </Routes>
    </BrowserRouter>
  );
}
