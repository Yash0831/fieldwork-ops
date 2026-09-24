package com.fieldwork.ops.auth.dto;

import com.fieldwork.ops.auth.RoleName;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Admin read model for a user. Never exposes the password hash. */
public record UserResponse(
        UUID id,
        String username,
        String email,
        String fullName,
        RoleName role,
        UUID teamId,
        boolean active,
        OffsetDateTime lastLoginAt) {}
