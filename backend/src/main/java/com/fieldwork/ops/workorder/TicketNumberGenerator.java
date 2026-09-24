package com.fieldwork.ops.workorder;

/**
 * Hook point for ticket-number allocation (format {@code WO-YYYY-NNNNNN}).
 *
 * <p>TODO (Phase 4): provide the real implementation as a Spring bean —
 * allocate from the {@code ticket_number_seq} database sequence created
 * in V3 and format the value as {@code WO-YYYY-NNNNNN}. There is
 * deliberately no default implementation: {@code WorkOrderService} takes
 * this as a constructor dependency, so the application context will not
 * start until Phase 4 wires the generator in. Phase 9 tests can supply a
 * stub.
 */
public interface TicketNumberGenerator {

    /** Allocates the next unique human-facing ticket number. */
    String generate();
}
