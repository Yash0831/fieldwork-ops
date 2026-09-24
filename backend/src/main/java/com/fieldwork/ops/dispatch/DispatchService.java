package com.fieldwork.ops.dispatch;

import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.exception.ResourceNotFoundException;
import com.fieldwork.ops.common.exception.UserNotAssignableException;
import com.fieldwork.ops.common.exception.WorkloadLimitExceededException;
import com.fieldwork.ops.common.security.CurrentUser;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderMapper;
import com.fieldwork.ops.workorder.WorkOrderRepository;
import com.fieldwork.ops.workorder.WorkOrderService;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import com.fieldwork.ops.workorder.dto.WorkOrderResponse;
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
    private final WorkOrderMapper workOrderMapper;
    private final DispatchProperties properties;

    /**
     * Assigns {@code workOrderId} to {@code technicianId}.
     *
     * <p>The assignee must be an <em>active</em> user with the
     * TECHNICIAN role — dispatching to a deactivated account or a
     * non-technician is rejected with 422.
     *
     * @param actor the authenticated dispatcher/admin performing the assignment
     * @throws UserNotAssignableException when the assignee is deactivated
     *         or does not carry the TECHNICIAN role
     * @throws WorkloadLimitExceededException when the technician already holds
     *         {@code dispatch.max-open-tickets-per-technician} OPEN/IN_PROGRESS tickets
     * @throws jakarta.persistence.OptimisticLockException on a concurrent assignment race
     */
    @Transactional
    public WorkOrder assign(UUID technicianId, UUID workOrderId, CurrentUser actor) {
        User technician = users
                .findById(technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("User", technicianId));
        if (!technician.isActive()) {
            throw new UserNotAssignableException(
                    "User " + technician.getUsername() + " is deactivated and cannot take tickets");
        }
        if (technician.getRole().getName() != RoleName.TECHNICIAN) {
            throw new UserNotAssignableException(
                    "User " + technician.getUsername() + " does not have the TECHNICIAN role");
        }

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
        return workOrderService.assignTicket(workOrderId, technicianId, actor);
    }

    /**
     * Assignment mapped to the response inside the same write
     * transaction, so the lazy associations are still attached when the
     * DTO is rendered.
     */
    @Transactional
    public WorkOrderResponse assignResponse(UUID technicianId, UUID workOrderId, CurrentUser actor) {
        return workOrderMapper.toResponse(assign(technicianId, workOrderId, actor));
    }
}
