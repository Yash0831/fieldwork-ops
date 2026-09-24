package com.fieldwork.ops.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/work-orders/{id}/comments}.
 *
 * <p>The author is always the authenticated principal — the request
 * carries no author id, so a caller cannot post as someone else.
 */
public record CommentRequest(
        @NotBlank @Size(max = 10000) String body,
        boolean internal) {}
