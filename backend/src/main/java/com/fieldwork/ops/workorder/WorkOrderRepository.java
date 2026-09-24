package com.fieldwork.ops.workorder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID> {

    Optional<WorkOrder> findByTicketNumber(String ticketNumber);

    List<WorkOrder> findByStatus(WorkOrderStatus status);

    List<WorkOrder> findByAssigneeIdAndStatus(UUID assigneeId, WorkOrderStatus status);

    /** Workload accounting for the dispatch rule: active tickets held by a technician. */
    long countByAssigneeIdAndStatusIn(UUID assigneeId, Collection<WorkOrderStatus> statuses);

    List<WorkOrder> findByRequesterId(UUID requesterId);

    List<WorkOrder> findByTeamIdAndStatus(UUID teamId, WorkOrderStatus status);

    List<WorkOrder> findByStatusAndPriority(WorkOrderStatus status, WorkOrderPriority priority);
}
