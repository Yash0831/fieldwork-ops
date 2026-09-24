package com.fieldwork.ops.reporting.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Aggregated ops view for {@code GET /api/v1/dashboard/summary}.
 * Pure counts — no business logic, computed by
 * {@code ReportingService} from repository queries.
 */
public record DashboardSummaryResponse(
        long totalWorkOrders,
        Map<String, Long> workOrdersByStatus,
        long openWorkOrders,
        long unassignedWorkOrders,
        long openBreaches,
        long activePolicies,
        OffsetDateTime generatedAt) {}
