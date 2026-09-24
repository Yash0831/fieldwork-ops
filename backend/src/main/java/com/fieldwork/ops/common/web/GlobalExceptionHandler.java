package com.fieldwork.ops.common.web;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fieldwork.ops.common.exception.IdempotencyConflictException;
import com.fieldwork.ops.common.exception.IllegalStateTransitionException;
import com.fieldwork.ops.common.exception.ResourceNotFoundException;
import com.fieldwork.ops.common.exception.UserNotAssignableException;
import com.fieldwork.ops.common.exception.WorkloadLimitExceededException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps domain, validation, and infrastructure failures onto the
 * standard {@link ErrorResponse} envelope.
 *
 * <p>Status mapping (by exception, never by message-sniffing):
 * <ul>
 *   <li>{@code not_found} → 404</li>
 *   <li>{@code illegal_state_transition}, {@code workload_limit_exceeded} → 422</li>
 *   <li>{@code idempotency_conflict}, optimistic-lock races, integrity conflicts → 409</li>
 *   <li>bean validation / malformed input → 400 with {@code fieldErrors}</li>
 *   <li>anything unexpected → 500 (logged with stack trace; the client
 *       gets a generic message, never internals)</li>
 * </ul>
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final Clock clock;

    // ------------------------------------------------------------------
    // Domain exceptions
    // ------------------------------------------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransition(
            IllegalStateTransitionException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(WorkloadLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleWorkloadLimit(
            WorkloadLimitExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(UserNotAssignableException.class)
    public ResponseEntity<ErrorResponse> handleUserNotAssignable(
            UserNotAssignableException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            IdempotencyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    // ------------------------------------------------------------------
    // Authentication / authorization (Phase 5)
    // ------------------------------------------------------------------

    /**
     * Bad credentials, invalid/expired refresh tokens, deactivated
     * accounts. The message never reveals which credential failed.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(
            AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request, List.of());
    }

    /**
     * Authenticated but forbidden — a failed {@code @PreAuthorize} role
     * gate or a service-layer ownership check.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request, List.of());
    }

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            RuntimeException ex, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                "The ticket was modified by another request; please reload it and retry.",
                request,
                List.of());
    }

    // ------------------------------------------------------------------
    // Validation and malformed input → 400 with fieldErrors
    // ------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), messageOf(fe)))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(
                        v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        ErrorResponse.FieldError fieldError = new ErrorResponse.FieldError(
                ex.getName(),
                "Invalid value '%s'".formatted(ex.getValue()));
        return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, List.of(fieldError));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        ErrorResponse.FieldError fieldError = new ErrorResponse.FieldError(
                ex.getParameterName(), "Required parameter is missing");
        return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, List.of(fieldError));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Invalid enum literals and the like deserve a pinpointed error.
        if (ex.getCause() instanceof InvalidFormatException ife) {
            String field = ife.getPath().isEmpty()
                    ? ""
                    : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            ErrorResponse.FieldError fieldError = new ErrorResponse.FieldError(
                    field, "Invalid value '%s'".formatted(ife.getValue()));
            return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, List.of(fieldError));
        }
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", request, List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, List.of());
    }

    // ------------------------------------------------------------------
    // Integrity conflicts (e.g. a lost active-policy uniqueness race)
    // ------------------------------------------------------------------

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                "The request conflicts with an existing record.",
                request,
                List.of());
    }

    // ------------------------------------------------------------------
    // Fallback — never leak internals
    // ------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error(
                "Unexpected error handling {} {}",
                request.getMethod(),
                request.getRequestURI(),
                ex);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.",
                request,
                List.of());
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<ErrorResponse.FieldError> fieldErrors) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(
                        OffsetDateTime.now(clock),
                        status.value(),
                        status.getReasonPhrase(),
                        message,
                        request.getRequestURI(),
                        fieldErrors));
    }

    private static String messageOf(org.springframework.validation.FieldError fieldError) {
        return fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "invalid value";
    }
}
