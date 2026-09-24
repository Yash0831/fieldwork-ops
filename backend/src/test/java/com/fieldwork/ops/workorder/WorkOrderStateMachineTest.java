package com.fieldwork.ops.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fieldwork.ops.common.exception.IllegalStateTransitionException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustive unit tests for the work-order lifecycle state machine.
 *
 * <p>The machine is a plain static transition table (no Spring, no DB),
 * so every edge — legal and illegal — is exercised directly.
 */
class WorkOrderStateMachineTest {

    /** The expected transition table, mirroring the machine's contract. */
    private static final Map<WorkOrderStatus, Set<WorkOrderStatus>> EXPECTED = new EnumMap<>(WorkOrderStatus.class);

    static {
        EXPECTED.put(WorkOrderStatus.OPEN, EnumSet.of(WorkOrderStatus.ASSIGNED, WorkOrderStatus.CANCELLED));
        EXPECTED.put(
                WorkOrderStatus.ASSIGNED,
                EnumSet.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.ON_HOLD, WorkOrderStatus.CANCELLED));
        EXPECTED.put(WorkOrderStatus.IN_PROGRESS, EnumSet.of(WorkOrderStatus.ON_HOLD, WorkOrderStatus.RESOLVED));
        EXPECTED.put(WorkOrderStatus.ON_HOLD, EnumSet.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.RESOLVED));
        EXPECTED.put(WorkOrderStatus.RESOLVED, EnumSet.of(WorkOrderStatus.CLOSED));
        EXPECTED.put(WorkOrderStatus.CLOSED, EnumSet.noneOf(WorkOrderStatus.class));
        EXPECTED.put(WorkOrderStatus.CANCELLED, EnumSet.noneOf(WorkOrderStatus.class));
    }

    // ------------------------------------------------------------------
    // Legal transitions
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1} is legal")
    @MethodSource("legalTransitions")
    void legalTransitionsAreAccepted(WorkOrderStatus from, WorkOrderStatus to) {
        assertThat(WorkOrderStateMachine.canTransition(from, to)).isTrue();
        // validateTransition must not throw for legal moves
        WorkOrderStateMachine.validateTransition(from, to, "WO-2026-000001");
    }

    static Stream<Arguments> legalTransitions() {
        // Flatten the expected table into (from, to) pairs.
        Stream.Builder<Arguments> pairs = Stream.builder();
        for (Map.Entry<WorkOrderStatus, Set<WorkOrderStatus>> e : EXPECTED.entrySet()) {
            for (WorkOrderStatus to : e.getValue()) {
                pairs.add(Arguments.of(e.getKey(), to));
            }
        }
        return pairs.build();
    }

    // ------------------------------------------------------------------
    // Illegal transitions
    // ------------------------------------------------------------------

    @Test
    void everyPairMatchesTheExpectedTable() {
        // Exhaustive: all 49 (from, to) pairs must agree with the contract table.
        for (WorkOrderStatus from : WorkOrderStatus.values()) {
            for (WorkOrderStatus to : WorkOrderStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(WorkOrderStateMachine.canTransition(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @ParameterizedTest(name = "{0} -> {1} is illegal")
    @MethodSource("illegalTransitions")
    void illegalTransitionsAreRejected(WorkOrderStatus from, WorkOrderStatus to) {
        assertThat(WorkOrderStateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> WorkOrderStateMachine.validateTransition(from, to, "WO-2026-000007"))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("WO-2026-000007")
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }

    static Stream<Arguments> illegalTransitions() {
        Stream.Builder<Arguments> pairs = Stream.builder();
        for (WorkOrderStatus from : WorkOrderStatus.values()) {
            for (WorkOrderStatus to : WorkOrderStatus.values()) {
                if (!EXPECTED.get(from).contains(to)) {
                    pairs.add(Arguments.of(from, to));
                }
            }
        }
        return pairs.build();
    }

    @ParameterizedTest(name = "self-transition {0} -> {0} is rejected")
    @EnumSource(WorkOrderStatus.class)
    void selfTransitionsAreRejected(WorkOrderStatus status) {
        // Reassignment (ASSIGNED -> ASSIGNED) is handled explicitly by the
        // service layer — it is NOT a state-machine transition.
        assertThat(WorkOrderStateMachine.canTransition(status, status)).isFalse();
        assertThatThrownBy(() -> WorkOrderStateMachine.validateTransition(status, status, "WO-2026-000008"))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("already in that status");
    }

    @Test
    void illegalTransitionExceptionCarriesTheOffendingPair() {
        assertThatThrownBy(() ->
                        WorkOrderStateMachine.validateTransition(
                                WorkOrderStatus.OPEN, WorkOrderStatus.RESOLVED, "WO-2026-000009"))
                .isInstanceOf(IllegalStateTransitionException.class)
                .satisfies(e -> {
                    IllegalStateTransitionException ex = (IllegalStateTransitionException) e;
                    assertThat(ex.getFromStatus()).isEqualTo(WorkOrderStatus.OPEN);
                    assertThat(ex.getToStatus()).isEqualTo(WorkOrderStatus.RESOLVED);
                    assertThat(ex.getTicketNumber()).isEqualTo("WO-2026-000009");
                    assertThat(ex.getCode()).isEqualTo("illegal_state_transition");
                });
    }

    // ------------------------------------------------------------------
    // Terminal states
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is terminal")
    @EnumSource(value = WorkOrderStatus.class, names = {"CLOSED", "CANCELLED"})
    void terminalStatesHaveNoOutgoingTransitions(WorkOrderStatus terminal) {
        assertThat(WorkOrderStateMachine.isTerminal(terminal)).isTrue();
        assertThat(WorkOrderStateMachine.allowedFrom(terminal)).isEmpty();
        for (WorkOrderStatus to : WorkOrderStatus.values()) {
            assertThat(WorkOrderStateMachine.canTransition(terminal, to)).isFalse();
        }
    }

    @ParameterizedTest(name = "{0} is not terminal")
    @EnumSource(
            value = WorkOrderStatus.class,
            names = {"CLOSED", "CANCELLED"},
            mode = EnumSource.Mode.EXCLUDE)
    void nonTerminalStatesHaveOutgoingTransitions(WorkOrderStatus status) {
        assertThat(WorkOrderStateMachine.isTerminal(status)).isFalse();
        assertThat(WorkOrderStateMachine.allowedFrom(status)).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // allowedFrom affordances
    // ------------------------------------------------------------------

    @Test
    void allowedFromMatchesTheContractTable() {
        for (WorkOrderStatus from : WorkOrderStatus.values()) {
            assertThat(WorkOrderStateMachine.allowedFrom(from))
                    .as("allowedFrom(%s)", from)
                    .containsExactlyInAnyOrderElementsOf(EXPECTED.get(from));
        }
    }

    // ------------------------------------------------------------------
    // Null handling
    // ------------------------------------------------------------------

    @Test
    void canTransitionNeverThrowsOnNull() {
        assertThat(WorkOrderStateMachine.canTransition(null, WorkOrderStatus.OPEN)).isFalse();
        assertThat(WorkOrderStateMachine.canTransition(WorkOrderStatus.OPEN, null)).isFalse();
        assertThat(WorkOrderStateMachine.canTransition(null, null)).isFalse();
    }

    @Test
    void validateTransitionRejectsNullArguments() {
        assertThatThrownBy(() -> WorkOrderStateMachine.validateTransition(null, WorkOrderStatus.OPEN, "WO-1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WorkOrderStateMachine.validateTransition(WorkOrderStatus.OPEN, null, "WO-1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WorkOrderStateMachine.allowedFrom(null)).isInstanceOf(NullPointerException.class);
    }
}
