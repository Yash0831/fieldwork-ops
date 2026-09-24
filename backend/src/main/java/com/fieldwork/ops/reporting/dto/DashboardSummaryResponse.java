package com.fieldwork.ops.reporting.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Aggregated ops view for {@code GET /api/v1/dashboard/summary}.
 * Pure counts — no business logic, computed by
 * {@code ReportingService} from repository queries.
 *
 * <p>{@code slaCompliancePercent} is the share of currently active
 * (non-terminal) tickets carrying no unresolved breach, 0–100 rounded
 * to one decimal. It is a point-in-time operational gauge, not a
 * contractual SLA report — breaches are immutable facts, this
 * percentage is just their current footprint.
 */
public record DashboardSummaryResponse(
        long totalWorkOrders,
        Map<String, Long> workOrdersByStatus,
        Map<String, Long> openWorkOrdersByPriority,
        long openWorkOrders,
        long unassignedWorkOrders,
        long openBreaches,
        double slaCompliancePercent,
        List<TechnicianLoadResponse> technicianLoad,
        long activePolicies,
        OffsetDateTime generatedAt) {}
