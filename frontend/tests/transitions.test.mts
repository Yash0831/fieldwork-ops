import test from 'node:test';
import assert from 'node:assert/strict';
import { TRANSITIONS, nextStates, ALL_STATUSES } from '../src/api/transitions.ts';

/**
 * Guards the client-side transition map against drift from the backend
 * state machine (com.fieldwork.ops.workorder.WorkOrderStateMachine).
 * If the backend adds a state or an edge, update BOTH this table and
 * the expectations below.
 */
test('transition map mirrors the backend state machine', () => {
  assert.deepEqual(TRANSITIONS.OPEN, ['ASSIGNED', 'CANCELLED']);
  assert.deepEqual(TRANSITIONS.ASSIGNED, ['IN_PROGRESS', 'ON_HOLD', 'CANCELLED']);
  assert.deepEqual(TRANSITIONS.IN_PROGRESS, ['ON_HOLD', 'RESOLVED']);
  assert.deepEqual(TRANSITIONS.ON_HOLD, ['IN_PROGRESS', 'RESOLVED']);
  assert.deepEqual(TRANSITIONS.RESOLVED, ['CLOSED']);
  assert.deepEqual(TRANSITIONS.CLOSED, []);
  assert.deepEqual(TRANSITIONS.CANCELLED, []);
});

test('no self-transitions exist (backend rejects no-op moves)', () => {
  for (const status of ALL_STATUSES) {
    assert.ok(
      !TRANSITIONS[status].includes(status),
      `${status} must not transition to itself`,
    );
  }
});

test('nextStates never includes the current status and covers every state', () => {
  for (const status of ALL_STATUSES) {
    const next = nextStates(status);
    assert.ok(!next.includes(status));
    assert.deepEqual(next, TRANSITIONS[status]);
  }
});

test('every status in the map is a known status', () => {
  assert.deepEqual(Object.keys(TRANSITIONS).sort(), [...ALL_STATUSES].sort());
});
