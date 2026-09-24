package com.fieldwork.ops.notification;

/**
 * Lifecycle of a notification row. The Phase 6 retry worker moves rows
 * PENDING/FAILED &rarr; SENT, and parks exhausted rows in DEAD_LETTER
 * for human review. Mirrors chk_notifications_status.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    DEAD_LETTER
}
