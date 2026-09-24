import type { ErrorEnvelope } from './types';

/**
 * Error raised for any non-2xx API response. Carries the backend's
 * standard error envelope fields so pages can render `message` and the
 * per-field validation failures.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: Array<{ field: string; message: string }>;

  constructor(status: number, message: string, fieldErrors: Array<{ field: string; message: string }> = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

/** Best-effort parse of the backend error envelope from a failed Response. */
export async function toApiError(res: Response): Promise<ApiError> {
  const fallback = `${res.status} ${res.statusText || 'Request failed'}`.trim();
  let message = fallback;
  let fieldErrors: Array<{ field: string; message: string }> = [];
  try {
    const body = (await res.json()) as Partial<ErrorEnvelope> | null;
    if (body && typeof body === 'object') {
      if (typeof body.message === 'string' && body.message.trim().length > 0) {
        message = body.message;
      }
      if (Array.isArray(body.fieldErrors)) {
        fieldErrors = body.fieldErrors
          .filter((fe) => fe && typeof fe.field === 'string' && typeof fe.message === 'string')
          .map((fe) => ({ field: fe.field, message: fe.message }));
      }
    }
  } catch {
    // Non-JSON error body (proxy error page, connection reset HTML, ...).
    // Keep the status-based fallback message.
  }
  return new ApiError(res.status, message, fieldErrors);
}

/** Human-readable rendering of an ApiError for the UI. */
export function formatApiError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.fieldErrors.length > 0) {
      const details = err.fieldErrors.map((fe) => `${fe.field}: ${fe.message}`).join('; ');
      return `${err.message} — ${details}`;
    }
    return err.message;
  }
  if (err instanceof Error) return err.message;
  return 'An unexpected error occurred.';
}
