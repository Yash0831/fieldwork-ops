-- V9: idempotency key for the Phase 6 notification listeners.
--
-- Every notification row created from a domain event carries a dedupe
-- key of <eventType>:<workOrderId>:<recipientId>. The listener
-- pre-checks the key before inserting; the UNIQUE index below is the
-- backstop that makes a redelivered event (or two racing listeners)
-- unable to double-notify. Nullable so rows not born from a domain
-- event are never subject to dedupe (Postgres treats NULLs as
-- distinct in a unique index).

ALTER TABLE notifications ADD COLUMN dedupe_key VARCHAR(255);

CREATE UNIQUE INDEX uq_notifications_dedupe_key
    ON notifications (dedupe_key);
