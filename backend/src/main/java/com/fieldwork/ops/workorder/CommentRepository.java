package com.fieldwork.ops.workorder;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    List<Comment> findByWorkOrderIdOrderByCreatedAtAsc(UUID workOrderId);

    List<Comment> findByWorkOrderIdAndInternalOrderByCreatedAtAsc(UUID workOrderId, boolean internal);

    /**
     * Thread with authors fetch-joined, so DTO mapping can read
     * {@code author} without a lazy load after the transaction closes.
     */
    @Query(
            """
            select c from Comment c
            left join fetch c.author
            where c.workOrder.id = :workOrderId
            order by c.createdAt asc
            """)
    List<Comment> findByWorkOrderIdWithAuthor(@Param("workOrderId") UUID workOrderId);
}
