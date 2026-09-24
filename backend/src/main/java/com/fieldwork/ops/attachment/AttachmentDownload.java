package com.fieldwork.ops.attachment;

import java.util.UUID;

/**
 * An attachment fetched from S3 for download: the stored metadata plus
 * the raw bytes. Returned fully in memory — attachments are capped at
 * {@code app.s3.max-file-size} (10 MB by default), so holding the bytes
 * is bounded and avoids leaking the S3 response stream into the web
 * layer.
 */
public record AttachmentDownload(
        UUID id, String fileName, String contentType, byte[] content) {}
