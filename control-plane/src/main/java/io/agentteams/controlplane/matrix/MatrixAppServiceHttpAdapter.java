package io.agentteams.controlplane.matrix;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Matrix Application Service transaction endpoint.
 *
 * <p>The adapter owns transport validation and Inbox/idempotency boundaries. It
 * does not know task state and delegates typed commands to the supplied handler.
 */
@RestController
@RequestMapping("/_matrix/app/v1/transactions")
public final class MatrixAppServiceHttpAdapter {
    private static final String MESSAGE_EVENT_TYPE = "m.room.message";
    private static final int MAX_TEXT_LENGTH = 255;
    private static final int MAX_BODY_LENGTH = 16_384;
    private static final int MAX_HANDLER_RESPONSE_LENGTH = 4_096;

    private final MatrixAppService appService;
    private final MatrixCommandHandler commandHandler;
    private final MatrixIdentityBinder identityBinder;

    public MatrixAppServiceHttpAdapter(MatrixAppService appService, MatrixCommandHandler commandHandler) {
        this(appService, commandHandler, null);
    }

    public MatrixAppServiceHttpAdapter(MatrixAppService appService, MatrixCommandHandler commandHandler,
            MatrixIdentityBinder identityBinder) {
        this.appService = Objects.requireNonNull(appService, "appService");
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.identityBinder = identityBinder;
    }

    @Autowired
    public MatrixAppServiceHttpAdapter(MatrixAppService appService,
            ObjectProvider<MatrixCommandHandler> commandHandlers,
            ObjectProvider<MatrixIdentityBinder> identityBinders) {
        this(appService, commandHandlers.getIfAvailable(() -> (sender, command) -> {
            throw new MatrixCommandHandlingException("no Matrix command handler is configured");
        }), identityBinders.getIfAvailable());
    }

    @PutMapping(path = "/{transactionId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpResponse> receive(@PathVariable String transactionId,
            @RequestBody TransactionRequest request) {
        try {
            validateTransaction(transactionId, request);
            for (EventRequest event : request.events()) {
                validateEvent(event);
            }
            if (!appService.beginTransaction(transactionId)) {
                return ok(HttpResponse.duplicate(transactionId));
            }

            List<EventResult> results = new ArrayList<>();
            for (EventRequest event : request.events()) {
                if (!MESSAGE_EVENT_TYPE.equals(event.type())) {
                    results.add(EventResult.ignored(event.eventId()));
                    continue;
                }

                String body = messageBody(event.content());
                MatrixAppService.Result result;
                try {
                    result = appService.receive(transactionId, event.eventId(), event.roomId(), event.sender(), body,
                            (sender, command) -> handleCommand(sender, command));
                } catch (MatrixIdentityBindingException error) {
                    // A permanent authorization rejection must not leave the transaction retryable forever.
                    appService.completeTransaction(transactionId);
                    throw error;
                }
                if (!result.accepted()) {
                    results.add(EventResult.duplicate(event.eventId()));
                } else if (new MatrixCommandParser().isCommand(body)) {
                    results.add(EventResult.handled(event.eventId(), safeResponse(result.response())));
                } else {
                    results.add(EventResult.ignored(event.eventId()));
                }
            }
            appService.completeTransaction(transactionId);
            return ok(HttpResponse.accepted(transactionId, results));
        } catch (MatrixRequestException | MatrixCommandException error) {
            return error(HttpStatus.BAD_REQUEST, transactionId, "INVALID_REQUEST",
                    "invalid Matrix AppService transaction");
        } catch (MatrixCommandHandlingException error) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, transactionId, "DEPENDENCY_UNAVAILABLE",
                    "Matrix command handler is unavailable");
        } catch (MatrixIdentityBindingException error) {
            return error(HttpStatus.FORBIDDEN, transactionId, "IDENTITY_NOT_BOUND",
                    "Matrix sender is not bound to a platform identity");
        } catch (MatrixIdentityServiceException error) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, transactionId, "DEPENDENCY_UNAVAILABLE",
                    "Matrix identity service is unavailable");
        } catch (DataAccessException error) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, transactionId, "DEPENDENCY_UNAVAILABLE",
                    "Matrix inbox is unavailable");
        } catch (RuntimeException error) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, transactionId, "HANDLER_ERROR",
                    "command could not be processed");
        }
    }

    private String handleCommand(String sender, MatrixCommand command) {
        if (identityBinder == null) return commandHandler.handle(sender, command);
        MatrixIdentity identity = identityBinder.bind(sender)
                .orElseThrow(() -> new MatrixIdentityBindingException("Matrix sender is not bound"));
        return commandHandler.handle(identity, command);
    }

    private static void validateTransaction(String transactionId, TransactionRequest request) {
        requireText(transactionId, "transactionId", MAX_TEXT_LENGTH);
        if (request == null || request.events() == null) {
            throw new MatrixRequestException();
        }
    }

    private static void validateEvent(EventRequest event) {
        if (event == null) {
            throw new MatrixRequestException();
        }
        requireText(event.eventId(), "eventId", MAX_TEXT_LENGTH);
        requireText(event.type(), "eventType", MAX_TEXT_LENGTH);
        requireText(event.roomId(), "roomId", MAX_TEXT_LENGTH);
        requireText(event.sender(), "sender", MAX_TEXT_LENGTH);
        if (MESSAGE_EVENT_TYPE.equals(event.type())) {
            messageBody(event.content());
        }
    }

    private static String messageBody(JsonNode content) {
        if (content == null || !content.isObject()) {
            throw new MatrixRequestException();
        }
        JsonNode body = content.get("body");
        if (body == null || !body.isTextual()) {
            throw new MatrixRequestException();
        }
        String value = body.textValue();
        requireText(value, "body", MAX_BODY_LENGTH);
        return value;
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new MatrixRequestException();
        }
    }

    private static String safeResponse(String response) {
        if (response == null || response.length() <= MAX_HANDLER_RESPONSE_LENGTH) {
            return response;
        }
        return response.substring(0, MAX_HANDLER_RESPONSE_LENGTH);
    }

    private static ResponseEntity<HttpResponse> ok(HttpResponse response) {
        return ResponseEntity.ok(response);
    }

    private static ResponseEntity<HttpResponse> error(HttpStatus status, String transactionId,
            String code, String message) {
        return ResponseEntity.status(status).body(HttpResponse.error(safeTransactionId(transactionId), code, message));
    }

    private static String safeTransactionId(String transactionId) {
        return transactionId != null && !transactionId.isBlank() && transactionId.length() <= MAX_TEXT_LENGTH
                ? transactionId : null;
    }

    public record TransactionRequest(List<EventRequest> events) {
        public TransactionRequest {
            events = events == null ? null : List.copyOf(events);
        }
    }

    public record EventRequest(
            @JsonProperty("event_id") String eventId,
            String type,
            @JsonProperty("room_id") String roomId,
            String sender,
            JsonNode content) {
    }

    public record HttpResponse(String transactionId, boolean accepted, boolean duplicate,
            String code, String message, List<EventResult> events) {
        public HttpResponse {
            events = events == null ? List.of() : List.copyOf(events);
        }

        static HttpResponse accepted(String transactionId, List<EventResult> events) {
            return new HttpResponse(transactionId, true, false, null, null, events);
        }

        static HttpResponse duplicate(String transactionId) {
            return new HttpResponse(transactionId, true, true, null, null, List.of());
        }

        static HttpResponse error(String transactionId, String code, String message) {
            return new HttpResponse(transactionId, false, false, code, message, List.of());
        }
    }

    public record EventResult(String eventId, EventStatus status, String response) {
        static EventResult handled(String eventId, String response) {
            return new EventResult(eventId, EventStatus.HANDLED, response);
        }

        static EventResult ignored(String eventId) {
            return new EventResult(eventId, EventStatus.IGNORED, null);
        }

        static EventResult duplicate(String eventId) {
            return new EventResult(eventId, EventStatus.DUPLICATE, null);
        }
    }

    public enum EventStatus { HANDLED, IGNORED, DUPLICATE }

    private static final class MatrixRequestException extends RuntimeException {
    }
}
