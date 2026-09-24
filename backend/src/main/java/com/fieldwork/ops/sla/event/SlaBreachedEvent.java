package com.fieldwork.ops.sla.event;

import com.fieldwork.ops.sla.BreachType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published when an SLA breach is recorded — currently via
 * {@code SlaService.recordBreach}, which the Phase 6 breach-scan job
 * will call. Listeners (notifications, escalation) arrive in Phase 6.
 */
public record SlaBreachedEvent(
        UUID workOrderId,
        String ticketNumber,
        BreachType breachType,
        OffsetDateTime breachedAt,
        OffsetDateTime detectedAt) {}
