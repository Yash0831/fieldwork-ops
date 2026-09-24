package com.fieldwork.ops.workorder;

import java.util.Collection;import java.util.List;
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
     *
     * <p>The {@code requester}/{@code assignee}/{@code team} associations
     * are fetch-joined so the web layer can map them to DTOs without
     * triggering lazy loads after the transaction closes. They are
     * to-one associations, so the fetch join cannot duplicate rows; the
     * explicit count query mirrors the predicates without the joins.
     */
    @Query(
            value =
                    """
                    select w from WorkOrder w
                    left join fetch w.requester
                    left join fetch w.assignee
                    left join fetch w.team
                    where (:status is null or w.status = :status)
                      and (:priority is null or w.priority = :priority)
                      and (:teamId is null or w.team.id = :teamId)
                      and (:assigneeId is null or w.assignee.id = :assigneeId)
                      and (:requesterId is null or w.requester.id = :requesterId)
                    """,
            countQuery =
                    """
                    select count(w) from WorkOrder w
                    where (:status is null or w.status = :status)
                      and (:priority is null or w.priority = :priority)
                      and (:teamId is null or w.team.id = :teamId)
                      and (:assigneeId is null or w.assignee.id = :assigneeId)
                      and (:requesterId is null or w.requester.id = :requesterId)
                    """)
    Page<WorkOrder> search(
            @Param("status") WorkOrderStatus status,
            @Param("priority") WorkOrderPriority priority,
            @Param("teamId") UUID teamId,
            @Param("assigneeId") UUID assigneeId,
            @Param("requesterId") UUID requesterId,
            Pageable pageable);

    /** Dashboard aggregation: ticket counts per lifecycle state. */
    long countByStatus(WorkOrderStatus status);

    /** Dashboard aggregation: OPEN tickets per priority. */
    long countByStatusAndPriority(WorkOrderStatus status, WorkOrderPriority priority);

    /**
     * Per-technician load of active tickets. Groups by the assignee's
     * identity columns (to-one associations — no row duplication) so the
     * dashboard can render a load table without N+1 queries.
     */
    @Query(
            """
            select w.assignee.id as id,
                   w.assignee.username as username,
                   w.assignee.fullName as fullName,
                   count(w) as ticketLoad
            from WorkOrder w
            where w.status in :statuses and w.assignee is not null
            group by w.assignee.id, w.assignee.username, w.assignee.fullName
            order by count(w) desc
            """)
    List<TechnicianLoad> loadByTechnician(@Param("statuses") List<WorkOrderStatus> statuses);

    /** Unassigned OPEN tickets — the dispatch queue depth. */
    long countByStatusAndAssigneeIsNull(WorkOrderStatus status);

    Optional<WorkOrder> findByTicketNumber(String ticketNumber);

    List<WorkOrder> findByStatus(WorkOrderStatus status);

    /** SLA breach scanner: all tickets in non-terminal states. */
    List<WorkOrder> findByStatusIn(Collection<WorkOrderStatus> statuses);

    List<WorkOrder> findByAssigneeIdAndStatus(UUID assigneeId, WorkOrderStatus status);

    /** Workload accounting for the dispatch rule: active tickets held by a technician. */
    long countByAssigneeIdAndStatusIn(UUID assigneeId, Collection<WorkOrderStatus> statuses);

    List<WorkOrder> findByRequesterId(UUID requesterId);

    List<WorkOrder> findByTeamIdAndStatus(UUID teamId, WorkOrderStatus status);

    List<WorkOrder> findByStatusAndPriority(WorkOrderStatus status, WorkOrderPriority priority);
}
