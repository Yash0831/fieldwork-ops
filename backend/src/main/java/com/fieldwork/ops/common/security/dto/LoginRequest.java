package com.fieldwork.ops.common.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Payload for {@code POST /api/v1/auth/login}. */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {}
