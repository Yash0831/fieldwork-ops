package com.fieldwork.ops.common.security;

import org.springframework.security.core.AuthenticationException;

/**
 * Thrown by {@link JwtTokenService} when an access token fails
 * signature or expiry validation. The {@link JwtAuthenticationFilter}
 * converts it into a 401 via the authentication entry point.
 */
public class InvalidAccessTokenException extends AuthenticationException {

    public InvalidAccessTokenException(Throwable cause) {
        super("Invalid or expired access token", cause);
    }
}
