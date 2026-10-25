// TASK:WEB-02a: who the visitor is. Restores the session with one refresh on load (spec §2), follows sign in/out from
// other tabs, and lets later features (WEB-02b cart merge) run code right after a sign in through onSignedIn.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

import { HttpError } from '../http';
import * as api from './api';
import { forgetCheckout } from '../checkout/submit';
import { broadcast, endSession, getAccessToken, onSessionChange, refreshAccessToken, startSession } from './session';

export type AuthStatus = 'unknown' | 'guest' | 'member';
type Auth = {
  status: AuthStatus;
  me: api.User | null;
  signIn(email: string, password: string): Promise<void>;
  register(input: api.RegisterInput): Promise<void>;
  /** Ends the session only after the server revoked it; throws (still signed in) when that failed. Leaves for `/`. */
  signOut(): Promise<void>;
  /** The server already ended the session (password change): end it here and leave protected pages for `to`. */
  leaveSession(to: string, state?: unknown): void;
  /** Where RequireAuth sends a member whose session was ended on purpose; null = /login?next=… */
  pendingExit(): { to: string; state?: unknown } | null;
  /** RequireAuth calls this once it has sent the member to the pending exit. */
  clearExit(): void;
  reloadMe(): Promise<void>;
  /** Runs after every sign in/registration. Errors are contained: a failing callback never fails the sign in. */
  onSignedIn(callback: () => unknown): () => void;
};

const Context = createContext<Auth | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('unknown');
  const [me, setMeState] = useState<api.User | null>(null);
  const meRef = useRef<api.User | null>(null);
  const signedIn = useRef(new Set<() => unknown>());
  const exit = useRef<{ to: string; state?: unknown } | null>(null);

  const setMe = (user: api.User | null) => { meRef.current = user; setMeState(user); };
  const reloadMe = useCallback(async () => {
    try {
      setMe(await api.getMe());
      setStatus('member');
    } catch (error) {
      // authRequest already refreshed and retried once, so a 401 means the session is gone. Anything else (5xx, 429,
      // network) says nothing about the session: keep a valid token signed in; the profile can load later.
      if (!(error instanceof HttpError && error.status === 401) && getAccessToken()) { setStatus('member'); return; }
      setMe(null);
      setStatus('guest');
    }
  }, []);

  useEffect(() => {
    const off = onSessionChange((active, otherTab) => {
      if (!active) { forgetCheckout(); setMe(null); setStatus('guest'); }
      // A token without a profile (boot restore), or another tab signed in — possibly as someone else.
      else if (otherTab || !meRef.current) void reloadMe();
    });
    // StrictMode runs this twice; refreshAccessToken is single-flight, so only one request goes out. A successful
    // restore loads the profile through the listener above.
    refreshAccessToken()
      .then((ok) => { if (!ok) setStatus('guest'); })
      .catch(() => setStatus('guest'));
    return off;
  }, [reloadMe]);

  const begin = useCallback(async (session: api.Session) => {
    exit.current = null;
    if (session.user) setMe(session.user);
    startSession(session.access_token, session.expires_in);
    if (!session.user) await reloadMe();
    if (getAccessToken()) setStatus('member');
    broadcast('login');
    // TASK:WEB-02b: each callback (e.g. the cart merge) is isolated, sync throw or async rejection alike.
    signedIn.current.forEach((callback) => {
      try {
        void Promise.resolve(callback()).catch(() => undefined);
      } catch {
        // contained: the sign in itself succeeded
      }
    });
  }, [reloadMe]);

  const value = useMemo<Auth>(() => ({
    status,
    me,
    signIn: async (email, password) => begin(await api.login(email, password)),
    register: async (input) => begin(await api.register(input)),
    signOut: async () => {
      // The refresh cookie is HttpOnly: only the server can drop it. If that fails, stay signed in rather than pretend.
      await api.logout();
      exit.current = { to: '/' };
      endSession();
      broadcast('logout');
    },
    leaveSession: (to, state) => {
      exit.current = { to, state };
      endSession();
      broadcast('logout');
    },
    pendingExit: () => exit.current,
    clearExit: () => { exit.current = null; },
    reloadMe,
    onSignedIn: (callback) => {
      signedIn.current.add(callback);
      return () => { signedIn.current.delete(callback); };
    },
  }), [status, me, begin, reloadMe]);

  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useAuth(): Auth {
  const value = useContext(Context);
  if (!value) throw new Error('useAuth outside AuthProvider');
  return value;
}
