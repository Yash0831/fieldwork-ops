package com.fieldwork.ops.common.exception;

import java.util.UUID;
import lombok.Getter;

/**
 * Thrown by the dispatch rules when a technician already holds the
 * maximum number of open tickets. Maps to 422 in the Phase 4 error
 * envelope: the request is valid, the workload rule rejects it.
 */
@Getter
public class WorkloadLimitExceededException extends DomainException {

    private final UUID technicianId;
    private final int limit;
    private final long currentLoad;

    public WorkloadLimitExceededException(UUID technicianId, int limit, long currentLoad) {
        super(
                "workload_limit_exceeded",
                "Technician %s already holds %d open tickets (limit %d)"
                        .formatted(technicianId, currentLoad, limit));
        this.technicianId = technicianId;
        this.limit = limit;
        this.currentLoad = currentLoad;
    }
}
