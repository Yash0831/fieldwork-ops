import test from 'node:test';
import assert from 'node:assert/strict';
import { decodeJwt } from '../src/auth/jwt.ts';

function b64url(obj: unknown): string {
  return Buffer.from(JSON.stringify(obj)).toString('base64url');
}

function makeToken(payload: unknown): string {
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.fakesignature`;
}

test('decodes sub, email and role from the payload', () => {
  const claims = decodeJwt(
    makeToken({ sub: '3fa85f64-5717-4562-b3fc-2c963f66afa6', email: 'tech@example.com', role: 'TECHNICIAN' }),
  );
  assert.equal(claims.sub, '3fa85f64-5717-4562-b3fc-2c963f66afa6');
  assert.equal(claims.email, 'tech@example.com');
  assert.equal(claims.role, 'TECHNICIAN');
});

test('accepts every known role', () => {
  for (const role of ['ADMIN', 'DISPATCHER', 'TECHNICIAN', 'REQUESTER']) {
    const claims = decodeJwt(makeToken({ sub: 'x', email: 'a@b.c', role }));
    assert.equal(claims.role, role);
  }
});

test('rejects tokens with the wrong segment count', () => {
  assert.throws(() => decodeJwt('only.two'), /three segments/);
  assert.throws(() => decodeJwt('a.b.c.d'), /three segments/);
});

test('rejects a non-JSON payload', () => {
  const bad = `${b64url({ alg: 'x' })}.not-json-at-all.sig`;
  assert.throws(() => decodeJwt(bad), /not valid JSON/);
});

test('rejects missing claims', () => {
  assert.throws(() => decodeJwt(makeToken({ email: 'a@b.c', role: 'ADMIN' })), /subject/);
  assert.throws(() => decodeJwt(makeToken({ sub: 'x', role: 'ADMIN' })), /email/);
  assert.throws(() => decodeJwt(makeToken({ sub: 'x', email: 'a@b.c' })), /role/);
});

test('rejects an unknown role', () => {
  assert.throws(
    () => decodeJwt(makeToken({ sub: 'x', email: 'a@b.c', role: 'SUPERUSER' })),
    /unknown role/,
  );
});

test('decodes base64url-specific characters (- and _)', () => {
  // Craft a payload whose base64 contains +/ so base64url uses -_ instead.
  const payload = { sub: '>>>???', email: 'a@b.c', role: 'ADMIN' };
  const claims = decodeJwt(makeToken(payload));
  assert.equal(claims.sub, '>>>???');
});
