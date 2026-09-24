package com.fieldwork.ops.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings, bound from {@code app.jwt} in application.yml.
 *
 * <p>The signing secret comes from the {@code JWT_SECRET} environment
 * variable; application.yml carries a dev-only default that is long
 * enough for HMAC-SHA-256 and triggers a loud startup warning (see
 * {@link JwtTokenService}). Real deployments must set {@code JWT_SECRET}
 * — see {@code .env.example}.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {

    public JwtProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(7);
        }
    }
}
