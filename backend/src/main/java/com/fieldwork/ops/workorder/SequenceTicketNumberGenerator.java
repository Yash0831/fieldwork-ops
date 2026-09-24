package com.fieldwork.ops.workorder;

import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link TicketNumberGenerator} backed by the {@code ticket_number_seq}
 * database sequence (created in V3). The sequence is the single source
 * of uniqueness, so concurrent instances never collide; the year comes
 * from the injected {@link Clock} so Phase 9 tests can freeze it.
 *
 * <p>Format: {@code WO-YYYY-NNNNNN} with the sequence zero-padded to six
 * digits (values beyond 999999 simply widen the number, never wrap).
 */
@Component
@RequiredArgsConstructor
public class SequenceTicketNumberGenerator implements TicketNumberGenerator {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    public String generate() {
        Long next = jdbcTemplate.queryForObject("SELECT nextval('ticket_number_seq')", Long.class);
        if (next == null) {
            throw new IllegalStateException("ticket_number_seq returned null");
        }
        int year = OffsetDateTime.now(clock).getYear();
        return "WO-%d-%06d".formatted(year, next);
    }
}
