// TASK:WEB-02a: the signed-in session (spec §2). The access token lives only in this module — never in web storage.
// The refresh cookie is single use (reuse revokes the whole family), so refreshes are single-flight: one shared Promise
// inside a tab, wrapped in a Web Lock across tabs. Other tabs learn about sign in/out through a BroadcastChannel.
import { HttpError, request, type RequestInit } from '../http';

const LOCK = 'storefront.auth.refresh';
const CHANNEL = 'storefront.auth';
const EARLY_REFRESH_MS = 30_000;

type Message = { type: 'login' | 'logout' };
type Tokens = { access_token: string; expires_in: number };

let token: string | null = null;
let expiresAt = 0;
let inflight: Promise<boolean> | null = null;
// Bumped by startSession/endSession: a refresh answered after either belongs to an older state and is ignored.
let epoch = 0;
let channel: BroadcastChannel | null = null;
/** `otherTab` is true when another tab signed in (the account may have changed even if this tab was signed in). */
const listeners = new Set<(signedIn: boolean, otherTab?: boolean) => void>();

export const getAccessToken = () => token;

function setToken(next: string | null, expiresIn = 0) {
  const was = token !== null;
  token = next;
  expiresAt = next ? Date.now() + expiresIn * 1000 : 0;
  if (was !== (next !== null)) listeners.forEach((listener) => listener(next !== null));
}

export function startSession(accessToken: string, expiresIn: number) {
  connect();
  epoch++;
  setToken(accessToken, expiresIn);
}

export function endSession() {
  epoch++;
  setToken(null);
}

export function onSessionChange(listener: (signedIn: boolean, otherTab?: boolean) => void): () => void {
  connect();
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Single-flight refresh: true = new token, false = no valid refresh cookie (signed out). Network/5xx errors throw. */
export function refreshAccessToken(): Promise<boolean> {
  if (inflight) return inflight;
  const run = async () => {
    const started = epoch;
    try {
      const tokens = await request<Tokens>('/api/v1/auth/refresh', { method: 'POST' });
      if (epoch !== started) return false;
      setToken(tokens.access_token, tokens.expires_in);
      return true;
    } catch (error) {
      if (error instanceof HttpError && (error.status === 401 || error.status === 403)) {
        if (epoch === started) endSession();
        return false;
      }
      throw error;
    }
  };
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  inflight = (locks ? (locks.request(LOCK, run) as Promise<boolean>) : run()).finally(() => { inflight = null; });
  return inflight;
}

/** Bearer request: refreshes first when the token is missing or nearly expired; after a 401 refreshes and retries once. */
export async function authRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!token || expiresAt - Date.now() < EARLY_REFRESH_MS) {
    if (!(await refreshAccessToken())) throw new HttpError(401, 'UNAUTHORIZED');
  }
  const send = () => request<T>(path, { ...init, headers: { ...init.headers, Authorization: `Bearer ${token}` } });
  try {
    return await send();
  } catch (error) {
    if (!(error instanceof HttpError) || error.status !== 401) throw error;
    if (!(await refreshAccessToken())) throw new HttpError(401, 'UNAUTHORIZED');
    return send();
  }
}

function connect() {
  if (channel || typeof BroadcastChannel === 'undefined') return;
  channel = new BroadcastChannel(CHANNEL);
  channel.onmessage = (event: MessageEvent<Message>) => {
    if (event.data?.type === 'logout') endSession();
    if (event.data?.type === 'login')
      refreshAccessToken().then((ok) => { if (ok) listeners.forEach((listener) => listener(true, true)); }, () => undefined);
  };
}

export function broadcast(type: Message['type']) {
  connect();
  channel?.postMessage({ type } satisfies Message);
}

export function resetSessionForTests() {
  token = null;
  expiresAt = 0;
  inflight = null;
  listeners.clear();
  channel?.close();
  channel = null;
}
