package com.fieldwork.ops.workorder;

/**
 * Hook point for ticket-number allocation (format {@code WO-YYYY-NNNNNN}).
 *
 * <p>The production implementation is {@link SequenceTicketNumberGenerator},
 * a Spring bean allocating from the {@code ticket_number_seq} database
 * sequence created in V3. Phase 9 tests can supply a stub.
 */
public interface TicketNumberGenerator {

    /** Allocates the next unique human-facing ticket number. */
    String generate();
}
