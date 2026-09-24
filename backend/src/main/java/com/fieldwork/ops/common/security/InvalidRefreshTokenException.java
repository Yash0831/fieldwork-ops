package com.fieldwork.ops.common.security;

import org.springframework.security.core.AuthenticationException;

/**
 * Thrown when a refresh token is unknown, expired, revoked — or a
 * revoked token is replayed (possible theft; the caller then revokes
 * the whole token family). Maps to 401.
 */
public class InvalidRefreshTokenException extends AuthenticationException {

    public InvalidRefreshTokenException() {
        super("Invalid or expired refresh token");
    }
}
