package com.fieldwork.ops.common.security;

import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.auth.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Creates and validates short-lived JWT access tokens.
 *
 * <p>Token shape: HMAC-SHA-256 signed, {@code sub} = user id,
 * claims {@code email} and {@code role} (the {@link RoleName}). The
 * 15-minute expiry keeps the blast radius of a leaked token small;
 * longer sessions ride on the rotating opaque refresh tokens managed
 * by {@link AuthService}, which are validated against the database.
 *
 * <p>The signing secret must be at least 32 bytes (256 bits). A
 * dev-only default from application.yml keeps local boot working and
 * logs a warning; anything shorter fails fast at startup.
 */
@Service
@Slf4j
public class JwtTokenService {

    /** Dev-only fallback secret (see application.yml); never use in a real environment. */
    static final String DEV_DEFAULT_SECRET = "dev-only-insecure-default-jwt-secret-min-32-bytes";

    private static final String EMAIL_CLAIM = "email";
    private static final String ROLE_CLAIM = "role";

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtTokenService(JwtProperties properties, Clock clock) {
        String secret = properties.secret();
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes (256 bits) for HMAC-SHA-256; "
                            + "set the JWT_SECRET environment variable (see .env.example)");
        }
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            log.warn(
                    "Using the dev-only default JWT signing secret — set JWT_SECRET "
                            + "before deploying anywhere real");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.accessTokenTtl = properties.accessTokenTtl();
        this.clock = clock;
    }

    /**
     * Issues an access token for {@code user}. The role is read from the
     * live user row (not cached), so a role change takes effect on the
     * next login/refresh — within 15 minutes at most.
     */
    public String createAccessToken(User user) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(EMAIL_CLAIM, user.getEmail())
                .claim(ROLE_CLAIM, user.getRole().getName().name())
                .issuedAt(now)
                .expiration(Date.from(now.toInstant().plus(accessTokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates the signature and expiry of an access token and returns
     * the principal it carries.
     *
     * @throws InvalidAccessTokenException when the token is malformed,
     *         expired, or signed with a different key
     */
    public CurrentUser parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new CurrentUser(
                    UUID.fromString(claims.getSubject()),
                    claims.get(EMAIL_CLAIM, String.class),
                    RoleName.valueOf(claims.get(ROLE_CLAIM, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidAccessTokenException(e);
        }
    }
}
