import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, formatApiError, toApiError } from '../src/api/errors.ts';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    statusText: 'Error',
    headers: { 'Content-Type': 'application/json' },
  });
}

test('parses the backend error envelope', async () => {
  const res = jsonResponse(422, {
    timestamp: '2026-09-23T12:00:00Z',
    status: 422,
    error: 'Unprocessable Entity',
    message: 'Technician is at capacity',
    path: '/api/v1/work-orders/1/assign',
    fieldErrors: [],
  });
  const err = await toApiError(res);
  assert.ok(err instanceof ApiError);
  assert.equal(err.status, 422);
  assert.equal(err.message, 'Technician is at capacity');
  assert.deepEqual(err.fieldErrors, []);
});

test('carries fieldErrors through', async () => {
  const res = jsonResponse(400, {
    status: 400,
    message: 'Validation failed',
    fieldErrors: [
      { field: 'title', message: 'must not be blank' },
      { field: 'email', message: 'must be a well-formed email address' },
    ],
  });
  const err = await toApiError(res);
  assert.equal(err.fieldErrors.length, 2);
  assert.equal(formatApiError(err), 'Validation failed — title: must not be blank; email: must be a well-formed email address');
});

test('falls back to the status line for non-JSON bodies', async () => {
  const res = new Response('<html>proxy error</html>', { status: 502, statusText: 'Bad Gateway' });
  const err = await toApiError(res);
  assert.equal(err.status, 502);
  assert.match(err.message, /502/);
});

test('falls back when the envelope has no message', async () => {
  const res = jsonResponse(500, { status: 500 });
  const err = await toApiError(res);
  assert.equal(err.status, 500);
  assert.match(err.message, /500/);
});

test('formatApiError handles non-ApiError values', () => {
  assert.equal(formatApiError(new Error('boom')), 'boom');
  assert.equal(formatApiError('plain string'), 'An unexpected error occurred.');
});
