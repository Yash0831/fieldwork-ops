package com.fieldwork.ops.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * The retry worker's polling query: notifications in the given
     * status whose next attempt is due.
     */
    List<Notification> findByStatusAndNextRetryAtBefore(NotificationStatus status, OffsetDateTime now);

    List<Notification> findByStatus(NotificationStatus status);

    List<Notification> findByRecipientId(UUID recipientId);
}
