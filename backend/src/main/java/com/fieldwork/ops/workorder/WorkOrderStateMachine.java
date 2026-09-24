package com.fieldwork.ops.workorder;

import com.fieldwork.ops.common.exception.IllegalStateTransitionException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The explicit, testable work-order lifecycle.
 *
 * <p>A plain final class with a static transition table — no Spring, no
 * database — so Phase 9 unit tests can exercise every edge without a
 * container. All status changes go through
 * {@link #validateTransition(WorkOrderStatus, WorkOrderStatus, String)};
 * the service layer owns the side effects (timestamps, history rows,
 * events) after validation passes.
 *
 * <p>Legal transitions:
 * <ul>
 *   <li>OPEN → ASSIGNED, CANCELLED</li>
 *   <li>ASSIGNED → IN_PROGRESS, ON_HOLD, CANCELLED</li>
 *   <li>IN_PROGRESS → ON_HOLD, RESOLVED</li>
 *   <li>ON_HOLD → IN_PROGRESS, RESOLVED</li>
 *   <li>RESOLVED → CLOSED</li>
 *   <li>CLOSED, CANCELLED are terminal</li>
 * </ul>
 *
 * <p>Reassignment (ASSIGNED → ASSIGNED with a different technician) is
 * <em>not</em> a state-machine transition: {@code WorkOrderService}
 * handles it explicitly and records a history row, but the machine
 * itself has no self-transitions.
 */
public final class WorkOrderStateMachine {

    private static final Map<WorkOrderStatus, Set<WorkOrderStatus>> TRANSITIONS;

    static {
        Map<WorkOrderStatus, Set<WorkOrderStatus>> table = new EnumMap<>(WorkOrderStatus.class);
        table.put(WorkOrderStatus.OPEN, EnumSet.of(WorkOrderStatus.ASSIGNED, WorkOrderStatus.CANCELLED));
        table.put(
                WorkOrderStatus.ASSIGNED,
                EnumSet.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.ON_HOLD, WorkOrderStatus.CANCELLED));
        table.put(WorkOrderStatus.IN_PROGRESS, EnumSet.of(WorkOrderStatus.ON_HOLD, WorkOrderStatus.RESOLVED));
        table.put(WorkOrderStatus.ON_HOLD, EnumSet.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.RESOLVED));
        table.put(WorkOrderStatus.RESOLVED, EnumSet.of(WorkOrderStatus.CLOSED));
        table.put(WorkOrderStatus.CLOSED, EnumSet.noneOf(WorkOrderStatus.class));
        table.put(WorkOrderStatus.CANCELLED, EnumSet.noneOf(WorkOrderStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(table);
    }

    private WorkOrderStateMachine() {}

    /**
     * Whether {@code from → to} is a legal transition. Never throws;
     * {@code null} arguments return {@code false}.
     */
    public static boolean canTransition(WorkOrderStatus from, WorkOrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Validates {@code from → to}, throwing {@link IllegalStateTransitionException}
     * on any illegal move (including no-op self-transitions, which are not
     * state-machine transitions).
     */
    public static void validateTransition(WorkOrderStatus from, WorkOrderStatus to, String ticketNumber) {
        Objects.requireNonNull(from, "from status must not be null");
        Objects.requireNonNull(to, "to status must not be null");
        if (from == to) {
            throw new IllegalStateTransitionException(
                    ticketNumber, from, to, "ticket is already in that status");
        }
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(ticketNumber, from, to);
        }
    }

    /**
     * The statuses reachable from {@code from}. Useful for UI affordances
     * (which buttons to render) and for tests.
     */
    public static Set<WorkOrderStatus> allowedFrom(WorkOrderStatus from) {
        Objects.requireNonNull(from, "from status must not be null");
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    /** Whether the status is terminal (no outgoing transitions). */
    public static boolean isTerminal(WorkOrderStatus status) {
        return allowedFrom(status).isEmpty();
    }
}
