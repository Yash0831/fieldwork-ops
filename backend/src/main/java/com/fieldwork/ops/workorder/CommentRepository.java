package com.fieldwork.ops.workorder;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    List<Comment> findByWorkOrderIdOrderByCreatedAtAsc(UUID workOrderId);

    List<Comment> findByWorkOrderIdAndInternalOrderByCreatedAtAsc(UUID workOrderId, boolean internal);
}
