import type { WorkOrderPriority, WorkOrderStatus } from '../api/types';

/** Short local date-time for tables, e.g. "Sep 23, 14:05". */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Full date-time for detail views. */
export function formatDateTimeFull(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(1)} ${units[unit]}`;
}

export function StatusBadge({ status }: { status: WorkOrderStatus }) {
  return <span className={`badge status-${status.toLowerCase().replace('_', '-')}`}>{status.replace('_', ' ')}</span>;
}

export function PriorityBadge({ priority }: { priority: WorkOrderPriority }) {
  return <span className={`badge priority-${priority.toLowerCase()}`}>{priority}</span>;
}

/** True when the SLA clock has run out and the ticket is still open. */
export function isBreached(dueAt: string | null | undefined, status: WorkOrderStatus): boolean {
  if (!dueAt) return false;
  if (status === 'CLOSED' || status === 'CANCELLED' || status === 'RESOLVED') return false;
  return new Date(dueAt).getTime() < Date.now();
}

export function ErrorBox({ error }: { error: string | null }) {
  if (!error) return null;
  return <div className="alert alert-error" role="alert">{error}</div>;
}

export function Spinner() {
  return <div className="spinner" aria-label="Loading">Loading…</div>;
}
