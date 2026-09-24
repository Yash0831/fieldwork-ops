package com.fieldwork.ops.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fieldwork.ops.auth.Role;
import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.auth.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * Security-relevant unit tests for JWT access tokens: issuance,
 * validation, expiry enforcement, and fail-fast secret handling.
 *
 * <p>No Spring, no database — the service takes its secret and clock
 * as constructor arguments, so every security property is testable
 * in isolation.
 */
class JwtTokenServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-23T12:00:00Z");
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final String SECRET = "test-secret-that-is-long-enough-32b!!";

    private static JwtTokenService service(Clock clock, String secret, Duration ttl) {
        return new JwtTokenService(new JwtProperties(secret, ttl, Duration.ofDays(7)), clock);
    }

    private static JwtTokenService service(Clock clock) {
        return service(clock, SECRET, Duration.ofMinutes(15));
    }

    /**
     * Hand-crafts a token with explicit timestamps. Needed for expiry
     * tests: jjwt validates {@code exp} against the <em>system</em>
     * clock, so the injected clock cannot drive expiry — the token
     * itself must carry the timestamps under test.
     */
    private static String craftToken(
            String secret, UUID userId, String email, RoleName role, Instant issuedAt, Instant expiresAt) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /** {@link User}/{@link Role} constructors are protected; subclass to instantiate them here. */
    private static User user(UUID id, RoleName roleName) {
        Role role = new Role() {};
        role.setName(roleName);
        User u = new User() {};
        u.setId(id);
        u.setUsername("tech1");
        u.setEmail("tech1@example.com");
        u.setRole(role);
        return u;
    }

    private static Claims parseClaims(String token, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    // ------------------------------------------------------------------
    // Issuance
    // ------------------------------------------------------------------

    @Test
    void issuedTokenRoundTripsToThePrincipal() {
        UUID userId = UUID.randomUUID();
        JwtTokenService tokens = service(Clock.systemUTC());

        String token = tokens.createAccessToken(user(userId, RoleName.TECHNICIAN));
        CurrentUser principal = tokens.parseAccessToken(token);

        assertThat(principal.id()).isEqualTo(userId);
        assertThat(principal.email()).isEqualTo("tech1@example.com");
        assertThat(principal.role()).isEqualTo(RoleName.TECHNICIAN);
    }

    @Test
    void issuedTokenCarriesTheConfiguredTtl() {
        // Issuance uses the service clock for iat/exp; the TTL gap is what matters.
        JwtTokenService tokens = service(Clock.systemUTC(), SECRET, Duration.ofMinutes(30));

        String token = tokens.createAccessToken(user(UUID.randomUUID(), RoleName.DISPATCHER));
        Claims claims = parseClaims(token, SECRET);

        long ttlSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(ttlSeconds).isEqualTo(30 * 60L);
    }

    @Test
    void roleChangeTakesEffectOnNextIssuance() {
        JwtTokenService tokens = service(Clock.systemUTC());
        UUID userId = UUID.randomUUID();

        String before = tokens.createAccessToken(user(userId, RoleName.TECHNICIAN));
        String after = tokens.createAccessToken(user(userId, RoleName.DISPATCHER));

        assertThat(tokens.parseAccessToken(before).role()).isEqualTo(RoleName.TECHNICIAN);
        assertThat(tokens.parseAccessToken(after).role()).isEqualTo(RoleName.DISPATCHER);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void expiredTokenIsRejected() {
        JwtTokenService tokens = service(Clock.systemUTC());
        Instant now = Instant.now();
        String expired = craftToken(
                SECRET,
                UUID.randomUUID(),
                "admin@example.com",
                RoleName.ADMIN,
                now.minusSeconds(3600),
                now.minusSeconds(60));

        assertThatThrownBy(() -> tokens.parseAccessToken(expired))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void tokenJustBeforeExpiryIsAccepted() {
        JwtTokenService tokens = service(Clock.systemUTC());
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        String valid = craftToken(
                SECRET, userId, "admin@example.com", RoleName.ADMIN, now.minusSeconds(60), now.plusSeconds(60));

        CurrentUser principal = tokens.parseAccessToken(valid);

        assertThat(principal.id()).isEqualTo(userId);
        assertThat(principal.role()).isEqualTo(RoleName.ADMIN);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtTokenService tokens = service(Clock.systemUTC());
        String token = tokens.createAccessToken(user(UUID.randomUUID(), RoleName.ADMIN));
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> tokens.parseAccessToken(tampered))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void tokenSignedWithADifferentKeyIsRejected() {
        JwtTokenService tokens = service(Clock.systemUTC());
        Instant now = Instant.now();
        String foreign = craftToken(
                "a-completely-different-32-byte-key!!",
                UUID.randomUUID(),
                "admin@example.com",
                RoleName.ADMIN,
                now,
                now.plusSeconds(900));

        assertThatThrownBy(() -> tokens.parseAccessToken(foreign))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        JwtTokenService tokens = service(Clock.systemUTC());

        assertThatThrownBy(() -> tokens.parseAccessToken("not-a-jwt"))
                .isInstanceOf(InvalidAccessTokenException.class);
        assertThatThrownBy(() -> tokens.parseAccessToken(""))
                .isInstanceOf(InvalidAccessTokenException.class);
    }

    // ------------------------------------------------------------------
    // Secret handling
    // ------------------------------------------------------------------

    @Test
    void shortSecretFailsFastAtConstruction() {
        assertThatThrownBy(() -> service(Clock.fixed(T0, UTC), "too-short", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void nullSecretFailsFastAtConstruction() {
        assertThatThrownBy(() -> service(Clock.fixed(T0, UTC), null, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void exactly32ByteSecretIsAccepted() {
        String exactly32 = "12345678901234567890123456789012";
        assertThat(exactly32.getBytes(StandardCharsets.UTF_8)).hasSize(32);

        JwtTokenService tokens = service(Clock.systemUTC(), exactly32, Duration.ofMinutes(15));
        String token = tokens.createAccessToken(user(UUID.randomUUID(), RoleName.REQUESTER));

        assertThat(tokens.parseAccessToken(token).role()).isEqualTo(RoleName.REQUESTER);
    }
}
