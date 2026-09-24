package com.fieldwork.ops.workorder.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published when a work order is created (after the idempotency record
 * is marked COMPLETED, inside the creation transaction). Listeners
 * arrive in Phase 6 (notifications, audit projection).
 */
public record WorkOrderCreatedEvent(
        UUID workOrderId, String ticketNumber, OffsetDateTime occurredAt) {}
