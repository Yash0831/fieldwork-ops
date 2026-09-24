package com.fieldwork.ops.notification;

import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.workorder.WorkOrder;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Creates notification rows from domain events and performs single
 * delivery attempts for the retry worker.
 *
 * <p><strong>Idempotency.</strong> Every row carries a dedupe key of
 * {@code <eventType>:<workOrderId>:<recipientId>} backed by a UNIQUE
 * index (V9). Creation pre-checks the key so a redelivered event is
 * suppressed without a failed transaction; the constraint is the
 * backstop for two listeners racing on the same event — the loser
 * rolls back having written nothing else.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    /** Result of one {@link #attemptDelivery(UUID)} call. */
    public enum DeliveryOutcome {
        SENT,
        RETRY_SCHEDULED,
        DEAD_LETTERED,
        SKIPPED
    }

    /** Backoff never waits longer than this between attempts. */
    private static final long MAX_BACKOFF_MINUTES = 60;

    private final NotificationRepository notifications;
    private final NotificationSender sender;
    private final NotificationRetryProperties retryProperties;
    private final Clock clock;

    /**
     * Queues one PENDING notification for a recipient, unless an
     * identical row already exists (redelivered event). The row's
     * {@code nextRetryAt} is now, so the worker picks it up on its next
     * poll.
     *
     * @return true when a new row was queued, false when suppressed as
     *         a duplicate
     * @throws DataIntegrityViolationException when a concurrent
     *         redelivery won the race — the caller's transaction rolls
     *         back; the notification exists exactly once
     */
    @Transactional
    public boolean createNotification(
            String eventType, WorkOrder workOrder, User recipient, String subject, String body) {
        if (recipient == null || !StringUtils.hasText(recipient.getEmail())) {
            log.warn(
                    "Skipping {} notification for ticket {}: recipient has no email address",
                    eventType,
                    workOrder != null ? workOrder.getTicketNumber() : "?");
            return false;
        }
        String dedupeKey = dedupeKey(eventType, workOrder.getId(), recipient);
        if (notifications.existsByDedupeKey(dedupeKey)) {
            log.debug(
                    "Duplicate {} notification suppressed for ticket {} recipient {}",
                    eventType,
                    workOrder.getTicketNumber(),
                    recipient.getEmail());
            return false;
        }
        Notification notification = new Notification();
        notification.setDedupeKey(dedupeKey);
        notification.setEventType(eventType);
        notification.setRelatedWorkOrder(workOrder);
        notification.setRecipient(recipient);
        notification.setRecipientEmail(recipient.getEmail());
        notification.setChannel(NotificationChannel.EMAIL);
        notification.setSubject(subject);
        notification.setBody(body);
        notification.setStatus(NotificationStatus.PENDING);
        notification.setMaxAttempts(retryProperties.maxAttempts());
        notification.setNextRetryAt(OffsetDateTime.now(clock));
        notification.setCreatedBy("system:event-listener");
        notifications.saveAndFlush(notification);
        log.info(
                "Queued {} notification for {} (ticket {})",
                eventType,
                recipient.getEmail(),
                workOrder.getTicketNumber());
        return true;
    }

    /**
     * Performs one delivery attempt for a single notification, in its
     * own transaction so a poison row can't fail the worker's batch.
     *
     * <p>State machine: success → SENT (with {@code sentAt}); failure →
     * {@code attemptCount}++, {@code nextRetryAt} pushed out by
     * exponential backoff, staying PENDING; attempts reaching
     * {@code maxAttempts} → DEAD_LETTER for human review. Rows already
     * SENT/DEAD_LETTER, or not yet due, are skipped — safe if two
     * workers ever race.
     */
    @Transactional
    public DeliveryOutcome attemptDelivery(UUID notificationId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Notification notification = notifications.findById(notificationId).orElse(null);
        if (notification == null
                || (notification.getStatus() != NotificationStatus.PENDING
                        && notification.getStatus() != NotificationStatus.FAILED)
                || (notification.getNextRetryAt() != null && notification.getNextRetryAt().isAfter(now))) {
            return DeliveryOutcome.SKIPPED;
        }
        try {
            sender.send(notification);
        } catch (Exception e) {
            int attempts = notification.getAttemptCount() + 1;
            notification.setAttemptCount(attempts);
            notification.setLastError(truncate(e.getMessage()));
            notification.setUpdatedBy("system:retry-worker");
            if (attempts >= notification.getMaxAttempts()) {
                notification.setStatus(NotificationStatus.DEAD_LETTER);
                notification.setNextRetryAt(null);
                log.warn(
                        "Notification {} dead-lettered after {}/{} attempts (to={}, eventType={}): {}",
                        notification.getId(),
                        attempts,
                        notification.getMaxAttempts(),
                        notification.getRecipientEmail(),
                        notification.getEventType(),
                        e.getMessage());
                return DeliveryOutcome.DEAD_LETTERED;
            }
            long backoffMinutes = backoffMinutes(attempts);
            notification.setStatus(NotificationStatus.PENDING);
            notification.setNextRetryAt(now.plusMinutes(backoffMinutes));
            log.info(
                    "Notification {} delivery attempt {}/{} failed; next retry in {} min: {}",
                    notification.getId(),
                    attempts,
                    notification.getMaxAttempts(),
                    backoffMinutes,
                    e.getMessage());
            return DeliveryOutcome.RETRY_SCHEDULED;
        }
        notification.setStatus(NotificationStatus.SENT);
        notification.setSentAt(now);
        notification.setLastError(null);
        notification.setNextRetryAt(null);
        notification.setUpdatedBy("system:retry-worker");
        log.info(
                "Notification {} sent to {} (eventType={})",
                notification.getId(),
                notification.getRecipientEmail(),
                notification.getEventType());
        return DeliveryOutcome.SENT;
    }

    /**
     * Exponential backoff in minutes after a failed attempt:
     * 1, 2, 4, 8, 16, … capped at 60 so a raised max-attempts can't
     * schedule a retry years out.
     */
    static long backoffMinutes(int attempt) {
        return Math.min(1L << (attempt - 1), MAX_BACKOFF_MINUTES);
    }

    private static String dedupeKey(String eventType, UUID workOrderId, User recipient) {
        String who = recipient.getId() != null
                ? recipient.getId().toString()
                : "email=" + recipient.getEmail();
        String key = eventType + ":" + workOrderId + ":" + who;
        return key.length() > 255 ? key.substring(0, 255) : key;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
