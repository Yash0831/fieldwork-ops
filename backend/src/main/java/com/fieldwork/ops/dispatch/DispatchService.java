package com.fieldwork.ops.dispatch;

import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.exception.ResourceNotFoundException;
import com.fieldwork.ops.common.exception.WorkloadLimitExceededException;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderRepository;
import com.fieldwork.ops.workorder.WorkOrderService;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Technician assignment with the workload rule.
 *
 * <p>This module owns the <em>decision</em> of who gets a ticket; the
 * actual status change stays in {@link WorkOrderService} (the workorder
 * module owns the state machine).
 *
 * <p>Concurrency: the work-order row carries a JPA {@code @Version}
 * column, so two concurrent {@code assign} calls for the same ticket
 * race on the version — the loser's flush throws
 * {@code jakarta.persistence.OptimisticLockException} and its
 * transaction rolls back. Callers must retry or surface the conflict;
 * there is no silent last-writer-wins. (Phase 9 covers this with a
 * concurrent-assignment test.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchService {

    /** Ticket states that count toward a technician's workload. */
    private static final List<WorkOrderStatus> ACTIVE_STATES =
            List.of(WorkOrderStatus.OPEN, WorkOrderStatus.IN_PROGRESS);

    private final WorkOrderRepository workOrders;
    private final UserRepository users;
    private final WorkOrderService workOrderService;
    private final DispatchProperties properties;

    /**
     * Assigns {@code workOrderId} to {@code technicianId}.
     *
     * @param assignedById the dispatcher performing the assignment, or null for system dispatch
     * @throws WorkloadLimitExceededException when the technician already holds
     *         {@code dispatch.max-open-tickets-per-technician} OPEN/IN_PROGRESS tickets
     * @throws jakarta.persistence.OptimisticLockException on a concurrent assignment race
     */
    @Transactional
    public WorkOrder assign(UUID technicianId, UUID workOrderId, UUID assignedById) {
        User technician = users
                .findById(technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("User", technicianId));

        long currentLoad = workOrders.countByAssigneeIdAndStatusIn(technicianId, ACTIVE_STATES);
        int limit = properties.getMaxOpenTicketsPerTechnician();
        if (currentLoad >= limit) {
            throw new WorkloadLimitExceededException(technicianId, limit, currentLoad);
        }

        log.info(
                "Dispatching ticket {} to technician {} (load {}/{})",
                workOrderId,
                technician.getUsername(),
                currentLoad,
                limit);
        return workOrderService.assignTicket(workOrderId, technicianId, assignedById);
    }
}
