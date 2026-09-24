import type { JwtClaims, RoleName } from '../api/types';

const VALID_ROLES: RoleName[] = ['ADMIN', 'DISPATCHER', 'TECHNICIAN', 'REQUESTER'];

/** base64url -> base64, then decode. Throws on malformed input. */
function base64UrlDecode(segment: string): string {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  // atob yields a Latin-1 string; re-encode to recover UTF-8.
  const bytes = Uint8Array.from(binary, (ch) => ch.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/**
 * Decodes the middle segment of a JWT WITHOUT verifying the signature.
 * Client-side we only use it to read the role for UI gating — the
 * backend re-validates the token on every request, which is the real
 * authorization boundary.
 *
 * Throws when the token is malformed or lacks sub/email/role.
 */
export function decodeJwt(token: string): JwtClaims {
  const parts = token.split('.');
  if (parts.length !== 3) throw new Error('Malformed token: expected three segments');
  let payload: unknown;
  try {
    payload = JSON.parse(base64UrlDecode(parts[1]));
  } catch {
    throw new Error('Malformed token: payload is not valid JSON');
  }
  if (typeof payload !== 'object' || payload === null) {
    throw new Error('Malformed token: payload is not an object');
  }
  const { sub, email, role } = payload as Record<string, unknown>;
  if (typeof sub !== 'string' || sub.length === 0) throw new Error('Token is missing the subject claim');
  if (typeof email !== 'string' || email.length === 0) throw new Error('Token is missing the email claim');
  if (typeof role !== 'string' || !VALID_ROLES.includes(role as RoleName)) {
    throw new Error(`Token carries an unknown role: ${String(role)}`);
  }
  return { ...(payload as object), sub, email, role: role as RoleName } as JwtClaims;
}
