package com.fieldwork.ops.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fieldwork.ops.sla.event.SlaBreachedEvent;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderPriority;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for the SLA engine: policy resolution, deadline math,
 * ON_HOLD pause accounting, and breach recording.
 *
 * <p>Time is fully deterministic: the service is constructed with a
 * fixed {@link Clock}, so deadline and pause assertions never depend
 * on wall-clock timing.
 */
@ExtendWith(MockitoExtension.class)
class SlaServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-23T12:00:00Z");
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final Clock CLOCK = Clock.fixed(T0, UTC);

    @Mock
    private SlaPolicyRepository policies;

    @Mock
    private SlaBreachRepository breaches;

    @Mock
    private ApplicationEventPublisher events;

    private SlaService slaService;

    @BeforeEach
    void setUp() {
        slaService = new SlaService(policies, breaches, events, CLOCK);
    }

    /** {@link WorkOrder}'s constructor is protected; subclass to instantiate it from this package. */
    private static WorkOrder newWorkOrder() {
        return new WorkOrder() {};
    }

    private static SlaPolicy policy(String name, WorkOrderPriority priority, String category) {
        SlaPolicy p = new SlaPolicy();
        p.setName(name);
        p.setPriority(priority);
        p.setCategory(category);
        p.setResponseMinutes(30);
        p.setResolutionMinutes(240);
        p.setActive(true);
        return p;
    }

    // ------------------------------------------------------------------
    // Policy resolution
    // ------------------------------------------------------------------

    @Test
    void resolvePolicyPrefersCategorySpecificOverBase() {
        SlaPolicy specific = policy("P1 network", WorkOrderPriority.P1, "NETWORK");
        SlaPolicy base = policy("P1 base", WorkOrderPriority.P1, null);
        when(policies.findActiveCandidates(WorkOrderPriority.P1, "NETWORK"))
                .thenReturn(List.of(specific, base));

        Optional<SlaPolicy> resolved = slaService.resolvePolicy(WorkOrderPriority.P1, "NETWORK");

        assertThat(resolved).contains(specific);
    }

    @Test
    void resolvePolicyFallsBackToBasePolicy() {
        SlaPolicy base = policy("P1 base", WorkOrderPriority.P1, null);
        when(policies.findActiveCandidates(WorkOrderPriority.P1, "POWER"))
                .thenReturn(List.of(base));

        assertThat(slaService.resolvePolicy(WorkOrderPriority.P1, "POWER")).contains(base);
    }

    @Test
    void resolvePolicyIsEmptyWhenNoActivePolicyExists() {
        when(policies.findActiveCandidates(WorkOrderPriority.P4, "GENERAL")).thenReturn(List.of());

        assertThat(slaService.resolvePolicy(WorkOrderPriority.P4, "GENERAL")).isEmpty();
    }

    // ------------------------------------------------------------------
    // Deadline computation
    // ------------------------------------------------------------------

    @Test
    void applyDeadlinesStampsResponseAndResolutionFromPolicy() {
        SlaPolicy p = policy("P1 base", WorkOrderPriority.P1, null);
        when(policies.findActiveCandidates(WorkOrderPriority.P1, "GENERAL")).thenReturn(List.of(p));
        WorkOrder wo = newWorkOrder();
        wo.setPriority(WorkOrderPriority.P1);
        wo.setCategory("GENERAL");

        slaService.applyDeadlines(wo);

        OffsetDateTime now = OffsetDateTime.ofInstant(T0, UTC);
        assertThat(wo.getResponseDueAt()).isEqualTo(now.plusMinutes(30));
        assertThat(wo.getResolutionDueAt()).isEqualTo(now.plusMinutes(240));
        // dueAt mirrors the resolution target (the headline queue deadline).
        assertThat(wo.getDueAt()).isEqualTo(wo.getResolutionDueAt());
    }

    @Test
    void applyDeadlinesLeavesDeadlinesUnsetWhenNoPolicyResolves() {
        when(policies.findActiveCandidates(WorkOrderPriority.P4, "GENERAL")).thenReturn(List.of());
        WorkOrder wo = newWorkOrder();
        wo.setPriority(WorkOrderPriority.P4);
        wo.setCategory("GENERAL");

        slaService.applyDeadlines(wo);

        assertThat(wo.getResponseDueAt()).isNull();
        assertThat(wo.getResolutionDueAt()).isNull();
        assertThat(wo.getDueAt()).isNull();
    }

    // ------------------------------------------------------------------
    // ON_HOLD pause accounting
    // ------------------------------------------------------------------

    @Test
    void holdCycleAccumulatesPausedSeconds() {
        WorkOrder wo = newWorkOrder();
        assertThat(wo.getOnHoldSince()).isNull();
        assertThat(wo.getSlaPausedSeconds()).isZero();

        slaService.enterHold(wo);
        assertThat(wo.getOnHoldSince()).isEqualTo(OffsetDateTime.ofInstant(T0, UTC));

        // 90 minutes later the hold ends: the elapsed time is banked and the stamp cleared.
        SlaService later = new SlaService(policies, breaches, events, Clock.fixed(T0.plusSeconds(90 * 60), UTC));
        later.exitHold(wo);

        assertThat(wo.getSlaPausedSeconds()).isEqualTo(90 * 60L);
        assertThat(wo.getOnHoldSince()).isNull();
    }

    @Test
    void multipleHoldSpellsAccumulate() {
        WorkOrder wo = newWorkOrder();

        slaService.enterHold(wo);
        new SlaService(policies, breaches, events, Clock.fixed(T0.plusSeconds(600), UTC)).exitHold(wo);
        // Second spell starts at T0+600 and ends at T0+1800 (another 1200s).
        SlaService secondSpell = new SlaService(policies, breaches, events, Clock.fixed(T0.plusSeconds(600), UTC));
        secondSpell.enterHold(wo);
        new SlaService(policies, breaches, events, Clock.fixed(T0.plusSeconds(1800), UTC)).exitHold(wo);

        assertThat(wo.getSlaPausedSeconds()).isEqualTo(600 + 1200L);
        assertThat(wo.getOnHoldSince()).isNull();
    }

    @Test
    void exitHoldWithNoHoldInProgressIsANoOp() {
        WorkOrder wo = newWorkOrder();
        wo.setSlaPausedSeconds(42);

        slaService.exitHold(wo);

        assertThat(wo.getSlaPausedSeconds()).isEqualTo(42);
        assertThat(wo.getOnHoldSince()).isNull();
    }

    // ------------------------------------------------------------------
    // Effective age
    // ------------------------------------------------------------------

    @Test
    void effectiveAgeSubtractsPausedTime() {
        WorkOrder wo = newWorkOrder();
        wo.setCreatedAt(OffsetDateTime.ofInstant(T0, UTC));
        wo.setSlaPausedSeconds(600);

        long age = slaService.effectiveAgeSeconds(wo, OffsetDateTime.ofInstant(T0.plusSeconds(3600), UTC));

        assertThat(age).isEqualTo(3000);
    }

    @Test
    void effectiveAgeIncludesTheOngoingHoldSpell() {
        WorkOrder wo = newWorkOrder();
        wo.setCreatedAt(OffsetDateTime.ofInstant(T0, UTC));
        wo.setStatus(WorkOrderStatus.ON_HOLD);
        wo.setOnHoldSince(OffsetDateTime.ofInstant(T0.plusSeconds(1800), UTC));
        wo.setSlaPausedSeconds(600);

        // Total 3600s, minus 600s banked, minus 1800s of the ongoing spell.
        long age = slaService.effectiveAgeSeconds(wo, OffsetDateTime.ofInstant(T0.plusSeconds(3600), UTC));

        assertThat(age).isEqualTo(1200);
    }

    @Test
    void effectiveAgeIsZeroWhenCreatedAtIsUnknown() {
        WorkOrder wo = newWorkOrder();

        assertThat(slaService.effectiveAgeSeconds(wo, OffsetDateTime.ofInstant(T0, UTC))).isZero();
    }

    @Test
    void effectiveAgeNeverGoesNegative() {
        WorkOrder wo = newWorkOrder();
        wo.setCreatedAt(OffsetDateTime.ofInstant(T0, UTC));
        wo.setSlaPausedSeconds(9999);

        long age = slaService.effectiveAgeSeconds(wo, OffsetDateTime.ofInstant(T0.plusSeconds(3600), UTC));

        assertThat(age).isZero();
    }

    // ------------------------------------------------------------------
    // Breach recording
    // ------------------------------------------------------------------

    @Test
    void recordBreachPersistsAndPublishesTheEvent() {
        WorkOrder wo = newWorkOrder();
        wo.setId(UUID.randomUUID());
        wo.setTicketNumber("WO-2026-000011");
        SlaPolicy p = policy("P1 base", WorkOrderPriority.P1, null);
        OffsetDateTime breachedAt = OffsetDateTime.ofInstant(T0.minusSeconds(300), UTC);

        SlaBreach breach = slaService.recordBreach(wo, p, BreachType.RESPONSE, breachedAt);

        ArgumentCaptor<SlaBreach> breachCaptor = ArgumentCaptor.forClass(SlaBreach.class);
        verify(breaches).save(breachCaptor.capture());
        SlaBreach saved = breachCaptor.getValue();
        assertThat(saved.getWorkOrder()).isSameAs(wo);
        assertThat(saved.getPolicy()).isSameAs(p);
        assertThat(saved.getBreachType()).isEqualTo(BreachType.RESPONSE);
        assertThat(saved.getBreachedAt()).isEqualTo(breachedAt);
        // detectedAt is stamped from the injected clock.
        assertThat(saved.getDetectedAt()).isEqualTo(OffsetDateTime.ofInstant(T0, UTC));

        ArgumentCaptor<SlaBreachedEvent> eventCaptor = ArgumentCaptor.forClass(SlaBreachedEvent.class);
        verify(events).publishEvent(eventCaptor.capture());
        SlaBreachedEvent event = eventCaptor.getValue();
        assertThat(event.workOrderId()).isEqualTo(wo.getId());
        assertThat(event.ticketNumber()).isEqualTo("WO-2026-000011");
        assertThat(event.breachType()).isEqualTo(BreachType.RESPONSE);
        assertThat(event.breachedAt()).isEqualTo(breachedAt);
        assertThat(event.detectedAt()).isEqualTo(OffsetDateTime.ofInstant(T0, UTC));

        assertThat(breach).isSameAs(saved);
    }

    // ------------------------------------------------------------------
    // Policy administration guards
    // ------------------------------------------------------------------

    @Test
    void createPolicyRejectsResponseTargetAboveResolutionTarget() {
        assertThatThrownBy(() -> slaService.createPolicy(
                        "bad", WorkOrderPriority.P1, null, 300, 240, true, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("responseMinutes");
    }

    @Test
    void createPolicyRejectsConflictingActivePolicy() {
        SlaPolicy existing = policy("P1 network", WorkOrderPriority.P1, "NETWORK");
        existing.setId(UUID.randomUUID());
        when(policies.findActiveCandidates(WorkOrderPriority.P1, "NETWORK"))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> slaService.createPolicy(
                        "P1 network v2", WorkOrderPriority.P1, "NETWORK", 30, 240, true, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createPolicyNormalizesBlankCategoryToNull() {
        when(policies.findActiveCandidates(WorkOrderPriority.P2, null)).thenReturn(List.of());
        when(policies.save(any(SlaPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        SlaPolicy created = slaService.createPolicy("P2 base", WorkOrderPriority.P2, "   ", 60, 480, true, "tester");

        ArgumentCaptor<SlaPolicy> captor = ArgumentCaptor.forClass(SlaPolicy.class);
        verify(policies).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isNull();
        assertThat(created.isActive()).isTrue();
        assertThat(created.getCreatedBy()).isEqualTo("tester");
    }

    @Test
    void listBreachesRejectsInvertedBounds() {
        OffsetDateTime from = OffsetDateTime.ofInstant(T0, UTC);
        OffsetDateTime to = OffsetDateTime.ofInstant(T0.minusSeconds(60), UTC);

        assertThatThrownBy(() -> slaService.listBreaches(from, to))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'from' must not be after 'to'");
    }
}
