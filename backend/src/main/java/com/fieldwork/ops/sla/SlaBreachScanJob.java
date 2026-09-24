package com.fieldwork.ops.sla;

import com.fieldwork.ops.common.logging.CorrelationIds;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderRepository;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic SLA breach detector (Phase 6).
 *
 * <p>Every run scans all non-terminal tickets, computes each ticket's
 * <em>effective</em> SLA age (wall-clock age minus ON_HOLD pause time)
 * via {@link SlaService#effectiveAgeSeconds}, and records a breach for
 * any ticket still awaiting its response/resolution milestone whose
 * effective age has passed the policy target. Recording goes through
 * {@link SlaService#recordBreach}, which persists the
 * {@code SlaBreach} and publishes {@code SlaBreachedEvent} for the
 * notification listener.
 *
 * <p>Re-running is safe: a ticket is skipped when a breach of that type
 * already exists, and the {@code UNIQUE(work_order_id, breach_type)}
 * constraint is the backstop for concurrent recorders.
 *
 * <p>Two deliberate scoping choices: (1) only tickets still awaiting
 * the milestone are candidates — a ticket that already responded can't
 * newly breach its response target, so {@code respondedAt} /
 * {@code resolvedAt} gate the checks; (2) terminal tickets (RESOLVED,
 * CLOSED, CANCELLED) are never scanned.
 *
 * <p><strong>Single-instance only.</strong> Two instances running this
 * job concurrently would detect the same breach; the unique constraint
 * keeps the data correct (the loser rolls back), but multi-instance
 * deployments should add distributed locking (e.g. ShedLock) instead of
 * relying on constraint violations for coordination — see the README's
 * known-limitations section.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlaBreachScanJob {

    private static final Set<WorkOrderStatus> SCANNED_STATUSES = EnumSet.of(
            WorkOrderStatus.OPEN,
            WorkOrderStatus.ASSIGNED,
            WorkOrderStatus.IN_PROGRESS,
            WorkOrderStatus.ON_HOLD);

    private final WorkOrderRepository workOrders;
    private final SlaBreachRepository breaches;
    private final SlaService slaService;
    private final Clock clock;

    /**
     * Scans for breaches on {@code sla.scan.cron} (default every
     * 5 minutes). One run, one correlation ID; per-ticket failures are
     * logged and don't abort the scan.
     */
    @Scheduled(cron = "${sla.scan.cron:0 */5 * * * *}")
    public void scanForBreaches() {
        CorrelationIds.withNewId(() -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<WorkOrder> candidates = workOrders.findByStatusIn(SCANNED_STATUSES);
            int[] counts = new int[2]; // [response breaches, resolution breaches]
            for (WorkOrder workOrder : candidates) {
                try {
                    scanOne(workOrder, now, counts);
                } catch (Exception e) {
                    log.error(
                            "SLA breach scan failed for ticket {}",
                            workOrder.getTicketNumber(),
                            e);
                }
            }
            log.info(
                    "SLA breach scan complete: {} ticket(s) scanned, {} response and {} resolution breach(es) recorded",
                    candidates.size(),
                    counts[0],
                    counts[1]);
        });
    }

    private void scanOne(WorkOrder workOrder, OffsetDateTime now, int[] counts) {
        Optional<SlaPolicy> policy = slaService.resolvePolicy(workOrder.getPriority(), workOrder.getCategory());
        if (policy.isEmpty()) {
            return;
        }
        SlaPolicy slaPolicy = policy.get();
        long effectiveAgeSeconds = slaService.effectiveAgeSeconds(workOrder, now);
        if (workOrder.getRespondedAt() == null
                && effectiveAgeSeconds >= slaPolicy.getResponseMinutes() * 60L
                && recordBreachIfAbsent(
                        workOrder,
                        slaPolicy,
                        BreachType.RESPONSE,
                        breachedAt(now, effectiveAgeSeconds, slaPolicy.getResponseMinutes()))) {
            counts[0]++;
        }
        if (workOrder.getResolvedAt() == null
                && effectiveAgeSeconds >= slaPolicy.getResolutionMinutes() * 60L
                && recordBreachIfAbsent(
                        workOrder,
                        slaPolicy,
                        BreachType.RESOLUTION,
                        breachedAt(now, effectiveAgeSeconds, slaPolicy.getResolutionMinutes()))) {
            counts[1]++;
        }
    }

    /**
     * Estimates when the effective age crossed the target: the crossing
     * instant, assuming no ON_HOLD time elapsed between the crossing and
     * now. A documented approximation — the scanner detects breaches,
     * it doesn't adjudicate contract disputes.
     */
    private static OffsetDateTime breachedAt(OffsetDateTime now, long effectiveAgeSeconds, int targetMinutes) {
        return now.minusSeconds(Math.max(0, effectiveAgeSeconds - targetMinutes * 60L));
    }

    /**
     * Records the breach unless one of that type already exists. The
     * unique-constraint catch is the backstop for two recorders
     * detecting the same breach concurrently.
     */
    private boolean recordBreachIfAbsent(
            WorkOrder workOrder, SlaPolicy policy, BreachType breachType, OffsetDateTime breachedAt) {
        if (breaches.findByWorkOrderIdAndBreachType(workOrder.getId(), breachType).isPresent()) {
            return false;
        }
        try {
            slaService.recordBreach(workOrder, policy, breachType, breachedAt);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug(
                    "Breach for ticket {} type {} was recorded concurrently; skipping",
                    workOrder.getTicketNumber(),
                    breachType);
            return false;
        }
    }
}
