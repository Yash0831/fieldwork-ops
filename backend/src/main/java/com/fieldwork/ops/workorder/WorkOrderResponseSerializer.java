package com.fieldwork.ops.workorder;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Serializes a freshly created work order into the JSON body the client
 * receives. Supplied by the web layer so {@link WorkOrderService} can
 * persist the creation response on the idempotency row (and return the
 * original payload on replay) without depending on web DTOs itself.
 */
@FunctionalInterface
public interface WorkOrderResponseSerializer {

    String serialize(WorkOrder workOrder) throws JsonProcessingException;
}
