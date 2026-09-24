-- V4: SLA domain.
--
-- sla_policies: one ACTIVE policy per (priority, category). A NULL
-- category means "applies to all categories"; the partial unique index
-- below lets inactive rows accumulate as policy history without
-- blocking a replacement active policy.
-- sla_breaches: the immutable breach record. Application code treats
-- these rows as insert-only (resolved_at is the only field ever
-- updated, when a breach is cleared); the UNIQUE(work_order_id,
-- breach_type) guard means a work order can breach each target at
-- most once, so the scanner is safe to re-run.

CREATE TABLE sla_policies (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name               VARCHAR(64) NOT NULL,
    priority           VARCHAR(8) NOT NULL,
    category           VARCHAR(64),
    response_minutes   INTEGER NOT NULL,
    resolution_minutes INTEGER NOT NULL,
    active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(255),
    updated_by         VARCHAR(255),
    CONSTRAINT chk_sla_policies_priority
        CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT chk_sla_policies_response_minutes CHECK (response_minutes > 0),
    CONSTRAINT chk_sla_policies_resolution_minutes CHECK (resolution_minutes > 0)
);

-- Exactly one active policy per (priority, category); NULL category
-- collapses to '' so "all categories" participates in the uniqueness.
CREATE UNIQUE INDEX uq_sla_policies_active_priority_category
    ON sla_policies (priority, COALESCE(category, '')) WHERE active;

CREATE TABLE sla_breaches (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_order_id UUID NOT NULL REFERENCES work_orders (id),
    policy_id     UUID NOT NULL REFERENCES sla_policies (id),
    breach_type   VARCHAR(16) NOT NULL,
    breached_at   TIMESTAMPTZ NOT NULL,
    detected_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at   TIMESTAMPTZ,
    note          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    CONSTRAINT chk_sla_breaches_breach_type
        CHECK (breach_type IN ('RESPONSE', 'RESOLUTION')),
    CONSTRAINT uq_sla_breaches_work_order_type
        UNIQUE (work_order_id, breach_type)
);

-- Breach-scan and compliance-report ordering.
CREATE INDEX idx_sla_breaches_breached_at ON sla_breaches (breached_at);
CREATE INDEX idx_sla_breaches_work_order_id ON sla_breaches (work_order_id);
