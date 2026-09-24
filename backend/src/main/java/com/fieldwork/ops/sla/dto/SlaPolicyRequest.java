package com.fieldwork.ops.sla.dto;

import com.fieldwork.ops.workorder.WorkOrderPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/sla/policies} and
 * {@code PUT /api/v1/sla/policies/{id}}. A null category means
 * "applies to all categories"; a null {@code active} on PUT leaves the
 * flag untouched (POST defaults it to true).
 *
 * <p>The response ≤ resolution invariant is enforced by
 * {@code SlaService}, not here — it is a domain rule, and the service
 * rejects violations with 400.
 */
public record SlaPolicyRequest(
        @NotBlank @Size(max = 64) String name,
        @NotNull WorkOrderPriority priority,
        @Size(max = 64) String category,
        @NotNull @Positive Integer responseMinutes,
        @NotNull @Positive Integer resolutionMinutes,
        Boolean active) {}
