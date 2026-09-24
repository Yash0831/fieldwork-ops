package com.fieldwork.ops.common.config;

import com.fieldwork.ops.common.logging.MdcTaskDecorator;
import com.fieldwork.ops.notification.NotificationRetryProperties;
import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables the Phase 6 background machinery: {@code @Async} event
 * listeners and {@code @Scheduled} jobs (SLA breach scan, notification
 * retry worker).
 *
 * <p>The async executor decorates every task with
 * {@link MdcTaskDecorator} so the correlation ID propagates from the
 * publishing request thread to the listener thread. {@code @Scheduled}
 * methods run on Spring's default single-threaded scheduler with
 * {@code fixedDelay}, so a job never overlaps itself.
 *
 * <p>Everything here is in-JVM: no message broker, no raw TCP. See the
 * README's known-limitations section before running more than one app
 * instance.
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(NotificationRetryProperties.class)
public class AsyncConfig {

    /**
     * Default executor for {@code @Async} methods. A modest pool: event
     * listeners are short-lived and I/O-light (one insert each).
     */
    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
