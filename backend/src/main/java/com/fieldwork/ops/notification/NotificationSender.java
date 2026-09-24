package com.fieldwork.ops.notification;

/**
 * The delivery seam for notifications. The retry worker calls
 * {@link #send(Notification)}; how a channel is actually delivered is
 * an implementation detail behind this interface.
 *
 * <p>Phase 6 ships {@link LoggingNotificationSender} (logs the send).
 * A real SMTP sender arrives later and plugs in here without touching
 * the worker, the listener, or the retry bookkeeping.
 */
public interface NotificationSender {

    /**
     * Attempts delivery of one notification.
     *
     * @throws NotificationDeliveryException (or any runtime exception)
     *         when delivery fails — the worker backs off and retries
     */
    void send(Notification notification);
}
