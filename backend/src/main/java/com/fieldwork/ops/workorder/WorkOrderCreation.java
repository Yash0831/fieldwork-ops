package com.fieldwork.ops.workorder;

import java.util.Objects;

/**
 * Result of {@link WorkOrderService#create}: the ticket, whether the
 * call was an idempotent replay of a completed key, and the exact JSON
 * response body the client must receive.
 *
 * <p>On a replay, {@code responseBody} is the payload stored when the
 * key first completed and {@code responseStatus} is its original status
 * (201) — the client sees the original response, not a re-serialized
 * ticket whose mutable fields (timestamps, version) may have drifted.
 */
public record WorkOrderCreation(
        WorkOrder workOrder, boolean replayed, String responseBody, int responseStatus) {

    public WorkOrderCreation {
        Objects.requireNonNull(workOrder, "workOrder must not be null");
        Objects.requireNonNull(responseBody, "responseBody must not be null");
    }
}
