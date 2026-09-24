import type { TokenResponse } from './types';
import { ApiError, toApiError } from './errors';

/**
 * API client: fetch wrapper with bearer auth and single-shot refresh.
 *
 * Token storage
 * --------------
 * Tokens live ONLY in this module's memory (module-scope variables).
 * They are deliberately never written to localStorage/sessionStorage:
 * web storage is readable by any JavaScript executing on the page, so a
 * single stored-XSS flaw would turn into persistent credential theft.
 * In-memory tokens die with the tab/process, and the backend rotates
 * refresh tokens on every use, bounding replay. Trade-off: a full page
 * reload drops the session and the user must sign in again.
 *
 * Refresh flow
 * ------------
 * On a 401 the client attempts exactly ONE refresh via
 * POST /api/v1/auth/refresh, then retries the original request once with
 * the new access token. Concurrent 401s share a single in-flight
 * refresh (no token-rotation stampede). If the refresh fails (or there
 * is no refresh token), tokens are cleared and the registered
 * `onUnauthorized` handler fires — the auth provider wires it to send
 * the user to /login.
 */
const API_BASE: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '/api/v1';

interface StoredTokens {
  accessToken: string;
  refreshToken: string;
}

let tokens: StoredTokens | null = null;
let onUnauthorized: (() => void) | null = null;
let refreshInFlight: Promise<boolean> | null = null;

export function setTokens(next: StoredTokens | null): void {
  tokens = next;
}

export function getAccessToken(): string | null {
  return tokens?.accessToken ?? null;
}

export function getRefreshToken(): string | null {
  return tokens?.refreshToken ?? null;
}

export function registerUnauthorizedHandler(fn: () => void): void {
  onUnauthorized = fn;
}

function handleUnauthorized(): void {
  tokens = null;
  refreshInFlight = null;
  onUnauthorized?.();
}

function join(base: string, path: string): string {
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`;
}

/** Login does not need (or use) an access token. */
export async function loginRequest(email: string, password: string): Promise<TokenResponse> {
  const res = await fetch(join(API_BASE, '/auth/login'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as TokenResponse;
}

/** Best-effort server-side revocation of the refresh token. */
export async function logoutRequest(refreshToken: string): Promise<void> {
  try {
    await fetch(join(API_BASE, '/auth/logout'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
  } catch {
    // Logout is idempotent server-side; a network failure here must not
    // block clearing the local session.
  }
}

/**
 * Attempts one token rotation. Returns true when the module now holds a
 * fresh access token. Concurrent callers share the single in-flight
 * request so a burst of 401s rotates the refresh token exactly once
 * (the backend revokes the whole token family on replay).
 */
async function tryRefresh(): Promise<boolean> {
  const current = tokens;
  if (!current?.refreshToken) return false;
  if (!refreshInFlight) {
    const presentedRefresh = current.refreshToken;
    refreshInFlight = (async () => {
      try {
        const res = await fetch(join(API_BASE, '/auth/refresh'), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: presentedRefresh }),
        });
        if (!res.ok) return false;
        const body = (await res.json()) as TokenResponse;
        if (!body.accessToken || !body.refreshToken) return false;
        tokens = { accessToken: body.accessToken, refreshToken: body.refreshToken };
        return true;
      } catch {
        return false;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

async function rawRequest(path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers);
  const accessToken = getAccessToken();
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  return fetch(join(API_BASE, path), { ...init, headers });
}

/**
 * Authenticated request. On 401, refreshes once and retries the original
 * request once. Throws ApiError for non-2xx responses (parsed from the
 * backend error envelope) and forces a logout on unrecoverable 401s.
 */
export async function apiResponse(path: string, init: RequestInit = {}): Promise<Response> {
  let res = await rawRequest(path, init);
  if (res.status === 401) {
    const refreshed = await tryRefresh();
    if (!refreshed) {
      handleUnauthorized();
      throw new ApiError(401, 'Your session has expired. Please sign in again.');
    }
    res = await rawRequest(path, init);
    if (res.status === 401) {
      // The fresh token was rejected too — treat the session as dead.
      handleUnauthorized();
      throw new ApiError(401, 'Your session has expired. Please sign in again.');
    }
  }
  if (!res.ok) throw await toApiError(res);
  return res;
}

/** JSON convenience wrapper over apiResponse. Handles 204 (no content). */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await apiResponse(path, init);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export function jsonBody<T>(body: T): RequestInit {
  return {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

/**
 * Downloads an attachment through the authenticated client (a plain
 * <a href> cannot carry the bearer token) and saves it with its stored
 * file name.
 */
export async function downloadAttachment(attachmentId: string, fileName: string): Promise<void> {
  const res = await apiResponse(`/attachments/${attachmentId}/download`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    // Revoke after the click is dispatched; the download holds its own reference.
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }
}
