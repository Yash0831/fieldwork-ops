package com.fieldwork.ops.notification;

/**
 * Delivery failed; the retry worker backs off and tries again until
 * the notification's max attempts are exhausted.
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
