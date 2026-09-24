package com.fieldwork.ops.workorder;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderStatusHistoryRepository extends JpaRepository<WorkOrderStatusHistory, UUID> {

    /**
     * History with the changer fetch-joined, so DTO mapping can read
     * {@code changedBy} without a lazy load after the transaction closes.
     */
    @Query(
            """
            select h from WorkOrderStatusHistory h
            left join fetch h.changedBy
            where h.workOrder.id = :workOrderId
            order by h.changedAt asc
            """)
    List<WorkOrderStatusHistory> findByWorkOrderIdWithChanger(@Param("workOrderId") UUID workOrderId);
}
