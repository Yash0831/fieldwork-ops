package com.fieldwork.ops.common.security;

import org.springframework.security.authentication.DisabledException;

/**
 * Thrown when the credentials are right but the account has been
 * deactivated (e.g. by an admin). Maps to 401 via the
 * {@code AuthenticationException} handler so deactivated users simply
 * cannot obtain tokens.
 */
public class AccountDeactivatedException extends DisabledException {

    public AccountDeactivatedException() {
        super("Account is deactivated");
    }
}
