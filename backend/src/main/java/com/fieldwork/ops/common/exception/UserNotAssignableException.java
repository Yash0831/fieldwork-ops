package com.fieldwork.ops.common.exception;

/**
 * A user cannot take the requested role in a workflow — e.g. dispatching
 * a ticket to a deactivated account or to a user who is not a
 * technician. Maps to 422: the request is understood but the business
 * rules reject it.
 */
public class UserNotAssignableException extends DomainException {

    public UserNotAssignableException(String message) {
        super("user_not_assignable", message);
    }
}
