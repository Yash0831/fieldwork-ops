package com.fieldwork.ops.notification;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * The retry worker's polling query: notifications in the given
     * status whose next attempt is due.
     */
    List<Notification> findByStatusAndNextRetryAtBefore(NotificationStatus status, OffsetDateTime now);

    /**
     * Retry worker's polling query across the retryable states (PENDING
     * plus manually-requeued FAILED), oldest due first, capped by the
     * pageable. Served by idx_notifications_status_next_retry_at.
     */
    List<Notification> findByStatusInAndNextRetryAtBefore(
            Collection<NotificationStatus> statuses, OffsetDateTime now, Pageable pageable);

    /** Idempotency pre-check for the event listeners (V9 dedupe key). */
    boolean existsByDedupeKey(String dedupeKey);

    Optional<Notification> findByDedupeKey(String dedupeKey);

    List<Notification> findByStatus(NotificationStatus status);

    List<Notification> findByRecipientId(UUID recipientId);
}
