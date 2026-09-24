package com.fieldwork.ops.notification;

import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.workorder.WorkOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Outbox-style notification record (Phase 6). Domain events create a
 * PENDING row; the scheduled retry worker picks up rows whose
 * {@code nextRetryAt} has passed, attempts delivery with exponential
 * backoff, and after {@code maxAttempts} moves the row to
 * DEAD_LETTER. {@code recipientId} is nullable so notifications can
 * also target plain email addresses (e.g. an external requester).
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id")
    private User recipient;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel = NotificationChannel.EMAIL;

    /** Domain event that triggered this notification, e.g. WORK_ORDER_CREATED. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /**
     * Idempotency key for the Phase 6 event listeners:
     * {@code <eventType>:<workOrderId>:<recipientId>}. Unique (V9), so a
     * redelivered domain event can never create a second notification
     * row. Nullable so rows not born from a domain event aren't subject
     * to dedupe.
     */
    @Column(name = "dedupe_key")
    private String dedupeKey;

    @Column(name = "subject")
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_work_order_id")
    private WorkOrder relatedWorkOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;
}
