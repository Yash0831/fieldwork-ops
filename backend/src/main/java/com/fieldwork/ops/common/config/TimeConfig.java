package com.fieldwork.ops.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link Clock} used by all business logic.
 *
 * <p>Services never call {@code LocalDateTime.now()} / {@code OffsetDateTime.now()}
 * directly — they take the injected clock, so Phase 9 tests can substitute
 * {@code Clock.fixed(...)} and drive time deterministically (SLA deadlines,
 * ON_HOLD pause accounting, breach detection).
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
