package com.fieldwork.ops.workorder;

/**
 * Lifecycle state of an idempotency-key row. Mirrors
 * chk_idempotency_keys_status.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
