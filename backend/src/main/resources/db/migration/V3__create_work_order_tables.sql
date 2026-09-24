-- V3: work-order domain.
--
-- work_orders is the transactional heart of the system:
-- * ticket_number is the human-facing identifier (e.g. 'WO-001000'),
--   allocated from ticket_number_seq by the Phase 4 service layer so
--   concurrent creations never collide. The sequence lives here so it
--   migrates with the table it serves.
-- * version is the @Version optimistic-locking column: every UPDATE
--   bumps it, and concurrent writers get OptimisticLockException
--   instead of silently overwriting each other (dispatch + status
--   changes race on this row).
-- * due_at is the headline deadline used by queue ordering; the
--   response_due_at / resolution_due_at pair carries the two SLA
--   targets individually for the breach scanner.
-- * Status/priority are VARCHAR + CHECK, not native enums: readable in
--   ad-hoc SQL, zero-cost to extend, and JPA maps them as plain enums.

CREATE SEQUENCE ticket_number_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE work_orders (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_number     VARCHAR(16) NOT NULL UNIQUE,
    title             VARCHAR(255) NOT NULL,
    description       TEXT,
    status            VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    priority          VARCHAR(8) NOT NULL DEFAULT 'P3',
    category          VARCHAR(64) NOT NULL DEFAULT 'GENERAL',
    requester_id      UUID NOT NULL REFERENCES users (id),
    assignee_id       UUID REFERENCES users (id) ON DELETE SET NULL,
    team_id           UUID REFERENCES teams (id) ON DELETE SET NULL,
    response_due_at   TIMESTAMPTZ,
    resolution_due_at TIMESTAMPTZ,
    due_at            TIMESTAMPTZ,
    responded_at      TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    estimated_hours   NUMERIC(6, 2),
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(255),
    updated_by        VARCHAR(255),
    CONSTRAINT chk_work_orders_status
        CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED', 'CLOSED')),
    CONSTRAINT chk_work_orders_priority
        CHECK (priority IN ('P1', 'P2', 'P3', 'P4'))
);

-- Ops queue ordering: filter by status, order/prioritise within it.
CREATE INDEX idx_work_orders_status_priority_due
    ON work_orders (status, priority, due_at);
-- Technician workload views: "my open tickets".
CREATE INDEX idx_work_orders_assignee_status
    ON work_orders (assignee_id, status);
-- Requester views and team queues.
CREATE INDEX idx_work_orders_requester ON work_orders (requester_id);
CREATE INDEX idx_work_orders_team_status ON work_orders (team_id, status);

-- Append-only transition log. from_status is NULL for the creation
-- event. changed_by_id is nullable so system transitions (e.g. the
-- breach scanner auto-escalating) can be recorded.
CREATE TABLE work_order_status_history (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_order_id UUID NOT NULL REFERENCES work_orders (id) ON DELETE CASCADE,
    from_status   VARCHAR(24),
    to_status     VARCHAR(24) NOT NULL,
    changed_by_id UUID REFERENCES users (id),
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    note          VARCHAR(1000),
    CONSTRAINT chk_wo_history_from_status
        CHECK (from_status IS NULL
               OR from_status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED', 'CLOSED')),
    CONSTRAINT chk_wo_history_to_status
        CHECK (to_status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED', 'CLOSED'))
);

CREATE INDEX idx_wo_history_work_order
    ON work_order_status_history (work_order_id, changed_at);

-- Comment thread. internal=true marks dispatcher/technician notes that
-- are hidden from the requester-facing view.
CREATE TABLE comments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_order_id UUID NOT NULL REFERENCES work_orders (id) ON DELETE CASCADE,
    author_id     UUID NOT NULL REFERENCES users (id),
    body          TEXT NOT NULL,
    internal      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255)
);

CREATE INDEX idx_comments_work_order ON comments (work_order_id, created_at);

-- Attachment metadata; bytes live in S3 (Phase 7). storage_key is the
-- S3 object key, checksum lets Phase 7 verify upload integrity.
CREATE TABLE attachments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_order_id   UUID NOT NULL REFERENCES work_orders (id) ON DELETE CASCADE,
    uploaded_by_id  UUID NOT NULL REFERENCES users (id),
    file_name       VARCHAR(255) NOT NULL,
    content_type    VARCHAR(128) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    storage_key     VARCHAR(512) NOT NULL,
    checksum_sha256 CHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    CONSTRAINT chk_attachments_size_bytes CHECK (size_bytes >= 0)
);

CREATE INDEX idx_attachments_work_order ON attachments (work_order_id);

-- Idempotent intake (Phase 4): the client sends an Idempotency-Key
-- header; the row records the request hash and, once served, the
-- response snapshot. Stale keys are swept by expires_at.
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    requester       VARCHAR(64) NOT NULL,
    request_hash    CHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    response_status INTEGER,
    response_body   TEXT,
    work_order_id   UUID REFERENCES work_orders (id) ON DELETE SET NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    CONSTRAINT chk_idempotency_keys_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
