import { describe, expect, it, vi } from 'vitest';
import { ApiError, createHttpClient } from '../../src/api/httpClient';

describe('HTTP client', () => {
  it('adds the in-memory bearer token and idempotency key to writes', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(JSON.stringify({ id: 'team-1' }), { status: 201 }));
    const client = createHttpClient({ getAccessToken: () => 'memory-token' });

    await client.request('/api/v1/teams', {
      method: 'POST',
      body: { name: 'ops', expectedVersion: 4 },
    });

    const request = fetchMock.mock.calls[0][0] as Request;
    expect(request.headers.get('Authorization')).toBe('Bearer memory-token');
    expect(request.headers.get('Idempotency-Key')).toMatch(/[a-f0-9-]{36}/);
    expect(await request.json()).toEqual({ name: 'ops', expectedVersion: 4 });
    fetchMock.mockRestore();
  });

  it('treats an empty successful response as an empty result', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 200 }));
    const client = createHttpClient();

    await expect(client.request('/api/v1/tasks/task-1/recovery')).resolves.toBeUndefined();

    vi.restoreAllMocks();
  });

  it.each([
    [401, 'UNAUTHENTICATED'],
    [403, 'FORBIDDEN'],
    [409, 'CONFLICT'],
    [429, 'RATE_LIMITED'],
    [503, 'UNAVAILABLE_DEPENDENCY'],
  ] as const)('normalizes %s errors as %s', async (status, code) => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ code, message: 'failure', details: { retryAfter: 5 } }), {
        status,
      }),
    );
    const client = createHttpClient();

    await expect(client.request('/api/v1/teams')).rejects.toMatchObject({
      status,
      code,
      details: { retryAfter: 5 },
    } satisfies Partial<ApiError>);
    vi.restoreAllMocks();
  });
});
