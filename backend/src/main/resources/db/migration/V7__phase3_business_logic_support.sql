-- V7: Phase 3 business-logic support.
--
-- 1. ON_HOLD pause accounting on work_orders: sla_paused_seconds
--    accumulates across hold spells (written by SlaService); on_hold_since
--    stamps the start of the current spell and is NULL otherwise.
-- 2. CANCELLED joins the status check constraints on work_orders and
--    work_order_status_history (legal from OPEN and ASSIGNED per the
--    Phase 3 state machine).

ALTER TABLE work_orders
    ADD COLUMN sla_paused_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN on_hold_since TIMESTAMPTZ;

ALTER TABLE work_orders
    DROP CONSTRAINT chk_work_orders_status;
ALTER TABLE work_orders
    ADD CONSTRAINT chk_work_orders_status
        CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED', 'CLOSED', 'CANCELLED'));

ALTER TABLE work_order_status_history
    DROP CONSTRAINT chk_wo_history_from_status;
ALTER TABLE work_order_status_history
    ADD CONSTRAINT chk_wo_history_from_status
        CHECK (from_status IS NULL
               OR from_status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED', 'CLOSED', 'CANCELLED'));

ALTER TABLE work_order_status_history
    DROP CONSTRAINT chk_wo_history_to_status;
ALTER TABLE work_order_status_history
    ADD CONSTRAINT chk_wo_history_to_status
        CHECK (to_status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED', 'CLOSED', 'CANCELLED'));
