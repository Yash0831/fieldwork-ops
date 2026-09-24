import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { formatApiError } from '../api/errors';
import type { BreachResponse, DashboardSummaryResponse } from '../api/types';
import { ErrorBox, PriorityBadge, Spinner, StatusBadge, formatDateTimeFull } from '../components/ui';
import { ALL_PRIORITIES, ALL_STATUSES } from '../api/transitions';

const RECENT_BREACH_LIMIT = 10;

/**
 * Ops dashboard. Renders exactly what the backend exposes:
 * GET /api/v1/dashboard/summary plus the newest rows of
 * GET /api/v1/sla/breaches.
 */
export default function DashboardPage() {
  const [summary, setSummary] = useState<DashboardSummaryResponse | null>(null);
  const [breaches, setBreaches] = useState<BreachResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const [sum, br] = await Promise.all([
          api<DashboardSummaryResponse>('/dashboard/summary'),
          api<BreachResponse[]>('/sla/breaches'),
        ]);
        if (!cancelled) {
          setSummary(sum);
          setBreaches(br.slice(0, RECENT_BREACH_LIMIT));
        }
      } catch (err) {
        if (!cancelled) setError(formatApiError(err));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) return <Spinner />;
  if (error) return <ErrorBox error={error} />;
  if (!summary) return <ErrorBox error="Dashboard data unavailable." />;

  const stats: Array<{ label: string; value: number | string; tone?: string }> = [
    { label: 'Open tickets', value: summary.openWorkOrders, tone: 'tone-blue' },
    { label: 'Unassigned', value: summary.unassignedWorkOrders, tone: 'tone-amber' },
    { label: 'Open breaches', value: summary.openBreaches, tone: 'tone-red' },
    {
      label: 'SLA compliance',
      value: `${summary.slaCompliancePercent.toFixed(1)}%`,
      tone: summary.slaCompliancePercent < 90 ? 'tone-red' : 'tone-blue',
    },
    { label: 'Active SLA policies', value: summary.activePolicies },
    { label: 'Total tickets', value: summary.totalWorkOrders },
  ];

  return (
    <div>
      <div className="page-head">
        <h1>Operations dashboard</h1>
        <p className="muted">Generated {formatDateTimeFull(summary.generatedAt)}</p>
      </div>

      <div className="stat-grid">
        {stats.map((s) => (
          <div key={s.label} className={`card stat ${s.tone ?? ''}`}>
            <div className="stat-value">{s.value}</div>
            <div className="stat-label">{s.label}</div>
          </div>
        ))}
      </div>

      <div className="detail-grid">
        <section className="card">
          <h2>Open tickets by status</h2>
          <table className="table">
            <thead>
              <tr>
                <th>Status</th>
                <th className="num">Count</th>
              </tr>
            </thead>
            <tbody>
              {ALL_STATUSES.map((status) => (
                <tr key={status}>
                  <td>
                    <StatusBadge status={status} />
                  </td>
                  <td className="num">{summary.workOrdersByStatus[status] ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="muted small">
            Counts come straight from the summary endpoint — every status is shown, even at zero.
          </p>
        </section>

        <section className="card">
          <h2>Open tickets by priority</h2>
          <table className="table">
            <thead>
              <tr>
                <th>Priority</th>
                <th className="num">Count</th>
              </tr>
            </thead>
            <tbody>
              {ALL_PRIORITIES.map((priority) => (
                <tr key={priority}>
                  <td>
                    <PriorityBadge priority={priority} />
                  </td>
                  <td className="num">{summary.openWorkOrdersByPriority[priority] ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section className="card">
          <h2>Technician load</h2>
          {summary.technicianLoad.length === 0 ? (
            <p className="muted">No technicians hold open tickets right now.</p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Technician</th>
                  <th className="num">Open tickets</th>
                </tr>
              </thead>
              <tbody>
                {summary.technicianLoad.map((t) => (
                  <tr key={t.id}>
                    <td>{t.fullName || t.username}</td>
                    <td className="num">{t.openTickets}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="muted small">
            OPEN and IN_PROGRESS tickets per technician — the same numbers dispatch uses for
            the workload rule.
          </p>
        </section>

        <section className="card">
          <h2>Recent SLA breaches</h2>
          {breaches.length === 0 ? (
            <p className="muted">No breaches recorded. The SLA clock is healthy.</p>
          ) : (
            <ul className="breach-list">
              {breaches.map((b) => (
                <li key={b.id}>
                  <div>
                    <Link to={`/tickets/${b.workOrderId}`} className="ticket-link">
                      {b.ticketNumber}
                    </Link>{' '}
                    <span className={`badge ${b.resolvedAt ? 'badge-resolved' : 'badge-breach'}`}>
                      {b.breachType}
                    </span>
                  </div>
                  <div className="muted small">
                    {b.policyName ?? 'Unnamed policy'} · breached {formatDateTimeFull(b.breachedAt)}
                    {b.resolvedAt ? ` · resolved ${formatDateTimeFull(b.resolvedAt)}` : ' · still open'}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
