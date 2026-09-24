package com.fieldwork.ops.sla;

import com.fieldwork.ops.sla.dto.BreachResponse;
import com.fieldwork.ops.sla.dto.SlaPolicyResponse;
import org.springframework.stereotype.Component;

/**
 * Entity → DTO mapping for the SLA web layer, mirroring
 * {@code WorkOrderMapper} on the workorder side.
 */
@Component
public class SlaMapper {

    public SlaPolicyResponse toResponse(SlaPolicy policy) {
        return new SlaPolicyResponse(
                policy.getId(),
                policy.getName(),
                policy.getPriority(),
                policy.getCategory(),
                policy.getResponseMinutes(),
                policy.getResolutionMinutes(),
                policy.isActive(),
                policy.getCreatedAt(),
                policy.getUpdatedAt());
    }

    public BreachResponse toBreachResponse(SlaBreach breach) {
        return new BreachResponse(
                breach.getId(),
                breach.getWorkOrder().getId(),
                breach.getWorkOrder().getTicketNumber(),
                breach.getPolicy().getId(),
                breach.getPolicy().getName(),
                breach.getBreachType(),
                breach.getBreachedAt(),
                breach.getDetectedAt(),
                breach.getResolvedAt(),
                breach.getNote());
    }
}
