package io.agentteams.manager.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agentteams.manager.conversation.ConversationEvent;
import io.agentteams.manager.conversation.ConversationRuntimeException;
import io.agentteams.manager.conversation.ConversationRuntimePort;
import io.agentteams.manager.conversation.ConversationOwner;
import io.agentteams.manager.conversation.ConversationService;
import io.agentteams.manager.security.ManagerAuthorizationException;
import io.agentteams.manager.security.ConversationScopeAuthorizer;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import io.agentteams.manager.security.ManagerProjectScopeResolver;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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

/** HTTP boundary for the lightweight runtime conversation used by the Console. */
@RestController
@RequestMapping("/api/v1/conversations")
public final class ConversationController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final long EVENT_WAIT_TIMEOUT_MILLIS = 30_000L;
    private static final long EVENT_POLL_INTERVAL_MILLIS = 25L;
    private final ConversationService service;
    private final ObjectMapper mapper;
    private final ConversationScopeAuthorizer scopeAuthorizer;
    private final ManagerProjectScopeResolver projectScopes;

    public ConversationController(ConversationService service) {
        this(service, new ObjectMapper(), ConversationScopeAuthorizer.legacy(), null);
    }

    public ConversationController(ConversationService service, ObjectMapper mapper,
            ConversationScopeAuthorizer scopeAuthorizer) {
        this(service, mapper, scopeAuthorizer, null);
    }

    @Autowired
    public ConversationController(ConversationService service, ObjectMapper mapper,
            ConversationScopeAuthorizer scopeAuthorizer, ManagerProjectScopeResolver projectScopes) {
        this.service = service;
        this.mapper = mapper;
        this.scopeAuthorizer = scopeAuthorizer;
        this.projectScopes = projectScopes;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> create(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CreateRequest request) {
        requireKey(idempotencyKey);
        ManagerPrincipal principal = ManagerRequestContext.require();
        if (request == null || request.sessionId() == null) {
            throw new IllegalArgumentException("sessionId is required");
        }
        String project = canonicalProject(principal, request.projectValue());
        requireScope(project, request.teamValue(), principal);
        ConversationRuntimePort.Context context = new ConversationRuntimePort.Context(
                project, request.teamValue(), request.workerValue(), request.taskValue(), request.sessionId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SessionResponse.from(service.createAndStart(context, idempotencyKey,
                        new ConversationOwner(principal.tenantId(), principal.subject()))));
    }

    @GetMapping
    public ConversationPageResponse list(@RequestParam(required = false) String projectId,
            @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String cursor) {
        ManagerPrincipal principal = ManagerRequestContext.require();
        String requestedProject = canonicalProject(principal, projectId);
        scopeAuthorizer.requireProjectAccessible(requestedProject, principal);
        ConversationService.ConversationPage page = service.list(requestedProject, pageSize, cursor, owner(principal));
        return ConversationPageResponse.from(page);
    }

    @GetMapping("/{sessionId}")
    public SessionResponse get(@PathVariable UUID sessionId) {
        ManagerPrincipal principal = ManagerRequestContext.require();
        ConversationService.Conversation conversation = service.get(sessionId, owner(principal));
        requireScope(conversation, principal);
        return SessionResponse.from(conversation);
    }

    @GetMapping("/{sessionId}/history")
    public HistoryResponse history(@PathVariable UUID sessionId) {
        ManagerPrincipal principal = ManagerRequestContext.require();
        ConversationService.Conversation conversation = service.get(sessionId, owner(principal));
        requireScope(conversation, principal);
        ConversationService.History history = service.history(sessionId, owner(principal));
        return new HistoryResponse(
                history.messages().stream().map(message -> new MessageHistoryResponse(
                        message.idempotencyKey(), message.content(), message.startCursor(), message.endCursor(),
                        message.status().name())).toList(),
                history.events().stream().map(this::eventResponse).toList());
    }

    @PostMapping("/{sessionId}/messages")
    public MessageResponse message(@PathVariable UUID sessionId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody MessageRequest request) {
        requireKey(idempotencyKey);
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        ManagerPrincipal principal = ManagerRequestContext.require();
        ConversationService.Conversation conversation = service.get(sessionId, owner(principal));
        requireScope(conversation, principal);
        ConversationService.SendResult result = service.send(sessionId, idempotencyKey, request.content(),
                request.expectedVersion(), owner(principal));
        return new MessageResponse(result.sessionId(), result.idempotencyKey(),
                result.events().stream().map(this::eventResponse).toList(),
                SessionResponse.from(service.get(sessionId)));
    }

    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> events(@PathVariable UUID sessionId,
            @RequestParam(name = "after", defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (after < 0) throw new IllegalArgumentException("after cursor must be non-negative");
        long cursor = Math.max(after, parseCursor(lastEventId));
        ManagerPrincipal principal = ManagerRequestContext.require();
        ConversationService.Conversation conversation = service.get(sessionId, owner(principal));
        requireScope(conversation, principal);
        waitForPendingMessage(sessionId, owner(principal));
        StringBuilder stream = new StringBuilder();
        for (ConversationEvent event : service.events(sessionId, cursor, owner(principal))) {
            stream.append("id: ").append(event.cursor()).append('\n')
                    .append("event: ").append(event.type()).append('\n');
            for (String line : event.data().split("\\R", -1)) {
                stream.append("data: ").append(line).append('\n');
            }
            stream.append('\n');
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream.toString());
    }

    private void waitForPendingMessage(UUID sessionId, ConversationOwner owner) {
        if (!service.hasPendingMessage(sessionId, owner)) return;
        long deadline = System.nanoTime() + EVENT_WAIT_TIMEOUT_MILLIS * 1_000_000L;
        while (service.hasPendingMessage(sessionId, owner) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(EVENT_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @PostMapping("/{sessionId}/cancel")
    public SessionResponse cancel(@PathVariable UUID sessionId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) CancelRequest request) {
        requireKey(idempotencyKey);
        ManagerPrincipal principal = ManagerRequestContext.require();
        ConversationService.Conversation conversation = service.get(sessionId, owner(principal));
        requireScope(conversation, principal);
        return SessionResponse.from(service.cancel(sessionId, idempotencyKey,
                request == null ? null : request.expectedVersion(), owner(principal)));
    }

    private static ConversationOwner owner(ManagerPrincipal principal) {
        return new ConversationOwner(principal.tenantId(), principal.subject());
    }

    private void requireScope(ConversationService.Conversation conversation, ManagerPrincipal principal) {
        ConversationRuntimePort.Context context = conversation.context();
        ConversationOwner owner = conversation.owner();
        boolean ownerMatches = owner != null && principal.tenantId().equals(owner.tenantId())
                && principal.subject().equals(owner.subject());
        if (!ownerMatches) {
            throw new ManagerAuthorizationException("conversation scope does not match authenticated principal");
        }
        scopeAuthorizer.requireAccessible(context.project(), context.team(), principal);
    }

    private void requireScope(String project, String team, ManagerPrincipal principal) {
        scopeAuthorizer.requireAccessible(project, team, principal);
    }

    private String canonicalProject(ManagerPrincipal principal, String requestedProject) {
        if (projectScopes == null) {
            return requestedProject == null || requestedProject.isBlank() ? principal.projectId() : requestedProject;
        }
        return projectScopes.canonicalize(principal, requestedProject).projectId();
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 255 characters");
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

    public record CreateRequest(UUID sessionId, String project, String team, String worker, String task,
            String projectId, String teamId, String workerId, String taskId) {
        String projectValue() { return project != null ? project : projectId; }
        String teamValue() { return team != null ? team : teamId; }
        String workerValue() { return worker != null ? worker : workerId; }
        String taskValue() { return task != null ? task : taskId; }
    }
    public record MessageRequest(String content, Long expectedVersion) { }
    private EventResponse eventResponse(ConversationEvent event) {
        JsonNode data;
        try {
            data = mapper.readTree(event.data());
        } catch (Exception error) {
            data = TextNode.valueOf(event.data());
        }
        return new EventResponse(event.cursor(), event.type(), data);
    }
    public record CancelRequest(Long expectedVersion) { }

    public record SessionResponse(UUID sessionId, ConversationRuntimePort.Context context, String status, long version) {
        static SessionResponse from(ConversationService.Conversation conversation) {
            return new SessionResponse(conversation.sessionId(), conversation.context(), conversation.status().name(),
                    conversation.version());
        }
    }
    public record ConversationPageResponse(List<ConversationSummaryResponse> items, String nextCursor,
            boolean hasMore, java.time.Instant serverTime) {
        static ConversationPageResponse from(ConversationService.ConversationPage page) {
            return new ConversationPageResponse(page.items().stream().map(ConversationSummaryResponse::from).toList(),
                    page.nextCursor(), page.hasMore(), page.serverTime());
        }
    }
    public record ConversationSummaryResponse(UUID sessionId, ConversationRuntimePort.Context context, String status,
            long version, java.time.Instant createdAt, java.time.Instant updatedAt, String lastMessage) {
        static ConversationSummaryResponse from(ConversationService.ConversationSummary summary) {
            return new ConversationSummaryResponse(summary.sessionId(), summary.context(), summary.status().name(),
                    summary.version(), summary.createdAt(), summary.updatedAt(), summary.lastMessage());
        }
    }
    public record EventResponse(long id, String event, JsonNode data) { }
    public record MessageResponse(UUID sessionId, String idempotencyKey, List<EventResponse> events,
            SessionResponse session) {
        public MessageResponse(UUID sessionId, String idempotencyKey, List<EventResponse> events) {
            this(sessionId, idempotencyKey, events, null);
        }
    }
    public record HistoryResponse(List<MessageHistoryResponse> messages, List<EventResponse> events) { }
    public record MessageHistoryResponse(String idempotencyKey, String content, long startCursor, Long endCursor,
            String status) { }
}
