import type { TokenResponse } from './types';

const API_BASE = '/api/v1';
const TOKEN_KEY = 'rentgg.tokens';

export interface StoredTokens {
  accessToken: string;
  refreshToken: string;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details?: Record<string, unknown>,
    /** Backend trace ID (X-Trace-Id), quoted to support when reporting a server error. */
    public traceId?: string,
  ) {
    super(message);
  }
}

export function loadTokens(): StoredTokens | null {
  try {
    const raw = localStorage.getItem(TOKEN_KEY);
    return raw ? (JSON.parse(raw) as StoredTokens) : null;
  } catch {
    return null;
  }
}

export function saveTokens(tokens: StoredTokens | null): void {
  try {
    if (tokens) localStorage.setItem(TOKEN_KEY, JSON.stringify(tokens));
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // Storage can be blocked (private mode); the session then lives in memory only.
  }
}

/** Invoked when refresh fails so the app can drop back to the login screen. */
let onSessionExpired: (() => void) | null = null;
export function setSessionExpiredHandler(fn: () => void): void {
  onSessionExpired = fn;
}

let refreshing: Promise<boolean> | null = null;

async function refreshTokens(): Promise<boolean> {
  const current = loadTokens();
  if (!current) return false;
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: current.refreshToken }),
    });
  } catch {
    // Network failure: keep the stored tokens so the user is not logged out by a transient outage.
    throw new ApiError(0, 'NETWORK_ERROR', 'Không thể kết nối tới máy chủ. Kiểm tra mạng và thử lại.');
  }
  if (!res.ok) {
    saveTokens(null);
    return false;
  }
  const next = (await res.json()) as TokenResponse;
  saveTokens({ accessToken: next.accessToken, refreshToken: next.refreshToken });
  return true;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  /** JSON-encoded, except FormData which is sent as multipart (file uploads). */
  body?: unknown;
  /** 'blob' returns the raw body (images) instead of parsing JSON. */
  responseType?: 'json' | 'blob';
  /** Required by the backend for order, payment and withdrawal writes. */
  idempotencyKey?: string;
  /** Skip the bearer token (login, register). */
  anonymous?: boolean;
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const doFetch = (forceAnonymous = false) => {
    const headers: Record<string, string> = {};
    const isForm = opts.body instanceof FormData;
    // The browser sets the multipart Content-Type (with boundary) itself.
    if (opts.body !== undefined && !isForm) headers['Content-Type'] = 'application/json';
    if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;
    const tokens = loadTokens();
    if (!opts.anonymous && !forceAnonymous && tokens) headers.Authorization = `Bearer ${tokens.accessToken}`;
    return fetch(`${API_BASE}${path}`, {
      method: opts.method ?? 'GET',
      headers,
      body: isForm ? (opts.body as FormData) : opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    });
  };

  const send = async (forceAnonymous = false): Promise<Response> => {
    try {
      return await doFetch(forceAnonymous);
    } catch {
      // fetch() rejects only when the request never reached the server (offline, DNS, CORS, server down).
      throw new ApiError(0, 'NETWORK_ERROR', 'Không thể kết nối tới máy chủ. Kiểm tra mạng và thử lại.');
    }
  };

  let res = await send();

  // Access token expired: refresh once (shared across parallel requests) and retry.
  if (res.status === 401 && !opts.anonymous && loadTokens()) {
    refreshing ??= refreshTokens().finally(() => {
      refreshing = null;
    });
    const ok = await refreshing;
    if (ok) {
      res = await send();
    } else {
      onSessionExpired?.();
      // The backend rejects an invalid token even on public endpoints (games, listings),
      // so retry once without it. Protected endpoints simply answer 401 again.
      res = await send(true);
    }
  }

  const traceId = res.headers.get('X-Trace-Id') ?? undefined;
  if (res.status === 204) return undefined as T;
  if (opts.responseType === 'blob' && res.ok) return (await res.blob()) as T;
  const text = await res.text();
  let data: unknown;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    // Not JSON (proxy or gateway error page): report it without leaking the body.
    if (!res.ok) throw new ApiError(res.status, 'UNKNOWN', `Máy chủ trả về lỗi ${res.status}.`, undefined, traceId);
    throw new ApiError(res.status, 'INVALID_RESPONSE', 'Phản hồi từ máy chủ không hợp lệ.', undefined, traceId);
  }
  if (!res.ok) {
    const err = (data ?? {}) as { code?: string; message?: string; details?: Record<string, unknown>; traceId?: string };
    throw new ApiError(
      res.status,
      err.code ?? 'UNKNOWN',
      err.message ?? `Lỗi ${res.status}`,
      err.details,
      err.traceId ?? traceId,
    );
  }
  return data as T;
}

/** Fresh key per user action; reuse it only when retrying the same action. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
