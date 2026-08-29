package io.agentteams.manager.api;

import io.agentteams.manager.session.ManagerEventRecord;
import io.agentteams.manager.session.ManagerSessionRecord;
import io.agentteams.manager.session.ManagerSessionServiceFacade;
import io.agentteams.manager.security.ManagerAuthorizationException;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manager/sessions")
public final class ManagerSessionController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ManagerSessionServiceFacade facade;

    public ManagerSessionController(ManagerSessionServiceFacade facade) {
        this.facade = facade;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> create(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CreateSessionRequest request) {
        requireKey(idempotencyKey);
        if (request == null) throw new IllegalArgumentException("request body is required");
        ManagerPrincipal principal = ManagerRequestContext.require();
        String tenantId = request.resolvedTenantId() == null ? principal.tenantId() : request.resolvedTenantId();
        String projectId = request.resolvedProjectId() == null ? principal.projectId() : request.resolvedProjectId();
        requireTrusted(request.actor(), principal.subject(), "actor");
        requireTrusted(tenantId, principal.tenantId(), "tenantId");
        requireTrusted(projectId, principal.projectId(), "projectId");
        ManagerSessionRecord session = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand(principal.tenantId(), principal.projectId(),
                        principal.teamId(), principal.subject()), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.from(session));
    }

    @GetMapping
    public ManagerSessionPageResponse list(@RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String teamId, @RequestParam(required = false) String actorId) {
        ManagerPrincipal principal = ManagerRequestContext.require();
        requireTrusted(projectId, principal.projectId(), "projectId");
        requireTrusted(teamId, principal.teamId(), "teamId");
        requireTrusted(actorId, principal.subject(), "actorId");
        ManagerSessionServiceFacade.SessionPage page = facade.listSessions(pageSize, cursor, principal.projectId(),
                principal.teamId(), principal.subject());
        return ManagerSessionPageResponse.from(page);
    }

    @PostMapping("/{sessionId}/messages")
    public MessageResponse message(@PathVariable UUID sessionId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody MessageRequest request) {
        requireKey(idempotencyKey);
        if (request == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        ManagerPrincipal principal = ManagerRequestContext.require();
        requireTrusted(request.actor(), principal.subject(), "actor");
        if (request.teamId() != null && !request.teamId().equals(principal.teamId())) {
            throw new ManagerAuthorizationException("team scope does not match authenticated principal");
        }
        Set<String> requestedPermissions = request.permissions() == null ? Set.of() : request.permissions();
        if (!principal.permissions().containsAll(requestedPermissions)) {
            throw new ManagerAuthorizationException("requested permissions exceed authenticated principal");
        }
        if (request.approved() && !principal.permissions().contains("manager:approve")) {
            throw new ManagerAuthorizationException("approval permission is required");
        }
        ManagerSessionServiceFacade.MessageResult result = facade.appendMessage(sessionId,
                request.expectedVersion(), idempotencyKey, principal.subject(), request.content(),
                principal.permissions(), request.approved(),
                request.taskId(), request.teamId());
        return MessageResponse.from(result);
    }

    @GetMapping("/{sessionId}")
    public SessionResponse get(@PathVariable UUID sessionId) {
        return SessionResponse.from(facade.getSession(sessionId));
    }

    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> events(@PathVariable UUID sessionId,
            @RequestParam(name = "after", defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (after < 0) throw new IllegalArgumentException("after cursor must be non-negative");
        long cursor = Math.max(after, parseCursor(lastEventId));
        List<ManagerEventRecord> events = facade.events(sessionId, cursor);
        StringBuilder stream = new StringBuilder();
        for (ManagerEventRecord event : events) {
            stream.append("id: ").append(event.cursor()).append('\n')
                    .append("event: ").append(event.type()).append('\n')
                    .append("data: ").append(io.agentteams.manager.session.ManagerEventRedactor.redact(event.payload()))
                    .append("\n\n");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream.toString());
    }

    @PostMapping("/{sessionId}/cancel")
    public SessionResponse cancel(@PathVariable UUID sessionId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CancelRequest request) {
        requireKey(idempotencyKey);
        if (request == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        ManagerPrincipal principal = ManagerRequestContext.require();
        requireTrusted(request.actor(), principal.subject(), "actor");
        return SessionResponse.from(facade.cancel(sessionId, request.expectedVersion(), idempotencyKey,
                principal.subject()));
    }

    public record CreateSessionRequest(String tenantId, String projectId, String actor, Scope scope) {
        String resolvedTenantId() { return tenantId != null ? tenantId : scope == null ? null : scope.tenantId(); }
        String resolvedProjectId() { return projectId != null ? projectId : scope == null ? null : scope.projectId(); }
    }

    public record Scope(String tenantId, String projectId) { }

    public record MessageRequest(String content, Long expectedVersion, String actor, Set<String> permissions,
            boolean approved, String taskId, String teamId) { }

    public record CancelRequest(Long expectedVersion, String actor) { }

    public record SessionResponse(UUID id, String tenantId, String projectId, String teamId, String actor,
            String status, long version) {
        static SessionResponse from(ManagerSessionRecord session) {
            return new SessionResponse(session.id(), session.tenantId(), session.projectId(), session.teamId(),
                    session.actor(),
                    session.status().name(), session.version());
        }
    }

    public record ManagerSessionPageResponse(List<SessionListResponse> items, String nextCursor, boolean hasMore,
            java.time.Instant serverTime) {
        static ManagerSessionPageResponse from(ManagerSessionServiceFacade.SessionPage page) {
            return new ManagerSessionPageResponse(page.items().stream().map(SessionListResponse::from).toList(),
                    page.nextCursor(), page.hasMore(), page.serverTime());
        }
    }

    public record SessionListResponse(UUID id, String tenantId, String projectId, String teamId, String actorId,
            String status, long version, java.time.Instant createdAt, java.time.Instant updatedAt) {
        static SessionListResponse from(ManagerSessionRecord session) {
            return new SessionListResponse(session.id(), session.tenantId(), session.projectId(), session.teamId(),
                    session.actor(),
                    session.status().name(), session.version(), session.createdAt(), session.updatedAt());
        }
    }

    public record MessageResponse(SessionResponse session, UUID messageId, String resultSummary,
            UUID toolCallId, String toolName, String toolStatus) {
        static MessageResponse from(ManagerSessionServiceFacade.MessageResult result) {
            return new MessageResponse(SessionResponse.from(result.session()), result.message().id(),
                    result.message().resultSummary(), result.toolCall() == null ? null : result.toolCall().id(),
                    result.toolCall() == null ? null : result.toolCall().toolName(),
                    result.toolCall() == null ? null : result.toolCall().status());
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 255 characters");
        }
    }

    private static void requireTrusted(String requested, String trusted, String field) {
        if (requested != null && !requested.equals(trusted)) {
            throw new ManagerAuthorizationException(field + " does not match authenticated principal");
        }
    }

    private static long parseCursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            long cursor = Long.parseLong(value);
            if (cursor < 0) throw new NumberFormatException();
            return cursor;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative cursor");
        }
    }
}
