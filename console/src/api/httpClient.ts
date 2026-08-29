import type { ApiErrorShape } from './types';
import { getMemoryAccessToken } from '../auth/memoryToken';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: Record<string, unknown>;

  constructor(status: number, payload: ApiErrorShape) {
    super(payload.message || '请求失败');
    this.name = 'ApiError';
    this.status = status;
    this.code = payload.code || codeForStatus(status);
    this.details = payload.details;
  }
}

function codeForStatus(status: number) {
  switch (status) {
    case 401:
      return 'UNAUTHENTICATED';
    case 403:
      return 'FORBIDDEN';
    case 409:
      return 'CONFLICT';
    case 429:
      return 'RATE_LIMITED';
    case 503:
      return 'UNAVAILABLE_DEPENDENCY';
    default:
      return 'REQUEST_FAILED';
  }
}

type RequestOptions = {
  method?: string;
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  headers?: HeadersInit;
  signal?: AbortSignal;
};

type ClientOptions = {
  baseUrl?: string;
  getAccessToken?: () => string | undefined;
  onUnauthorized?: () => void;
};

export type HttpClient = {
  request<T>(path: string, options?: RequestOptions): Promise<T>;
  requestText(path: string, options?: RequestOptions): Promise<string>;
};

export function createHttpClient(options: ClientOptions = {}): HttpClient {
  async function send(path: string, requestOptions: RequestOptions) {
    const method = (requestOptions.method || 'GET').toUpperCase();
    const url = new URL(path, options.baseUrl || window.location.origin);
    Object.entries(requestOptions.query || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== '') url.searchParams.set(key, String(value));
    });
    const headers = new Headers(requestOptions.headers);
    if (!headers.has('Accept')) headers.set('Accept', 'application/json');
    if (requestOptions.body !== undefined) headers.set('Content-Type', 'application/json');
    const token = options.getAccessToken?.();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    if (method !== 'GET' && method !== 'HEAD') {
      headers.set('Idempotency-Key', headers.get('Idempotency-Key') || crypto.randomUUID());
    }
    const response = await fetch(
      new Request(url, {
        method,
        headers,
        body: requestOptions.body === undefined ? undefined : JSON.stringify(requestOptions.body),
        signal: requestOptions.signal,
      }),
    );
    if (!response.ok) {
      const text = await response.text();
      let payload: ApiErrorShape = { message: text || '请求失败' };
      try {
        payload = JSON.parse(text) as ApiErrorShape;
      } catch {
        // Non-JSON gateway errors still become the same typed ApiError.
      }
      const error = new ApiError(response.status, payload);
      if (response.status === 401) options.onUnauthorized?.();
      throw error;
    }
    return response;
  }

  return {
    async request<T>(path: string, requestOptions: RequestOptions = {}) {
      const response = await send(path, requestOptions);
      if (response.status === 204) return undefined as T;
      return (await response.json()) as T;
    },
    async requestText(path: string, requestOptions: RequestOptions = {}) {
      return (await send(path, requestOptions)).text();
    },
  };
}

export const apiClient = createHttpClient({
  getAccessToken: getMemoryAccessToken,
  onUnauthorized: () => window.dispatchEvent(new Event('agentteams:unauthorized')),
});
