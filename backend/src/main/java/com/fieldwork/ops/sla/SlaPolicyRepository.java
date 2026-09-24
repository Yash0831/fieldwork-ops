package com.fieldwork.ops.sla;

import com.fieldwork.ops.workorder.WorkOrderPriority;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

    /**
     * All active policies for a priority; the caller prefers the
     * category-specific row over the base (null-category) row.
     */
    List<SlaPolicy> findByPriorityAndActiveTrue(WorkOrderPriority priority);

    /**
     * Active policies for a priority whose category either matches or is
     * NULL (applies-to-all), ordered category-specific first.
     *
     * <p>IMPORTANT: the derived finder
     * {@code findByPriorityAndCategoryAndActiveTrue(priority, null)} can
     * <em>not</em> be used for the NULL-category fallback — Spring Data
     * binds the parameter with {@code = NULL}, which never matches.
     * This explicit query uses {@code IS NULL} semantics instead and
     * orders the category-specific row first so the service can take
     * the head of the list.
     */
    @Query(
            """
            select p from SlaPolicy p
            where p.priority = :priority
              and p.active = true
              and (p.category = :category or p.category is null)
            order by case when p.category is null then 1 else 0 end
            """)
    List<SlaPolicy> findActiveCandidates(
            @Param("priority") WorkOrderPriority priority, @Param("category") String category);

    Optional<SlaPolicy> findByPriorityAndCategoryAndActiveTrue(WorkOrderPriority priority, String category);

    /** Ordered policy list for the admin view. */
    List<SlaPolicy> findAllByOrderByPriorityAscNameAsc();

    /** Dashboard aggregation: how many policies are currently enforcing. */
    long countByActiveTrue();
}
