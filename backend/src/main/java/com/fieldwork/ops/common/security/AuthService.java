package com.fieldwork.ops.common.security;

import com.fieldwork.ops.auth.RefreshToken;
import com.fieldwork.ops.auth.RefreshTokenRepository;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.security.dto.TokenResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credential authentication and refresh-token lifecycle.
 *
 * <p>Token strategy:
 * <ul>
 *   <li><b>Access tokens</b> are stateless JWTs (15 min) carrying user
 *       id, email, and role — validated by signature alone, no DB hit.</li>
 *   <li><b>Refresh tokens</b> are opaque 256-bit random strings (7
 *       days). Only their SHA-256 hash is persisted, so a database leak
 *       does not yield usable tokens.</li>
 *   <li><b>Rotation:</b> every {@code /auth/refresh} revokes the
 *       presented token and issues a fresh pair. Replaying a revoked
 *       token is treated as possible theft: the user's whole token
 *       family is revoked and the request fails with 401.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    /** Raw refresh tokens carry 256 bits of entropy, Base64URL-encoded. */
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokens;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Verifies credentials and issues the initial token pair.
     *
     * @throws InvalidCredentialsException when the email is unknown or
     *         the password does not match (same message either way)
     * @throws AccountDeactivatedException when the account is deactivated
     */
    @Transactional
    public TokenResponse login(String email, String rawPassword) {
        User user = users.findByEmail(email).orElseThrow(InvalidCredentialsException::new);
        if (!user.isActive()) {
            throw new AccountDeactivatedException();
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        user.setLastLoginAt(OffsetDateTime.now(clock));
        log.info("User {} logged in", user.getEmail());
        return issueTokenPair(user);
    }

    /**
     * Rotates a refresh token: the presented token is revoked and a new
     * pair is issued. Unknown, expired, or already-revoked tokens fail
     * with {@link InvalidRefreshTokenException}; a replayed revoked
     * token additionally revokes the whole family.
     */
    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokens
                .findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!stored.isUsableAt(now)) {
            if (stored.isRevoked()) {
                // A revoked token should never be presented again — treat
                // it as possible token theft and kill the whole family.
                log.warn(
                        "Revoked refresh token replayed for user {}; revoking token family",
                        stored.getUser().getId());
                revokeAllForUser(stored.getUser().getId());
            }
            throw new InvalidRefreshTokenException();
        }
        User user = stored.getUser();
        if (!user.isActive()) {
            revokeAllForUser(user.getId());
            throw new AccountDeactivatedException();
        }
        stored.setRevoked(true);
        return issueTokenPair(user);
    }

    /**
     * Revokes a single refresh token. Idempotent: unknown or already
     * revoked tokens are silently ignored so logout never fails.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(hash(rawRefreshToken)).ifPresent(token -> token.setRevoked(true));
    }

    /** Revokes every live refresh token of a user (theft response, deactivation). */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokens.revokeAllByUserId(userId);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private TokenResponse issueTokenPair(User user) {
        String accessToken = jwtTokens.createAccessToken(user);

        byte[] random = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(random);
        String rawRefreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        RefreshToken stored = RefreshToken.issue(
                user, hash(rawRefreshToken), OffsetDateTime.now(clock).plus(jwtProperties.refreshTokenTtl()));
        refreshTokens.save(stored);

        return new TokenResponse(
                accessToken, rawRefreshToken, jwtProperties.accessTokenTtl().toSeconds());
    }

    /** SHA-256 hex of the raw token — the only form ever persisted. */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest is not available", e);
        }
    }
}
