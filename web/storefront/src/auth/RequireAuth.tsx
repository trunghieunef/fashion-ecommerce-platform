// TASK:WEB-02a: account routes need a member; a guest goes to /login with the page to come back to.
import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';

import { Loading, PAGE } from '../catalog/ui';
import { useAuth } from './AuthProvider';

/** Goes to a deliberate exit once, then forgets it so a later guest visit gets the usual /login?next=. */
function ExitTo({ to, state, done }: { to: string; state?: unknown; done: () => void }) {
  const navigate = useNavigate();
  useEffect(() => { navigate(to, { replace: true, state }); done(); }, []);
  return null;
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, pendingExit, clearExit } = useAuth();
  const { pathname, search } = useLocation();
  if (status === 'unknown') return <main id="main" tabIndex={-1} className={PAGE}><Loading /></main>;
  if (status === 'guest') {
    // Signed out on purpose (sign out, password change): go where that action said, not back to /login?next=.
    const exit = pendingExit();
    if (exit) return <ExitTo to={exit.to} state={exit.state} done={clearExit} />;
    return <Navigate to={`/login?next=${encodeURIComponent(pathname + search)}`} replace />;
  }
  return <>{children}</>;
}
