package com.fieldwork.ops.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/work-orders/{id}/comments}.
 *
 * <p>{@code authorId} is explicit until Phase 5 wires authentication
 * and the controller can resolve the principal itself.
 */
public record CommentRequest(
        @NotNull UUID authorId,
        @NotBlank @Size(max = 10000) String body,
        boolean internal) {}
