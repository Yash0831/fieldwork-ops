package com.fieldwork.ops.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for the {@code WO-YYYY-NNNNNN} ticket-number format.
 */
@ExtendWith(MockitoExtension.class)
class SequenceTicketNumberGeneratorTest {

    private static final String NEXTVAL_SQL = "SELECT nextval('ticket_number_seq')";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SequenceTicketNumberGenerator generator(Clock clock) {
        return new SequenceTicketNumberGenerator(jdbcTemplate, clock);
    }

    @Test
    void generateFormatsSequenceAsWoYearPaddedNumber() {
        when(jdbcTemplate.queryForObject(NEXTVAL_SQL, Long.class)).thenReturn(42L);
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC);

        assertThat(generator(clock).generate()).isEqualTo("WO-2026-000042");
    }

    @Test
    void generatePadsToSixDigits() {
        when(jdbcTemplate.queryForObject(NEXTVAL_SQL, Long.class)).thenReturn(7L);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        assertThat(generator(clock).generate()).isEqualTo("WO-2026-000007");
    }

    @Test
    void generateTakesTheYearFromTheInjectedClock() {
        when(jdbcTemplate.queryForObject(NEXTVAL_SQL, Long.class)).thenReturn(1L);
        Clock clock = Clock.fixed(Instant.parse("2027-12-31T23:59:59Z"), ZoneOffset.UTC);

        assertThat(generator(clock).generate()).isEqualTo("WO-2027-000001");
    }

    @Test
    void sequenceValuesBeyondSixDigitsWidenTheNumber() {
        when(jdbcTemplate.queryForObject(NEXTVAL_SQL, Long.class)).thenReturn(1_234_567L);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        // Never wraps: uniqueness is preserved past 999999.
        assertThat(generator(clock).generate()).isEqualTo("WO-2026-1234567");
    }

    @Test
    void nullSequenceValueFailsFast() {
        when(jdbcTemplate.queryForObject(NEXTVAL_SQL, Long.class)).thenReturn(null);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> generator(clock).generate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ticket_number_seq returned null");
    }

    @Test
    void generatedNumbersMatchTheDocumentedFormat() {
        when(jdbcTemplate.queryForObject(NEXTVAL_SQL, Long.class)).thenReturn(999_999L);
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);

        assertThat(generator(clock).generate()).matches("WO-\\d{4}-\\d{6}");
    }
}
