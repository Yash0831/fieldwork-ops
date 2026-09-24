package com.fieldwork.ops.workorder;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderStatusHistoryRepository extends JpaRepository<WorkOrderStatusHistory, UUID> {

    List<WorkOrderStatusHistory> findByWorkOrderIdOrderByChangedAtAsc(UUID workOrderId);
}
