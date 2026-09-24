-- V5: operations domain — notifications and the audit log.
--
-- notifications is the outbox-style retry queue (Phase 6): the retry
-- worker selects PENDING/FAILED rows whose next_retry_at has passed,
-- attempts delivery, and backs off exponentially until max_attempts,
-- at which point the row goes to DEAD_LETTER for human review.
-- audit_log is append-only (created_at only, no updated_* columns):
-- a tamper-evident record of who did what, with a JSONB details
-- payload for the before/after diff.

CREATE TABLE notifications (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id          UUID REFERENCES users (id) ON DELETE SET NULL,
    recipient_email       VARCHAR(255) NOT NULL,
    channel               VARCHAR(16) NOT NULL DEFAULT 'EMAIL',
    event_type            VARCHAR(64) NOT NULL,
    subject               VARCHAR(255),
    body                  TEXT NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count         INTEGER NOT NULL DEFAULT 0,
    max_attempts          INTEGER NOT NULL DEFAULT 5,
    next_retry_at         TIMESTAMPTZ,
    sent_at               TIMESTAMPTZ,
    last_error            TEXT,
    related_work_order_id UUID REFERENCES work_orders (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(255),
    updated_by            VARCHAR(255),
    CONSTRAINT chk_notifications_channel
        CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    CONSTRAINT chk_notifications_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD_LETTER')),
    CONSTRAINT chk_notifications_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_notifications_max_attempts CHECK (max_attempts > 0)
);

-- Retry worker's polling query: due rows first.
CREATE INDEX idx_notifications_status_next_retry_at
    ON notifications (status, next_retry_at);
CREATE INDEX idx_notifications_recipient_id ON notifications (recipient_id);

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id    UUID REFERENCES users (id) ON DELETE SET NULL,
    actor_name  VARCHAR(128),
    action      VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id   VARCHAR(64) NOT NULL,
    details     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- "Show me everything that happened to work order X", actor timelines,
-- and action-type reports.
CREATE INDEX idx_audit_log_entity
    ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_log_actor ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log (action, occurred_at DESC);
