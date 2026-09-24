package com.fieldwork.ops.workorder.dto;

import com.fieldwork.ops.workorder.WorkOrderPriority;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Full ticket representation returned by the work-order endpoints.
 * {@code sla} carries the current SLA deadline info (response and
 * resolution targets stamped at creation, the headline {@code dueAt},
 * and whether the SLA clock is currently paused on hold).
 */
public record WorkOrderResponse(
        UUID id,
        String ticketNumber,
        String title,
        String description,
        WorkOrderStatus status,
        WorkOrderPriority priority,
        String category,
        UserSummary requester,
        UserSummary assignee,
        TeamSummary team,
        SlaInfo sla,
        BigDecimal estimatedHours,
        OffsetDateTime respondedAt,
        OffsetDateTime resolvedAt,
        OffsetDateTime closedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy) {

    public record UserSummary(UUID id, String username, String fullName) {}

    public record TeamSummary(UUID id, String name) {}

    public record SlaInfo(
            OffsetDateTime responseDueAt,
            OffsetDateTime resolutionDueAt,
            OffsetDateTime dueAt,
            boolean onHold,
            long pausedSeconds) {}
}
