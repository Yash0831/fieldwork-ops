package com.fieldwork.ops.common.security;

import com.fieldwork.ops.auth.RoleName;
import java.util.UUID;

/**
 * The authenticated principal. Carries exactly what the access-token
 * claims carry — user id, email, and role — and is stored as the
 * {@code Authentication#getPrincipal()} by
 * {@link JwtAuthenticationFilter}.
 *
 * <p>Controllers resolve it via {@link SecurityUtils#requireCurrentUser()}
 * and pass it into the service layer, which enforces ownership checks
 * (a technician may only touch assigned tickets, a requester only their
 * own). Audit columns ({@code createdBy}/{@code updatedBy}) use the
 * email as the human-readable actor reference.
 */
public record CurrentUser(UUID id, String email, RoleName role) {

    public CurrentUser {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
    }
}
