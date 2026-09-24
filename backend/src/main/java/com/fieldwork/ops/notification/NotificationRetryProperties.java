package com.fieldwork.ops.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retry-worker tuning, bound from {@code notification.retry} in
 * application.yml. Registered via {@code AsyncConfig}.
 */
@ConfigurationProperties(prefix = "notification.retry")
public record NotificationRetryProperties(
        /**
         * How often the retry worker polls for due notifications, in
         * milliseconds.
         */
        @DefaultValue("60000") long fixedDelay,
        /**
         * Delivery attempts stamped onto each new notification before
         * the worker dead-letters it.
         */
        @DefaultValue("5") int maxAttempts,
        /** Maximum due notifications processed in a single worker run. */
        @DefaultValue("100") int batchSize) {}
