package com.fieldwork.ops.sla;

import com.fieldwork.ops.common.exception.ResourceNotFoundException;
import com.fieldwork.ops.sla.event.SlaBreachedEvent;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderPriority;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

    // ------------------------------------------------------------------
    // Policy administration (Phase 4 REST)
    // ------------------------------------------------------------------

    /** All policies, ordered by priority then name, for the admin list view. */
    @Transactional(readOnly = true)
    public List<SlaPolicy> listPolicies() {
        return policies.findAllByOrderByPriorityAscNameAsc();
    }

    @Transactional(readOnly = true)
    public SlaPolicy getPolicy(UUID policyId) {
        return policies
                .findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("SlaPolicy", policyId));
    }

    /**
     * Creates a policy. The response target must not exceed the
     * resolution target, and an <em>active</em> policy must not collide
     * with an existing active policy for the same (priority, category)
     * — the database's partial unique index is the final guard.
     *
     * @param requestedBy human-readable actor recorded on audit columns; may be null
     */
    @Transactional
    public SlaPolicy createPolicy(
            String name,
            WorkOrderPriority priority,
            String category,
            int responseMinutes,
            int resolutionMinutes,
            boolean active,
            String requestedBy) {
        validateTargets(responseMinutes, resolutionMinutes);
        String normalizedCategory = normalizeCategory(category);
        if (active) {
            assertNoActiveConflict(priority, normalizedCategory, null);
        }
        SlaPolicy policy = new SlaPolicy();
        policy.setName(name);
        policy.setPriority(priority);
        policy.setCategory(normalizedCategory);
        policy.setResponseMinutes(responseMinutes);
        policy.setResolutionMinutes(resolutionMinutes);
        policy.setActive(active);
        policy.setCreatedBy(requestedBy);
        policy.setUpdatedBy(requestedBy);
        return policies.save(policy);
    }

    /**
     * Full replacement of a policy's mutable fields. A null
     * {@code active} leaves the current flag untouched.
     *
     * @param requestedBy human-readable actor recorded on audit columns; may be null
     */
    @Transactional
    public SlaPolicy updatePolicy(
            UUID policyId,
            String name,
            WorkOrderPriority priority,
            String category,
            int responseMinutes,
            int resolutionMinutes,
            Boolean active,
            String requestedBy) {
        SlaPolicy policy = getPolicy(policyId);
        validateTargets(responseMinutes, resolutionMinutes);
        String normalizedCategory = normalizeCategory(category);
        boolean willBeActive = active != null ? active : policy.isActive();
        if (willBeActive) {
            assertNoActiveConflict(priority, normalizedCategory, policyId);
        }
        policy.setName(name);
        policy.setPriority(priority);
        policy.setCategory(normalizedCategory);
        policy.setResponseMinutes(responseMinutes);
        policy.setResolutionMinutes(resolutionMinutes);
        policy.setActive(willBeActive);
        policy.setUpdatedBy(requestedBy);
        log.info("Updated SLA policy '{}' (id={})", name, policyId);
        return policy;
    }

    // ------------------------------------------------------------------
    // Breach reads (Phase 4 REST)
    // ------------------------------------------------------------------

    /**
     * Breaches whose deadline passed in [{@code from}, {@code to}].
     * Null bounds are widened (epoch → now on the injected clock), so
     * omitting both returns every recorded breach, newest first.
     */
    @Transactional(readOnly = true)
    public List<SlaBreach> listBreaches(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime start = from != null
                ? from
                : OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime end = to != null ? to : OffsetDateTime.now(clock);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        return breaches.findByBreachedAtBetweenOrderByBreachedAtDesc(start, end);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void validateTargets(int responseMinutes, int resolutionMinutes) {
        if (responseMinutes > resolutionMinutes) {
            throw new IllegalArgumentException(
                    "responseMinutes (%d) must not exceed resolutionMinutes (%d)"
                            .formatted(responseMinutes, resolutionMinutes));
        }
    }

    private static String normalizeCategory(String category) {
        return category == null || category.isBlank() ? null : category.strip();
    }

    /**
     * Rejects an active policy that would collide with an existing
     * active policy for the same (priority, category) — a friendly
     * pre-check ahead of the partial unique index. Category-specific
     * and applies-to-all rows coexist; only an exact (priority,
     * category) match conflicts.
     */
    private void assertNoActiveConflict(
            WorkOrderPriority priority, String category, UUID excludeId) {
        boolean conflict = policies.findActiveCandidates(priority, category).stream()
                .anyMatch(p -> Objects.equals(p.getCategory(), category)
                        && (excludeId == null || !p.getId().equals(excludeId)));
        if (conflict) {
            throw new IllegalArgumentException(
                    "An active SLA policy already exists for priority %s and category %s"
                            .formatted(priority, category == null ? "<all>" : category));
        }
    }
}
