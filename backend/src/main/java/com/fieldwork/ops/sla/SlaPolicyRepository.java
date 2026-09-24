package com.fieldwork.ops.sla;

import com.fieldwork.ops.workorder.WorkOrderPriority;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

    /**
     * All active policies for a priority; the caller prefers the
     * category-specific row over the base (null-category) row.
     */
    List<SlaPolicy> findByPriorityAndActiveTrue(WorkOrderPriority priority);

    Optional<SlaPolicy> findByPriorityAndCategoryAndActiveTrue(WorkOrderPriority priority, String category);
}
