package com.fieldwork.ops.common.security;

import com.fieldwork.ops.auth.RoleName;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Static helpers for resolving the authenticated principal outside of
 * controller method arguments.
 *
 * <p>The principal is always a {@link CurrentUser} — set by
 * {@link JwtAuthenticationFilter} — never a raw username string, so
 * callers get the user id and role without another database lookup.
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /** The authenticated principal, or empty when the request is anonymous. */
    public static Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /**
     * The authenticated principal, or {@link AccessDeniedException} when
     * the request is anonymous. Use at the top of any endpoint or service
     * path that must have an actor.
     */
    public static CurrentUser requireCurrentUser() {
        return currentUser()
                .orElseThrow(() -> new AccessDeniedException("Authentication required"));
    }

    /** Shorthand for {@code requireCurrentUser().id()}. */
    public static UUID currentUserId() {
        return requireCurrentUser().id();
    }

    /** True when the authenticated principal carries the given role. */
    public static boolean hasRole(RoleName role) {
        return currentUser().map(user -> user.role() == role).orElse(false);
    }
}
