package com.fieldwork.ops.common.exception;

import lombok.Getter;

/**
 * The idempotency contract was violated: either the key is already tied
 * to an in-flight request (retry too early — back off and retry), or the
 * key was reused with a <em>different</em> payload (a client bug, worth
 * a 422 in Phase 4). Never thrown when a completed key simply replays
 * the original ticket — that path returns the ticket, no exception.
 */
@Getter
public class IdempotencyConflictException extends DomainException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey, String detail) {
        super(
                "idempotency_conflict",
                "Idempotency key '%s': %s".formatted(idempotencyKey, detail));
        this.idempotencyKey = idempotencyKey;
    }
}
