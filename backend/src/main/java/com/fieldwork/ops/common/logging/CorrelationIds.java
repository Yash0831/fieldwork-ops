package com.fieldwork.ops.common.logging;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * Carries the correlation ID on the SLF4J MDC outside HTTP requests —
 * for {@code @Scheduled} jobs and {@code @Async} event listeners, where
 * {@link CorrelationIdFilter} never runs.
 *
 * <p>Async listeners inherit the publishing request's ID via
 * {@link MdcTaskDecorator}; when no ID is present (scheduled jobs, or a
 * pool thread without a decorated task) a fresh one is generated per
 * job run / per event so the logs stay traceable.
 */
public final class CorrelationIds {

    private CorrelationIds() {}

    /** MDC key shared with {@link CorrelationIdFilter}. */
    public static final String MDC_KEY = CorrelationIdFilter.CORRELATION_ID_MDC_KEY;

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Runs {@code action} with the given correlation ID on the MDC,
     * restoring whatever was there before (or clearing it) afterwards.
     */
    public static void withId(String correlationId, Runnable action) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, correlationId);
        try {
            action.run();
        } finally {
            if (previous != null) {
                MDC.put(MDC_KEY, previous);
            } else {
                MDC.remove(MDC_KEY);
            }
        }
    }

    /** Runs {@code action} with a fresh correlation ID on the MDC. */
    public static void withNewId(Runnable action) {
        withId(generate(), action);
    }

    /**
     * Runs {@code action} keeping an already-present ID (e.g. propagated
     * from the publishing request thread); generates one when absent.
     * Use at the top of every {@code @Async} listener and
     * {@code @Scheduled} job body.
     */
    public static void ensurePresent(Runnable action) {
        if (MDC.get(MDC_KEY) != null) {
            action.run();
        } else {
            withNewId(action);
        }
    }
}
