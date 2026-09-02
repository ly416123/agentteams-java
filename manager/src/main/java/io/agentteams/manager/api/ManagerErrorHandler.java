package io.agentteams.manager.api;

import io.agentteams.manager.InvalidModelOutputException;
import io.agentteams.manager.conversation.ConversationRuntimeException;
import io.agentteams.manager.conversation.ConversationVersionConflictException;
import io.agentteams.manager.ManagerToolConflictException;
import io.agentteams.manager.ManagerToolTemporaryFailureException;
import io.agentteams.manager.ModelCallAdmissionRejectedException;
import io.agentteams.manager.ModelCallAdmissionTemporaryFailureException;
import io.agentteams.manager.ModelProviderException;
import io.agentteams.manager.QuotaRejectedException;
import io.agentteams.manager.session.ManagerSessionNotFoundException;
import io.agentteams.manager.session.SessionCancelledException;
import io.agentteams.manager.session.SessionVersionConflictException;
import io.agentteams.manager.security.ManagerAuthenticationException;
import io.agentteams.manager.security.ManagerAuthorizationException;
import io.agentteams.manager.security.ManagerScopeUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public final class ManagerErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ManagerErrorHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> input(Exception error, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "TOOL_INPUT_INVALID", "request validation failed", request,
                Map.of());
    }

    @ExceptionHandler(ManagerAuthenticationException.class)
    ResponseEntity<ErrorResponse> authentication(ManagerAuthenticationException error, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "authentication required", request, Map.of());
    }

    @ExceptionHandler({ManagerAuthorizationException.class, SecurityException.class})
    ResponseEntity<ErrorResponse> authorization(SecurityException error, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "AUTHORIZATION_REJECTED", "permission denied", request, Map.of());
    }

    @ExceptionHandler(ManagerScopeUnavailableException.class)
    ResponseEntity<ErrorResponse> scopeUnavailable(ManagerScopeUnavailableException error,
            HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SCOPE_AUTHORIZATION_UNAVAILABLE",
                "conversation scope authorization is unavailable", request, Map.of());
    }

    @ExceptionHandler(InvalidModelOutputException.class)
    ResponseEntity<ErrorResponse> modelOutput(InvalidModelOutputException error, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_OUTPUT_INVALID",
                "model output failed structured validation", request, Map.of());
    }

    @ExceptionHandler({ModelProviderException.class})
    ResponseEntity<ErrorResponse> modelUnavailable(ModelProviderException error, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE", "model provider is unavailable",
                request, Map.of());
    }

    @ExceptionHandler({ModelCallAdmissionRejectedException.class, QuotaRejectedException.class})
    ResponseEntity<ErrorResponse> quota(Exception error, HttpServletRequest request) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_REJECTED", "project quota rejected the call", request,
                Map.of());
    }

    @ExceptionHandler(ModelCallAdmissionTemporaryFailureException.class)
    ResponseEntity<ErrorResponse> quotaTemporary(ModelCallAdmissionTemporaryFailureException error,
            HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "QUOTA_UNAVAILABLE", "quota service is unavailable", request,
                Map.of());
    }

    @ExceptionHandler(ManagerToolConflictException.class)
    ResponseEntity<ErrorResponse> toolConflict(ManagerToolConflictException error, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "TOOL_CONFLICT", "tool operation conflicted", request, Map.of());
    }

    @ExceptionHandler(ManagerToolTemporaryFailureException.class)
    ResponseEntity<ErrorResponse> toolTemporaryFailure(ManagerToolTemporaryFailureException error,
            HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TOOL_TEMPORARY_FAILURE",
                "tool dependency is unavailable", request, Map.of());
    }

    @ExceptionHandler(SessionVersionConflictException.class)
    ResponseEntity<ErrorResponse> version(SessionVersionConflictException error, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "SESSION_VERSION_CONFLICT", "session version does not match", request,
                Map.of("expectedVersion", error.expectedVersion(), "actualVersion", error.actualVersion()));
    }

    @ExceptionHandler(SessionCancelledException.class)
    ResponseEntity<ErrorResponse> cancelled(SessionCancelledException error, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "SESSION_CANCELLED", "session is cancelled", request, Map.of());
    }

    @ExceptionHandler(ConversationVersionConflictException.class)
    ResponseEntity<ErrorResponse> conversationVersion(ConversationVersionConflictException error,
            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONVERSATION_VERSION_CONFLICT",
                "conversation version does not match", request,
                Map.of("expectedVersion", error.expectedVersion(), "actualVersion", error.actualVersion()));
    }

    @ExceptionHandler(ManagerSessionNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(ManagerSessionNotFoundException error, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "session not found", request, Map.of());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> storage(DataAccessException error, HttpServletRequest request) {
        LOG.warn("Manager storage failure type={} sqlState={}", error.getClass().getSimpleName(), sqlState(error));
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TOOL_TEMPORARY_FAILURE",
                "session storage is unavailable", request, Map.of());
    }

    private static String sqlState(DataAccessException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sqlError) return sqlError.getSQLState();
            cause = cause.getCause();
        }
        return "unknown";
    }

    @ExceptionHandler(ConversationRuntimeException.class)
    ResponseEntity<ErrorResponse> conversation(ConversationRuntimeException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT, INVALID_STATE, CANCELLED, RECOVERY_REQUIRED -> HttpStatus.CONFLICT;
            case WORKER_UNAVAILABLE, MODEL_PROVIDER_UNAVAILABLE, TIMEOUT, CONNECTION_CLOSED ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        if (error.code() == ConversationRuntimeException.Code.WORKER_UNAVAILABLE) {
            return error(status, "MODEL_UNAVAILABLE", "model provider is unavailable", request, Map.of());
        }
        if (error.code() == ConversationRuntimeException.Code.SESSION_NOT_FOUND) {
            return error(status, "NOT_FOUND", "session not found", request, Map.of());
        }
        if (error.code() == ConversationRuntimeException.Code.RECOVERY_REQUIRED) {
            return error(status, "CONVERSATION_RECOVERY_REQUIRED",
                    "conversation message requires operator recovery before retry", request, Map.of());
        }
        return error(status, "CONVERSATION_" + error.code().name(), "conversation request failed", request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internal(Exception error, HttpServletRequest request) {
        LOG.error("Manager request failed type={} correlationId={}", error.getClass().getName(), correlationId(request));
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "request could not be completed", request,
                Map.of());
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
            HttpServletRequest request, Map<String, Object> details) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(status).header("X-Correlation-Id", correlationId)
                .body(new ErrorResponse(code, message, correlationId, details));
    }

    private static String correlationId(HttpServletRequest request) {
        String value = request == null ? null : request.getHeader("X-Correlation-Id");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    public record ErrorResponse(String code, String message, String correlationId, Map<String, Object> details) { }
}
