package io.agentteams.manager.session;

import io.agentteams.manager.ManagerToolConflictException;
import io.agentteams.manager.ManagerSessionService;
import io.agentteams.manager.ManagerToolRegistry;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
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
import java.util.Base64;

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
        ManagerRequestContext.current().ifPresent(principal -> {
            if (!principal.tenantId().equals(command.tenantId())
                    || !principal.projectId().equals(command.projectId())
                    || !principal.teamId().equals(command.teamId())
                    || !principal.subject().equals(command.actor())) {
                throw new io.agentteams.manager.security.ManagerAuthorizationException(
                        "manager session is outside the caller scope");
            }
        });
        Instant now = clock.instant();
        ManagerSessionRecord requested = ManagerSessionRecord.newSession(UUID.randomUUID(), command.tenantId(),
                command.projectId(), command.teamId(), command.actor(), now);
        ManagerSessionRecord stored = repository.insertSession(requested, idempotencyKey);
        if (!stored.id().equals(requested.id()) && (!stored.tenantId().equals(command.tenantId())
                || !stored.projectId().equals(command.projectId()) || !stored.teamId().equals(command.teamId())
                || !stored.actor().equals(command.actor()))) {
            throw new ManagerToolConflictException("session idempotency key was reused with a different scope");
        }
        if (stored.id().equals(requested.id())) {
            repository.appendEvent(stored.id(), "SESSION_CREATED", "{\"status\":\"ACTIVE\"}", idempotencyKey, now);
        }
        return stored;
    }

    public ManagerSessionRecord getSession(UUID sessionId) {
        ManagerSessionRecord session = repository.findSession(Objects.requireNonNull(sessionId, "sessionId"))
                .orElseThrow(() -> new ManagerSessionNotFoundException(sessionId));
        ManagerRequestContext.current().ifPresent(principal -> requireScope(principal, session));
        return session;
    }

    public SessionPage listSessions(Integer pageSize, String cursor) {
        ManagerPrincipal principal = ManagerRequestContext.current().orElseThrow(() ->
                new io.agentteams.manager.security.ManagerAuthorizationException("authentication required"));
        return listSessions(pageSize, cursor, principal.projectId(), principal.teamId(), principal.subject());
    }

    public SessionPage listSessions(Integer pageSize, String cursor, String projectId, String teamId, String actorId) {
        int size = pageSize == null ? 50 : pageSize;
        if (size < 1 || size > 200) throw new IllegalArgumentException("pageSize must be between 1 and 200");
        ManagerPrincipal principal = ManagerRequestContext.current().orElseThrow(() ->
                new io.agentteams.manager.security.ManagerAuthorizationException("authentication required"));
        requireRequestedScope(principal, projectId, teamId, actorId);
        SessionCursor position = decodeCursor(cursor);
        List<ManagerSessionRecord> rows = repository.findSessions(principal.tenantId(), projectId, teamId, actorId,
                position == null ? null : position.updatedAt(),
                position == null ? null : position.id(), size + 1);
        boolean hasMore = rows.size() > size;
        List<ManagerSessionRecord> items = rows.subList(0, Math.min(size, rows.size()));
        String next = hasMore && !items.isEmpty()
                ? encodeCursor(items.get(items.size() - 1).updatedAt(), items.get(items.size() - 1).id()) : null;
        return new SessionPage(items, next, hasMore, clock.instant());
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
        ManagerRequestContext.current().ifPresent(principal -> {
            if (approved && !principal.permissions().contains("manager:approve")) {
                throw new io.agentteams.manager.security.ManagerAuthorizationException(
                        "approval permission is required");
            }
            if (teamId != null && !teamId.equals(principal.teamId())) {
                throw new io.agentteams.manager.security.ManagerAuthorizationException(
                        "team scope does not match authenticated principal");
            }
        });
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

        Set<String> verifiedPermissions = ManagerRequestContext.current()
                .map(ManagerPrincipal::permissions).orElse(permissions);
        ManagerToolRegistry.ToolContext context = new ManagerToolRegistry.ToolContext(verifiedPermissions, approved,
                session.tenantId(), session.projectId(), null, taskId, teamId, "create_task", null, null,
                session.id().toString());
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

    public record CreateSessionCommand(String tenantId, String projectId, String teamId, String actor) {
        public CreateSessionCommand {
            requireText(tenantId, "tenantId");
            requireText(projectId, "projectId");
            requireText(teamId, "teamId");
            requireText(actor, "actor");
        }

        public CreateSessionCommand(String tenantId, String projectId, String actor) {
            this(tenantId, projectId, "legacy", actor);
        }
    }

    public record MessageResult(ManagerSessionRecord session, ManagerMessageRecord message,
            ManagerToolCallRecord toolCall) { }

    public record SessionPage(List<ManagerSessionRecord> items, String nextCursor, boolean hasMore,
            Instant serverTime) {
        public SessionPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            Objects.requireNonNull(serverTime, "serverTime");
        }
    }

    private record SessionCursor(Instant updatedAt, UUID id) { }

    private static String encodeCursor(Instant updatedAt, UUID id) {
        String raw = updatedAt.toString() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static SessionCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 512) throw new IllegalArgumentException();
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf(':');
            if (separator <= 0 || separator == raw.length() - 1) throw new IllegalArgumentException();
            return new SessionCursor(Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("cursor is invalid", error);
        }
    }

    private static void requireKey(String value) { requireText(value, "idempotencyKey"); }

    private static void requireScope(ManagerPrincipal principal, ManagerSessionRecord session) {
        if (!principal.tenantId().equals(session.tenantId())
                || !principal.projectId().equals(session.projectId())
                || !principal.teamId().equals(session.teamId())
                || !principal.subject().equals(session.actor())) {
            throw new io.agentteams.manager.security.ManagerAuthorizationException(
                    "manager session is outside the caller scope");
        }
    }

    private static void requireRequestedScope(ManagerPrincipal principal, String projectId, String teamId,
            String actorId) {
        if (projectId == null || teamId == null || actorId == null
                || !principal.projectId().equals(projectId)
                || !principal.teamId().equals(teamId)
                || !principal.subject().equals(actorId)) {
            throw new io.agentteams.manager.security.ManagerAuthorizationException(
                    "manager session list is outside the caller scope");
        }
    }
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
