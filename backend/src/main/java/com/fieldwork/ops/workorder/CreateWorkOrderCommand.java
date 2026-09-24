package com.fieldwork.ops.workorder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Input for {@code WorkOrderService.create}. A record (not an entity):
 * the Phase 4 web layer will map its DTOs onto this command.
 */
public record CreateWorkOrderCommand(
        String title,
        String description,
        WorkOrderPriority priority,
        String category,
        UUID requesterId,
        UUID teamId,
        BigDecimal estimatedHours) {

    public CreateWorkOrderCommand {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (requesterId == null) {
            throw new IllegalArgumentException("requesterId must not be null");
        }
        // Defaults applied here so every caller gets the same intake semantics.
        if (priority == null) {
            priority = WorkOrderPriority.P3;
        }
        if (category == null || category.isBlank()) {
            category = "GENERAL";
        }
    }
}
