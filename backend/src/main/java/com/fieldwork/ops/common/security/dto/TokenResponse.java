package com.fieldwork.ops.common.security.dto;

/**
 * Token pair returned by {@code /auth/login} and {@code /auth/refresh}.
 * {@code expiresIn} is the access-token lifetime in seconds.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String tokenType) {

    public TokenResponse(String accessToken, String refreshToken, long expiresIn) {
        this(accessToken, refreshToken, expiresIn, "Bearer");
    }
}
