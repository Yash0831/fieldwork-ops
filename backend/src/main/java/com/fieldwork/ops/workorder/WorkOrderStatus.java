package com.fieldwork.ops.workorder;

/**
 * Guarded work-order lifecycle. Transitions are owned by the Phase 3
 * state machine; nothing outside the workorder module may change a
 * status directly. Mirrors chk_work_orders_status.
 */
public enum WorkOrderStatus {
    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    ON_HOLD,
    RESOLVED,
    CLOSED
}
