import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, apiResponse, downloadAttachment, jsonBody } from '../api/client';
import { formatApiError } from '../api/errors';
import { nextStates } from '../api/transitions';
import type {
  AttachmentResponse,
  CommentResponse,
  StatusHistoryResponse,
  TechnicianResponse,
  WorkOrderResponse,
  WorkOrderStatus,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import {
  ErrorBox,
  PriorityBadge,
  Spinner,
  StatusBadge,
  formatBytes,
  formatDateTime,
  formatDateTimeFull,
  isBreached,
} from '../components/ui';

function personName(u: { fullName: string; username: string } | null): string {
  if (!u) return '—';
  return u.fullName || u.username;
}

function MetaItem({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="meta-item">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

/**
 * Ticket detail: lifecycle transitions, dispatch assignment, comments,
 * history, attachments.
 *
 * Role gating here mirrors the backend @PreAuthorize rules so controls
 * only render for roles that may use them; the server still enforces
 * them (these gates are UI convenience, not a security boundary).
 */
export default function TicketDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const [ticket, setTicket] = useState<WorkOrderResponse | null>(null);
  const [history, setHistory] = useState<StatusHistoryResponse[]>([]);
  const [comments, setComments] = useState<CommentResponse[]>([]);
  const [technicians, setTechnicians] = useState<TechnicianResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // action-local state
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionBusy, setActionBusy] = useState(false);
  const [noteFor, setNoteFor] = useState<WorkOrderStatus | null>(null);
  const [note, setNote] = useState('');
  const [technicianId, setTechnicianId] = useState('');
  const [commentBody, setCommentBody] = useState('');
  const [commentInternal, setCommentInternal] = useState(false);
  const [uploadBusy, setUploadBusy] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const reload = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const [detail, hist, thread] = await Promise.all([
        api<WorkOrderResponse>(`/work-orders/${id}`),
        api<StatusHistoryResponse[]>(`/work-orders/${id}/history`),
        api<CommentResponse[]>(`/work-orders/${id}/comments`),
      ]);
      setTicket(detail);
      setHistory(hist);
      setComments(thread);
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setLoading(false);
    }
  }, [id]);

  const loadTechnicians = useCallback(async () => {
    try {
      setTechnicians(await api<TechnicianResponse[]>('/technicians'));
    } catch {
      setTechnicians([]); // directory is a convenience; assignment still works without it
    }
  }, []);

  useEffect(() => {
    setComments([]);
    void reload();
  }, [reload]);

  useEffect(() => {
    if (user && (user.role === 'DISPATCHER' || user.role === 'ADMIN')) {
      void loadTechnicians();
    }
  }, [user, loadTechnicians]);

  const canTransition = user && (user.role === 'TECHNICIAN' || user.role === 'ADMIN');
  const canAssign = user && (user.role === 'DISPATCHER' || user.role === 'ADMIN');
  const canComment =
    user && (user.role === 'REQUESTER' || user.role === 'TECHNICIAN' || user.role === 'ADMIN');

  const handleTransition = async (toStatus: WorkOrderStatus) => {
    if (!id) return;
    setActionError(null);
    setActionBusy(true);
    try {
      const updated = await api<WorkOrderResponse>(
        `/work-orders/${id}/status`,
        { method: 'PATCH', ...jsonBody({ toStatus, note: note.trim() || null }) },
      );
      setTicket(updated);
      const hist = await api<StatusHistoryResponse[]>(`/work-orders/${id}/history`);
      setHistory(hist);
      setNoteFor(null);
      setNote('');
    } catch (err) {
      setActionError(formatApiError(err));
    } finally {
      setActionBusy(false);
    }
  };

  const handleAssign = async (e: FormEvent) => {
    e.preventDefault();
    if (!id || !technicianId.trim()) return;
    setActionError(null);
    setActionBusy(true);
    try {
      const updated = await api<WorkOrderResponse>(
        `/work-orders/${id}/assign`,
        { method: 'POST', ...jsonBody({ technicianId: technicianId.trim() }) },
      );
      setTicket(updated);
      setTechnicianId('');
    } catch (err) {
      setActionError(formatApiError(err));
    } finally {
      setActionBusy(false);
    }
  };

  const handleAddComment = async (e: FormEvent) => {
    e.preventDefault();
    if (!id || !commentBody.trim()) return;
    setActionError(null);
    setActionBusy(true);
    try {
      const created = await api<CommentResponse>(
        `/work-orders/${id}/comments`,
        { method: 'POST', ...jsonBody({ body: commentBody.trim(), internal: commentInternal }) },
      );
      setComments((prev) => [...prev, created]);
      setCommentBody('');
      setCommentInternal(false);
    } catch (err) {
      setActionError(formatApiError(err));
    } finally {
      setActionBusy(false);
    }
  };

  const handleUpload = async (e: FormEvent) => {
    e.preventDefault();
    const file = fileInputRef.current?.files?.[0];
    if (!id || !file) return;
    setActionError(null);
    setUploadBusy(true);
    try {
      const form = new FormData();
      form.append('file', file); // backend expects multipart field "file"
      const created = await apiResponse(`/work-orders/${id}/attachments`, {
        method: 'POST',
        body: form,
      }).then((res) => res.json() as Promise<AttachmentResponse>);
      setTicket((prev) => (prev ? { ...prev, attachments: [...prev.attachments, created] } : prev));
      if (fileInputRef.current) fileInputRef.current.value = '';
    } catch (err) {
      setActionError(formatApiError(err));
    } finally {
      setUploadBusy(false);
    }
  };

  const handleDownload = async (att: AttachmentResponse) => {
    setActionError(null);
    try {
      await downloadAttachment(att.id, att.fileName);
    } catch (err) {
      setActionError(formatApiError(err));
    }
  };

  if (loading) return <Spinner />;
  if (error) return <ErrorBox error={error} />;
  if (!ticket) return <ErrorBox error="Ticket not found." />;

  const breached = isBreached(ticket.sla?.dueAt, ticket.status);
  const transitions = canTransition ? nextStates(ticket.status) : [];

  return (
    <div>
      <Link to="/" className="back-link">
        ← Back to queue
      </Link>

      <div className="page-head ticket-head">
        <div>
          <div className="ticket-number">{ticket.ticketNumber}</div>
          <h1>{ticket.title}</h1>
        </div>
        <div className="badges">
          <StatusBadge status={ticket.status} />
          <PriorityBadge priority={ticket.priority} />
          {breached && <span className="badge badge-breach">SLA BREACHED</span>}
          {ticket.sla?.onHold && <span className="badge badge-hold">SLA PAUSED</span>}
        </div>
      </div>

      <ErrorBox error={actionError} />

      <div className="detail-grid">
        <div className="detail-main">
          <section className="card">
            <h2>Details</h2>
            {ticket.description && <p className="description">{ticket.description}</p>}
            <dl className="meta-grid">
              <MetaItem label="Requester">{personName(ticket.requester)}</MetaItem>
              <MetaItem label="Assignee">{personName(ticket.assignee)}</MetaItem>
              <MetaItem label="Team">{ticket.team?.name ?? '—'}</MetaItem>
              <MetaItem label="Category">{ticket.category ?? '—'}</MetaItem>
              <MetaItem label="Est. hours">{ticket.estimatedHours ?? '—'}</MetaItem>
              <MetaItem label="Responded">{formatDateTimeFull(ticket.respondedAt)}</MetaItem>
              <MetaItem label="Resolved">{formatDateTimeFull(ticket.resolvedAt)}</MetaItem>
              <MetaItem label="Closed">{formatDateTimeFull(ticket.closedAt)}</MetaItem>
              <MetaItem label="Created">{formatDateTimeFull(ticket.createdAt)}</MetaItem>
              <MetaItem label="Updated">{formatDateTimeFull(ticket.updatedAt)}</MetaItem>
            </dl>
          </section>

          <section className="card">
            <h2>SLA</h2>
            {ticket.sla ? (
              <dl className="meta-grid">
                <MetaItem label="Response due">{formatDateTimeFull(ticket.sla.responseDueAt)}</MetaItem>
                <MetaItem label="Resolution due">{formatDateTimeFull(ticket.sla.resolutionDueAt)}</MetaItem>
                <MetaItem label="Headline due">{formatDateTimeFull(ticket.sla.dueAt)}</MetaItem>
                <MetaItem label="Clock">{ticket.sla.onHold ? 'Paused (on hold)' : 'Running'}</MetaItem>
                <MetaItem label="Paused time">
                  {ticket.sla.pausedSeconds > 0
                    ? `${Math.round(ticket.sla.pausedSeconds / 60)} min`
                    : '—'}
                </MetaItem>
              </dl>
            ) : (
              <p className="muted">No SLA information recorded.</p>
            )}
          </section>

          {transitions.length > 0 && (
            <section className="card">
              <h2>Change status</h2>
              <div className="btn-row">
                {transitions.map((to) => (
                  <button
                    key={to}
                    type="button"
                    className={`btn ${noteFor === to ? 'btn-primary' : ''}`}
                    disabled={actionBusy}
                    onClick={() => {
                      setNoteFor((cur) => (cur === to ? null : to));
                      setNote('');
                    }}
                  >
                    → {to.replace('_', ' ')}
                  </button>
                ))}
              </div>
              {noteFor && (
                <form
                  className="note-form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void handleTransition(noteFor);
                  }}
                >
                  <label className="field">
                    <span>Note (optional, shown in history)</span>
                    <input
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                      maxLength={1000}
                      placeholder={`Why is this moving to ${noteFor.replace('_', ' ')}?`}
                    />
                  </label>
                  <div className="btn-row">
                    <button type="submit" className="btn btn-primary" disabled={actionBusy}>
                      {actionBusy ? 'Saving…' : `Confirm → ${noteFor.replace('_', ' ')}`}
                    </button>
                    <button type="button" className="btn btn-ghost" onClick={() => setNoteFor(null)}>
                      Cancel
                    </button>
                  </div>
                </form>
              )}
            </section>
          )}

          {canAssign && (
            <section className="card">
              <h2>Assign technician</h2>
              <form className="row-form" onSubmit={handleAssign}>
                <label className="field grow">
                  <span>Technician</span>
                  <select value={technicianId} onChange={(e) => setTechnicianId(e.target.value)}>
                    <option value="">Select a technician…</option>
                    {technicians.map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.fullName} ({t.username}) — {t.openTickets} open
                        {t.teamName ? ` · ${t.teamName}` : ''}
                      </option>
                    ))}
                  </select>
                </label>
                <button type="submit" className="btn btn-primary" disabled={actionBusy || !technicianId}>
                  {actionBusy ? 'Assigning…' : 'Assign'}
                </button>
              </form>
              <p className="muted small">
                Re-assignment keeps the ticket in its current status. The workload rule applies —
                over-capacity technicians are rejected with a 422.
              </p>
            </section>
          )}

          <section className="card">
            <h2>Comments</h2>
            {comments.length === 0 ? (
              <p className="muted">No comments yet.</p>
            ) : (
              <ul className="comment-list">
                {comments.map((c) => (
                  <li key={c.id} className={c.internal ? 'comment internal' : 'comment'}>
                    <div className="comment-head">
                      <strong>{personName(c.author)}</strong>
                      {c.internal && <span className="badge badge-internal">internal</span>}
                      <span className="muted small">{formatDateTimeFull(c.createdAt)}</span>
                    </div>
                    <p>{c.body}</p>
                  </li>
                ))}
              </ul>
            )}
            {canComment && (
              <form className="comment-form" onSubmit={handleAddComment}>
                <label className="field">
                  <span>Add a comment</span>
                  <textarea
                    value={commentBody}
                    onChange={(e) => setCommentBody(e.target.value)}
                    rows={3}
                    maxLength={10000}
                    placeholder="Write an update…"
                  />
                </label>
                <div className="btn-row">
                  <label className="check">
                    <input
                      type="checkbox"
                      checked={commentInternal}
                      onChange={(e) => setCommentInternal(e.target.checked)}
                    />
                    <span>Internal (hidden from the requester)</span>
                  </label>
                  <button
                    type="submit"
                    className="btn btn-primary"
                    disabled={actionBusy || !commentBody.trim()}
                  >
                    {actionBusy ? 'Posting…' : 'Post comment'}
                  </button>
                </div>
              </form>
            )}
          </section>
        </div>

        <div className="detail-side">
          <section className="card">
            <h2>History</h2>
            {history.length === 0 ? (
              <p className="muted">No history recorded.</p>
            ) : (
              <ol className="timeline">
                {history.map((h) => (
                  <li key={h.id}>
                    <div className="timeline-head">
                      {h.fromStatus ? (
                        <>
                          <StatusBadge status={h.fromStatus} /> → <StatusBadge status={h.toStatus} />
                        </>
                      ) : (
                        <>
                          Created as <StatusBadge status={h.toStatus} />
                        </>
                      )}
                    </div>
                    <div className="muted small">
                      {h.changedBy ? personName(h.changedBy) : 'System'} ·{' '}
                      {formatDateTimeFull(h.changedAt)}
                    </div>
                    {h.note && <p className="timeline-note">{h.note}</p>}
                  </li>
                ))}
              </ol>
            )}
          </section>

          <section className="card">
            <h2>Attachments</h2>
            {ticket.attachments.length === 0 ? (
              <p className="muted">No attachments.</p>
            ) : (
              <ul className="attachment-list">
                {ticket.attachments.map((att) => (
                  <li key={att.id}>
                    <div className="attachment-info">
                      <strong>{att.fileName}</strong>
                      <span className="muted small">
                        {formatBytes(att.sizeBytes)} · {att.uploadedBy?.fullName ?? att.uploadedBy?.username ?? '—'} ·{' '}
                        {formatDateTime(att.uploadedAt)}
                      </span>
                    </div>
                    <button type="button" className="btn btn-small" onClick={() => void handleDownload(att)}>
                      Download
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <form className="upload-form" onSubmit={handleUpload}>
              <input ref={fileInputRef} type="file" aria-label="Choose a file to attach" />
              <button type="submit" className="btn" disabled={uploadBusy}>
                {uploadBusy ? 'Uploading…' : 'Upload'}
              </button>
            </form>
          </section>
        </div>
      </div>
    </div>
  );
}
