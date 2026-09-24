package com.fieldwork.ops.reporting;

import com.fieldwork.ops.reporting.dto.DashboardSummaryResponse;
import com.fieldwork.ops.reporting.dto.TechnicianLoadResponse;
import com.fieldwork.ops.sla.SlaBreachRepository;
import com.fieldwork.ops.sla.SlaPolicyRepository;
import com.fieldwork.ops.workorder.WorkOrderPriority;
import com.fieldwork.ops.workorder.WorkOrderRepository;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Ticket states that count as "active" for compliance and load. */
    private static final List<WorkOrderStatus> ACTIVE_STATUSES = List.of(
            WorkOrderStatus.OPEN,
            WorkOrderStatus.ASSIGNED,
            WorkOrderStatus.IN_PROGRESS,
            WorkOrderStatus.ON_HOLD);

    private final WorkOrderRepository workOrders;
    private final SlaBreachRepository breaches;
    private final SlaPolicyRepository policies;
    private final Clock clock;

    /**
     * Ops dashboard summary: ticket volume by lifecycle state and open
     * priority, the unassigned OPEN queue depth, open breaches, the
     * point-in-time SLA compliance percentage, and per-technician load.
     */
    @Transactional(readOnly = true)
    public DashboardSummaryResponse dashboardSummary() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (WorkOrderStatus status : WorkOrderStatus.values()) {
            byStatus.put(status.name(), workOrders.countByStatus(status));
        }
        Map<String, Long> openByPriority = new LinkedHashMap<>();
        for (WorkOrderPriority priority : WorkOrderPriority.values()) {
            openByPriority.put(
                    priority.name(), workOrders.countByStatusAndPriority(WorkOrderStatus.OPEN, priority));
        }
        long activeTickets =
                ACTIVE_STATUSES.stream().mapToLong(workOrders::countByStatus).sum();
        long breachedActive = breaches.countDistinctBreachedWorkOrders(ACTIVE_STATUSES);
        double compliance = activeTickets == 0
                ? 100.0
                : Math.round(1000.0 * (activeTickets - breachedActive) / activeTickets) / 10.0;
        List<TechnicianLoadResponse> load = workOrders
                .loadByTechnician(List.of(WorkOrderStatus.OPEN, WorkOrderStatus.IN_PROGRESS))
                .stream()
                .map(t -> new TechnicianLoadResponse(t.getId(), t.getUsername(), t.getFullName(), t.getTicketLoad()))
                .toList();
        return new DashboardSummaryResponse(
                workOrders.count(),
                byStatus,
                openByPriority,
                workOrders.countByStatus(WorkOrderStatus.OPEN),
                workOrders.countByStatusAndAssigneeIsNull(WorkOrderStatus.OPEN),
                breaches.countByResolvedAtIsNull(),
                compliance,
                load,
                policies.countByActiveTrue(),
                OffsetDateTime.now(clock));
    }
}
