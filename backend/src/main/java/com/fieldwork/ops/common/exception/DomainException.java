package com.fieldwork.ops.common.exception;

import lombok.Getter;

/**
 * Base type for all domain (business-rule) failures.
 *
 * <p>Domain exceptions signal that the request was understood but the
 * business rules reject it — illegal state transitions, unknown
 * resources, workload caps, idempotency conflicts. They carry a stable
 * machine-readable {@code code} so the Phase 4 global exception handler
 * can map them to the standard error envelope (and, by convention, to
 * 4xx statuses) without string-matching messages.
 */
@Getter
public class DomainException extends RuntimeException {

    /** Stable machine-readable code, e.g. {@code "illegal_state_transition"}. */
    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
