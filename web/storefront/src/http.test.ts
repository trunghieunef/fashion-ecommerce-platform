// @vitest-environment jsdom
// TASK:WEB-02a: the shared JSON request helper reads the API envelope and the ApiError body.
import { afterEach, expect, it, vi } from 'vitest';

import { HttpError, request } from './http';
import { json, mockFetch } from './test/harness';

afterEach(() => { vi.unstubAllGlobals(); });

const metadata = { request_id: 'web02a-test', trace_id: 'web02a-test' };

it('returns the envelope data', async () => {
  mockFetch(() => json({ code: 'OK', data: { id: 7 }, metadata }));
  await expect(request<{ id: number }>('/api/v1/users/me')).resolves.toEqual({ id: 7 });
});

it('204 resolves to undefined', async () => {
  mockFetch(() => new Response(null, { status: 204 }));
  await expect(request('/api/v1/auth/logout', { method: 'POST' })).resolves.toBeUndefined();
});

it('a 400 carries code and field errors', async () => {
  mockFetch(() => json({ code: 'VALIDATION_ERROR', message: 'invalid', errors: [{ field: 'email', message: 'invalid' }], metadata }, 400));
  const error = await request('/api/v1/auth/register', { method: 'POST', body: {} }).catch((e: unknown) => e);
  expect(error).toBeInstanceOf(HttpError);
  expect((error as HttpError).status).toBe(400);
  expect((error as HttpError).code).toBe('VALIDATION_ERROR');
  expect((error as HttpError).fields).toEqual([{ field: 'email', message: 'invalid' }]);
});

it('a non-JSON error body has a null code', async () => {
  mockFetch(() => new Response('<html>bad gateway</html>', { status: 502 }));
  const error = (await request('/api/v1/users/me').catch((e: unknown) => e)) as HttpError;
  expect(error.status).toBe(502);
  expect(error.code).toBeNull();
  expect(error.fields).toEqual([]);
});

it('a network failure is status 0', async () => {
  mockFetch(() => Promise.reject(new TypeError('Failed to fetch')));
  const error = (await request('/api/v1/users/me').catch((e: unknown) => e)) as HttpError;
  expect(error).toBeInstanceOf(HttpError);
  expect(error.status).toBe(0);
});

it('sends JSON with a content type', async () => {
  const calls = mockFetch(() => json({ code: 'OK', data: null, metadata }));
  await request('/api/v1/auth/login', { method: 'POST', body: { email: 'a@example.test' }, headers: { 'X-Test': '1' } });
  expect(calls[0]).toMatchObject({ url: '/api/v1/auth/login', method: 'POST', body: { email: 'a@example.test' } });
  expect(calls[0].headers['content-type']).toBe('application/json');
  expect(calls[0].headers['x-test']).toBe('1');
});
