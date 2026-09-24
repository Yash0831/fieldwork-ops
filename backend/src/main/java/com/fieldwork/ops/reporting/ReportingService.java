package com.fieldwork.ops.reporting;

import com.fieldwork.ops.reporting.dto.DashboardSummaryResponse;
import com.fieldwork.ops.sla.SlaBreachRepository;
import com.fieldwork.ops.sla.SlaPolicyRepository;
import com.fieldwork.ops.workorder.WorkOrderRepository;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only operational reporting. Queries only — no writes, no
 * business rules. Aggregations are simple repository counts; no read
 * pattern here justifies a caching layer yet.
 */
@Service
@RequiredArgsConstructor
public class ReportingService {

    private final WorkOrderRepository workOrders;
    private final SlaBreachRepository breaches;
    private final SlaPolicyRepository policies;
    private final Clock clock;

    /**
     * Ops dashboard summary: ticket volume by lifecycle state, the
     * unassigned OPEN queue depth, open breaches, and active policy
     * count.
     */
    @Transactional(readOnly = true)
    public DashboardSummaryResponse dashboardSummary() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (WorkOrderStatus status : WorkOrderStatus.values()) {
            byStatus.put(status.name(), workOrders.countByStatus(status));
        }
        return new DashboardSummaryResponse(
                workOrders.count(),
                byStatus,
                workOrders.countByStatus(WorkOrderStatus.OPEN),
                workOrders.countByStatusAndAssigneeIsNull(WorkOrderStatus.OPEN),
                breaches.countByResolvedAtIsNull(),
                policies.countByActiveTrue(),
                OffsetDateTime.now(clock));
    }
}
