package com.fieldwork.ops.common.logging;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Copies the submitting thread's MDC onto {@code @Async} worker threads,
 * so an async event listener logs under the originating request's
 * correlation ID instead of losing it at the thread-pool boundary.
 *
 * <p>The context is cleared when the task finishes so pooled threads
 * never leak one request's ID into the next task. Listeners still call
 * {@link CorrelationIds#ensurePresent(Runnable)} as a backstop for
 * tasks submitted without a decorated context.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> submittingContext = MDC.getCopyOfContextMap();
        return () -> {
            if (submittingContext != null) {
                MDC.setContextMap(submittingContext);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
