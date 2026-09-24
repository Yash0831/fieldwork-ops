package com.fieldwork.ops.sla;

import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlaBreachRepository extends JpaRepository<SlaBreach, UUID> {

    List<SlaBreach> findByWorkOrderId(UUID workOrderId);

    Optional<SlaBreach> findByWorkOrderIdAndBreachType(UUID workOrderId, BreachType breachType);

    List<SlaBreach> findByBreachedAtBetween(OffsetDateTime from, OffsetDateTime to);

    List<SlaBreach> findByBreachedAtBetweenOrderByBreachedAtDesc(OffsetDateTime from, OffsetDateTime to);

    List<SlaBreach> findByResolvedAtIsNull();

    /** Dashboard aggregation: breaches still open. */
    long countByResolvedAtIsNull();

    /**
     * Distinct active tickets carrying at least one unresolved breach —
     * the numerator's complement for the SLA compliance percentage.
     */
    @Query(
            """
            select count(distinct b.workOrder.id) from SlaBreach b
            where b.resolvedAt is null and b.workOrder.status in :statuses
            """)
    long countDistinctBreachedWorkOrders(
            @Param("statuses") List<WorkOrderStatus> statuses);
}
