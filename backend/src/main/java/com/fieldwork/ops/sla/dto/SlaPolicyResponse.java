package com.fieldwork.ops.sla.dto;

import com.fieldwork.ops.workorder.WorkOrderPriority;
import java.time.OffsetDateTime;
import java.util.UUID;

/** SLA policy as exposed by {@code /api/v1/sla/policies}. */
public record SlaPolicyResponse(
        UUID id,
        String name,
        WorkOrderPriority priority,
        String category,
        int responseMinutes,
        int resolutionMinutes,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
