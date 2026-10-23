// TASK:WEB-02a: account routes need a member; a guest goes to /login with the page to come back to.
import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';

import { Loading, PAGE } from '../catalog/ui';
import { useAuth } from './AuthProvider';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const { pathname, search } = useLocation();
  if (status === 'unknown') return <main id="main" tabIndex={-1} className={PAGE}><Loading /></main>;
  if (status === 'guest') return <Navigate to={`/login?next=${encodeURIComponent(pathname + search)}`} replace />;
  return <>{children}</>;
}
