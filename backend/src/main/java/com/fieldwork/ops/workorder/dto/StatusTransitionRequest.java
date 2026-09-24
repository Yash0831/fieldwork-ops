package com.fieldwork.ops.workorder.dto;

import com.fieldwork.ops.workorder.WorkOrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code PATCH /api/v1/work-orders/{id}/status}.
 */
public record StatusTransitionRequest(
        @NotNull WorkOrderStatus toStatus, @Size(max = 1000) String note) {}
