// TASK:WEB-02a: shared JSON request helper for the account/cart APIs — reads the `{ code, data, metadata }` envelope and
// the ApiError body (contracts/openapi/common.yaml). Catalog reads keep their own helper in catalog/api.ts.

export type FieldError = { field: string; message: string };

/** Non-2xx response; status 0 means the network failed. `code` is the ApiError code, null when the body is not JSON. */
export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string | null = null, readonly fields: FieldError[] = [], message = '') {
    super(message || `http:${status}${code ? `:${code}` : ''}`);
  }
}

export type RequestInit = { method?: string; body?: unknown; signal?: AbortSignal; headers?: Record<string, string> };

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { ...init.headers };
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  let response: Response;
  try {
    response = await fetch(path, {
      method: init.method ?? 'GET',
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      signal: init.signal,
    });
  } catch (error) {
    if (init.signal?.aborted) throw error;
    throw new HttpError(0);
  }
  if (response.status === 204) return undefined as T;
  const body = (await response.json().catch(() => null)) as
    | { code?: string; data?: T; message?: string; errors?: { field?: string; message?: string }[] }
    | null;
  if (!response.ok) {
    const fields = (body?.errors ?? [])
      .filter((e) => e.field)
      .map((e) => ({ field: e.field as string, message: e.message ?? '' }));
    throw new HttpError(response.status, body?.code ?? null, fields, body?.message ?? '');
  }
  return body?.data as T;
}
