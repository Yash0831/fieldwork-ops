package com.fieldwork.ops.workorder.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/work-orders/{id}/assign}. Routes
 * through {@code DispatchService}, so the workload rule applies.
 */
public record AssignRequest(@NotNull UUID technicianId) {}
