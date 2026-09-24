package com.fieldwork.ops.common.security;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * Thrown when the email is unknown or the password does not match.
 * Carries a single generic message on purpose: callers must never
 * learn whether the email exists.
 */
public class InvalidCredentialsException extends BadCredentialsException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
