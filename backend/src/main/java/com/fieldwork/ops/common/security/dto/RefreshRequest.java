package com.fieldwork.ops.common.security.dto;

import jakarta.validation.constraints.NotBlank;

/** Payload for {@code POST /api/v1/auth/refresh} and {@code POST /api/v1/auth/logout}. */
public record RefreshRequest(@NotBlank String refreshToken) {}
