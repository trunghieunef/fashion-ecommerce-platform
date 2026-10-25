// TASK:WEB-01b: sticky header — category mega-menu (desktop), full-screen menu drawer (mobile), search overlay and
// language switch (spec section 3.1). Categories are optional: while loading or failed the header still works.
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';

import { listCategories, listCollections, type Category, type Collection } from '../catalog/api';
import { useLoad } from '../catalog/ui';
import { useAuth } from '../auth/AuthProvider';
import { useCart } from '../cart/CartProvider';
import { useLang } from '../i18n';
import { Drawer } from '../ui/Drawer';
import { BagIcon, ChevronDown, CloseIcon, MenuIcon, SearchIcon, UserIcon } from '../ui/icons';

const LABEL = 'text-sm font-semibold uppercase tracking-[0.02em]';
const COLLECTIONS = 'collections';
const categoryHref = (id: string) => `/products?category_id=${id}`;

type Lazy = { status: 'idle' | 'loading' | 'error' } | { status: 'ready'; items: Collection[] };

/** Collections are fetched the first time a menu needs them, not on every page view (spec 3.1). */
function useLazyCollections(): [Lazy, () => void] {
  const [state, setState] = useState<Lazy>({ status: 'idle' });
  const alive = useRef<AbortController | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    alive.current = controller;
    return () => controller.abort();
  }, []);
  function load() {
    const controller = alive.current;
    if (!controller || state.status === 'loading' || state.status === 'ready') return;
    setState({ status: 'loading' });
    listCollections(controller.signal)
      .then((page) => setState({ status: 'ready', items: page.items }))
      .catch(() => { if (!controller.signal.aborted) setState({ status: 'error' }); });
  }
  return [state, load];
}

function CollectionLinks({ state, retry }: { state: Lazy; retry: () => void }) {
  const { t, pick } = useLang();
  if (state.status === 'error')
    return <p className="text-sm">{t('loadError')} <button type="button" className="underline" onClick={retry}>{t('retry')}</button></p>;
  if (state.status !== 'ready') return <p className="text-sm text-muted">{t('loading')}</p>;
  if (state.items.length === 0) return <p className="text-sm text-muted">{t('noCollections')}</p>;
  return (
    <ul className="grid gap-2">
      {state.items.map((c) => (
        <li key={c.id}><Link to={`/collections/${c.slug}`} className="hover:underline">{pick(c.name_vi, c.name_en)}</Link></li>
      ))}
    </ul>
  );
}

export function Header() {
  const { t, lang, setLang, pick } = useLang();
  const location = useLocation();
  const navigate = useNavigate();
  const [categories] = useLoad(listCategories, []);
  const { status } = useAuth();
  const { count } = useCart();
  // TASK:WEB-02b: the badge number is decorative; the link label carries the count (no competing live region).
  const cartLabel = t('cartLabel').replace('{n}', String(count));
  // TASK:WEB-02a: while the session is being restored the button points at /account (RequireAuth waits) and is busy.
  const account = status === 'guest' ? { href: '/login', label: t('signIn') } : { href: '/account', label: t('account') };
  const [collections, loadCollections] = useLazyCollections();
  const [openMenu, setOpenMenu] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);
  const [drawer, setDrawer] = useState(false);
  const searchButton = useRef<HTMLButtonElement>(null);
  const wasSearching = useRef(false);
  // Escape hands focus back to the menu button; that focus must not reopen the panel it just closed.
  const refocusing = useRef(false);

  // Any navigation closes every menu (Review Focus 2: no drawer left open over the new page).
  useEffect(() => { setOpenMenu(null); setSearching(false); setDrawer(false); }, [location]);
  useEffect(() => {
    if (wasSearching.current && !searching) searchButton.current?.focus();
    wasSearching.current = searching;
  }, [searching]);

  const tops: Category[] = categories.status === 'ready' ? categories.data : [];
  const toggleLang = () => setLang(lang === 'vi' ? 'en' : 'vi');
  function open(id: string) {
    setOpenMenu(id);
    if (id === COLLECTIONS) loadCollections();
  }
  function closeOnEscape(event: KeyboardEvent) {
    if (event.key !== 'Escape' || !openMenu) return;
    const id = openMenu;
    setOpenMenu(null);
    refocusing.current = true;
    document.getElementById(`menu-${id}`)?.focus();
    refocusing.current = false;
  }
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const q = String(new FormData(event.currentTarget).get('q') ?? '').trim();
    if (q) navigate(`/search?q=${encodeURIComponent(q)}`);
  }

  const menuButton = (id: string, label: string) => (
    <button type="button" id={`menu-${id}`} aria-expanded={openMenu === id} aria-controls={`panel-${id}`}
      onPointerEnter={() => open(id)} onFocus={() => { if (!refocusing.current) open(id); }} onClick={() => open(id)}
      className={`${LABEL} flex min-h-11 items-center gap-1 border-b-2 ${openMenu === id ? 'border-fg' : 'border-transparent'}`}>
      {label}
    </button>
  );
  const panel = (id: string, children: ReactNode) => openMenu === id && (
    <div id={`panel-${id}`} className="absolute inset-x-0 top-full border-b border-fg bg-bg px-8 py-6">{children}</div>
  );

  if (searching)
    return (
      <header className="sticky top-0 z-40 border-b border-fg bg-bg">
        <form role="search" onSubmit={search} className="mx-auto flex max-w-screen-2xl items-center gap-2 px-4 py-2 lg:px-8">
          <SearchIcon className="size-5 shrink-0" />
          <label className="sr-only" htmlFor="site-search">{t('searchLabel')}</label>
          <input id="site-search" name="q" type="search" maxLength={100} autoFocus placeholder={t('search')}
            onKeyDown={(e) => { if (e.key === 'Escape') setSearching(false); }}
            className="min-h-11 flex-1 bg-transparent text-base uppercase outline-none placeholder:text-muted" />
          <button type="button" aria-label={t('closeSearch')} onClick={() => setSearching(false)} className="grid size-11 place-items-center">
            <CloseIcon />
          </button>
        </form>
      </header>
    );

  return (
    <header className="sticky top-0 z-40 border-b border-fg bg-bg">
      <div className="relative mx-auto flex max-w-screen-2xl items-center justify-between gap-4 px-4 lg:px-8">
        <button type="button" aria-label={t('menu')} onClick={() => setDrawer(true)} className="-ml-2 grid size-11 place-items-center lg:hidden">
          <MenuIcon />
        </button>
        <Link to="/" className="py-3 text-xl font-bold uppercase tracking-[-0.02em]">{t('store')}</Link>

        <nav aria-label={t('nav')} className="hidden flex-1 self-stretch lg:flex"
          onPointerLeave={() => setOpenMenu(null)} onKeyDown={closeOnEscape}
          onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setOpenMenu(null); }}>
          <ul className="flex flex-wrap items-center gap-x-6">
            {tops.map((c) => {
              const name = pick(c.name_vi, c.name_en);
              if (c.children.length === 0)
                return <li key={c.id}><Link to={categoryHref(c.id)} className={`${LABEL} flex min-h-11 items-center`}>{name}</Link></li>;
              return (
                <li key={c.id}>
                  {menuButton(c.id, name)}
                  {panel(c.id, (
                    <ul className="grid max-w-xs gap-2">
                      <li><Link to={categoryHref(c.id)} className={`${LABEL} hover:underline`}>{t('viewAll')}</Link></li>
                      {c.children.map((child) => (
                        <li key={child.id}><Link to={categoryHref(child.id)} className="hover:underline">{pick(child.name_vi, child.name_en)}</Link></li>
                      ))}
                    </ul>
                  ))}
                </li>
              );
            })}
            <li>
              {menuButton(COLLECTIONS, t('collections'))}
              {panel(COLLECTIONS, <CollectionLinks state={collections} retry={loadCollections} />)}
            </li>
            <li><Link to="/products?sort=NEWEST" className={`${LABEL} flex min-h-11 items-center`}>{t('newArrivals')}</Link></li>
            <li><Link to="/products" className={`${LABEL} flex min-h-11 items-center`}>{t('products')}</Link></li>
          </ul>
        </nav>

        <div className="flex items-center gap-2">
          <button type="button" ref={searchButton} aria-label={t('openSearch')} onClick={() => setSearching(true)} className="grid size-11 place-items-center">
            <SearchIcon />
          </button>
          <Link to={account.href} aria-label={account.label} aria-busy={status === 'unknown' || undefined} className="grid size-11 place-items-center">
            <UserIcon />
          </Link>
          <Link to="/cart" aria-label={cartLabel} className="relative grid size-11 place-items-center">
            <BagIcon />
            {count > 0 && (
              <span aria-hidden="true" className="absolute top-1 right-0 grid min-w-5 place-items-center rounded-full bg-fg px-1 text-xs font-semibold text-bg">
                {count > 99 ? '99+' : count}
              </span>
            )}
          </Link>
          <button type="button" lang={lang === 'vi' ? 'en' : 'vi'} onClick={toggleLang} className={`${LABEL} hidden min-h-11 px-2 lg:block`}>
            {t('switchLang')}
          </button>
        </div>
      </div>

      <Drawer open={drawer} onClose={() => setDrawer(false)} title={t('menu')} side="full">
        <ul className="grid">
          {tops.map((c) => {
            const name = pick(c.name_vi, c.name_en);
            if (c.children.length === 0)
              return <li key={c.id} className="border-b border-fg"><Link to={categoryHref(c.id)} className={`${LABEL} flex min-h-12 items-center`}>{name}</Link></li>;
            return (
              <li key={c.id} className="border-b border-fg">
                <details className="group">
                  <summary className={`${LABEL} flex min-h-12 cursor-pointer list-none items-center justify-between`}>
                    {name}<ChevronDown className="size-5 transition group-open:rotate-180" />
                  </summary>
                  <ul className="grid gap-3 pb-4">
                    <li><Link to={categoryHref(c.id)}>{t('viewAll')}</Link></li>
                    {c.children.map((child) => <li key={child.id}><Link to={categoryHref(child.id)}>{pick(child.name_vi, child.name_en)}</Link></li>)}
                  </ul>
                </details>
              </li>
            );
          })}
          <li className="border-b border-fg">
            <details className="group" onToggle={(e) => { if (e.currentTarget.open) loadCollections(); }}>
              <summary className={`${LABEL} flex min-h-12 cursor-pointer list-none items-center justify-between`}>
                {t('collections')}<ChevronDown className="size-5 transition group-open:rotate-180" />
              </summary>
              <div className="pb-4"><CollectionLinks state={collections} retry={loadCollections} /></div>
            </details>
          </li>
          <li className="border-b border-fg"><Link to="/products?sort=NEWEST" className={`${LABEL} flex min-h-12 items-center`}>{t('newArrivals')}</Link></li>
          <li className="border-b border-fg"><Link to="/products" className={`${LABEL} flex min-h-12 items-center`}>{t('products')}</Link></li>
          <li className="border-b border-fg"><Link to={account.href} className={`${LABEL} flex min-h-12 items-center`}>{account.label}</Link></li>
          {status === 'member' && <li className="border-b border-fg"><Link to="/account/orders" className={`${LABEL} flex min-h-12 items-center`}>{t('myOrders')}</Link></li>}
          <li className="border-b border-fg"><Link to="/cart" className={`${LABEL} flex min-h-12 items-center`}>{cartLabel}</Link></li>
          <li><button type="button" lang={lang === 'vi' ? 'en' : 'vi'} onClick={toggleLang} className={`${LABEL} min-h-12`}>{t('switchLang')}</button></li>
        </ul>
      </Drawer>
    </header>
  );
}
