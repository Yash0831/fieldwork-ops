package com.fieldwork.ops.workorder.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A single entry of a ticket's comment thread. */
public record CommentResponse(
        UUID id,
        String body,
        boolean internal,
        WorkOrderResponse.UserSummary author,
        OffsetDateTime createdAt) {}
