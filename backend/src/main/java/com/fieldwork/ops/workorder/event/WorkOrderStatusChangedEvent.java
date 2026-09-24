package com.fieldwork.ops.workorder.event;

import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published after every guarded status transition (and after a dispatch
 * assignment changes the status to ASSIGNED). Pure reassignment
 * (ASSIGNED → ASSIGNED with a new technician) does not publish this —
 * the status did not change. Listeners arrive in Phase 6.
 */
public record WorkOrderStatusChangedEvent(
        UUID workOrderId,
        String ticketNumber,
        WorkOrderStatus fromStatus,
        WorkOrderStatus toStatus,
        OffsetDateTime occurredAt) {}
