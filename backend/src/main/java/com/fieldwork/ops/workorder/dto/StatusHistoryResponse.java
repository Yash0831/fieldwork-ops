package com.fieldwork.ops.workorder.dto;

import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/work-orders/{id}/history}. {@code fromStatus}
 * is null for the creation entry; {@code changedBy} is null for
 * system-driven transitions.
 */
public record StatusHistoryResponse(
        UUID id,
        WorkOrderStatus fromStatus,
        WorkOrderStatus toStatus,
        WorkOrderResponse.UserSummary changedBy,
        OffsetDateTime changedAt,
        String note) {}
