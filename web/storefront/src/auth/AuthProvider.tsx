// TASK:WEB-02a: who the visitor is. Restores the session with one refresh on load (spec §2), follows sign in/out from
// other tabs, and lets later features (WEB-02b cart merge) run code right after a sign in through onSignedIn.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

import * as api from './api';
import { broadcast, endSession, onSessionChange, refreshAccessToken, startSession } from './session';

export type AuthStatus = 'unknown' | 'guest' | 'member';
type Auth = {
  status: AuthStatus;
  me: api.User | null;
  signIn(email: string, password: string): Promise<void>;
  register(input: api.RegisterInput): Promise<void>;
  signOut(): Promise<void>;
  reloadMe(): Promise<void>;
  onSignedIn(callback: () => void): () => void;
};

const Context = createContext<Auth | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('unknown');
  const [me, setMeState] = useState<api.User | null>(null);
  const meRef = useRef<api.User | null>(null);
  const signedIn = useRef(new Set<() => void>());

  const setMe = (user: api.User | null) => { meRef.current = user; setMeState(user); };
  const reloadMe = useCallback(async () => {
    try {
      setMe(await api.getMe());
      setStatus('member');
    } catch {
      setMe(null);
      setStatus('guest');
    }
  }, []);

  useEffect(() => {
    const off = onSessionChange((active) => {
      if (!active) { setMe(null); setStatus('guest'); }
      // Signed in from another tab: we have a token but not the profile yet.
      else if (!meRef.current) void reloadMe();
    });
    // StrictMode runs this twice; refreshAccessToken is single-flight, so only one request goes out.
    refreshAccessToken()
      .then((ok) => (ok ? reloadMe() : setStatus('guest')))
      .catch(() => setStatus('guest'));
    return off;
  }, [reloadMe]);

  const begin = useCallback(async (session: api.Session) => {
    if (session.user) setMe(session.user);
    startSession(session.access_token, session.expires_in);
    if (!session.user) await reloadMe();
    setStatus('member');
    broadcast('login');
    signedIn.current.forEach((callback) => callback());
  }, [reloadMe]);

  const value = useMemo<Auth>(() => ({
    status,
    me,
    signIn: async (email, password) => begin(await api.login(email, password)),
    register: async (input) => begin(await api.register(input)),
    signOut: async () => {
      try {
        await api.logout();
      } catch {
        // The local session ends anyway; the server-side family expires on its own.
      }
      endSession();
      broadcast('logout');
    },
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
