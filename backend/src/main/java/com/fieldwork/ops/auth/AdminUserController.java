package com.fieldwork.ops.auth;

import com.fieldwork.ops.auth.dto.UserResponse;
import com.fieldwork.ops.common.security.CurrentUser;
import com.fieldwork.ops.common.security.SecurityUtils;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal admin user management. Exists so the platform can enforce
 * the "deactivated technician" edge case end to end: deactivating a
 * technician revokes their sessions and blocks future dispatch to them
 * (see {@code DispatchService}).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserAdminService userAdminService;

    /** Lists every user. The password hash is never exposed. */
    @GetMapping
    public List<UserResponse> listUsers() {
        return userAdminService.listUsers();
    }

    /**
     * Deactivates an account: login and token refresh stop working and
     * all refresh tokens are revoked immediately.
     */
    @PatchMapping("/{id}/deactivate")
    public UserResponse deactivate(@PathVariable UUID id) {
        CurrentUser actor = SecurityUtils.requireCurrentUser();
        return userAdminService.deactivate(id, actor);
    }

    /** Reactivates a previously deactivated account. */
    @PatchMapping("/{id}/reactivate")
    public UserResponse reactivate(@PathVariable UUID id) {
        CurrentUser actor = SecurityUtils.requireCurrentUser();
        return userAdminService.reactivate(id, actor);
    }
}
