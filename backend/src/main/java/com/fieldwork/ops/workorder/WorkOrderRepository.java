package com.fieldwork.ops.workorder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID> {

    /**
     * Filtered queue search for the list endpoint. Every filter is
     * optional — a null parameter disables that predicate.
     */
    @Query(
            """
            select w from WorkOrder w
            where (:status is null or w.status = :status)
              and (:priority is null or w.priority = :priority)
              and (:teamId is null or w.team.id = :teamId)
              and (:assigneeId is null or w.assignee.id = :assigneeId)
            """)
    Page<WorkOrder> search(
            @Param("status") WorkOrderStatus status,
            @Param("priority") WorkOrderPriority priority,
            @Param("teamId") UUID teamId,
            @Param("assigneeId") UUID assigneeId,
            Pageable pageable);

    /** Dashboard aggregation: ticket counts per lifecycle state. */
    long countByStatus(WorkOrderStatus status);

    /** Unassigned OPEN tickets — the dispatch queue depth. */
    long countByStatusAndAssigneeIsNull(WorkOrderStatus status);

    Optional<WorkOrder> findByTicketNumber(String ticketNumber);

    List<WorkOrder> findByStatus(WorkOrderStatus status);

    List<WorkOrder> findByAssigneeIdAndStatus(UUID assigneeId, WorkOrderStatus status);

    /** Workload accounting for the dispatch rule: active tickets held by a technician. */
    long countByAssigneeIdAndStatusIn(UUID assigneeId, Collection<WorkOrderStatus> statuses);

    List<WorkOrder> findByRequesterId(UUID requesterId);

    List<WorkOrder> findByTeamIdAndStatus(UUID teamId, WorkOrderStatus status);

    List<WorkOrder> findByStatusAndPriority(WorkOrderStatus status, WorkOrderPriority priority);
}
