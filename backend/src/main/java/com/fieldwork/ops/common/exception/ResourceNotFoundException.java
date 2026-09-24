package com.fieldwork.ops.common.exception;

import lombok.Getter;

/**
 * A referenced aggregate (work order, user, team, …) does not exist.
 * Maps to 404 in the Phase 4 error envelope.
 */
@Getter
public class ResourceNotFoundException extends DomainException {

    private final String resourceType;
    private final String resourceId;

    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(
                "not_found",
                "%s not found: %s".formatted(resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = String.valueOf(resourceId);
    }
}
