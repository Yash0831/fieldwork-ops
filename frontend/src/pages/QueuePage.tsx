import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { formatApiError } from '../api/errors';
import { ALL_STATUSES } from '../api/transitions';
import type {
  WorkOrderListResponse,
  WorkOrderPriority,
  WorkOrderStatus,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import {
  ErrorBox,
  PriorityBadge,
  Spinner,
  StatusBadge,
  formatDateTime,
  isBreached,
} from '../components/ui';

const PAGE_SIZE = 20;
const PRIORITIES: WorkOrderPriority[] = ['P1', 'P2', 'P3', 'P4'];

function personName(u: { fullName: string; username: string } | null): string {
  if (!u) return '—';
  return u.fullName || u.username;
}

/**
 * Dispatcher queue. Filters + server-side pagination over
 * GET /api/v1/work-orders. REQUESTERs always see only their own tickets —
 * the backend forces that scoping, so there is nothing to toggle for
 * them; other roles get an optional "Mine only" checkbox.
 */
export default function QueuePage() {
  const { user } = useAuth();
  const [status, setStatus] = useState<'' | WorkOrderStatus>('');
  const [priority, setPriority] = useState<'' | WorkOrderPriority>('');
  const [mine, setMine] = useState(false);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<WorkOrderListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchQueue = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams();
      if (status) params.set('status', status);
      if (priority) params.set('priority', priority);
      if (mine) params.set('mine', 'true');
      params.set('page', String(page));
      params.set('size', String(PAGE_SIZE));
      const result = await api<WorkOrderListResponse>(`/work-orders?${params.toString()}`);
      setData(result);
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setLoading(false);
    }
  }, [status, priority, mine, page]);

  useEffect(() => {
    void fetchQueue();
  }, [fetchQueue]);

  // Any filter change resets to the first page.
  const updateFilter = <T,>(setter: (v: T) => void, value: T) => {
    setter(value);
    setPage(0);
  };

  const isRequester = user?.role === 'REQUESTER';

  return (
    <div>
      <div className="page-head">
        <h1>Ticket queue</h1>
        {isRequester && <p className="muted">Showing tickets you requested.</p>}
      </div>

      <div className="card filters">
        <label className="field inline">
          <span>Status</span>
          <select value={status} onChange={(e) => updateFilter(setStatus, e.target.value as '' | WorkOrderStatus)}>
            <option value="">All</option>
            {ALL_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s.replace('_', ' ')}
              </option>
            ))}
          </select>
        </label>
        <label className="field inline">
          <span>Priority</span>
          <select
            value={priority}
            onChange={(e) => updateFilter(setPriority, e.target.value as '' | WorkOrderPriority)}
          >
            <option value="">All</option>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </label>
        {!isRequester && (
          <label className="check inline">
            <input type="checkbox" checked={mine} onChange={(e) => updateFilter(setMine, e.target.checked)} />
            <span>Mine only</span>
          </label>
        )}
        <button type="button" className="btn" onClick={() => void fetchQueue()}>
          Refresh
        </button>
      </div>

      <ErrorBox error={error} />
      {loading && <Spinner />}

      {!loading && data && (
        <>
          <div className="card table-card">
            <table className="table">
              <thead>
                <tr>
                  <th>Ticket</th>
                  <th>Title</th>
                  <th>Status</th>
                  <th>Priority</th>
                  <th>Assignee</th>
                  <th>Requester</th>
                  <th>SLA due</th>
                  <th>Updated</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((wo) => {
                  const breached = isBreached(wo.sla?.dueAt, wo.status);
                  return (
                    <tr key={wo.id} className={breached ? 'row-breached' : undefined}>
                      <td>
                        <Link to={`/tickets/${wo.id}`} className="ticket-link">
                          {wo.ticketNumber}
                        </Link>
                      </td>
                      <td className="title-cell">{wo.title}</td>
                      <td>
                        <StatusBadge status={wo.status} />
                      </td>
                      <td>
                        <PriorityBadge priority={wo.priority} />
                      </td>
                      <td>{personName(wo.assignee)}</td>
                      <td>{personName(wo.requester)}</td>
                      <td>
                        {breached ? (
                          <span className="badge badge-breach" title={`Due ${wo.sla?.dueAt}`}>
                            BREACHED
                          </span>
                        ) : (
                          formatDateTime(wo.sla?.dueAt)
                        )}
                      </td>
                      <td>{formatDateTime(wo.updatedAt)}</td>
                    </tr>
                  );
                })}
                {data.content.length === 0 && (
                  <tr>
                    <td colSpan={8} className="muted centered">
                      No tickets match these filters.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="pager">
            <button
              type="button"
              className="btn"
              disabled={data.first}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              ← Prev
            </button>
            <span className="muted">
              Page {data.page + 1} of {Math.max(1, data.totalPages)} · {data.totalElements} ticket
              {data.totalElements === 1 ? '' : 's'}
            </span>
            <button
              type="button"
              className="btn"
              disabled={data.last}
              onClick={() => setPage((p) => p + 1)}
            >
              Next →
            </button>
          </div>
        </>
      )}
    </div>
  );
}
