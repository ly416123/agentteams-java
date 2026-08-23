package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.service.ModelCatalogDependencyException;
import io.agentteams.controlplane.service.UnavailableDependencyException;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.quota.QuotaExceededException;
import io.agentteams.domain.task.IllegalTaskTransitionException;
import io.agentteams.domain.task.StaleTaskVersionException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public final class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> validation(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "request validation failed");
    }

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ApiError> quotaExceeded(QuotaExceededException ignored) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", "project quota exceeded");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException ignored) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "resource not found");
    }

    @ExceptionHandler({IdempotencyConflictException.class, DuplicateKeyException.class})
    ResponseEntity<ApiError> conflict(Exception ignored) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "request conflicts with current resource state");
    }

    @ExceptionHandler(ModelCatalogDependencyException.class)
    ResponseEntity<ApiError> modelCatalogDependency(ModelCatalogDependencyException error) {
        return error(HttpStatus.CONFLICT, error.code(), "model catalog dependency prevents this operation");
    }

    @ExceptionHandler({OptimisticLockFailure.class, StaleTaskVersionException.class})
    ResponseEntity<ApiError> optimisticConflict(Exception ignored) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "resource version does not match");
    }

    @ExceptionHandler(IllegalTaskTransitionException.class)
    ResponseEntity<ApiError> illegalTransition(IllegalTaskTransitionException ignored) {
        return error(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION", "requested task transition is not allowed");
    }

    @ExceptionHandler({UnavailableDependencyException.class, DataAccessException.class})
    ResponseEntity<ApiError> unavailable(Exception ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE_DEPENDENCY",
                "required dependency is unavailable");
    }

    @ExceptionHandler(AuthorizationException.class)
    ResponseEntity<ApiError> forbidden(AuthorizationException ignored) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "permission denied");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception error) {
        LOG.error("Unhandled API request failure type={} message={}", error.getClass().getName(), error.getMessage(), error);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "request could not be completed");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }

    public record ApiError(String code, String message) {
    }
}
