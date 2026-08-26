package io.agentteams.manager.session;

import io.agentteams.manager.ManagerToolConflictException;
import io.agentteams.manager.ManagerSessionService;
import io.agentteams.manager.ManagerToolRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Coordinates durable session facts with the existing model/tool pipeline. */
public class ManagerSessionServiceFacade {
    private final ManagerSessionRepository repository;
    private final ManagerSessionService modelService;
    private final Clock clock;

    public ManagerSessionServiceFacade(ManagerSessionRepository repository, ManagerSessionService modelService,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.modelService = modelService;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ManagerSessionRecord createSession(CreateSessionCommand command, String idempotencyKey) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        ManagerSessionRecord requested = ManagerSessionRecord.newSession(UUID.randomUUID(), command.tenantId(),
                command.projectId(), command.actor(), now);
        ManagerSessionRecord stored = repository.insertSession(requested, idempotencyKey);
        if (!stored.id().equals(requested.id()) && (!stored.tenantId().equals(command.tenantId())
                || !stored.projectId().equals(command.projectId()) || !stored.actor().equals(command.actor()))) {
            throw new ManagerToolConflictException("session idempotency key was reused with a different scope");
        }
        if (stored.id().equals(requested.id())) {
            repository.appendEvent(stored.id(), "SESSION_CREATED", "{\"status\":\"ACTIVE\"}", idempotencyKey, now);
        }
        return stored;
    }

    public ManagerSessionRecord getSession(UUID sessionId) {
        return repository.findSession(Objects.requireNonNull(sessionId, "sessionId"))
                .orElseThrow(() -> new ManagerSessionNotFoundException(sessionId));
    }

    public MessageResult appendMessage(UUID sessionId, long expectedVersion, String idempotencyKey,
            String actor, String content, Set<String> permissions, boolean approved, String taskId, String teamId) {
        requireKey(idempotencyKey);
        requireText(actor, "actor");
        requireText(content, "content");
        Objects.requireNonNull(permissions, "permissions");
        ManagerSessionRecord session = getSession(sessionId);
        Optional<ManagerMessageRecord> existing = repository.findMessage(sessionId, idempotencyKey);
        if (existing.isPresent()) {
            ManagerMessageRecord message = existing.get();
            if (!message.contentHash().equals(sha256(content)) || !message.actor().equals(actor)) {
                throw new ManagerToolConflictException("message idempotency key was reused with different input");
            }
            ManagerToolCallRecord toolCall = repository.findToolCall(sessionId, idempotencyKey).orElse(null);
            return new MessageResult(getSession(sessionId), message, toolCall);
        }
        if (session.status() == ManagerSessionRecord.Status.CANCELLED) throw new SessionCancelledException();
        ManagerMessageRecord requested = ManagerMessageRecord.processing(UUID.randomUUID(), sessionId,
                idempotencyKey, actor, sha256(content), clock.instant());
        ManagerSessionRepository.MessageReservation reservation = repository.reserveMessage(sessionId,
                expectedVersion, requested);
        ManagerMessageRecord message = reservation.message();
        if (!message.contentHash().equals(sha256(content)) || !message.actor().equals(actor)) {
            throw new ManagerToolConflictException("message idempotency key was reused with different input");
        }
        if (!reservation.acquired()) {
            ManagerToolCallRecord priorToolCall = repository.findToolCall(sessionId, idempotencyKey).orElse(null);
            return new MessageResult(reservation.session(), message, priorToolCall);
        }
        if (modelService == null) throw new IllegalStateException("manager model service is not configured");

        ManagerToolRegistry.ToolContext context = new ManagerToolRegistry.ToolContext(permissions, approved,
                session.tenantId(), session.projectId(), null, taskId, teamId, "create_task", null, null);
        Object result;
        try {
            result = modelService.handleCreateTask(content, context);
        } catch (RuntimeException error) {
            repository.failMessage(sessionId, idempotencyKey, safeFailureSummary(error));
            throw error;
        }
        String resultSummary = bounded(String.valueOf(result), 512);
        Instant now = clock.instant();
        message = repository.completeMessage(sessionId, idempotencyKey, resultSummary);
        ManagerToolCallRecord toolCall = ManagerToolCallRecord.completed(UUID.randomUUID(), sessionId,
                idempotencyKey, "create_task", sha256(content), resultSummary, now);
        repository.insertToolCall(toolCall);
        repository.appendEvent(sessionId, "MESSAGE_COMPLETED",
                "{\"messageId\":\"" + message.id() + "\",\"tool\":\"create_task\"}", idempotencyKey, now);
        return new MessageResult(reservation.session(), message, toolCall);
    }

    public ManagerSessionRecord cancel(UUID sessionId, long expectedVersion, String idempotencyKey, String actor) {
        requireKey(idempotencyKey);
        requireText(actor, "actor");
        ManagerSessionRecord session = getSession(sessionId);
        Optional<ManagerEventRecord> existing = repository.findEvent(sessionId, idempotencyKey);
        if (existing.isPresent()) return session;
        if (session.status() == ManagerSessionRecord.Status.CANCELLED) throw new SessionCancelledException();
        if (session.version() != expectedVersion) {
            throw new SessionVersionConflictException(sessionId, expectedVersion, session.version());
        }
        Instant now = clock.instant();
        ManagerSessionRecord cancelled = repository.updateSession(sessionId, expectedVersion,
                ManagerSessionRecord.Status.CANCELLED, now);
        repository.appendEvent(sessionId, "SESSION_CANCELLED", "{\"status\":\"CANCELLED\"}", idempotencyKey, now);
        return cancelled;
    }

    public List<ManagerEventRecord> events(UUID sessionId, long afterCursor) {
        if (afterCursor < 0) throw new IllegalArgumentException("after cursor must not be negative");
        getSession(sessionId);
        return repository.findEventsAfter(sessionId, afterCursor);
    }

    public record CreateSessionCommand(String tenantId, String projectId, String actor) {
        public CreateSessionCommand {
            requireText(tenantId, "tenantId");
            requireText(projectId, "projectId");
            requireText(actor, "actor");
        }
    }

    public record MessageResult(ManagerSessionRecord session, ManagerMessageRecord message,
            ManagerToolCallRecord toolCall) { }

    private static void requireKey(String value) { requireText(value, "idempotencyKey"); }
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException(field + " must be non-blank and at most 255 characters");
        }
    }

    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeFailureSummary(RuntimeException error) {
        String category = error.getClass().getSimpleName();
        return bounded("message failed: " + category, 512);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
