package com.fieldwork.ops.auth;

import com.fieldwork.ops.auth.dto.UserResponse;
import com.fieldwork.ops.common.exception.ResourceNotFoundException;
import com.fieldwork.ops.common.security.AuthService;
import com.fieldwork.ops.common.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin user management: listing users and deactivating/reactivating
 * accounts. Deactivation immediately revokes every refresh token of the
 * account (so a deactivated technician's sessions die with the account)
 * while leaving their short-lived access tokens to expire naturally
 * within 15 minutes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAdminService {

    private final UserRepository users;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return users.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * Deactivates an account and revokes its refresh tokens. An admin
     * cannot deactivate their own account.
     */
    @Transactional
    public UserResponse deactivate(UUID userId, CurrentUser actor) {
        User user = loadUser(userId);
        if (user.getId().equals(actor.id())) {
            throw new AccessDeniedException("You cannot deactivate your own account");
        }
        user.setActive(false);
        user.setUpdatedBy(actor.email());
        authService.revokeAllForUser(userId);
        log.info("User {} deactivated by {}", user.getEmail(), actor.email());
        return toResponse(user);
    }

    @Transactional
    public UserResponse reactivate(UUID userId, CurrentUser actor) {
        User user = loadUser(userId);
        user.setActive(true);
        user.setUpdatedBy(actor.email());
        log.info("User {} reactivated by {}", user.getEmail(), actor.email());
        return toResponse(user);
    }

    private User loadUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().getName(),
                user.getTeam() == null ? null : user.getTeam().getId(),
                user.isActive(),
                user.getLastLoginAt());
    }
}
