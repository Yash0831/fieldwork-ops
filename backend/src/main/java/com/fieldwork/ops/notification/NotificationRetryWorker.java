package com.fieldwork.ops.notification;

import com.fieldwork.ops.common.logging.CorrelationIds;
import com.fieldwork.ops.notification.NotificationService.DeliveryOutcome;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers queued notifications (Phase 6).
 *
 * <p>Each run picks up PENDING notifications whose {@code nextRetryAt}
 * has passed (oldest due first, capped by
 * {@code notification.retry.batch-size}) and attempts delivery through
 * the {@link NotificationSender} seam. Failures back off exponentially
 * (1, 2, 4, 8, 16… minutes); a notification that exhausts its
 * {@code maxAttempts} is parked in DEAD_LETTER for human review.
 *
 * <p>Each notification is attempted in its own transaction, and one
 * poison row can't fail the batch. {@code FAILED} rows are also picked
 * up: that status is the manual-requeue state — an operator moves a
 * DEAD_LETTER row to FAILED to force re-delivery, and the worker treats
 * it like PENDING from there.
 *
 * <p><strong>Single-instance only.</strong> Two workers polling the
 * same rows would double-deliver; the per-row re-check in
 * {@code attemptDelivery} narrows but does not close that race.
 * Multi-instance deployments need distributed locking (e.g. ShedLock)
 * around this job — see the README's known-limitations section.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRetryWorker {

    private final NotificationRepository notifications;
    private final NotificationService notificationService;
    private final NotificationRetryProperties retryProperties;
    private final Clock clock;

    /**
     * Polls for due notifications. {@code fixedDelay} (not a fixed
     * rate) guarantees a run never overlaps itself on this instance.
     */
    @Scheduled(fixedDelayString = "${notification.retry.fixed-delay:60000}")
    public void deliverDueNotifications() {
        CorrelationIds.withNewId(() -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<Notification> due = notifications.findByStatusInAndNextRetryAtBefore(
                    List.of(NotificationStatus.PENDING, NotificationStatus.FAILED),
                    now,
                    PageRequest.of(0, retryProperties.batchSize()));
            if (due.isEmpty()) {
                log.debug("Notification retry worker: nothing due");
                return;
            }
            log.info("Notification retry worker: {} notification(s) due", due.size());
            int sent = 0;
            int rescheduled = 0;
            int deadLettered = 0;
            int skipped = 0;
            for (Notification notification : due) {
                try {
                    DeliveryOutcome outcome = notificationService.attemptDelivery(notification.getId());
                    switch (outcome) {
                        case SENT -> sent++;
                        case RETRY_SCHEDULED -> rescheduled++;
                        case DEAD_LETTERED -> deadLettered++;
                        case SKIPPED -> skipped++;
                    }
                } catch (Exception e) {
                    // attemptDelivery is transactional per row; this is a
                    // last-resort guard so one poison row can't kill the batch.
                    log.error(
                            "Notification retry worker failed on notification {}",
                            notification.getId(),
                            e);
                }
            }
            log.info(
                    "Notification retry worker done: sent={}, rescheduled={}, deadLettered={}, skipped={}",
                    sent,
                    rescheduled,
                    deadLettered,
                    skipped);
        });
    }
}
