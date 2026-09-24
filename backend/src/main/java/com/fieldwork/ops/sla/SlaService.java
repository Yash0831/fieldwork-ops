package com.fieldwork.ops.sla;

import com.fieldwork.ops.sla.event.SlaBreachedEvent;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderPriority;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SLA engine: policy resolution, deadline computation, and ON_HOLD
 * pause accounting.
 *
 * <p>Policy resolution prefers the category-specific active policy over
 * the base (NULL-category) policy for the same priority. Deadlines are
 * computed once at creation from the injected {@link Clock}; the clock
 * is never consulted via static {@code now()} calls, so Phase 9 tests
 * can freeze time.
 *
 * <p>Pause accounting: entering ON_HOLD stamps {@code onHoldSince};
 * leaving ON_HOLD adds the elapsed hold seconds to
 * {@code slaPausedSeconds} and clears the stamp. The deadlines themselves
 * are not rewritten — the Phase 6 breach scanner computes
 * {@code effectiveAgeSeconds} (age minus paused time) before comparing
 * against the policy targets.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaService {

    private final SlaPolicyRepository policies;
    private final SlaBreachRepository breaches;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * Resolves the active policy for a priority/category pair: the
     * category-specific row wins, falling back to the NULL-category
     * (applies-to-all) row. Empty when no active policy exists.
     */
    @Transactional(readOnly = true)
    public Optional<SlaPolicy> resolvePolicy(WorkOrderPriority priority, String category) {
        List<SlaPolicy> candidates = policies.findActiveCandidates(priority, category);
        return candidates.stream().findFirst();
    }

    /**
     * Computes response/resolution deadlines from the resolved policy and
     * stamps them on the work order. {@code dueAt} mirrors the resolution
     * target (the headline deadline used for queue ordering). No-op when
     * no policy resolves — deadlines stay null rather than guessed.
     */
    public void applyDeadlines(WorkOrder workOrder) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<SlaPolicy> policy = resolvePolicy(workOrder.getPriority(), workOrder.getCategory());
        if (policy.isEmpty()) {
            log.warn(
                    "No active SLA policy for priority={} category={}; leaving deadlines unset on new ticket",
                    workOrder.getPriority(),
                    workOrder.getCategory());
            return;
        }
        SlaPolicy p = policy.get();
        workOrder.setResponseDueAt(now.plusMinutes(p.getResponseMinutes()));
        workOrder.setResolutionDueAt(now.plusMinutes(p.getResolutionMinutes()));
        workOrder.setDueAt(workOrder.getResolutionDueAt());
        log.debug(
                "Applied SLA policy '{}' to ticket {}: response due {}, resolution due {}",
                p.getName(),
                workOrder.getTicketNumber(),
                workOrder.getResponseDueAt(),
                workOrder.getResolutionDueAt());
    }

    /**
     * Records the start of an ON_HOLD spell. Called by
     * {@code WorkOrderService} when a transition enters ON_HOLD.
     */
    public void enterHold(WorkOrder workOrder) {
        workOrder.setOnHoldSince(OffsetDateTime.now(clock));
    }

    /**
     * Ends the current ON_HOLD spell: the elapsed seconds are added to
     * {@code slaPausedSeconds} and the hold-start stamp is cleared.
     * Called when a transition leaves ON_HOLD. A no-op when no hold is
     * in progress (defensive — the state machine guarantees the pairing).
     */
    public void exitHold(WorkOrder workOrder) {
        OffsetDateTime holdStart = workOrder.getOnHoldSince();
        if (holdStart == null) {
            return;
        }
        long elapsed = Duration.between(holdStart, OffsetDateTime.now(clock)).getSeconds();
        workOrder.setSlaPausedSeconds(workOrder.getSlaPausedSeconds() + Math.max(0, elapsed));
        workOrder.setOnHoldSince(null);
    }

    /**
     * SLA-relevant age of a ticket at a given instant: wall-clock age
     * since creation minus all paused (ON_HOLD) time, including the
     * ongoing spell when the ticket is currently on hold. Used by the
     * Phase 6 breach scanner to compare against policy targets.
     */
    public long effectiveAgeSeconds(WorkOrder workOrder, OffsetDateTime at) {
        if (workOrder.getCreatedAt() == null) {
            return 0;
        }
        long total = Duration.between(workOrder.getCreatedAt(), at).getSeconds();
        long paused = workOrder.getSlaPausedSeconds();
        if (workOrder.getStatus() == WorkOrderStatus.ON_HOLD && workOrder.getOnHoldSince() != null) {
            paused += Duration.between(workOrder.getOnHoldSince(), at).getSeconds();
        }
        return Math.max(0, total - Math.max(0, paused));
    }

    /**
     * Records a breach and publishes {@link SlaBreachedEvent}. The
     * {@code UNIQUE(work_order_id, breach_type)} database constraint makes
     * this safe to call from a re-runnable scanner: a duplicate insert
     * fails loudly rather than double-counting. Called by the Phase 6
     * breach-scan job; {@code breachedAt} is the deadline that passed,
     * {@code detectedAt} is stamped from the injected clock.
     */
    @Transactional
    public SlaBreach recordBreach(
            WorkOrder workOrder, SlaPolicy policy, BreachType breachType, OffsetDateTime breachedAt) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        SlaBreach breach = new SlaBreach();
        breach.setWorkOrder(workOrder);
        breach.setPolicy(policy);
        breach.setBreachType(breachType);
        breach.setBreachedAt(breachedAt);
        breach.setDetectedAt(now);
        breaches.save(breach);
        events.publishEvent(new SlaBreachedEvent(
                workOrder.getId(), workOrder.getTicketNumber(), breachType, breachedAt, now));
        log.info(
                "Recorded {} breach for ticket {} (policy '{}', deadline {})",
                breachType,
                workOrder.getTicketNumber(),
                policy.getName(),
                breachedAt);
        return breach;
    }
}
