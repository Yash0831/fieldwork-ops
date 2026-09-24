package com.fieldwork.ops.sla.dto;

import com.fieldwork.ops.sla.BreachType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of {@code GET /api/v1/sla/breaches}. */
public record BreachResponse(
        UUID id,
        UUID workOrderId,
        String ticketNumber,
        UUID policyId,
        String policyName,
        BreachType breachType,
        OffsetDateTime breachedAt,
        OffsetDateTime detectedAt,
        OffsetDateTime resolvedAt,
        String note) {}
