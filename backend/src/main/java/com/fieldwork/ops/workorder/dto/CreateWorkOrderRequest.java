package com.fieldwork.ops.workorder.dto;

import com.fieldwork.ops.workorder.WorkOrderPriority;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Intake payload for {@code POST /api/v1/work-orders}. The controller
 * maps this onto {@code CreateWorkOrderCommand}; defaults (P3 priority,
 * GENERAL category) are applied by the command itself.
 */
public record CreateWorkOrderRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 20000) String description,
        WorkOrderPriority priority,
        @Size(max = 64) String category,
        @NotNull UUID requesterId,
        UUID teamId,
        @Positive @DecimalMax("9999.99") BigDecimal estimatedHours) {}
