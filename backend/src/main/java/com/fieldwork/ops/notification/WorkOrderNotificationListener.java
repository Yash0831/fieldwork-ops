package com.fieldwork.ops.notification;

import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.logging.CorrelationIds;
import com.fieldwork.ops.sla.event.SlaBreachedEvent;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderRepository;
import com.fieldwork.ops.workorder.event.WorkOrderCreatedEvent;
import com.fieldwork.ops.workorder.event.WorkOrderStatusChangedEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns domain events into notification rows (Phase 6).
 *
 * <p>Each handler runs {@code @Async} after the publishing transaction
 * commits ({@code AFTER_COMMIT}), so a rolled-back work-order change
 * never notifies anyone. Handlers are idempotent: every row carries a
 * dedupe key of {@code <eventType>:<workOrderId>:<recipientId>} with a
 * UNIQUE backstop, so a redelivered event can't double-notify.
 *
 * <p><strong>Channel note.</strong> Rows are written with channel EMAIL
 * and double as the in-app inbox record (queried via
 * {@code NotificationRepository.findByRecipientId}); the retry worker
 * delivers the email leg. There is no separate IN_APP channel value —
 * the row itself <em>is</em> the in-app notification.
 *
 * <p><strong>Recipients.</strong> New tickets notify all active
 * dispatchers; status changes notify the requester and the assignee;
 * SLA breaches notify the assignee and the dispatchers, who act as the
 * operational supervisors (no SUPERVISOR value exists in
 * {@code RoleName}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkOrderNotificationListener {

    public static final String EVENT_WORK_ORDER_CREATED = "WORK_ORDER_CREATED";
    public static final String EVENT_WORK_ORDER_STATUS_CHANGED = "WORK_ORDER_STATUS_CHANGED";
    public static final String EVENT_SLA_BREACHED = "SLA_BREACHED";

    private final NotificationService notificationService;
    private final WorkOrderRepository workOrders;
    private final UserRepository users;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkOrderCreated(WorkOrderCreatedEvent event) {
        CorrelationIds.ensurePresent(() -> {
            WorkOrder workOrder = loadWorkOrder(event.workOrderId());
            if (workOrder == null) {
                return;
            }
            List<User> dispatchers = users.findByRoleNameAndActiveTrue(RoleName.DISPATCHER);
            if (dispatchers.isEmpty()) {
                log.warn("No active dispatchers to notify about new ticket {}", event.ticketNumber());
                return;
            }
            String subject = "[%s] New %s ticket: %s"
                    .formatted(workOrder.getTicketNumber(), workOrder.getPriority(), workOrder.getTitle());
            String body = """
                    A new ticket is waiting for triage.

                    Ticket:   %s
                    Title:    %s
                    Priority: %s
                    Category: %s"""
                    .formatted(
                            workOrder.getTicketNumber(),
                            workOrder.getTitle(),
                            workOrder.getPriority(),
                            workOrder.getCategory());
            int queued = notifyAll(EVENT_WORK_ORDER_CREATED, workOrder, dispatchers, subject, body);
            log.info(
                    "Queued {} new-ticket notification(s) for {} dispatcher(s) (ticket {})",
                    queued,
                    dispatchers.size(),
                    event.ticketNumber());
        });
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkOrderStatusChanged(WorkOrderStatusChangedEvent event) {
        CorrelationIds.ensurePresent(() -> {
            WorkOrder workOrder = loadWorkOrder(event.workOrderId());
            if (workOrder == null) {
                return;
            }
            Set<UUID> recipientIds = new LinkedHashSet<>();
            if (workOrder.getRequester() != null) {
                recipientIds.add(workOrder.getRequester().getId());
            }
            if (workOrder.getAssignee() != null) {
                recipientIds.add(workOrder.getAssignee().getId());
            }
            List<User> recipients = resolveUsers(recipientIds);
            if (recipients.isEmpty()) {
                log.warn("No recipients for status change on ticket {}", event.ticketNumber());
                return;
            }
            String subject = "[%s] Status changed: %s → %s"
                    .formatted(workOrder.getTicketNumber(), event.fromStatus(), event.toStatus());
            String body = """
                    Ticket %s moved from %s to %s.

                    Title:    %s
                    Priority: %s"""
                    .formatted(
                            workOrder.getTicketNumber(),
                            event.fromStatus(),
                            event.toStatus(),
                            workOrder.getTitle(),
                            workOrder.getPriority());
            int queued = notifyAll(EVENT_WORK_ORDER_STATUS_CHANGED, workOrder, recipients, subject, body);
            log.info(
                    "Queued {} status-change notification(s) for ticket {} ({} → {})",
                    queued,
                    event.ticketNumber(),
                    event.fromStatus(),
                    event.toStatus());
        });
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlaBreached(SlaBreachedEvent event) {
        CorrelationIds.ensurePresent(() -> {
            WorkOrder workOrder = loadWorkOrder(event.workOrderId());
            if (workOrder == null) {
                return;
            }
            Set<UUID> recipientIds = new LinkedHashSet<>();
            if (workOrder.getAssignee() != null) {
                recipientIds.add(workOrder.getAssignee().getId());
            }
            for (User dispatcher : users.findByRoleNameAndActiveTrue(RoleName.DISPATCHER)) {
                recipientIds.add(dispatcher.getId());
            }
            List<User> recipients = resolveUsers(recipientIds);
            if (recipients.isEmpty()) {
                log.warn("No recipients for SLA breach on ticket {}", event.ticketNumber());
                return;
            }
            String subject = "[%s] SLA breached: %s"
                    .formatted(workOrder.getTicketNumber(), event.breachType());
            String body = """
                    Ticket %s breached its %s SLA target.

                    Deadline passed: %s
                    Detected:        %s
                    Priority:        %s"""
                    .formatted(
                            workOrder.getTicketNumber(),
                            event.breachType(),
                            event.breachedAt(),
                            event.detectedAt(),
                            workOrder.getPriority());
            int queued = notifyAll(EVENT_SLA_BREACHED, workOrder, recipients, subject, body);
            log.info(
                    "Queued {} SLA-breach notification(s) for ticket {} ({})",
                    queued,
                    event.ticketNumber(),
                    event.breachType());
        });
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private WorkOrder loadWorkOrder(UUID workOrderId) {
        return workOrders.findById(workOrderId).orElseGet(() -> {
            log.warn("Domain event for unknown work order {}; skipping notifications", workOrderId);
            return null;
        });
    }

    /**
     * Loads fully-initialized user entities for the recipient ids. The
     * work order's requester/assignee associations are lazy proxies on a
     * detached entity here, so they are re-resolved rather than
     * traversed.
     */
    private List<User> resolveUsers(Collection<UUID> ids) {
        List<User> result = new ArrayList<>();
        for (UUID id : ids) {
            if (id == null) {
                continue;
            }
            users.findById(id).ifPresent(result::add);
        }
        return result;
    }

    /**
     * Queues one notification per recipient. A lost dedupe race rolls
     * back only that recipient's insert — the rest still go out.
     */
    private int notifyAll(
            String eventType, WorkOrder workOrder, List<User> recipients, String subject, String body) {
        int queued = 0;
        for (User recipient : recipients) {
            try {
                if (notificationService.createNotification(eventType, workOrder, recipient, subject, body)) {
                    queued++;
                }
            } catch (DataIntegrityViolationException e) {
                log.debug(
                        "Concurrent duplicate {} notification for ticket {} recipient {}; suppressing",
                        eventType,
                        workOrder.getTicketNumber(),
                        recipient.getEmail());
            }
        }
        return queued;
    }
}
