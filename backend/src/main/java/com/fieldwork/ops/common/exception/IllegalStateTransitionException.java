package com.fieldwork.ops.common.exception;

import com.fieldwork.ops.workorder.WorkOrderStatus;
import lombok.Getter;

/**
 * Thrown when a work-order status change is not allowed by the
 * {@code WorkOrderStateMachine}. Carries the offending from/to pair so
 * callers (and the Phase 4 error envelope) can report it precisely.
 */
@Getter
public class IllegalStateTransitionException extends DomainException {

    private final WorkOrderStatus fromStatus;
    private final WorkOrderStatus toStatus;
    private final String ticketNumber;

    public IllegalStateTransitionException(
            String ticketNumber, WorkOrderStatus fromStatus, WorkOrderStatus toStatus) {
        super(
                "illegal_state_transition",
                "Ticket %s cannot transition from %s to %s"
                        .formatted(ticketNumber, fromStatus, toStatus));
        this.ticketNumber = ticketNumber;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public IllegalStateTransitionException(
            String ticketNumber, WorkOrderStatus fromStatus, WorkOrderStatus toStatus, String detail) {
        super(
                "illegal_state_transition",
                "Ticket %s cannot transition from %s to %s: %s"
                        .formatted(ticketNumber, fromStatus, toStatus, detail));
        this.ticketNumber = ticketNumber;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }
}
