-- V1: foundation placeholder.
--
-- The full relational schema (users, roles, teams, work_orders,
-- work_order_status_history, sla_policies, sla_breaches, comments,
-- attachments, notifications, idempotency_keys, audit_log) is introduced
-- in Phase 2 with proper primary keys, foreign keys, indexes, check
-- constraints and audit columns.
--
-- This migration exists so the application (with Flyway enabled and
-- hibernate.ddl-auto=validate) boots cleanly during Phase 1 while the
-- domain model is still being designed.

CREATE TABLE IF NOT EXISTS schema_version_marker (
    id          SMALLINT PRIMARY KEY,
    phase       VARCHAR(32) NOT NULL,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    note        TEXT
);

INSERT INTO schema_version_marker (id, phase, note)
VALUES (1, 'phase-1-foundation', 'Placeholder. Real schema arrives in Phase 2.')
ON CONFLICT (id) DO NOTHING;
