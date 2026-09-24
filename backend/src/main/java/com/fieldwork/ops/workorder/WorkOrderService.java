package com.fieldwork.ops.workorder;

import com.fieldwork.ops.auth.Team;
import com.fieldwork.ops.auth.TeamRepository;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.exception.IdempotencyConflictException;
import com.fieldwork.ops.common.exception.ResourceNotFoundException;
import com.fieldwork.ops.sla.SlaService;
import com.fieldwork.ops.workorder.event.WorkOrderCreatedEvent;
import com.fieldwork.ops.workorder.event.WorkOrderStatusChangedEvent;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work-order lifecycle service: idempotent creation, guarded status
 * transitions, and assignment.
 *
 * <p>Status changes are the <em>only</em> way a ticket's status moves:
 * every path validates against {@link WorkOrderStateMachine}, persists a
 * {@link WorkOrderStatusHistory} row, maintains the lifecycle timestamps
 * and ON_HOLD pause accounting (via {@link SlaService}), and publishes a
 * domain event.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderService {

    /** How long an idempotency key remains replayable after creation. */
    private static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofHours(24);

    private final WorkOrderRepository workOrders;
    private final WorkOrderStatusHistoryRepository history;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final UserRepository users;
    private final TeamRepository teams;
    private final SlaService slaService;
    private final TicketNumberGenerator ticketNumbers;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final EntityManager entityManager;

    // ------------------------------------------------------------------
    // Creation (idempotent)
    // ------------------------------------------------------------------

    /**
     * Creates a ticket from {@code command}.
     *
     * <p>When {@code idempotencyKey} is supplied, a repeated call with the
     * same key returns the original ticket and never creates a duplicate:
     * <ul>
     *   <li>key already COMPLETED with the same payload → the original ticket is returned;</li>
     *   <li>key already COMPLETED with a <em>different</em> payload → {@link IdempotencyConflictException}
     *       (client bug);</li>
     *   <li>key IN_PROGRESS and not expired → {@link IdempotencyConflictException}
     *       (an earlier request is still running — back off and retry);</li>
     *   <li>key IN_PROGRESS/FAILED but expired → the stale row is reclaimed and the
     *       request proceeds as new.</li>
     * </ul>
     *
     * <p>The key row is inserted <em>before</em> the ticket, so two
     * requests racing with the same key collide on the primary key: the
     * loser catches the integrity violation, clears the poisoned
     * persistence context, and replays the winner's ticket.
     *
     * @param requestedBy human-readable actor recorded on audit columns;
     *        may be null for system-driven creation
     */
    @Transactional
    public WorkOrder create(CreateWorkOrderCommand command, String idempotencyKey, String requestedBy) {
        Objects.requireNonNull(command, "command must not be null");
        String key = normalizeKey(idempotencyKey);

        if (key != null) {
            Optional<IdempotencyKey> existing = idempotencyKeys.findById(key);
            if (existing.isPresent()) {
                WorkOrder replayed = replay(existing.get(), command);
                if (replayed != null) {
                    return replayed;
                }
                // Stale/failed row was reclaimed above — fall through to a fresh claim.
            }
        }

        IdempotencyKey claim = null;
        if (key != null) {
            claim = newClaim(key, command, requestedBy);
            try {
                idempotencyKeys.saveAndFlush(claim);
            } catch (DataIntegrityViolationException race) {
                // Lost a concurrent race for the same key. The failed insert
                // leaves the persistence context unusable, so clear it before
                // continuing in this transaction, then replay the winner.
                entityManager.clear();
                log.info("Idempotency key {} raced; replaying winner's ticket", key);
                return replay(
                        idempotencyKeys
                                .findById(key)
                                .orElseThrow(() -> new IdempotencyConflictException(
                                        key, "key was claimed concurrently but the row is not visible")),
                        command);
            }
        }

        User requester = users
                .findById(command.requesterId())
                .orElseThrow(() -> new ResourceNotFoundException("User", command.requesterId()));
        Team team = command.teamId() == null
                ? null
                : teams.findById(command.teamId())
                        .orElseThrow(() -> new ResourceNotFoundException("Team", command.teamId()));

        WorkOrder workOrder = new WorkOrder();
        // TODO(Phase 4): TicketNumberGenerator bean allocating from ticket_number_seq (WO-YYYY-NNNNNN).
        workOrder.setTicketNumber(ticketNumbers.generate());
        workOrder.setTitle(command.title());
        workOrder.setDescription(command.description());
        workOrder.setPriority(command.priority());
        workOrder.setCategory(command.category());
        workOrder.setRequester(requester);
        workOrder.setTeam(team);
        workOrder.setEstimatedHours(command.estimatedHours());
        workOrder.setStatus(WorkOrderStatus.OPEN);
        workOrder.setCreatedBy(requestedBy);
        workOrder.setUpdatedBy(requestedBy);
        workOrders.save(workOrder);

        // Resolve the SLA policy and stamp response/resolution deadlines.
        slaService.applyDeadlines(workOrder);

        recordHistory(workOrder, null, WorkOrderStatus.OPEN, null, "Ticket created");

        if (claim != null) {
            claim.setWorkOrder(workOrder);
            claim.setStatus(IdempotencyStatus.COMPLETED);
            claim.setResponseStatus(201);
            // TODO(Phase 4): persist the serialized creation response in
            // responseBody so replays can return the original payload.
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        events.publishEvent(new WorkOrderCreatedEvent(workOrder.getId(), workOrder.getTicketNumber(), now));
        log.info(
                "Created ticket {} (idempotency key: {})",
                workOrder.getTicketNumber(),
                key == null ? "<none>" : key);
        return workOrder;
    }

    // ------------------------------------------------------------------
    // Status transitions
    // ------------------------------------------------------------------

    /**
     * Moves a ticket to {@code target}, guarded by the state machine.
     * Persists a history row, maintains lifecycle timestamps and ON_HOLD
     * pause accounting, and publishes {@link WorkOrderStatusChangedEvent}.
     *
     * @param changedById the acting user, or null for system-driven transitions
     */
    @Transactional
    public WorkOrder transitionStatus(
            UUID workOrderId, WorkOrderStatus target, UUID changedById, String note) {
        WorkOrder workOrder = loadWorkOrder(workOrderId);
        User changedBy = changedById == null
                ? null
                : users.findById(changedById)
                        .orElseThrow(() -> new ResourceNotFoundException("User", changedById));
        return applyTransition(workOrder, target, changedBy, note);
    }

    /**
     * Assigns a ticket to a technician (OPEN → ASSIGNED). Reassignment of
     * an already-ASSIGNED ticket is allowed explicitly: it records a
     * history row but is not a state-machine transition (the machine has
     * no self-transitions) and publishes no status-changed event.
     */
    @Transactional
    public WorkOrder assignTicket(UUID workOrderId, UUID technicianId, UUID assignedById) {
        WorkOrder workOrder = loadWorkOrder(workOrderId);
        User technician = users
                .findById(technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("User", technicianId));
        User assignedBy = assignedById == null
                ? null
                : users.findById(assignedById)
                        .orElseThrow(() -> new ResourceNotFoundException("User", assignedById));

        WorkOrderStatus from = workOrder.getStatus();
        workOrder.setAssignee(technician);
        workOrder.setUpdatedBy(assignedBy == null ? "system" : assignedBy.getUsername());

        if (from == WorkOrderStatus.ASSIGNED) {
            recordHistory(
                    workOrder, from, from, assignedBy, "Reassigned to " + technician.getUsername());
            log.info("Ticket {} reassigned to {}", workOrder.getTicketNumber(), technician.getUsername());
            return workOrder;
        }
        return applyTransition(workOrder, WorkOrderStatus.ASSIGNED, assignedBy,
                "Assigned to " + technician.getUsername());
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public WorkOrder getById(UUID workOrderId) {
        return loadWorkOrder(workOrderId);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private WorkOrder applyTransition(
            WorkOrder workOrder, WorkOrderStatus target, User changedBy, String note) {
        WorkOrderStatus from = workOrder.getStatus();
        WorkOrderStateMachine.validateTransition(from, target, workOrder.getTicketNumber());
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (target == WorkOrderStatus.ON_HOLD) {
            slaService.enterHold(workOrder);
        } else if (from == WorkOrderStatus.ON_HOLD) {
            slaService.exitHold(workOrder);
        }

        workOrder.setStatus(target);
        workOrder.setUpdatedBy(changedBy == null ? "system" : changedBy.getUsername());
        if (from == WorkOrderStatus.OPEN && workOrder.getRespondedAt() == null) {
            // First movement out of OPEN counts as the SLA "response".
            workOrder.setRespondedAt(now);
        }
        if (target == WorkOrderStatus.RESOLVED && workOrder.getResolvedAt() == null) {
            workOrder.setResolvedAt(now);
        }
        if (target == WorkOrderStatus.CLOSED && workOrder.getClosedAt() == null) {
            workOrder.setClosedAt(now);
        }

        recordHistory(workOrder, from, target, changedBy, note);
        events.publishEvent(new WorkOrderStatusChangedEvent(
                workOrder.getId(), workOrder.getTicketNumber(), from, target, now));
        log.info("Ticket {} transitioned {} -> {}", workOrder.getTicketNumber(), from, target);
        return workOrder;
    }

    private void recordHistory(
            WorkOrder workOrder, WorkOrderStatus from, WorkOrderStatus to, User changedBy, String note) {
        WorkOrderStatusHistory entry = new WorkOrderStatusHistory();
        entry.setWorkOrder(workOrder);
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setChangedBy(changedBy);
        entry.setChangedAt(OffsetDateTime.now(clock));
        entry.setNote(note);
        history.save(entry);
    }

    private WorkOrder loadWorkOrder(UUID workOrderId) {
        return workOrders
                .findById(workOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", workOrderId));
    }

    /**
     * Replays an existing idempotency row. Returns the original ticket, or
     * {@code null} when the row was stale/failed and has been reclaimed —
     * the caller then proceeds with a fresh claim.
     */
    private WorkOrder replay(IdempotencyKey existing, CreateWorkOrderCommand command) {
        String key = existing.getIdemKey();
        if (existing.getStatus() != IdempotencyStatus.COMPLETED) {
            if (existing.getExpiresAt().isBefore(OffsetDateTime.now(clock))) {
                // The original request died (or failed) before completing and
                // the key has expired: reclaim it and treat this as a new request.
                // Phase 6's key-sweeper also reaps these rows on a schedule.
                log.info("Reclaiming stale idempotency key {}", key);
                idempotencyKeys.delete(existing);
                entityManager.flush();
                return null;
            }
            throw new IdempotencyConflictException(
                    key, "an earlier request with this key is still in progress; retry later");
        }
        if (!existing.getRequestHash().equals(requestHash(command))) {
            throw new IdempotencyConflictException(key, "key was already used with a different payload");
        }
        WorkOrder workOrder = existing.getWorkOrder();
        if (workOrder == null) {
            throw new IdempotencyConflictException(key, "key is completed but references no ticket");
        }
        log.info("Idempotent replay for key {}: returning original ticket {}", key, workOrder.getTicketNumber());
        return workOrder;
    }

    private IdempotencyKey newClaim(String key, CreateWorkOrderCommand command, String requestedBy) {
        IdempotencyKey claim = new IdempotencyKey();
        claim.setIdemKey(key);
        claim.setRequester(requestedBy == null || requestedBy.isBlank() ? "unknown" : requestedBy);
        claim.setRequestHash(requestHash(command));
        claim.setStatus(IdempotencyStatus.IN_PROGRESS);
        claim.setExpiresAt(OffsetDateTime.now(clock).plus(IDEMPOTENCY_KEY_TTL));
        claim.setCreatedBy(requestedBy);
        claim.setUpdatedBy(requestedBy);
        return claim;
    }

    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.strip();
    }

    /** Stable SHA-256 fingerprint of the creation payload, for replay validation. */
    private static String requestHash(CreateWorkOrderCommand command) {
        String canonical = String.join(
                "|",
                command.title(),
                Objects.toString(command.description(), ""),
                command.priority().name(),
                command.category(),
                command.requesterId().toString(),
                Objects.toString(command.teamId(), ""),
                Objects.toString(command.estimatedHours(), ""));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest is not available", e);
        }
    }
}
