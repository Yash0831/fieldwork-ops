import type { WorkOrderPriority, WorkOrderStatus } from './types';

/**
 * Client-side mirror of the backend state machine
 * (com.fieldwork.ops.workorder.WorkOrderStateMachine).
 *
 * Used ONLY to decide which transition buttons to render; the backend
 * is the authority and still rejects illegal moves. Keep this table in
 * sync with the backend enum + state machine if either changes.
 */
export const TRANSITIONS: Record<WorkOrderStatus, WorkOrderStatus[]> = {
  OPEN: ['ASSIGNED', 'CANCELLED'],
  ASSIGNED: ['IN_PROGRESS', 'ON_HOLD', 'CANCELLED'],
  IN_PROGRESS: ['ON_HOLD', 'RESOLVED'],
  ON_HOLD: ['IN_PROGRESS', 'RESOLVED'],
  RESOLVED: ['CLOSED'],
  CLOSED: [],
  CANCELLED: [],
};

/** Valid next states from `status` (never includes the current status). */
export function nextStates(status: WorkOrderStatus): WorkOrderStatus[] {
  return TRANSITIONS[status] ?? [];
}

export const ALL_STATUSES: WorkOrderStatus[] = [
  'OPEN',
  'ASSIGNED',
  'IN_PROGRESS',
  'ON_HOLD',
  'RESOLVED',
  'CLOSED',
  'CANCELLED',
];

export const ALL_PRIORITIES: WorkOrderPriority[] = ['P1', 'P2', 'P3', 'P4'];
