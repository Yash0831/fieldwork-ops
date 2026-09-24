package com.fieldwork.ops.workorder.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Attachment metadata as returned by the attachment endpoints and
 * embedded in {@link WorkOrderResponse}. The file bytes live in S3;
 * clients fetch them via {@code GET /api/v1/attachments/{id}/download}.
 */
public record AttachmentResponse(
        UUID id,
        String fileName,
        String contentType,
        long sizeBytes,
        Uploader uploadedBy,
        OffsetDateTime uploadedAt) {

    public record Uploader(UUID id, String username, String fullName) {}
}
