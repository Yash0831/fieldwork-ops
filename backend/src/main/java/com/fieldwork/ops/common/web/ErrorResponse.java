package com.fieldwork.ops.common.web;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Standard error envelope for the REST API.
 *
 * <p>{@code error} is the HTTP reason phrase; {@code fieldErrors} carries
 * per-field validation failures (empty — never null — otherwise).
 */
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {}
}
