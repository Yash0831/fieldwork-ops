package com.fieldwork.ops.common.exception;

/**
 * An attachment upload was rejected before touching object storage:
 * the file is empty, its content type is not on the allowlist, or it
 * exceeds the configured size limit. Maps to HTTP 400 — the request,
 * not the system, is at fault.
 */
public class InvalidAttachmentException extends DomainException {

    public InvalidAttachmentException(String message) {
        super("invalid_attachment", message);
    }
}
